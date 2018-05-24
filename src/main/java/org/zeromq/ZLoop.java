package org.zeromq;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import org.zeromq.ZMQ.Context;
import org.zeromq.ZMQ.PollItem;
import org.zeromq.ZMQ.Poller;

import zmq.ZError;

/**
 * The ZLoop class provides an event-driven reactor pattern. The reactor
 * handles zmq.PollItem items (pollers or writers, sockets or fds), and
 * once-off or repeated timers. Its resolution is 1 msec. It uses a tickless
 * timer to reduce CPU interrupts in inactive processes.
 */
public class ZLoop
{
    public interface IZLoopHandler<T>
    {
        /**
         * Called back when it's time to execute an action
         * @param loop the loop itself
         * @param item the pollitme. Possibly null
         * @param arg the optional argument
         * @return -1 to stop the loop, any other value to continue it
         */
        public int handle(ZLoop loop, PollItem item, T arg);
    }

    /**
     * Opaque tagging interface to remove timers, pollers and readers
     */
    public interface Handle
    {
    }

    private abstract static class AbstractHandle<T> implements Handle
    {
        final IZLoopHandler<T> handler; //  Function to execute
        final T                arg;     //  Application argument to poll item
        boolean                deleted; //  Flag as deleted (to clean up later)

        private AbstractHandle(IZLoopHandler<T> handler, T arg)
        {
            this.handler = handler;
            this.arg = arg;
        }

        boolean handle(ZLoop loop, PollItem item)
        {
            return handler.handle(loop, item, arg) != -1;
        }
    }

    private static class SReader<T> extends AbstractHandle<T>
    {
        final ZMQ.Socket socket;   //  Socket to read from
        int              errors;   //  If too many errors, kill reader
        boolean          tolerant; //  Unless configured as tolerant

        private SReader(ZMQ.Socket socket, IZLoopHandler<T> handler, T arg)
        {
            super(handler, arg);
            this.socket = socket;
            this.errors = 0;
            this.tolerant = false;
        }
    }

    private static class STicket<T> extends AbstractHandle<T>
    {
        int        tag;   //  Object tag for runtime detection
        final long delay; //  Delay (ms) before executing
        long       when;  //  Clock time to invoke the ticket

        private STicket(long delay, IZLoopHandler<T> handler, T arg)
        {
            super(handler, arg);
            this.tag = TICKET_TAG;
            this.delay = delay;
            this.when = now() + delay;
        }

        public void destroy()
        {
            this.tag = 0xDeadBeef;
        }
    }

    private static class SPoller<T> extends AbstractHandle<T>
    {
        final PollItem item;     //  ZeroMQ socket or file descriptor
        int            errors;   //  If too many errors, kill poller
        boolean        tolerant; //  Unless configured as tolerant

        protected SPoller(PollItem item, IZLoopHandler<T> handler, T arg)
        {
            super(handler, arg);
            this.item = item;
            this.errors = 0;
            this.tolerant = false;
        }
    }

    private static class STimer<T> extends AbstractHandle<T>
    {
        int  delay;
        int  times;
        long when; //  Clock time when alarm goes off

        public STimer(int delay, int times, IZLoopHandler<T> handler, T arg)
        {
            super(handler, arg);
            this.delay = delay;
            this.times = times;
            this.when = now() + delay;
        }

        private static final Comparator<STimer<?>> COMPARATOR = new Comparator<ZLoop.STimer<?>>()
        {
            @Override
            public int compare(STimer<?> first, STimer<?> second)
            {
                if (first.when > second.when) {
                    return 1;
                }
                if (first.when < second.when) {
                    return -1;
                }
                return 0;
            }
        };
    }

    //  As we pass void * to/from the caller for working with tickets, we
    //  check validity using an object tag. This value is unique in CZMQ.
    private static final int TICKET_TAG = 0xcafe0007;

    private final Context          context;     // Context managing the pollers.
    private final List<SReader<?>> readers;     // List of socket readers
    private final List<SPoller<?>> pollers;     // List of poll items
    private final List<STimer<?>>  timers;      // List of timers
    private final List<STicket<?>> tickets;     // List of tickets
    private long                   maxTimers;   // Limit on number of timers
    private long                   ticketDelay; // Ticket delay value
    private int                    pollSize;    // Size of poll set
    private Poller                 pollset;     // zmq_poll set
    private SReader<?>[]           readact;     // Readers for this poll set
    private SPoller<?>[]           pollact;     // Pollers for this poll set
    private boolean                needRebuild; // True if pollset needs rebuilding
    private boolean                verbose;     // True if verbose tracing wanted
    private boolean                terminated;  // True when stopped running
    private boolean                nonStop;     // Don't stop running on Ctrl-C
    private final List<STimer<?>>  newTimers;   // List of timers to add

