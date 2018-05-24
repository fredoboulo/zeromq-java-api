package zmq.api;

/**
 * Manages set of timers.
 *
 * Timers can be added with a given interval, when the interval of time expires after addition, handler method is executed with given arguments.
 * Timer is repetitive and will be executed over time until canceled.
 *
 * This is a DRAFT class, and may change without notice.
 */
public interface ATimer
{
    /**
     * Opaque representation of a timer.
     */
    public static interface TimerHandle
    {
    }

    /**
     * Called when the timer has been expired.
     */
    public static interface Handler
    {
        /**
         * Called when the timer has been expired.
         * @param args the optional arguments given when adding the timer.
         */
        void time(Object... args);
    }

    /**
     * Add timer to the set, timer repeats forever, or until cancel is called.
     *
     * @param interval the interval of repetition in milliseconds.
     * @param handler the callback called at the expiration of the timer.
     * @param args the optional arguments for the handler.
     * @return an opaque handle for further cancel.
     */
    TimerHandle add(long interval, Handler handler, Object... args);

    /**
     * Changes the interval of the timer.
     *
     * This method is slow, canceling existing and adding a new timer yield better performance.
     * @param timer the timer to change the interval to.
     * @return true if set, otherwise false.
     */
    boolean setInterval(TimerHandle handle, long interval);

    /**
     * Reset the timer.
     *
     * This method is slow, canceling existing and adding a new timer yield better performance.
     * @param timer the timer to reset.
     * @return true if reset, otherwise false.
     */
    boolean reset(TimerHandle handle);

    /**
     * Cancel a timer.
     *
     * @param timer the timer to cancel.
     * @return true if cancelled, otherwise false.
     */
    boolean cancel(TimerHandle handle);

    /**
     * Returns the time in millisecond until the next timer.
     *
     * @return the time in millisecond until the next timer.
     */
    long timeout();

    /**
     * Execute the timers.
     *
     * @return the number of timers triggered.
     */
    int execute();
}