    public ZLoop(Context context)
    {
        assert (context != null);
        this.context = context;

        readers = new ArrayList<>();
        pollers = new ArrayList<>();
        timers = new ArrayList<>();
        newTimers = new ArrayList<>();
        tickets = new ArrayList<>();
    }

    public ZLoop(ZContext ctx)
    {
        this(ctx.getContext());
    }

    /**
     * @deprecated no-op behaviour
     */
    @Deprecated
    public void destroy()
    {
        // do nothing
    }

    private static long now()
    {
        return ZMQ.Clock.monoMS();
    }

    //  We hold an array of pollers that matches the pollset, so we can
    //  register/cancel pollers orthogonally to executing the pollset
    //  activity on pollers. Returns 0 on success, -1 on failure.
    private void rebuild()
    {
        pollSize = pollers.size() + readers.size();

        if (pollset != null) {
            pollset.close();
        }
        pollset = context.poller(pollSize);
        assert (pollset != null);

        pollact = new SPoller[pollers.size()];
        readact = new SReader[readers.size()];

        int itemNbr = 0;
        for (SReader<?> reader : readers) {
            pollset.register(new PollItem(reader.socket, ZMQ.Poller.POLLIN));
            readact[itemNbr++] = reader;
        }
        itemNbr = 0;
        for (SPoller<?> poller : pollers) {
            pollset.register(poller.item);
            pollact[itemNbr++] = poller;
        }
        needRebuild = false;
    }

    private long ticklessTimer()
    {
        //  Calculate tickless timer, up to 1 hour
        long tickless = ZMQ.Clock.monoMS() + 1000 * 3600;

        //  Scan timers, which are not sorted
        //  TODO: sort timers properly on insertion
        timers.sort(STimer.COMPARATOR);
        for (STimer<?> timer : timers) {
            if (tickless > timer.when) {
                tickless = timer.when;
            }
        }
        //  Tickets are sorted, so check first ticket
        if (!tickets.isEmpty() && tickless > tickets.get(0).when) {
            tickless = tickets.get(0).when;
        }
        long timeout = tickless - now();
        if (timeout < 0) {
            timeout = 0;
        }
        debug("polling for %d msec", timeout);
        return timeout;
    }

    //  Register socket reader with the reactor. When the reader has messages,
    //  the reactor will call the handler, passing the arg.
    //  If you register the same socket more than once,
    //  each instance will invoke its corresponding handler.
    public <T> Handle addReader(ZMQ.Socket socket, IZLoopHandler<T> handler, T arg)
    {
        SReader<T> reader = new SReader<>(socket, handler, arg);
        readers.add(reader);
        needRebuild = true;
        debug("register %s reader", socket.typename());
        return reader;
    }

    //  Cancel a socket reader from the reactor. If multiple readers exist for
    //  same socket, cancels ALL of them.
    public void removeReader(Handle handle)
    {
        assert (handle instanceof SReader);
        SReader<?> reader = (SReader<?>) handle;
        if (readers.remove(reader)) {
            needRebuild = true;
        }
        debug("cancel %s reader", reader.socket.typename());
    }

    //  Configure a registered reader to ignore errors. If you do not set this,
    //  then reader that have errors are removed from the reactor silently.
    public void setTolerantReader(Handle handle)
    {
        assert (handle instanceof SReader);
        SReader<?> reader = (SReader<?>) handle;
        reader.tolerant = true;
    }

    //  --------------------------------------------------------------------------
    //  Register pollitem with the reactor. When the pollitem is ready, will call
    //  the handler, passing the arg. Returns 0 if OK, -1 if there was an error.
    //  If you register the pollitem more than once, each instance will invoke its
    //  corresponding handler.

    public <T> int addPoller(PollItem pollItem, IZLoopHandler<T> handler, T arg)
    {
        Handle handle = poller(pollItem, handler, arg);
        if (handle == null) {
            return -1;
        }
        return 0;
    }

    public <T> Handle poller(PollItem pollItem, IZLoopHandler<T> handler, T arg)
    {
        if (pollItem.getRawSocket() == null && pollItem.getSocket() == null) {
            return null;
        }

        SPoller<T> poller = new SPoller<>(pollItem, handler, arg);
        pollers.add(poller);

        needRebuild = true;
        debug(
              "register %s poller (%s, %s)",
              pollItem.getSocket() != null ? pollItem.getSocket().getType() : "RAW",
              pollItem.getSocket(),
              pollItem.getRawSocket());
        return poller;
    }

    //  --------------------------------------------------------------------------
    //  Cancel a pollitem from the reactor, specified by socket or FD. If both
    //  are specified, uses only socket. If multiple poll items exist for same
    //  socket/FD, cancels ALL of them.

    public void removePoller(PollItem pollItem)
    {
        Iterator<SPoller<?>> it = pollers.iterator();
        while (it.hasNext()) {
            SPoller<?> p = it.next();
            if (pollItem.equals(p.item)) {
                it.remove();
                needRebuild = true;
            }
        }
        debug(
              "cancel %s poller (%s, %s)",
              pollItem.getSocket() != null ? pollItem.getSocket().typename() : "RAW",
              pollItem.getSocket(),
              pollItem.getRawSocket());
    }

    public void removePoller(Handle handle)
    {
        assert (handle instanceof SPoller);
        SPoller<?> poller = (SPoller<?>) handle;
        if (pollers.remove(poller)) {
            needRebuild = true;
        }
        debug(
              "cancel %s poller (%s, %s)",
              poller.item.getSocket() != null ? poller.item.getSocket().typename() : "RAW",
              poller.item.getSocket(),
              poller.item.getRawSocket());

    }

    //  Configure a registered poller to ignore errors. If you do not set this,
    //  then poller that have errors are removed from the reactor silently.
    public void setTolerantPoller(Handle handle)
    {
        assert (handle instanceof SPoller);
        SPoller<?> poller = (SPoller<?>) handle;
        poller.tolerant = true;
    }

    //  Register a timer that expires after some delay and repeats some number of
    //  times. At each expiry, will call the handler, passing the arg. To
    //  run a timer forever, use 0 times. Returns 0 if OK, -1 if there was an
    //  error.

    public <T> int addTimer(int delay, int times, IZLoopHandler<T> handler, T arg)
    {
        Handle handle = timer(delay, times, handler, arg);
        if (handle == null) {
            return -1;
        }
        return 0;
    }

    public <T> Handle timer(int delay, int times, IZLoopHandler<T> handler, T arg)
    {
        //  Catch excessive use of timers
        if (maxTimers > 0 && timers.size() == maxTimers) {
            error("timer limit reached (max=%d)", maxTimers);
            return null;
        }

        STimer<T> timer = new STimer<>(delay, times, handler, arg);

        //  We cannot touch self->timers because we may be executing that
        //  from inside the poll loop. So, we hold the new timer on the newTimers
        //  list, and process that list when we're done executing timers.
        newTimers.add(timer);
        debug("register timer delay=%d times=%d", delay, times);

        return timer;
    }

    //  --------------------------------------------------------------------------
    //  Cancel all timers for a specific argument (as provided in zloop_timer)
    //  Returns 0 on success.

    public int removeTimer(Object arg)
    {
        if (arg instanceof Handle) {
            removeTimer((Handle) arg);
            return 0;
        }
        assert (arg != null);

        //  We cannot touch self->timers because we may be executing that
        //  from inside the poll loop. So, we hold the arg on the zombie
        //  list, and process that list when we're done executing timers.
        for (STimer<?> timer : timers) {
            if (timer.arg == arg) {
                timer.deleted = true;
                break;
            }
        }
        debug("cancel timer");

        return 0;
    }

    public boolean removeTimer(Handle handle)
    {
        assert (handle instanceof STimer);
        STimer<?> timer = (STimer<?>) handle;

        //  We cannot touch self->timers because we may be executing that
        //  from inside the poll loop. So, we hold the arg on the zombie
        //  list, and process that list when we're done executing timers.
        timer.deleted = true;
        debug("cancel timer");

        return true;
    }

    //  Register a ticket timer. Ticket timers are very fast in the case where
    //  you use a lot of timers (thousands), and frequently remove and add them.
    //  The main use case is expiry timers for servers that handle many clients,
    //  and which reset the expiry timer for each message received from a client.
    //  Whereas normal timers perform poorly as the number of clients grows, the
    //  cost of ticket timers is constant, no matter the number of clients. You
    //  must set the ticket delay using zloop_set_ticket_delay before creating a
    //  ticket. Returns a handle to the timer that you should use in
    //  zloop_ticket_reset and zloop_ticket_delete.
    public <T> Handle addTicket(IZLoopHandler<T> handler, T arg)
    {
        assert (ticketDelay > 0);
        STicket<?> ticket = new STicket<>(ticketDelay, handler, arg);
        boolean rc = tickets.add(ticket);
        assert (rc);
        return ticket;
    }

    //  Reset a ticket timer, which moves it to the end of the ticket list and
    //  resets its execution time. This is a very fast operation.
    public void resetTicket(Handle handle)
    {
        assert (handle instanceof STicket);
        STicket<?> ticket = (STicket<?>) handle;
        assert (ticket.tag == TICKET_TAG);

        ticket.when = now() + ticket.delay;

        boolean rc = tickets.remove(ticket);
        assert (rc);
        rc = tickets.add(ticket);
        assert (rc);
    }

    /**
     * Deletes a ticket timer.
     *
     * We do not actually delete the ticket here, as
     * other code may still refer to the ticket. We mark as deleted, and remove
     * later and safely.
     *
     * @param handle the handle of the ticket to delete.
     */
    public void deleteTicket(Handle handle)
    {
        assert (handle instanceof STicket);
        STicket<?> ticket = (STicket<?>) handle;
        assert (ticket.tag == TICKET_TAG);

        ticket.deleted = true;
        //  Move deleted tickets to end of list for fast cleanup
        boolean rc = tickets.remove(ticket);
        assert (rc);
        rc = tickets.add(ticket);
        assert (rc);
    }

    //  Set the ticket delay, which applies to all tickets. If you lower the
    //  delay and there are already tickets created, the results are undefined.
    public void ticketDelay(long ticketDelay)
    {
        this.ticketDelay = ticketDelay;
    }

    //  --------------------------------------------------------------------------
    //  Set verbose tracing of reactor on/off
    public void verbose(boolean verbose)
    {
        this.verbose = verbose;
    }

    //  By default the reactor stops if the process receives a SIGINT or SIGTERM
    //  signal. This makes it impossible to shut-down message based architectures
    //  like zactors. This method lets you switch off break handling. The default
    //  nonstop setting is off (false).
    public void nonStop(boolean nonstop)
    {
        this.nonStop = nonstop;
    }

    //  Set hard limit on number of timers allowed. Setting more than a small
    //  number of timers (10-100) can have a dramatic impact on the performance
    //  of the reactor. For high-volume cases, use ticket timers. If the hard
    //  limit is reached, the reactor stops creating new timers and logs an
    //  error.
    public void maxTimers(long maxTimers)
    {
        this.maxTimers = maxTimers;
    }

    //  --------------------------------------------------------------------------
    //  Start the reactor. Takes control of the thread and returns when the 0MQ
    //  context is terminated or the process is interrupted, or any event handler
    //  returns -1. Event handlers may register new sockets and timers, and
    //  cancel sockets. Returns 0 if interrupted, -1 if cancelled by a
    //  handler, positive on internal error

    public int start()
    {
        boolean active = false;
        int rc = 0;

        timers.addAll(newTimers);
        newTimers.clear();

        //  Recalculate all timers now
        for (STimer<?> timer : timers) {
            timer.when = timer.delay + now();
        }

        //  Main reactor loop
        main: while (!Thread.currentThread().isInterrupted() || nonStop) {
            if (needRebuild) {
                // If s_rebuild_pollset() fails, break out of the loop and
                // return its error
                rebuild();
            }
            long wait = ticklessTimer();

            rc = pollset.poll(wait);

            if (rc == -1 && !nonStop) {
                debug("interrupted (%d)\n", rc);

                active = false;
                rc = 0;
                break; //  Context has been shut down
            }
            //  Handle any timers that have now expired
            final long now = now();
            Iterator<STimer<?>> it = timers.iterator();
            while (it.hasNext()) {
                STimer<?> timer = it.next();
                if (now >= timer.when) {
                    debug("call timer handler");

                    active = timer.handle(this, null);
                    if (!active) {
                        break main; //  Timer handler signaled break
                    }
                    if (timer.times != 0 && --timer.times == 0) {
                        it.remove();
                    }
                    else {
                        timer.when += timer.delay;
                    }
                }
            }
            //  Handle any tickets that have now expired
            ListIterator<STicket<?>> iter = tickets.listIterator();
            while (iter.hasNext()) {
                STicket<?> ticket = iter.next();
                if (now >= ticket.when) {
                    debug("call timer handler");

                    if (!ticket.deleted) {
                        active = ticket.handle(this, null);
                        if (!active) {
                            break main; //  TODO Ticket handler signaled break
                        }
                    }
                    // TODO remove every tickets ?
                    ticket.destroy();
                    iter.remove();
                }
            }
            //  Handle any tickets that were flagged for deletion
            iter = tickets.listIterator(tickets.size());
            while (iter.hasPrevious()) {
                STicket<?> ticket = iter.previous();
                if (ticket.deleted) {
                    ticket.destroy();
                    iter.remove();
                }
                else {
                    break;
                }
            }

            //  Check if timers changed pollset
            if (needRebuild) {
                continue;
            }

            //  Handle any readers and pollers that are ready
            for (int itemNbr = 0; itemNbr < readact.length; itemNbr++) {
                SReader<?> reader = readact[itemNbr];
                if (reader.handler != null) {
                    if (pollset.pollerr(itemNbr) && !reader.tolerant) {
                        warning(
                                "can't read %s socket : %s",
                                reader.socket.typename(),
                                ZError.toString(reader.socket.errno()));
                        //  Give handler one chance to handle error, then kill
                        //  reader because it'll disrupt the reactor otherwise.
                        if (reader.errors++ > 0) {
                            removeReader(reader);
                            pollset.unregister(pollset.getItem(itemNbr));
                        }
                    }
                    else {
                        reader.errors = 0;
                    }

                    if (pollset.getItem(itemNbr).readyOps() > 0) {
                        debug("call %s socket handler", reader.socket.typename());
                        active = reader.handle(this, pollset.getItem(itemNbr));
                        if (!active || needRebuild) {
                            break;
                        }
                    }
                }

            }
            for (int itemNbr = 0; itemNbr < pollact.length; itemNbr++) {
                SPoller<?> poller = pollact[itemNbr];
                if (pollset.pollerr(itemNbr + readact.length) && !poller.tolerant) {
                    warning(
                            "can't poll %s socket (%s, %s)",
                            poller.item.getSocket() != null ? poller.item.getSocket().getType() : "RAW",
                            poller.item.getSocket(),
                            poller.item.getRawSocket());

                    //  Give handler one chance to handle error, then kill
                    //  poller because it'll disrupt the reactor otherwise.
                    if (poller.errors++ > 0) {
                        removePoller(poller.item);
                        pollset.unregister(pollset.getItem(itemNbr));
                    }
                }
                else {
                    poller.errors = 0; //  A non-error happened
                }

                if (pollset.getItem(itemNbr).readyOps() > 0) {
                    debug(
                          "call %s socket handler (%s, %s)",
                          poller.item.getSocket() != null ? poller.item.getSocket().getType() : "RAW",
                          poller.item.getSocket(),
                          poller.item.getRawSocket());

                    active = poller.handle(this, poller.item);
                    if (!active || needRebuild) {
                        break; //  Poller handler signaled break
                    }
                }
            }

            // Now handle any timer zombies
            // This is going to be slow if we have many zombies
            it = timers.iterator();
            while (it.hasNext()) {
                STimer<?> timer = it.next();
                if (timer.deleted) {
                    it.remove();
                }
            }

            //  Now handle any new timers added inside the loop
            timers.addAll(newTimers);
            newTimers.clear();

            if (!active) {
                break;
            }
        }
        terminated = true;

        if (!active) {
            rc = -1;
        }
        return rc;

    }

    protected void debug(String format, Object... args)
    {
        log("D", format, args);
    }

    protected void info(String format, Object... args)
    {
        log("I", format, args);
    }

    protected void warning(String format, Object... args)
    {
        log("W", format, args);
    }

    protected void error(String format, Object... args)
    {
        log("E", format, args);
    }

    protected void log(String prefix, String format, Object... args)
    {
        if (verbose) {
            System.out.printf(prefix + ": zloop: " + format + "%n", args);
        }
    }
}
