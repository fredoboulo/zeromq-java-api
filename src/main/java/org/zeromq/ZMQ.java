package org.zeromq;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.Random;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import zmq.ZError;
import zmq.api.AContext;
import zmq.api.AEvent;
import zmq.api.AMechanism;
import zmq.api.AMetadata;
import zmq.api.AMsg;
import zmq.api.APollItem;
import zmq.api.AProvider;
import zmq.api.ASocket;
import zmq.api.ATimer;
import zmq.api.ATimer.TimerHandle;
import zmq.api.Draft;

/**
 * The ØMQ lightweight messaging kernel is a library which extends the standard socket interfaces
 * with features traditionally provided by specialised messaging middleware products.
 * ØMQ sockets provide an abstraction of asynchronous message queues, multiple messaging patterns,
 * message filtering (subscriptions), seamless access to multiple transport protocols and more.
 * <p/>
 * Following is an overview of ØMQ concepts, describes how ØMQ abstracts standard sockets
 * and provides a reference manual for the functions provided by the ØMQ library.
 * <p/>
 * <h1>Contexts</h1>
 * Before using any ØMQ library functions you must create a {@link ZMQ.Context ØMQ context} using {@link ZMQ#context(int)}.
 * When you exit your application you must destroy the context using {@link ZMQ.Context#close()}.
 * <p/>
 * <h2>Thread safety</h2>
 * A ØMQ context is thread safe and may be shared among as many application threads as necessary,
 * without any additional locking required on the part of the caller.
 * <br/>
 * Individual ØMQ sockets are not thread safe except in the case
 * where full memory barriers are issued when migrating a socket from one thread to another.
 * <br/>
 * In practice this means applications can create a socket in one thread with {@link ZMQ.Context#socket(int)}
 * and then pass it to a newly created thread as part of thread initialization.
 * <p/>
 * <h2>Multiple contexts</h2>
 * Multiple contexts may coexist within a single application.
 * <br/>
 * Thus, an application can use ØMQ directly and at the same time make use of any number of additional libraries
 * or components which themselves make use of ØMQ as long as the above guidelines regarding thread safety are adhered to.
 * <p/>
 * <h1>Messages</h1>
 * A ØMQ message is a discrete unit of data passed between applications or components of the same application.
 * ØMQ messages have no internal structure and from the point of view of ØMQ itself
 * they are considered to be opaque binary data.
 * <p/>
 * <h1>Sockets</h1>
 * {@link ZMQ.Socket ØMQ sockets} present an abstraction of a asynchronous message queue,
 * with the exact queueing semantics depending on the socket type in use.
 * <p/>
 * <h1>Transports</h1>
 * A ØMQ socket can use multiple different underlying transport mechanisms.
 * Each transport mechanism is suited to a particular purpose and has its own advantages and drawbacks.
 * <p/>
 * The following transport mechanisms are provided:<br/>
 * <ul>
 * <li>Unicast transport using TCP</li>
 * <li>Local inter-process communication transport</li>
 * <li>Local in-process (inter-thread) communication transport</li>
 * </ul>
 * <p/>
 * <h1>Proxies</h1>
 * ØMQ provides proxies to create fanout and fan-in topologies.
 * A proxy connects a frontend socket to a backend socket
 * and switches all messages between the two sockets, opaquely.
 * A proxy may optionally capture all traffic to a third socket.
 * <p/>
 * <h1>Security</h1>
 * A ØMQ socket can select a security mechanism. Both peers must use the same security mechanism.
 * <br/>
 * The following security mechanisms are provided for IPC and TCP connections:<br/>
 * <ul>
 * <li>Null security</li>
 * <li>Plain-text authentication using username and password</li>
 * <li>Elliptic curve authentication and encryption</li>
 * </ul>
 */
public class ZMQ
{
    private static final AProvider PROVIDER = ServiceLoader.load(AProvider.class).iterator().next();

    /**
     * Socket flag to indicate that more message parts are coming.
     */
    public static final int SNDMORE = zmq.api.ZMQ.ZMQ_SNDMORE;
    /**
     * Socket flag to indicate that more message parts are coming.
     */
    public static final int RCVMORE = zmq.api.ZMQ.ZMQ_RCVMORE;

    // Values for flags in Socket's send and recv functions.
    /**
     * Socket flag to indicate a nonblocking send or recv mode.
     */
    public static final int DONTWAIT = zmq.api.ZMQ.ZMQ_DONTWAIT;
    public static final int NOBLOCK  = zmq.api.ZMQ.ZMQ_DONTWAIT;

    // Socket types, used when creating a Socket.
    /**
     * Flag to specify a exclusive pair of sockets.
     * <p/>
     * A socket of type PAIR can only be connected to a single peer at any one time.
     * <br/>
     * No message routing or filtering is performed on messages sent over a PAIR socket.
     * <br/>
     * When a PAIR socket enters the mute state due to having reached the high water mark for the connected peer,
     * or if no peer is connected, then any send() operations on the socket shall block until the peer becomes available for sending;
     * messages are not discarded.
     * <p/>
     * <table summary="" border="1">
     *   <th colspan="2">Summary of socket characteristics</th>
     *   <tr><td>Compatible peer sockets</td><td>PAIR</td></tr>
     *   <tr><td>Direction</td><td>Bidirectional</td></tr>
     *   <tr><td>Send/receive pattern</td><td>Unrestricted</td></tr>
     *   <tr><td>Incoming routing strategy</td><td>N/A</td></tr>
     *   <tr><td>Outgoing routing strategy</td><td>N/A</td></tr>
     *   <tr><td>Action in mute state</td><td>Block</td></tr>
     * </table>
     * <p/>
     * <strong>PAIR sockets are designed for inter-thread communication across the inproc transport
     * and do not implement functionality such as auto-reconnection.
     * PAIR sockets are considered experimental and may have other missing or broken aspects.</strong>
     */
    public static final int PAIR   = zmq.api.ZMQ.ZMQ_PAIR;
    /**
     * Flag to specify a PUB socket, receiving side must be a SUB or XSUB.
     * <p/>
     * A socket of type PUB is used by a publisher to distribute data.
     * <br/>
     * Messages sent are distributed in a fan out fashion to all connected peers.
     * <br/>
     * The {@link ZMQ.Socket#recv()} function is not implemented for this socket type.
     * <br/>
     * When a PUB socket enters the mute state due to having reached the high water mark for a subscriber,
     * then any messages that would be sent to the subscriber in question shall instead be dropped until the mute state ends.
     * <br/>
     * The send methods shall never block for this socket type.
     * <p/>
     * <table summary="" border="1">
     *   <th colspan="2">Summary of socket characteristics</th>
     *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#SUB}, {@link ZMQ#XSUB}</td></tr>
     *   <tr><td>Direction</td><td>Unidirectional</td></tr>
     *   <tr><td>Send/receive pattern</td><td>Send only</td></tr>
     *   <tr><td>Incoming routing strategy</td><td>N/A</td></tr>
     *   <tr><td>Outgoing routing strategy</td><td>Fan out</td></tr>
     *   <tr><td>Action in mute state</td><td>Drop</td></tr>
     * </table>
     */
    public static final int PUB    = zmq.api.ZMQ.ZMQ_PUB;
    /**
     * Flag to specify the receiving part of the PUB or XPUB socket.
     * <p/>
     * A socket of type SUB is used by a subscriber to subscribe to data distributed by a publisher.
     * <br/>
     * Initially a SUB socket is not subscribed to any messages,
     * use the {@link ZMQ.Socket#subscribe(byte[])} option to specify which messages to subscribe to.
     * <br/>
     * The send methods are not implemented for this socket type.
     * <p/>
     * <table summary="" border="1">
     *   <th colspan="2">Summary of socket characteristics</th>
     *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#PUB}, {@link ZMQ#XPUB}</td></tr>
     *   <tr><td>Direction</td><td>Unidirectional</td></tr>
     *   <tr><td>Send/receive pattern</td><td>Receive only</td></tr>
     *   <tr><td>Incoming routing strategy</td><td>Fair-queued</td></tr>
     *   <tr><td>Outgoing routing strategy</td><td>N/A</td></tr>
     * </table>
     */
    public static final int SUB    = zmq.api.ZMQ.ZMQ_SUB;
    /**
     * Flag to specify a REQ socket, receiving side must be a REP or ROUTER.
     * <p/>
     * A socket of type REQ is used by a client to send requests to and receive replies from a service.
     * <br/>
     * This socket type allows only an alternating sequence of send(request) and subsequent recv(reply) calls.
     * <br/>
     * Each request sent is round-robined among all services, and each reply received is matched with the last issued request.
     * <br/>
     * If no services are available, then any send operation on the socket shall block until at least one service becomes available.
     * <br/>
     * The REQ socket shall not discard messages.
     * <p/>
     * <table summary="" border="1">
     *   <th colspan="2">Summary of socket characteristics</th>
     *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#REP}, {@link ZMQ#ROUTER}</td></tr>
     *   <tr><td>Direction</td><td>Bidirectional</td></tr>
     *   <tr><td>Send/receive pattern</td><td>Send, Receive, Send, Receive, ...</td></tr>
     *   <tr><td>Incoming routing strategy</td><td>Last peer</td></tr>
     *   <tr><td>Outgoing routing strategy</td><td>Round-robin</td></tr>
     *   <tr><td>Action in mute state</td><td>Block</td></tr>
     * </table>
     */
    public static final int REQ    = zmq.api.ZMQ.ZMQ_REQ;
    /**
     * Flag to specify the receiving part of a REQ or DEALER socket.
     * <p/>
     * A socket of type REP is used by a service to receive requests from and send replies to a client.
     * <br/>
     * This socket type allows only an alternating sequence of recv(request) and subsequent send(reply) calls.
     * <br/>
     * Each request received is fair-queued from among all clients, and each reply sent is routed to the client that issued the last request.
     * <br/>
     * If the original requester does not exist any more the reply is silently discarded.
     * <p/>
     * <table summary="" border="1">
     *   <th colspan="2">Summary of socket characteristics</th>
     *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#REQ}, {@link ZMQ#DEALER}</td></tr>
     *   <tr><td>Direction</td><td>Bidirectional</td></tr>
     *   <tr><td>Send/receive pattern</td><td>Receive, Send, Receive, Send, ...</td></tr>
     *   <tr><td>Incoming routing strategy</td><td>Fair-queued</td></tr>
     *   <tr><td>Outgoing routing strategy</td><td>Last peer</td></tr>
     * </table>
     */
    public static final int REP    = zmq.api.ZMQ.ZMQ_REP;
    /**
     * Flag to specify a DEALER socket (aka XREQ).
     * <p/>
     * DEALER is really a combined ventilator / sink
     * that does load-balancing on output and fair-queuing on input
     * with no other semantics. It is the only socket type that lets
     * you shuffle messages out to N nodes and shuffle the replies
     * back, in a raw bidirectional asynch pattern.
     * <p/>
     * A socket of type DEALER is an advanced pattern used for extending request/reply sockets.
     * <br/>
     * Each message sent is round-robined among all connected peers, and each message received is fair-queued from all connected peers.
     * <br/>
     * When a DEALER socket enters the mute state due to having reached the high water mark for all peers,
     * or if there are no peers at all, then any send() operations on the socket shall block
     * until the mute state ends or at least one peer becomes available for sending; messages are not discarded.
     * <br/>
     * When a DEALER socket is connected to a {@link ZMQ#REP} socket each message sent must consist of an empty message part, the delimiter, followed by one or more body parts.
     * <p/>
     * <table summary="" border="1">
     *   <th colspan="2">Summary of socket characteristics</th>
     *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#ROUTER}, {@link ZMQ#REP}, {@link ZMQ#DEALER}</td></tr>
     *   <tr><td>Direction</td><td>Bidirectional</td></tr>
     *   <tr><td>Send/receive pattern</td><td>Unrestricted</td></tr>
     *   <tr><td>Incoming routing strategy</td><td>Fair-queued</td></tr>
     *   <tr><td>Outgoing routing strategy</td><td>Round-robin</td></tr>
     *   <tr><td>Action in mute state</td><td>Block</td></tr>
     * </table>
     */
    public static final int DEALER = zmq.api.ZMQ.ZMQ_DEALER;
    /**
    * Old alias for DEALER flag.
    * Flag to specify a XREQ socket, receiving side must be a XREP.
    *
    * @deprecated  As of release 3.0 of zeromq, replaced by {@link #DEALER}
    */
    @Deprecated
    public static final int XREQ   = DEALER;
    /**
     * Flag to specify ROUTER socket (aka XREP).
     * <p/>
     * ROUTER is the socket that creates and consumes request-reply
     * routing envelopes. It is the only socket type that lets you route
     * messages to specific connections if you know their identities.
     * <p/>
     * A socket of type ROUTER is an advanced socket type used for extending request/reply sockets.
     * <p/>
     * When receiving messages a ROUTER socket shall prepend a message part containing the identity
     * of the originating peer to the message before passing it to the application.
     * <br/>
     * Messages received are fair-queued from among all connected peers.
     * <p/>
     * When sending messages a ROUTER socket shall remove the first part of the message
     * and use it to determine the identity of the peer the message shall be routed to.
     * If the peer does not exist anymore the message shall be silently discarded by default,
     * unless {@link ZMQ.Socket#setRouterMandatory(boolean)} socket option is set to true.
     * <p/>
     * When a ROUTER socket enters the mute state due to having reached the high water mark for all peers,
     * then any messages sent to the socket shall be dropped until the mute state ends.
     * <br/>
     * Likewise, any messages routed to a peer for which the individual high water mark has been reached shall also be dropped,
     * , unless {@link ZMQ.Socket#setRouterMandatory(boolean)} socket option is set to true.
     * <p/>
     * When a {@link ZMQ#REQ} socket is connected to a ROUTER socket, in addition to the identity of the originating peer
     * each message received shall contain an empty delimiter message part.
     * <br/>
     * Hence, the entire structure of each received message as seen by the application becomes:
     * one or more identity parts,
     * delimiter part,
     * one or more body parts.
     * <p/>
     * When sending replies to a REQ socket the application must include the delimiter part.
     * <p/>
     * <table summary="" border="1">
     *   <th colspan="2">Summary of socket characteristics</th>
     *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#DEALER}, {@link ZMQ#REQ}, {@link ZMQ#ROUTER}</td></tr>
     *   <tr><td>Direction</td><td>Bidirectional</td></tr>
     *   <tr><td>Send/receive pattern</td><td>Unrestricted</td></tr>
     *   <tr><td>Incoming routing strategy</td><td>Fair-queued</td></tr>
     *   <tr><td>Outgoing routing strategy</td><td>See text</td></tr>
     *   <tr><td>Action in mute state</td><td>Drop (See text)</td></tr>
     * </table>
     */
    public static final int ROUTER = zmq.api.ZMQ.ZMQ_ROUTER;
    /**
     * Old alias for ROUTER flag.
     * Flag to specify the receiving part of a XREQ socket.
     *
     * @deprecated  As of release 3.0 of zeromq, replaced by {@link #ROUTER}
     */
    @Deprecated
    public static final int XREP   = ROUTER;
    /**
     * Flag to specify the receiving part of a PUSH socket.
     * <p/>
     * A socket of type ZMQ_PULL is used by a pipeline node to receive messages from upstream pipeline nodes.
     * <br/>
     * Messages are fair-queued from among all connected upstream nodes.
     * <br/>
     * The send() function is not implemented for this socket type.
     * <p/>
     * <table summary="" border="1">
     *   <th colspan="2">Summary of socket characteristics</th>
     *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#PUSH}</td></tr>
     *   <tr><td>Direction</td><td>Unidirectional</td></tr>
     *   <tr><td>Send/receive pattern</td><td>Receive only</td></tr>
     *   <tr><td>Incoming routing strategy</td><td>Fair-queued</td></tr>
     *   <tr><td>Outgoing routing strategy</td><td>N/A</td></tr>
     *   <tr><td>Action in mute state</td><td>Block</td></tr>
     * </table>
     */
    public static final int PULL   = zmq.api.ZMQ.ZMQ_PULL;
    /**
     * Flag to specify a PUSH socket, receiving side must be a PULL.
     * <p/>
     * A socket of type PUSH is used by a pipeline node to send messages to downstream pipeline nodes.
     * <br/>
     * Messages are round-robined to all connected downstream nodes.
     * <br/>
     * The recv() function is not implemented for this socket type.
     * <br/>
     * When a PUSH socket enters the mute state due to having reached the high water mark for all downstream nodes,
     * or if there are no downstream nodes at all, then any send() operations on the socket shall block until the mute state ends
     * or at least one downstream node becomes available for sending; messages are not discarded.
     * <p/>
     * <table summary="" border="1">
     *   <th colspan="2">Summary of socket characteristics</th>
     *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#PULL}</td></tr>
     *   <tr><td>Direction</td><td>Unidirectional</td></tr>
     *   <tr><td>Send/receive pattern</td><td>Send only</td></tr>
     *   <tr><td>Incoming routing strategy</td><td>N/A</td></tr>
     *   <tr><td>Outgoing routing strategy</td><td>Round-robin</td></tr>
     *   <tr><td>Action in mute state</td><td>Block</td></tr>
     * </table>
     */
    public static final int PUSH   = zmq.api.ZMQ.ZMQ_PUSH;
    /**
     * Flag to specify a XPUB socket, receiving side must be a SUB or XSUB.
     * <p/>
     * Subscriptions can be received as a message. Subscriptions start with
     * a '1' byte. Unsubscriptions start with a '0' byte.
     * <p/>
     * Same as {@link ZMQ#PUB} except that you can receive subscriptions from the peers in form of incoming messages.
     * <br/>
     * Subscription message is a byte 1 (for subscriptions) or byte 0 (for unsubscriptions) followed by the subscription body.
     * <br/>
     * Messages without a sub/unsub prefix are also received, but have no effect on subscription status.
     * <p/>
     * <table summary="" border="1">
     *   <th colspan="2">Summary of socket characteristics</th>
     *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#SUB}, {@link ZMQ#XSUB}</td></tr>
     *   <tr><td>Direction</td><td>Unidirectional</td></tr>
     *   <tr><td>Send/receive pattern</td><td>Send messages, receive subscriptions</td></tr>
     *   <tr><td>Incoming routing strategy</td><td>N/A</td></tr>
     *   <tr><td>Outgoing routing strategy</td><td>Fan out</td></tr>
     *   <tr><td>Action in mute state</td><td>Drop</td></tr>
     * </table>
     */
    public static final int XPUB   = zmq.api.ZMQ.ZMQ_XPUB;
    /**
     * Flag to specify the receiving part of the PUB or XPUB socket.
     * <p/>
     * Same as {@link ZMQ#SUB} except that you subscribe by sending subscription messages to the socket.
     * <br/>
     * Subscription message is a byte 1 (for subscriptions) or byte 0 (for unsubscriptions) followed by the subscription body.
     * <br/>
     * Messages without a sub/unsub prefix may also be sent, but have no effect on subscription status.
     * <p/>
     * <table summary="" border="1">
     *   <th colspan="2">Summary of socket characteristics</th>
     *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#PUB}, {@link ZMQ#XPUB}</td></tr>
     *   <tr><td>Direction</td><td>Unidirectional</td></tr>
     *   <tr><td>Send/receive pattern</td><td>Receive messages, send subscriptions</td></tr>
     *   <tr><td>Incoming routing strategy</td><td>Fair-queued</td></tr>
     *   <tr><td>Outgoing routing strategy</td><td>N/A</td></tr>
     *   <tr><td>Action in mute state</td><td>Drop</td></tr>
     * </table>
     */
    public static final int XSUB   = zmq.api.ZMQ.ZMQ_XSUB;
    /**
     * Flag to specify a STREAM socket.
     * <p/>
     * A socket of type STREAM is used to send and receive TCP data from a non-ØMQ peer, when using the tcp:// transport.
     * A STREAM socket can act as client and/or server, sending and/or receiving TCP data asynchronously.
     * <br/>
     * When receiving TCP data, a STREAM socket shall prepend a message part containing the identity
     * of the originating peer to the message before passing it to the application.
     * <br/>
     * Messages received are fair-queued from among all connected peers.
     * When sending TCP data, a STREAM socket shall remove the first part of the message
     * and use it to determine the identity of the peer the message shall be routed to,
     * and unroutable messages shall cause an EHOSTUNREACH or EAGAIN error.
     * <br/>
     * To open a connection to a server, use the {@link ZMQ.Socket#connect(String)} call, and then fetch the socket identity using the {@link ZMQ.Socket#getIdentity()} call.
     * To close a specific connection, send the identity frame followed by a zero-length message.
     * When a connection is made, a zero-length message will be received by the application.
     * Similarly, when the peer disconnects (or the connection is lost), a zero-length message will be received by the application.
     * The {@link ZMQ#SNDMORE} flag is ignored on data frames. You must send one identity frame followed by one data frame.
     * <br/>
     * Also, please note that omitting the SNDMORE flag will prevent sending further data (from any client) on the same socket.
     * <p/>
     * <table summary="" border="1">
     *   <th colspan="2">Summary of socket characteristics</th>
     *   <tr><td>Compatible peer sockets</td><td>none</td></tr>
     *   <tr><td>Direction</td><td>Bidirectional</td></tr>
     *   <tr><td>Send/receive pattern</td><td>Unrestricted</td></tr>
     *   <tr><td>Incoming routing strategy</td><td>Fair-queued</td></tr>
     *   <tr><td>Outgoing routing strategy</td><td>See text</td></tr>
     *   <tr><td>Action in mute state</td><td>EAGAIN</td></tr>
     * </table>
     */
    public static final int STREAM = zmq.api.ZMQ.ZMQ_STREAM;

    public enum Sockets
    {
        /**
         * Flag to specify a exclusive pair of sockets.
         * <p/>
         * A socket of type PAIR can only be connected to a single peer at any one time.
         * <br/>
         * No message routing or filtering is performed on messages sent over a PAIR socket.
         * <br/>
         * When a PAIR socket enters the mute state due to having reached the high water mark for the connected peer,
         * or if no peer is connected, then any send() operations on the socket shall block until the peer becomes available for sending;
         * messages are not discarded.
         * <p/>
         * <table summary="" border="1">
         *   <th colspan="2">Summary of socket characteristics</th>
         *   <tr><td>Compatible peer sockets</td><td>PAIR</td></tr>
         *   <tr><td>Direction</td><td>Bidirectional</td></tr>
         *   <tr><td>Send/receive pattern</td><td>Unrestricted</td></tr>
         *   <tr><td>Incoming routing strategy</td><td>N/A</td></tr>
         *   <tr><td>Outgoing routing strategy</td><td>N/A</td></tr>
         *   <tr><td>Action in mute state</td><td>Block</td></tr>
         * </table>
         * <p/>
         * <strong>PAIR sockets are designed for inter-thread communication across the inproc transport
         * and do not implement functionality such as auto-reconnection.
         * PAIR sockets are considered experimental and may have other missing or broken aspects.</strong>
         */
        PAIR(zmq.api.ZMQ.ZMQ_PAIR),
        /**
         * Flag to specify a PUB socket, receiving side must be a SUB or XSUB.
         * <p/>
         * A socket of type PUB is used by a publisher to distribute data.
         * <br/>
         * Messages sent are distributed in a fan out fashion to all connected peers.
         * <br/>
         * The {@link ZMQ.Socket#recv()} function is not implemented for this socket type.
         * <br/>
         * When a PUB socket enters the mute state due to having reached the high water mark for a subscriber,
         * then any messages that would be sent to the subscriber in question shall instead be dropped until the mute state ends.
         * <br/>
         * The send methods shall never block for this socket type.
         * <p/>
         * <table summary="" border="1">
         *   <th colspan="2">Summary of socket characteristics</th>
         *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#SUB}, {@link ZMQ#XSUB}</td></tr>
         *   <tr><td>Direction</td><td>Unidirectional</td></tr>
         *   <tr><td>Send/receive pattern</td><td>Send only</td></tr>
         *   <tr><td>Incoming routing strategy</td><td>N/A</td></tr>
         *   <tr><td>Outgoing routing strategy</td><td>Fan out</td></tr>
         *   <tr><td>Action in mute state</td><td>Drop</td></tr>
         * </table>
         */
        PUB(zmq.api.ZMQ.ZMQ_PUB),
        /**
         * Flag to specify the receiving part of the PUB or XPUB socket.
         * <p/>
         * A socket of type SUB is used by a subscriber to subscribe to data distributed by a publisher.
         * <br/>
         * Initially a SUB socket is not subscribed to any messages,
         * use the {@link ZMQ.Socket#subscribe(byte[])} option to specify which messages to subscribe to.
         * <br/>
         * The send methods are not implemented for this socket type.
         * <p/>
         * <table summary="" border="1">
         *   <th colspan="2">Summary of socket characteristics</th>
         *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#PUB}, {@link ZMQ#XPUB}</td></tr>
         *   <tr><td>Direction</td><td>Unidirectional</td></tr>
         *   <tr><td>Send/receive pattern</td><td>Receive only</td></tr>
         *   <tr><td>Incoming routing strategy</td><td>Fair-queued</td></tr>
         *   <tr><td>Outgoing routing strategy</td><td>N/A</td></tr>
         * </table>
         */
        SUB(zmq.api.ZMQ.ZMQ_SUB),
        /**
         * Flag to specify a REQ socket, receiving side must be a REP or ROUTER.
         * <p/>
         * A socket of type REQ is used by a client to send requests to and receive replies from a service.
         * <br/>
         * This socket type allows only an alternating sequence of send(request) and subsequent recv(reply) calls.
         * <br/>
         * Each request sent is round-robined among all services, and each reply received is matched with the last issued request.
         * <br/>
         * If no services are available, then any send operation on the socket shall block until at least one service becomes available.
         * <br/>
         * The REQ socket shall not discard messages.
         * <p/>
         * <table summary="" border="1">
         *   <th colspan="2">Summary of socket characteristics</th>
         *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#REP}, {@link ZMQ#ROUTER}</td></tr>
         *   <tr><td>Direction</td><td>Bidirectional</td></tr>
         *   <tr><td>Send/receive pattern</td><td>Send, Receive, Send, Receive, ...</td></tr>
         *   <tr><td>Incoming routing strategy</td><td>Last peer</td></tr>
         *   <tr><td>Outgoing routing strategy</td><td>Round-robin</td></tr>
         *   <tr><td>Action in mute state</td><td>Block</td></tr>
         * </table>
         */
        REQ(zmq.api.ZMQ.ZMQ_REQ),
        /**
         * Flag to specify the receiving part of a REQ or DEALER socket.
         * <p/>
         * A socket of type REP is used by a service to receive requests from and send replies to a client.
         * <br/>
         * This socket type allows only an alternating sequence of recv(request) and subsequent send(reply) calls.
         * <br/>
         * Each request received is fair-queued from among all clients, and each reply sent is routed to the client that issued the last request.
         * <br/>
         * If the original requester does not exist any more the reply is silently discarded.
         * <p/>
         * <table summary="" border="1">
         *   <th colspan="2">Summary of socket characteristics</th>
         *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#REQ}, {@link ZMQ#DEALER}</td></tr>
         *   <tr><td>Direction</td><td>Bidirectional</td></tr>
         *   <tr><td>Send/receive pattern</td><td>Receive, Send, Receive, Send, ...</td></tr>
         *   <tr><td>Incoming routing strategy</td><td>Fair-queued</td></tr>
         *   <tr><td>Outgoing routing strategy</td><td>Last peer</td></tr>
         * </table>
         */
        REP(zmq.api.ZMQ.ZMQ_REP),
        /**
         * Flag to specify a DEALER socket (aka XREQ).
         * <p/>
         * DEALER is really a combined ventilator / sink
         * that does load-balancing on output and fair-queuing on input
         * with no other semantics. It is the only socket type that lets
         * you shuffle messages out to N nodes and shuffle the replies
         * back, in a raw bidirectional asynch pattern.
         * <p/>
         * A socket of type DEALER is an advanced pattern used for extending request/reply sockets.
         * <br/>
         * Each message sent is round-robined among all connected peers, and each message received is fair-queued from all connected peers.
         * <br/>
         * When a DEALER socket enters the mute state due to having reached the high water mark for all peers,
         * or if there are no peers at all, then any send() operations on the socket shall block
         * until the mute state ends or at least one peer becomes available for sending; messages are not discarded.
         * <br/>
         * When a DEALER socket is connected to a {@link ZMQ#REP} socket each message sent must consist of an empty message part, the delimiter, followed by one or more body parts.
         * <p/>
         * <table summary="" border="1">
         *   <th colspan="2">Summary of socket characteristics</th>
         *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#ROUTER}, {@link ZMQ#REP}, {@link ZMQ#DEALER}</td></tr>
         *   <tr><td>Direction</td><td>Bidirectional</td></tr>
         *   <tr><td>Send/receive pattern</td><td>Unrestricted</td></tr>
         *   <tr><td>Incoming routing strategy</td><td>Fair-queued</td></tr>
         *   <tr><td>Outgoing routing strategy</td><td>Round-robin</td></tr>
         *   <tr><td>Action in mute state</td><td>Block</td></tr>
         * </table>
         */
        DEALER(zmq.api.ZMQ.ZMQ_DEALER),
        /**
         * Flag to specify ROUTER socket (aka XREP).
         * <p/>
         * ROUTER is the socket that creates and consumes request-reply
         * routing envelopes. It is the only socket type that lets you route
         * messages to specific connections if you know their identities.
         * <p/>
         * A socket of type ROUTER is an advanced socket type used for extending request/reply sockets.
         * <p/>
         * When receiving messages a ROUTER socket shall prepend a message part containing the identity
         * of the originating peer to the message before passing it to the application.
         * <br/>
         * Messages received are fair-queued from among all connected peers.
         * <p/>
         * When sending messages a ROUTER socket shall remove the first part of the message
         * and use it to determine the identity of the peer the message shall be routed to.
         * If the peer does not exist anymore the message shall be silently discarded by default,
         * unless {@link ZMQ.Socket#setRouterMandatory(boolean)} socket option is set to true.
         * <p/>
         * When a ROUTER socket enters the mute state due to having reached the high water mark for all peers,
         * then any messages sent to the socket shall be dropped until the mute state ends.
         * <br/>
         * Likewise, any messages routed to a peer for which the individual high water mark has been reached shall also be dropped,
         * , unless {@link ZMQ.Socket#setRouterMandatory(boolean)} socket option is set to true.
         * <p/>
         * When a {@link ZMQ#REQ} socket is connected to a ROUTER socket, in addition to the identity of the originating peer
         * each message received shall contain an empty delimiter message part.
         * <br/>
         * Hence, the entire structure of each received message as seen by the application becomes:
         * one or more identity parts,
         * delimiter part,
         * one or more body parts.
         * <p/>
         * When sending replies to a REQ socket the application must include the delimiter part.
         * <p/>
         * <table summary="" border="1">
         *   <th colspan="2">Summary of socket characteristics</th>
         *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#DEALER}, {@link ZMQ#REQ}, {@link ZMQ#ROUTER}</td></tr>
         *   <tr><td>Direction</td><td>Bidirectional</td></tr>
         *   <tr><td>Send/receive pattern</td><td>Unrestricted</td></tr>
         *   <tr><td>Incoming routing strategy</td><td>Fair-queued</td></tr>
         *   <tr><td>Outgoing routing strategy</td><td>See text</td></tr>
         *   <tr><td>Action in mute state</td><td>Drop (See text)</td></tr>
         * </table>
         */
        ROUTER(zmq.api.ZMQ.ZMQ_ROUTER),
        /**
         * Flag to specify the receiving part of a PUSH socket.
         * <p/>
         * A socket of type ZMQ_PULL is used by a pipeline node to receive messages from upstream pipeline nodes.
         * <br/>
         * Messages are fair-queued from among all connected upstream nodes.
         * <br/>
         * The send() function is not implemented for this socket type.
         * <p/>
         * <table summary="" border="1">
         *   <th colspan="2">Summary of socket characteristics</th>
         *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#PUSH}</td></tr>
         *   <tr><td>Direction</td><td>Unidirectional</td></tr>
         *   <tr><td>Send/receive pattern</td><td>Receive only</td></tr>
         *   <tr><td>Incoming routing strategy</td><td>Fair-queued</td></tr>
         *   <tr><td>Outgoing routing strategy</td><td>N/A</td></tr>
         *   <tr><td>Action in mute state</td><td>Block</td></tr>
         * </table>
         */
        PULL(zmq.api.ZMQ.ZMQ_PULL),
        /**
         * Flag to specify a PUSH socket, receiving side must be a PULL.
         * <p/>
         * A socket of type PUSH is used by a pipeline node to send messages to downstream pipeline nodes.
         * <br/>
         * Messages are round-robined to all connected downstream nodes.
         * <br/>
         * The recv() function is not implemented for this socket type.
         * <br/>
         * When a PUSH socket enters the mute state due to having reached the high water mark for all downstream nodes,
         * or if there are no downstream nodes at all, then any send() operations on the socket shall block until the mute state ends
         * or at least one downstream node becomes available for sending; messages are not discarded.
         * <p/>
         * <table summary="" border="1">
         *   <th colspan="2">Summary of socket characteristics</th>
         *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#PULL}</td></tr>
         *   <tr><td>Direction</td><td>Unidirectional</td></tr>
         *   <tr><td>Send/receive pattern</td><td>Send only</td></tr>
         *   <tr><td>Incoming routing strategy</td><td>N/A</td></tr>
         *   <tr><td>Outgoing routing strategy</td><td>Round-robin</td></tr>
         *   <tr><td>Action in mute state</td><td>Block</td></tr>
         * </table>
         */
        PUSH(zmq.api.ZMQ.ZMQ_PUSH),
        /**
         * Flag to specify a XPUB socket, receiving side must be a SUB or XSUB.
         * <p/>
         * Subscriptions can be received as a message. Subscriptions start with
         * a '1' byte. Unsubscriptions start with a '0' byte.
         * <p/>
         * Same as {@link ZMQ#PUB} except that you can receive subscriptions from the peers in form of incoming messages.
         * <br/>
         * Subscription message is a byte 1 (for subscriptions) or byte 0 (for unsubscriptions) followed by the subscription body.
         * <br/>
         * Messages without a sub/unsub prefix are also received, but have no effect on subscription status.
         * <p/>
         * <table summary="" border="1">
         *   <th colspan="2">Summary of socket characteristics</th>
         *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#SUB}, {@link ZMQ#XSUB}</td></tr>
         *   <tr><td>Direction</td><td>Unidirectional</td></tr>
         *   <tr><td>Send/receive pattern</td><td>Send messages, receive subscriptions</td></tr>
         *   <tr><td>Incoming routing strategy</td><td>N/A</td></tr>
         *   <tr><td>Outgoing routing strategy</td><td>Fan out</td></tr>
         *   <tr><td>Action in mute state</td><td>Drop</td></tr>
         * </table>
         */
        XPUB(zmq.api.ZMQ.ZMQ_XPUB),
        /**
         * Flag to specify the receiving part of the PUB or XPUB socket.
         * <p/>
         * Same as {@link ZMQ#SUB} except that you subscribe by sending subscription messages to the socket.
         * <br/>
         * Subscription message is a byte 1 (for subscriptions) or byte 0 (for unsubscriptions) followed by the subscription body.
         * <br/>
         * Messages without a sub/unsub prefix may also be sent, but have no effect on subscription status.
         * <p/>
         * <table summary="" border="1">
         *   <th colspan="2">Summary of socket characteristics</th>
         *   <tr><td>Compatible peer sockets</td><td>{@link ZMQ#PUB}, {@link ZMQ#XPUB}</td></tr>
         *   <tr><td>Direction</td><td>Unidirectional</td></tr>
         *   <tr><td>Send/receive pattern</td><td>Receive messages, send subscriptions</td></tr>
         *   <tr><td>Incoming routing strategy</td><td>Fair-queued</td></tr>
         *   <tr><td>Outgoing routing strategy</td><td>N/A</td></tr>
         *   <tr><td>Action in mute state</td><td>Drop</td></tr>
         * </table>
         */
        XSUB(zmq.api.ZMQ.ZMQ_XSUB),
        /**
         * Flag to specify a STREAM socket.
         * <p/>
         * A socket of type STREAM is used to send and receive TCP data from a non-ØMQ peer, when using the tcp:// transport.
         * A STREAM socket can act as client and/or server, sending and/or receiving TCP data asynchronously.
         * <br/>
         * When receiving TCP data, a STREAM socket shall prepend a message part containing the identity
         * of the originating peer to the message before passing it to the application.
         * <br/>
         * Messages received are fair-queued from among all connected peers.
         * When sending TCP data, a STREAM socket shall remove the first part of the message
         * and use it to determine the identity of the peer the message shall be routed to,
         * and unroutable messages shall cause an EHOSTUNREACH or EAGAIN error.
         * <br/>
         * To open a connection to a server, use the {@link ZMQ.Socket#connect(String)} call, and then fetch the socket identity using the {@link ZMQ.Socket#getIdentity()} call.
         * To close a specific connection, send the identity frame followed by a zero-length message.
         * When a connection is made, a zero-length message will be received by the application.
         * Similarly, when the peer disconnects (or the connection is lost), a zero-length message will be received by the application.
         * The {@link ZMQ#SNDMORE} flag is ignored on data frames. You must send one identity frame followed by one data frame.
         * <br/>
         * Also, please note that omitting the SNDMORE flag will prevent sending further data (from any client) on the same socket.
         * <p/>
         * <table summary="" border="1">
         *   <th colspan="2">Summary of socket characteristics</th>
         *   <tr><td>Compatible peer sockets</td><td>none</td></tr>
         *   <tr><td>Direction</td><td>Bidirectional</td></tr>
         *   <tr><td>Send/receive pattern</td><td>Unrestricted</td></tr>
         *   <tr><td>Incoming routing strategy</td><td>Fair-queued</td></tr>
         *   <tr><td>Outgoing routing strategy</td><td>See text</td></tr>
         *   <tr><td>Action in mute state</td><td>EAGAIN</td></tr>
         * </table>
         */
        STREAM(zmq.api.ZMQ.ZMQ_STREAM);

        private final int type;

        private Sockets(int type)
        {
            this.type = type;
        }

        public static Sockets of(int type)
        {
            for (Sockets socket : Sockets.values()) {
                if (socket.type == type) {
                    return socket;
                }
            }
            return null;
        }
    }

    /**
     * Flag to specify a STREAMER device.
     */
    @Deprecated
    public static final int STREAMER   = zmq.api.ZMQ.ZMQ_STREAMER;
    /**
     * Flag to specify a FORWARDER device.
     */
    @Deprecated
    public static final int FORWARDER  = zmq.api.ZMQ.ZMQ_FORWARDER;
    /**
     * Flag to specify a QUEUE device.
     */
    @Deprecated
    public static final int QUEUE      = zmq.api.ZMQ.ZMQ_QUEUE;
    /**
     * @see org.zeromq.ZMQ#PULL
     */
    @Deprecated
    public static final int UPSTREAM   = PULL;
    /**
     * @see org.zeromq.ZMQ#PUSH
     */
    @Deprecated
    public static final int DOWNSTREAM = PUSH;

    /**
     * EVENT_CONNECTED: connection established.
     * The EVENT_CONNECTED event triggers when a connection has been
     * established to a remote peer. This can happen either synchronous
     * or asynchronous. Value is the FD of the newly connected socket.
     */
    public static final int EVENT_CONNECTED          = zmq.api.ZMQ.ZMQ_EVENT_CONNECTED;
    /**
     * EVENT_CONNECT_DELAYED: synchronous connect failed, it's being polled.
     * The EVENT_CONNECT_DELAYED event triggers when an immediate connection
     * attempt is delayed and its completion is being polled for. Value has
     * no meaning.
     */
    public static final int EVENT_CONNECT_DELAYED    = zmq.api.ZMQ.ZMQ_EVENT_CONNECT_DELAYED;
    /**
     * @see org.zeromq.ZMQ#EVENT_CONNECT_DELAYED
     */
    @Deprecated
    public static final int EVENT_DELAYED            = EVENT_CONNECT_DELAYED;
    /**
     * EVENT_CONNECT_RETRIED: asynchronous connect / reconnection attempt.
     * The EVENT_CONNECT_RETRIED event triggers when a connection attempt is
     * being handled by reconnect timer. The reconnect interval's recomputed
     * for each attempt. Value is the reconnect interval.
     */
    public static final int EVENT_CONNECT_RETRIED    = zmq.api.ZMQ.ZMQ_EVENT_CONNECT_RETRIED;
    /**
     * @see org.zeromq.ZMQ#EVENT_CONNECT_RETRIED
     */
    @Deprecated
    public static final int EVENT_RETRIED            = EVENT_CONNECT_RETRIED;
    /**
     * EVENT_LISTENING: socket bound to an address, ready to accept connections.
     * The EVENT_LISTENING event triggers when a socket's successfully bound to
     * a an interface. Value is the FD of the newly bound socket.
     */
    public static final int EVENT_LISTENING          = zmq.api.ZMQ.ZMQ_EVENT_LISTENING;
    /**
     * EVENT_BIND_FAILED: socket could not bind to an address.
     * The EVENT_BIND_FAILED event triggers when a socket could not bind to a
     * given interface. Value is the errno generated by the bind call.
     */
    public static final int EVENT_BIND_FAILED        = zmq.api.ZMQ.ZMQ_EVENT_BIND_FAILED;
    /**
     * EVENT_ACCEPTED: connection accepted to bound interface.
     * The EVENT_ACCEPTED event triggers when a connection from a remote peer
     * has been established with a socket's listen address. Value is the FD of
     * the accepted socket.
     */
    public static final int EVENT_ACCEPTED           = zmq.api.ZMQ.ZMQ_EVENT_ACCEPTED;
    /**
     * EVENT_ACCEPT_FAILED: could not accept client connection.
     * The EVENT_ACCEPT_FAILED event triggers when a connection attempt to a
     * socket's bound address fails. Value is the errno generated by accept.
     */
    public static final int EVENT_ACCEPT_FAILED      = zmq.api.ZMQ.ZMQ_EVENT_ACCEPT_FAILED;
    /**
     * EVENT_CLOSED: connection closed.
     * The EVENT_CLOSED event triggers when a connection's underlying
     * descriptor has been closed. Value is the former FD of the for the
     * closed socket. FD has been closed already!
     */
    public static final int EVENT_CLOSED             = zmq.api.ZMQ.ZMQ_EVENT_CLOSED;
    /**
     * EVENT_CLOSE_FAILED: connection couldn't be closed.
     * The EVENT_CLOSE_FAILED event triggers when a descriptor could not be
     * released back to the OS. Implementation note: ONLY FOR IPC SOCKETS.
     * Value is the errno generated by unlink.
     */
    public static final int EVENT_CLOSE_FAILED       = zmq.api.ZMQ.ZMQ_EVENT_CLOSE_FAILED;
    /**
     * EVENT_DISCONNECTED: broken session.
     * The EVENT_DISCONNECTED event triggers when the stream engine (tcp and
     * ipc specific) detects a corrupted / broken session. Value is the FD of
     * the socket.
     */
    public static final int EVENT_DISCONNECTED       = zmq.api.ZMQ.ZMQ_EVENT_DISCONNECTED;
    /**
     * EVENT_MONITOR_STOPPED: monitor has been stopped.
     * The EVENT_MONITOR_STOPPED event triggers when the monitor for a socket is
     * stopped.
     */
    public static final int EVENT_MONITOR_STOPPED    = zmq.api.ZMQ.ZMQ_EVENT_MONITOR_STOPPED;
    /**
     * EVENT_HANDSHAKE_PROTOCOL: protocol has been successfully negotiated.
     * The EVENT_HANDSHAKE_PROTOCOL event triggers when the stream engine (tcp and ipc)
     * successfully negotiated a protocol version with the peer. Value is the version number
     * (0 for unversioned, 3 for V3).
     */
    public static final int EVENT_HANDSHAKE_PROTOCOL = zmq.api.ZMQ.ZMQ_EVENT_HANDSHAKE_PROTOCOL;
    /**
     * EVENT_ALL: all events known.
     * The EVENT_ALL constant can be used to set up a monitor for all known events.
     */
    public static final int EVENT_ALL                = zmq.api.ZMQ.ZMQ_EVENT_ALL;

    public static final byte[] MESSAGE_SEPARATOR = zmq.api.ZMQ.MESSAGE_SEPARATOR;

    public static final byte[] SUBSCRIPTION_ALL = zmq.api.ZMQ.SUBSCRIPTION_ALL;

    public static final byte[] PROXY_PAUSE     = zmq.api.ZMQ.PROXY_PAUSE;
    public static final byte[] PROXY_RESUME    = zmq.api.ZMQ.PROXY_RESUME;
    public static final byte[] PROXY_TERMINATE = zmq.api.ZMQ.PROXY_TERMINATE;

    public static final Charset CHARSET = zmq.api.ZMQ.CHARSET;

    private ZMQ()
    {
    }

    /**
     * Create a new Context.
     *
     * @param ioThreads
     *            Number of threads to use, usually 1 is sufficient for most use cases.
     * @return the Context
     */
    public static Context context(int ioThreads)
    {
        return new Context(ioThreads);
    }

    /**
     * Container for all sockets in a single process,
     * acting as the transport for inproc sockets,
     * which are the fastest way to connect threads in one process.
     */
    public static class Context implements Closeable
    {
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AContext      ctx;

        /**
         * Class constructor.
         *
         * @param ioThreads
         *            size of the threads pool to handle I/O operations.
         */
        protected Context(int ioThreads)
        {
            ctx = PROVIDER.context(ioThreads);
        }

        /**
         * Returns true if terminate() has been called on ctx.
         */
        public boolean isTerminated()
        {
            return !ctx.isAlive();
        }

        /**
         * The size of the 0MQ thread pool to handle I/O operations.
         */
        public int getIOThreads()
        {
            return ctx.getOption(zmq.api.ZMQ.ZMQ_IO_THREADS);
        }

        /**
         * Set the size of the 0MQ thread pool to handle I/O operations.
         */
        public boolean setIOThreads(int ioThreads)
        {
            return ctx.setOption(zmq.api.ZMQ.ZMQ_IO_THREADS, ioThreads);
        }

        /**
         * The maximum number of sockets allowed on the context
         */
        public int getMaxSockets()
        {
            return ctx.getOption(zmq.api.ZMQ.ZMQ_MAX_SOCKETS);
        }

        /**
         * Sets the maximum number of sockets allowed on the context
         */
        public boolean setMaxSockets(int maxSockets)
        {
            return ctx.setOption(zmq.api.ZMQ.ZMQ_MAX_SOCKETS, maxSockets);
        }

        /**
         * @deprecated use {@link #isBlocky()} instead
         */
        @Deprecated
        public boolean getBlocky()
        {
            return isBlocky();
        }

        public boolean isBlocky()
        {
            return ctx.getOption(zmq.api.ZMQ.ZMQ_BLOCKY) != 0;
        }

        public boolean setBlocky(boolean block)
        {
            return ctx.setOption(zmq.api.ZMQ.ZMQ_BLOCKY, block ? 1 : 0);
        }

        public boolean isIPv6()
        {
            return ctx.getOption(zmq.api.ZMQ.ZMQ_IPV6) != 0;
        }

        public boolean getIPv6()
        {
            return isIPv6();
        }

        public boolean setIPv6(boolean ipv6)
        {
            return ctx.setOption(zmq.api.ZMQ.ZMQ_IPV6, ipv6 ? 1 : 0);
        }

        /**
         * This is an explicit "destructor". It can be called to ensure the corresponding 0MQ
         * Context has been disposed of.
         */
        public void term()
        {
            if (closed.compareAndSet(false, true)) {
                ctx.terminate();
            }
        }

        public boolean isClosed()
        {
            return closed.get();
        }

        /**
         * Creates a ØMQ socket within the specified context and return an opaque handle to the newly created socket.
         * <br/>
         * The type argument specifies the socket type, which determines the semantics of communication over the socket.
         * <br/>
         * The newly created socket is initially unbound, and not associated with any endpoints.
         * <br/>
         * In order to establish a message flow a socket must first be connected
         * to at least one endpoint with {@link org.zeromq.ZMQ.Socket#connect(String)},
         * or at least one endpoint must be created for accepting incoming connections with {@link org.zeromq.ZMQ.Socket#bind(String)}.
         *
         * @param type
         *            the socket type.
         * @return the newly created Socket.
         */
        public Socket socket(int type)
        {
            return new Socket(this, type);
        }

        /**
         * Create a new Selector within this context.
         *
         * @return the newly created Selector.
         */
        public Selector selector()
        {
            return ctx.createSelector();
        }

        /**
         * Closes a Selector that was created within this context.
         *
         * @param selector the Selector to close.
         * @return true if the selector was closed. otherwise false
         * (mostly because it was not created by the context).
         */
        public boolean close(Selector selector)
        {
            return ctx.closeSelector(selector);
        }

        /**
         * Create a new Poller within this context, with a default size.
         * DO NOT FORGET TO CLOSE THE POLLER AFTER USE with {@link Poller#close()}
         *
         * @return the newly created Poller.
         */
        public Poller poller()
        {
            return new Poller(this);
        }

        /**
         * Create a new Poller within this context, with a specified initial size.
         * DO NOT FORGET TO CLOSE THE POLLER AFTER USE with {@link Poller#close()}
         * @param size
         *            the poller initial size.
         * @return the newly created Poller.
         */
        public Poller poller(int size)
        {
            return new Poller(this, size);
        }

        /**
         * Destroys the ØMQ context context.
         * Context termination is performed in the following steps:
         * <ul>
         * <li>Any blocking operations currently in progress on sockets open within context
         * shall return immediately with an error code of ETERM.
         * With the exception of {@link ZMQ.Socket#close()}, any further operations on sockets
         * open within context shall fail with an error code of ETERM.</li>
         * <li>After interrupting all blocking calls, this method shall block until the following conditions are satisfied:
         * <ul>
         * <li>All sockets open within context have been closed with {@link ZMQ.Socket#close()}.</li>
         * <li>For each socket within context, all messages sent by the application with {@link ZMQ.Socket#send} have either
         * been physically transferred to a network peer,
         * or the socket's linger period set with the {@link ZMQ.Socket#setLinger(int)} socket option has expired.</li>
         * </ul>
         * </li>
         * </ul>
         * <p/>
         * <h1>Warning</h1>
         * <br/>
         * As ZMQ_LINGER defaults to "infinite", by default this method will block indefinitely if there are any pending connects or sends.
         * We strongly recommend to
         * <ul>
         * <li>set ZMQ_LINGER to zero on all sockets </li>
         * <li>close all sockets, before calling this method</li>
         * </ul>
         */
        @Override
        public void close()
        {
            term();
        }
    }

    /**
     * Abstracts an asynchronous message queue, with the exact queuing semantics depending on the socket type in use.
     * <br/>
     * Where conventional sockets transfer streams of bytes or discrete datagrams, ØMQ sockets transfer discrete messages.
     * <p/>
     * <h1>Key differences to conventional sockets</h1>
     * <br/>
     * Generally speaking, conventional sockets present a synchronous interface to either
     * connection-oriented reliable byte streams (SOCK_STREAM),
     * or connection-less unreliable datagrams (SOCK_DGRAM).
     * <br/>
     * In comparison, ØMQ sockets present an abstraction of an asynchronous message queue,
     * with the exact queueing semantics depending on the socket type in use.
     * Where conventional sockets transfer streams of bytes or discrete datagrams, ØMQ sockets transfer discrete messages.
     * <p/>
     * ØMQ sockets being asynchronous means that the timings of the physical connection setup and tear down, reconnect and effective delivery
     * are transparent to the user and organized by ØMQ itself.
     * Further, messages may be queued in the event that a peer is unavailable to receive them.
     * <p/>
     * Conventional sockets allow only strict one-to-one (two peers), many-to-one (many clients, one server), or in some cases one-to-many (multicast) relationships.
     * With the exception of {@link ZMQ#PAIR}, ØMQ sockets may be connected to multiple endpoints using {@link ZMQ.Socket#connect(String)},
     * while simultaneously accepting incoming connections from multiple endpoints bound to the socket using {@link ZMQ.Socket#bind(String)},
     * thus allowing many-to-many relationships.
     * <p/>
     * <h1>Thread safety</h1>
     * <br/>
     * ØMQ sockets are not thread safe. <strong>Applications MUST NOT use a socket from multiple threads</strong>
     * except after migrating a socket from one thread to another with a "full fence" memory barrier.
     * <br/>
     * ØMQ sockets are not Thread.interrupt safe. <strong>Applications MUST NOT interrupt threads using ØMQ sockets</strong>.
     * <p/>
     * <h1>Messaging patterns</h1>
     * <br/>
     * <ul>
     * <li>Request-reply
     * <br/>The request-reply pattern is used for sending requests from a {@link ZMQ#REQ} client to one or more {@link ZMQ#REP} services, and receiving subsequent replies to each request sent.
     * The request-reply pattern is formally defined by http://rfc.zeromq.org/spec:28.
     * {@link ZMQ#REQ}, {@link ZMQ#REP}, {@link ZMQ#DEALER}, {@link ZMQ#ROUTER} socket types belong to this pattern.
     * </li>
     * <li>Publish-subscribe
     * <br/>
     * The publish-subscribe pattern is used for one-to-many distribution of data from a single publisher to multiple subscribers in a fan out fashion.
     * The publish-subscribe pattern is formally defined by http://rfc.zeromq.org/spec:29.
     * {@link ZMQ#SUB}, {@link ZMQ#PUB}, {@link ZMQ#XSUB}, {@link ZMQ#XPUB} socket types belong to this pattern.
     * </li>
     * <li>Pipeline
     * <br/>
     * The pipeline pattern is used for distributing data to nodes arranged in a pipeline. Data always flows down the pipeline, and each stage of the pipeline is connected to at least one node.
     * When a pipeline stage is connected to multiple nodes data is round-robined among all connected nodes.
     * The pipeline pattern is formally defined by http://rfc.zeromq.org/spec:30.
     * {@link ZMQ#PUSH}, {@link ZMQ#PULL} socket types belong to this pattern.
     * </li>
     * <li>Exclusive pair
     * <br/>
     * The exclusive pair pattern is used to connect a peer to precisely one other peer. This pattern is used for inter-thread communication across the inproc transport,
     * using {@link ZMQ#PAIR} socket type.
     * The exclusive pair pattern is formally defined by http://rfc.zeromq.org/spec:31.
     * </li>
     * <li>Native
     * <br/>
     * The native pattern is used for communicating with TCP peers and allows asynchronous requests and replies in either direction,
     * using {@link ZMQ#STREAM} socket type.
     * </li>
     * </ul>
     */
    public static class Socket implements Closeable
    {
        public enum Mechanism
        {
            NULL(AMechanism.NULL),
            PLAIN(AMechanism.PLAIN),
            CURVE(AMechanism.CURVE),
            GSSAPI(AMechanism.GSSAPI);

            private final AMechanism delegate;

            private Mechanism(AMechanism delegate)
            {
                this.delegate = delegate;
            }

            public static Mechanism of(AMechanism mechanism)
            {
                for (Mechanism mech : Mechanism.values()) {
                    if (mech.delegate == mechanism) {
                        return mech;
                    }
                }
                return null;
            }
        }

        //  This port range is defined by IANA for dynamic or private ports
        //  We use this when choosing a port for dynamic binding.
        private static final int DYNFROM = 0xc000;
        private static final int DYNTO   = 0xffff;

        private final AContext      ctx;
        private final ASocket       base;
        private final AtomicBoolean isClosed = new AtomicBoolean(false);

        /**
         * Class constructor.
         *
         * @param context
         *            a 0MQ context previously created.
         * @param type
         *            the socket type.
         */
        protected Socket(Context context, int type)
        {
            ctx = context.ctx;
            base = ctx.createSocket(type);
        }

        protected Socket(ASocket base)
        {
            ctx = null;
            this.base = base;
        }

        /**
         * DO NOT USE if you're trying to build a special proxy
         *
         * @return raw zmq.SocketBase
         */
        public ASocket base()
        {
            return base;
        }

        /**
         * This is an explicit "destructor". It can be called to ensure the corresponding 0MQ Socket
         * has been disposed of.
         */
        @Override
        public void close()
        {
            if (isClosed.compareAndSet(false, true)) {
                base.close();
            }
        }

        /**
         * The 'ZMQ_TYPE option shall retrieve the socket type for the specified
         * 'socket'.  The socket type is specified at socket creation time and
         * cannot be modified afterwards.
         *
         * @return the socket type.
         */
        public int getType()
        {
            if (before(2, 1, 0)) {
                return -1;
            }
            return (int) base.getSocketOpt(zmq.api.ZMQ.ZMQ_TYPE);
        }

        public String typename()
        {
            if (before(2, 1, 0)) {
                return "NONE";
            }
            return Sockets.of(getType()).name();
        }

        /**
         * The 'ZMQ_LINGER' option shall retrieve the period for pending outbound
         * messages to linger in memory after closing the socket. Value of -1 means
         * infinite. Pending messages will be kept until they are fully transferred to
         * the peer. Value of 0 means that all the pending messages are dropped immediately
         * when socket is closed. Positive value means number of milliseconds to keep
         * trying to send the pending messages before discarding them.
         *
         * @return the linger period.
         * @see #setLinger(int)
         */
        public long getLinger()
        {
            if (before(2, 1, 0)) {
                return -1;
            }
            return base.getSocketOpt(zmq.api.ZMQ.ZMQ_LINGER);
        }

        boolean setSocketOpt(int option, Object value)
        {
            try {
                boolean set = base.setSocketOpt(option, value);
                set &= base.errno() != ZError.EINVAL;
                return set;
            }
            catch (ZError.CtxTerminatedException e) {
                return false;
            }
        }

        /**
         * The ZMQ_LINGER option shall set the linger period for the specified socket.
         * The linger period determines how long pending messages which have yet to be sent to a peer
         * shall linger in memory after a socket is disconnected with disconnect or closed with close,
         * and further affects the termination of the socket's context with Ctx#term.
         * The following outlines the different behaviours: A value of -1 specifies an infinite linger period.
         * Pending messages shall not be discarded after a call to disconnect() or close();
         * attempting to terminate the socket's context with Ctx#term() shall block until all pending messages have been sent to a peer.
         * The value of 0 specifies no linger period. Pending messages shall be discarded immediately after a call to disconnect() or close().
         * Positive values specify an upper bound for the linger period in milliseconds.
         * Pending messages shall not be discarded after a call to disconnect() or close();
         * attempting to terminate the socket's context with Ctx#term() shall block until either all pending messages have been sent to a peer,
         * or the linger period expires, after which any pending messages shall be discarded.
         *
         * @param value
         *            the linger period in milliseconds.
         * @return true if the option was set, otherwise false
         * @see #getLinger()
         */
        public boolean setLinger(long value)
        {
            if (before(2, 1, 10)) {
                return false;
            }
            return base.setSocketOpt(zmq.api.ZMQ.ZMQ_LINGER, value);
        }

        /**
         * The ZMQ_LINGER option shall set the linger period for the specified socket.
         * The linger period determines how long pending messages which have yet to be sent to a peer
         * shall linger in memory after a socket is disconnected with disconnect or closed with close,
         * and further affects the termination of the socket's context with Ctx#term.
         * The following outlines the different behaviours: A value of -1 specifies an infinite linger period.
         * Pending messages shall not be discarded after a call to disconnect() or close();
         * attempting to terminate the socket's context with Ctx#term() shall block until all pending messages have been sent to a peer.
         * The value of 0 specifies no linger period. Pending messages shall be discarded immediately after a call to disconnect() or close().
         * Positive values specify an upper bound for the linger period in milliseconds.
         * Pending messages shall not be discarded after a call to disconnect() or close();
         * attempting to terminate the socket's context with Ctx#term() shall block until either all pending messages have been sent to a peer,
         * or the linger period expires, after which any pending messages shall be discarded.
         *
         * @param value
         *            the linger period in milliseconds.
         * @return true if the option was set, otherwise false
         * @see #getLinger()
         */
        public boolean setLinger(int value)
        {
            return base.setSocketOpt(zmq.api.ZMQ.ZMQ_LINGER, value);
        }

        /**
         * The ZMQ_RECONNECT_IVL option shall retrieve the initial reconnection interval for the specified socket.
         * The reconnection interval is the period ØMQ shall wait between attempts to reconnect
         * disconnected peers when using connection-oriented transports.
         * The value -1 means no reconnection.
         *
         * CAUTION: The reconnection interval may be randomized by ØMQ to prevent reconnection storms in topologies with a large number of peers per socket.
         *
         * @return the reconnectIVL.
         * @see #setReconnectIVL(int)
         * @since 3.0.0
         */
        public long getReconnectIVL()
        {
            if (before(2, 1, 10)) {
                return -1;
            }
            return base.getSocketOpt(zmq.api.ZMQ.ZMQ_RECONNECT_IVL);
        }

        /**
         * The ZMQ_RECONNECT_IVL option shall set the initial reconnection interval for the specified socket.
         * The reconnection interval is the period ØMQ shall wait between attempts
         * to reconnect disconnected peers when using connection-oriented transports.
         * The value -1 means no reconnection.
         *
         * @return true if the option was set, otherwise false
         * @deprecated reconnect interval option uses long range, use {@link #setReconnectIVL(long)} instead
         * @see #getReconnectIVL()
         */
        @Deprecated
        public boolean setReconnectIVL(int value)
        {
            return setReconnectIVL(Long.valueOf(value));
        }

        /**
         * The ZMQ_RECONNECT_IVL option shall set the initial reconnection interval for the specified socket.
         * The reconnection interval is the period ØMQ shall wait between attempts
         * to reconnect disconnected peers when using connection-oriented transports.
         * The value -1 means no reconnection.
         *
         * @return true if the option was set, otherwise false.
         * @see #getReconnectIVL()
         * @since 3.0.0
         */
        public boolean setReconnectIVL(long value)
        {
            if (before(2, 1, 10)) {
                return false;
            }
            return base.setSocketOpt(zmq.api.ZMQ.ZMQ_RECONNECT_IVL, value);
        }

        /**
         * The ZMQ_BACKLOG option shall retrieve the maximum length of the queue
         * of outstanding peer connections for the specified socket;
         * this only applies to connection-oriented transports.
         * For details refer to your operating system documentation for the listen function.
         *
         * @return the the maximum length of the queue of outstanding peer connections.
         * @see #setBacklog(int)
         * @since 3.0.0
         */
        public long getBacklog()
        {
            if (before(3, 0, 0)) {
                return -1;
            }
            return base.getSocketOpt(zmq.api.ZMQ.ZMQ_BACKLOG);
        }

        /**
         * The ZMQ_BACKLOG option shall set the maximum length
         * of the queue of outstanding peer connections for the specified socket;
         * this only applies to connection-oriented transports.
         * For details refer to your operating system documentation for the listen function.
         *
         * @param value the maximum length of the queue of outstanding peer connections.
         * @return true if the option was set, otherwise false.
         * @deprecated this option uses integer range, use {@link #setBacklog(int)} instead.
         * @see #getBacklog()
         */
        @Deprecated
        public boolean setBacklog(long value)
        {
            return setBacklog(Long.valueOf(value).intValue());
        }

        /**
         * The ZMQ_BACKLOG option shall set the maximum length
         * of the queue of outstanding peer connections for the specified socket;
         * this only applies to connection-oriented transports.
         * For details refer to your operating system documentation for the listen function.
         *
         * @param value the maximum length of the queue of outstanding peer connections.
         * @return true if the option was set, otherwise false.
         * @see #getBacklog()
         * @since 3.0.0
         */
        public boolean setBacklog(int value)
        {
            if (before(3, 0, 0)) {
                return false;
            }
            return setSocketOpt(zmq.api.ZMQ.ZMQ_BACKLOG, value);
        }

        /**
         * The ZMQ_HANDSHAKE_IVL option shall retrieve the maximum handshake interval
         * for the specified socket.
         * Handshaking is the exchange of socket configuration information
         * (socket type, identity, security) that occurs when a connection is first opened,
         * only for connection-oriented transports.
         * If handshaking does not complete within the configured time,
         * the connection shall be closed. The value 0 means no handshake time limit.
         *
         * @return the maximum handshake interval.
         * @see #setHandshakeIvl(int)
         */
        public long getHandshakeIvl()
        {
            return base.getSocketOpt(zmq.api.ZMQ.ZMQ_HANDSHAKE_IVL);
        }

        /**
         * The ZMQ_HEARTBEAT_IVL option shall set the interval
         * between sending ZMTP heartbeats for the specified socket.
         * If this option is set and is greater than 0,
         * then a PING ZMTP command will be sent every ZMQ_HEARTBEAT_IVL milliseconds.
         * @return heartbeat interval in milliseconds
         */
        public int getHeartbeatIvl()
        {
            return (int) base.getSocketOpt(zmq.api.ZMQ.ZMQ_HEARTBEAT_IVL);
        }

        /**
         * The ZMQ_HEARTBEAT_TIMEOUT option shall set
         * how long to wait before timing-out a connection
         * after sending a PING ZMTP command and not receiving any traffic.
         * This option is only valid if ZMQ_HEARTBEAT_IVL is also set,
         * and is greater than 0. The connection will time out
         * if there is no traffic received after sending the PING command,
         * but the received traffic does not have to be a PONG command
         * - any received traffic will cancel the timeout.
         * @return heartbeat timeout in milliseconds
         */
        public int getHeartbeatTimeout()
        {
            return (int) base.getSocketOpt(zmq.api.ZMQ.ZMQ_HEARTBEAT_TIMEOUT);
        }

        /**
         * The ZMQ_HEARTBEAT_TTL option shall set the timeout
         * on the remote peer for ZMTP heartbeats.
         * If this option is greater than 0,
         * the remote side shall time out the connection
         * if it does not receive any more traffic within the TTL period.
         * This option does not have any effect if ZMQ_HEARTBEAT_IVL is not set or is 0.
         * Internally, this value is rounded down to the nearest decisecond,
         * any value less than 100 will have no effect.
         * @return heartbeat time-to-live in milliseconds
         */
        public int getHeartbeatTtl()
        {
            return (int) base.getSocketOpt(zmq.api.ZMQ.ZMQ_HEARTBEAT_TTL);
        }

        /**
         * The ZMQ_HEARTBEAT_CONTEXT option shall set the ping context
         * of the peer for ZMTP heartbeats.
         *
         * This API is in DRAFT state and is subject to change at ANY time until declared stable.
         *
         * If this option is set, every ping message sent for heartbeat will contain this context.
         * @return the context to be sent with ping messages. Empty array by default.
         */
        @Draft
        public byte[] getHeartbeatContext()
        {
            return (byte[]) base.getSocketOptx(zmq.api.ZMQ.ZMQ_HEARTBEAT_CONTEXT);
        }

        /**
         * The ZMQ_HANDSHAKE_IVL option shall set the maximum handshake interval for the specified socket.
         * Handshaking is the exchange of socket configuration information (socket type, identity, security)
         * that occurs when a connection is first opened, only for connection-oriented transports.
         * If handshaking does not complete within the configured time, the connection shall be closed.
         * The value 0 means no handshake time limit.
         *
         * @param maxHandshakeIvl the maximum handshake interval
         * @return true if the option was set, otherwise false
         * @see #getHandshakeIvl()
         */
        public boolean setHandshakeIvl(long maxHandshakeIvl)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_HANDSHAKE_IVL, maxHandshakeIvl);
        }

        /**
         * The ZMQ_HEARTBEAT_IVL option shall set the interval
         * between sending ZMTP heartbeats for the specified socket.
         * If this option is set and is greater than 0,
         * then a PING ZMTP command will be sent every ZMQ_HEARTBEAT_IVL milliseconds.
         * @param heartbeatIvl heartbeat interval in milliseconds
         * @return true if the option was set, otherwise false
         */
        public boolean setHeartbeatIvl(int heartbeatIvl)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_HEARTBEAT_IVL, heartbeatIvl);
        }

        /**
         * The ZMQ_HEARTBEAT_TIMEOUT option shall set
         * how long to wait before timing-out a connection
         * after sending a PING ZMTP command and not receiving any traffic.
         * This option is only valid if ZMQ_HEARTBEAT_IVL is also set,
         * and is greater than 0. The connection will time out
         * if there is no traffic received after sending the PING command,
         * but the received traffic does not have to be a PONG command
         * - any received traffic will cancel the timeout.
         * @param heartbeatTimeout heartbeat timeout in milliseconds
         * @return true if the option was set, otherwise false
         */
        public boolean setHeartbeatTimeout(int heartbeatTimeout)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_HEARTBEAT_TIMEOUT, heartbeatTimeout);
        }

        /**
         * The ZMQ_HEARTBEAT_TTL option shall set the timeout
         * on the remote peer for ZMTP heartbeats.
         * If this option is greater than 0,
         * the remote side shall time out the connection
         * if it does not receive any more traffic within the TTL period.
         * This option does not have any effect if ZMQ_HEARTBEAT_IVL is not set or is 0.
         * Internally, this value is rounded down to the nearest decisecond,
         * any value less than 100 will have no effect.
         * @param heartbeatTtl heartbeat time-to-live in milliseconds
         * @return true if the option was set, otherwise false
         */
        public boolean setHeartbeatTtl(int heartbeatTtl)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_HEARTBEAT_TTL, heartbeatTtl);
        }

        /**
         * The ZMQ_HEARTBEAT_CONTEXT option shall set the ping context
         * of the peer for ZMTP heartbeats.
         *
         * This API is in DRAFT state and is subject to change at ANY time until declared stable.
         *
         * If this option is set, every ping message sent for heartbeat will contain this context.
         * @param pingContext the context to be sent with ping messages.
         * @return true if the option was set, otherwise false
         */
        @Draft
        public boolean setHeartbeatContext(byte[] pingContext)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_HEARTBEAT_CONTEXT, pingContext);
        }

        /**
         * Retrieve the IP_TOS option for the socket.
         *
         * @return the value of the Type-Of-Service set for the socket.
         * @see #setTos(int)
         */
        public int getTos()
        {
            return (int) base.getSocketOpt(zmq.api.ZMQ.ZMQ_TOS);
        }

        /**
         * Sets the ToS fields (Differentiated services (DS)
         * and Explicit Congestion Notification (ECN) field of the IP header.
         * The ToS field is typically used to specify a packets priority.
         * The availability of this option is dependent on intermediate network equipment
         * that inspect the ToS field andprovide a path for low-delay, high-throughput, highly-reliable service, etc.
         *
         * @return true if the option was set, otherwise false.
         * @see #getTos()
         */
        public boolean setTos(int value)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_TOS, value);
        }

        /**
         * The ZMQ_RECONNECT_IVL_MAX option shall retrieve the maximum reconnection interval for the specified socket.
         * This is the maximum period ØMQ shall wait between attempts to reconnect.
         * On each reconnect attempt, the previous interval shall be doubled untill ZMQ_RECONNECT_IVL_MAX is reached.
         * This allows for exponential backoff strategy.
         * Default value means no exponential backoff is performed and reconnect interval calculations are only based on ZMQ_RECONNECT_IVL.
         *
         * @return the reconnectIVLMax.
         * @see #setReconnectIVLMax(int)
         * @since 3.0.0
         */
        public long getReconnectIVLMax()
        {
            if (before(2, 1, 10)) {
                return -1;
            }
            return base.getSocketOpt(zmq.api.ZMQ.ZMQ_RECONNECT_IVL_MAX);
        }

        /**
         * The ZMQ_RECONNECT_IVL_MAX option shall set the maximum reconnection interval for the specified socket.
         * This is the maximum period ØMQ shall wait between attempts to reconnect.
         * On each reconnect attempt, the previous interval shall be doubled until ZMQ_RECONNECT_IVL_MAX is reached.
         * This allows for exponential backoff strategy.
         * Default value means no exponential backoff is performed and reconnect interval calculations are only based on ZMQ_RECONNECT_IVL.
         *
         * @return true if the option was set, otherwise false
         * @deprecated this option uses long range, use {@link #setReconnectIVLMax(long)} instead
         * @see #getReconnectIVLMax()
         */
        @Deprecated
        public boolean setReconnectIVLMax(int value)
        {
            return setReconnectIVLMax(Long.valueOf(value));
        }

        /**
         * The ZMQ_RECONNECT_IVL_MAX option shall set the maximum reconnection interval for the specified socket.
         * This is the maximum period ØMQ shall wait between attempts to reconnect.
         * On each reconnect attempt, the previous interval shall be doubled until ZMQ_RECONNECT_IVL_MAX is reached.
         * This allows for exponential backoff strategy.
         * Default value means no exponential backoff is performed and reconnect interval calculations are only based on ZMQ_RECONNECT_IVL.
         *
         * @return true if the option was set, otherwise false
         * @see #getReconnectIVLMax()
         * @since 3.0.0
         */
        public boolean setReconnectIVLMax(long value)
        {
            if (before(2, 1, 10)) {
                return false;
            }
            return setSocketOpt(zmq.api.ZMQ.ZMQ_RECONNECT_IVL_MAX, value);
        }

        /**
         * The option shall retrieve limit for the inbound messages.
         * If a peer sends a message larger than ZMQ_MAXMSGSIZE it is disconnected.
         * Value of -1 means no limit.
         *
         * @return the maxMsgSize.
         * @see #setMaxMsgSize(long)
         */
        public long getMaxMsgSize()
        {
            if (before(3, 0, 0)) {
                return -1;
            }
            return (Long) base.getSocketOptx(zmq.api.ZMQ.ZMQ_MAXMSGSIZE);
        }

        /**
         * Limits the size of the inbound message.
         * If a peer sends a message larger than ZMQ_MAXMSGSIZE it is disconnected.
         * Value of -1 means no limit.
         *
         * @return true if the option was set, otherwise false
         * @see #getMaxMsgSize()
         * @since 3.0.0
         */
        public boolean setMaxMsgSize(long value)
        {
            if (before(3, 0, 0)) {
                return false;
            }
            return setSocketOpt(zmq.api.ZMQ.ZMQ_MAXMSGSIZE, value);
        }

        /**
         * The ZMQ_SNDHWM option shall return the high water mark for outbound messages on the specified socket.
         * The high water mark is a hard limit on the maximum number of outstanding messages ØMQ
         * shall queue in memory for any single peer that the specified socket is communicating with.
         * A value of zero means no limit.
         * If this limit has been reached the socket shall enter an exceptional state and depending on the socket type,
         * ØMQ shall take appropriate action such as blocking or dropping sent messages.
         * Refer to the individual socket descriptions in zmq_socket(3) for details on the exact action taken for each socket type.
         *
         * @return the SndHWM.
         * @see #setSndHWM(int)
         */
        public long getSndHWM()
        {
            return base.getSocketOpt(zmq.api.ZMQ.ZMQ_SNDHWM);
        }

        /**
         * The ZMQ_SNDHWM option shall set the high water mark for outbound messages on the specified socket.
         * The high water mark is a hard limit on the maximum number of outstanding messages ØMQ
         * shall queue in memory for any single peer that the specified socket is communicating with.
         * A value of zero means no limit.
         * If this limit has been reached the socket shall enter an exceptional state and depending on the socket type,
         * ØMQ shall take appropriate action such as blocking or dropping sent messages.
         * Refer to the individual socket descriptions in zmq_socket(3) for details on the exact action taken for each socket type.
         *
         * CAUTION: ØMQ does not guarantee that the socket will accept as many as ZMQ_SNDHWM messages,
         * and the actual limit may be as much as 60-70% lower depending on the flow of messages on the socket.
         *
         * @return true if the option was set, otherwise false.
         * @deprecated this option uses long range, use {@link #setSndHWM(long)} instead
         * @see #getSndHWM()
         */
        @Deprecated
        public boolean setSndHWM(int value)
        {
            return setSndHWM(Long.valueOf(value));
        }

        /**
         * The ZMQ_SNDHWM option shall set the high water mark for outbound messages on the specified socket.
         * The high water mark is a hard limit on the maximum number of outstanding messages ØMQ
         * shall queue in memory for any single peer that the specified socket is communicating with.
         * A value of zero means no limit.
         * If this limit has been reached the socket shall enter an exceptional state and depending on the socket type,
         * ØMQ shall take appropriate action such as blocking or dropping sent messages.
         * Refer to the individual socket descriptions in zmq_socket(3) for details on the exact action taken for each socket type.
         *
         * CAUTION: ØMQ does not guarantee that the socket will accept as many as ZMQ_SNDHWM messages,
         * and the actual limit may be as much as 60-70% lower depending on the flow of messages on the socket.
         *
         * @param value
         * @return true if the option was set, otherwise false.
         * @see #getSndHWM()
         * @since 3.0.0
         */
        public boolean setSndHWM(long value)
        {
            if (before(3, 0, 0)) {
                return false;
            }
            return setSocketOpt(zmq.api.ZMQ.ZMQ_SNDHWM, value);
        }

        /**
         * The ZMQ_RCVHWM option shall return the high water mark for inbound messages on the specified socket.
         * The high water mark is a hard limit on the maximum number of outstanding messages ØMQ
         * shall queue in memory for any single peer that the specified socket is communicating with.
         * A value of zero means no limit.
         * If this limit has been reached the socket shall enter an exceptional state and depending on the socket type,
         * ØMQ shall take appropriate action such as blocking or dropping sent messages.
         * Refer to the individual socket descriptions in zmq_socket(3) for details on the exact action taken for each socket type.
         *
         * @return the recvHWM period.
         * @see #setRcvHWM(int)
         * @since 3.0.0
         */
        public long getRcvHWM()
        {
            if (before(3, 0, 0)) {
                return -1;
            }
            return base.getSocketOpt(zmq.api.ZMQ.ZMQ_RCVHWM);
        }

        /**
         * The ZMQ_RCVHWM option shall set the high water mark for inbound messages on the specified socket.
         * The high water mark is a hard limit on the maximum number of outstanding messages ØMQ
         * shall queue in memory for any single peer that the specified socket is communicating with.
         * A value of zero means no limit.
         * If this limit has been reached the socket shall enter an exceptional state and depending on the socket type,
         * ØMQ shall take appropriate action such as blocking or dropping sent messages.
         * Refer to the individual socket descriptions in zmq_socket(3) for details on the exact action taken for each socket type.
         *
         * @return true if the option was set, otherwise false
         * @deprecated this option uses long range, use {@link #setRcvHWM(long)} instead
         * @see #getRcvHWM()
         * @since 3.0.0
         */
        @Deprecated
        public boolean setRcvHWM(int value)
        {
            return setRcvHWM(Long.valueOf(value));
        }

        /**
         * The ZMQ_RCVHWM option shall set the high water mark for inbound messages on the specified socket.
         * The high water mark is a hard limit on the maximum number of outstanding messages ØMQ
         * shall queue in memory for any single peer that the specified socket is communicating with.
         * A value of zero means no limit.
         * If this limit has been reached the socket shall enter an exceptional state and depending on the socket type,
         * ØMQ shall take appropriate action such as blocking or dropping sent messages.
         * Refer to the individual socket descriptions in zmq_socket(3) for details on the exact action taken for each socket type.
         *
         * @param value
         * @return true if the option was set, otherwise false.
         * @see #getRcvHWM()
         * @since 3.0.0
         */
        public boolean setRcvHWM(long value)
        {
            if (before(3, 0, 0)) {
                return false;
            }
            return setSocketOpt(zmq.api.ZMQ.ZMQ_RCVHWM, value);
        }

        /**
         * @see #setHWM(int)
         *
         * @return the High Water Mark.
         */
        public long getHWM()
        {
            if (after(3, 0, 0)) {
                return -1;
            }
            return base.getSocketOpt(zmq.api.ZMQ.ZMQ_HWM);
        }

        /**
         * The 'ZMQ_HWM' option shall set the high water mark for the specified 'socket'. The high
         * water mark is a hard limit on the maximum number of outstanding messages 0MQ shall queue
         * in memory for any single peer that the specified 'socket' is communicating with.
         *
         * If this limit has been reached the socket shall enter an exceptional state and depending
         * on the socket type, 0MQ shall take appropriate action such as blocking or dropping sent
         * messages. Refer to the individual socket descriptions in the man page of zmq_socket[3] for
         * details on the exact action taken for each socket type.
         *
         * @param hwm
         *            the number of messages to queue.
         * @return true if the option was set, otherwise false.
         */
        public boolean setHWM(long hwm)
        {
            if (after(3, 0, 0)) {
                boolean rc;
                rc = base.setSocketOpt(zmq.api.ZMQ.ZMQ_SNDHWM, hwm);
                rc &= base.setSocketOpt(zmq.api.ZMQ.ZMQ_RCVHWM, hwm);
                return rc;
            }
            return base.setSocketOpt(zmq.api.ZMQ.ZMQ_HWM, hwm);
        }

        /**
         * The 'ZMQ_HWM' option shall set the high water mark for the specified 'socket'. The high
         * water mark is a hard limit on the maximum number of outstanding messages 0MQ shall queue
         * in memory for any single peer that the specified 'socket' is communicating with.
         *
         * If this limit has been reached the socket shall enter an exceptional state and depending
         * on the socket type, 0MQ shall take appropriate action such as blocking or dropping sent
         * messages. Refer to the individual socket descriptions in the man page of zmq_socket[3] for
         * details on the exact action taken for each socket type.
         *
         * @param hwm
         *            the number of messages to queue.
         * @return true if the option was set, otherwise false
         */
        public boolean setHWM(int hwm)
        {
            boolean set = false;
            set |= setSndHWM(hwm);
            set |= setRcvHWM(hwm);
            return set;
        }

        /**
         * @see #setSwap(long)
         *
         * @return the number of messages to swap at most.
         */
        public long getSwap()
        {
            if (after(3, 0, 0)) {
                return -1;
            }
            return base.getSocketOpt(zmq.api.ZMQ.ZMQ_SWAP);
        }

        /**
         * If set, a socket shall keep only one message in its inbound/outbound queue,
         * this message being the last message received/the last message to be sent.
         * Ignores ZMQ_RCVHWM and ZMQ_SNDHWM options.
         * Does not support multi-part messages, in particular,
         * only one part of it is kept in the socket internal queue.
         *
         * @param conflate true to keep only one message, false for standard behaviour.
         * @return true if the option was set, otherwise false.
         * @see #isConflate()
         */
        public boolean setConflate(boolean conflate)
        {
            if (after(4, 0, 0)) {
                return setSocketOpt(zmq.api.ZMQ.ZMQ_CONFLATE, conflate ? 1 : 0);
            }
            return false;
        }

        /**
         * If in conflate mode, a socket shall keep only one message in its inbound/outbound queue,
         * this message being the last message received/the last message to be sent.
         * Ignores ZMQ_RCVHWM and ZMQ_SNDHWM options.
         * Does not support multi-part messages, in particular,
         * only one part of it is kept in the socket internal queue.
         *
         * @return true to keep only one message, false for standard behaviour.
         * @see #setConflate(boolean)
         */
        public boolean isConflate()
        {
            if (after(4, 0, 0)) {
                return base.getSocketOpt(zmq.api.ZMQ.ZMQ_CONFLATE) != 0;
            }
            return false;
        }

        /**
         * If in conflate mode, a socket shall keep only one message in its inbound/outbound queue,
         * this message being the last message received/the last message to be sent.
         * Ignores ZMQ_RCVHWM and ZMQ_SNDHWM options.
         * Does not support multi-part messages, in particular,
         * only one part of it is kept in the socket internal queue.
         *
         * @return true to keep only one message, false for standard behaviour.
         * @see #setConflate(boolean)
         */
        public boolean getConflate()
        {
            return isConflate();
        }

        /**
         * Get the Swap. The 'ZMQ_SWAP' option shall set the disk offload (swap) size for the
         * specified 'socket'. A socket which has 'ZMQ_SWAP' set to a non-zero value may exceed its
         * high water mark; in this case outstanding messages shall be offloaded to storage on disk
         * rather than held in memory.
         *
         * @param value
         *            The value of 'ZMQ_SWAP' defines the maximum size of the swap space in bytes.
         */
        public boolean setSwap(long value)
        {
            if (after(3, 0, 0)) {
                return false;
            }
            return setSocketOpt(zmq.api.ZMQ.ZMQ_SWAP, Long.valueOf(value).intValue());
        }

        /**
         * @see #setAffinity(long)
         *
         * @return the affinity.
         */
        public long getAffinity()
        {
            return base.getSocketOpt(zmq.api.ZMQ.ZMQ_AFFINITY);
        }

        /**
         * Get the Affinity. The 'ZMQ_AFFINITY' option shall set the I/O thread affinity for newly
         * created connections on the specified 'socket'.
         *
         * Affinity determines which threads from the 0MQ I/O thread pool associated with the
         * socket's _context_ shall handle newly created connections. A value of zero specifies no
         * affinity, meaning that work shall be distributed fairly among all 0MQ I/O threads in the
         * thread pool. For non-zero values, the lowest bit corresponds to thread 1, second lowest
         * bit to thread 2 and so on. For example, a value of 3 specifies that subsequent
         * connections on 'socket' shall be handled exclusively by I/O threads 1 and 2.
         *
         * See also  in the man page of init[3] for details on allocating the number of I/O threads for a
         * specific _context_.
         *
         * @param value
         *            the io_thread affinity.
         * @return true if the option was set, otherwise false
         */
        public boolean setAffinity(long value)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_AFFINITY, value);
        }

        /**
         * @see #setIdentity(byte[])
         *
         * @return the Identitiy.
         */
        public byte[] getIdentity()
        {
            return (byte[]) base.getSocketOptx(zmq.api.ZMQ.ZMQ_IDENTITY);
        }

        /**
         * The 'ZMQ_IDENTITY' option shall set the identity of the specified 'socket'. Socket
         * identity determines if existing 0MQ infastructure (_message queues_, _forwarding
         * devices_) shall be identified with a specific application and persist across multiple
         * runs of the application.
         *
         * If the socket has no identity, each run of an application is completely separate from
         * other runs. However, with identity set the socket shall re-use any existing 0MQ
         * infrastructure configured by the previous run(s). Thus the application may receive
         * messages that were sent in the meantime, _message queue_ limits shall be shared with
         * previous run(s) and so on.
         *
         * Identity should be at least one byte and at most 255 bytes long. Identities starting with
         * binary zero are reserved for use by 0MQ infrastructure.
         *
         * @param identity
         * @return true if the option was set, otherwise false
         */
        public boolean setIdentity(byte[] identity)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_IDENTITY, identity);
        }

        /**
         * @see #setRate(long)
         *
         * @return the Rate.
         */
        public long getRate()
        {
            return base.getSocketOpt(zmq.api.ZMQ.ZMQ_RATE);
        }

        /**
         * The 'ZMQ_RATE' option shall set the maximum send or receive data rate for multicast
         * transports such as in the man page of zmq_pgm[7] using the specified 'socket'.
         *
         * @param value maximum send or receive data rate for multicast, default 100
         * @return true if the option was set, otherwise false
         */
        public boolean setRate(long value)
        {
            return base.setSocketOpt(zmq.api.ZMQ.ZMQ_RATE, value);
        }

        /**
         * The ZMQ_RECOVERY_IVL option shall retrieve the recovery interval for multicast transports
         * using the specified socket. The recovery interval determines the maximum time in milliseconds
         * that a receiver can be absent from a multicast group before unrecoverable data loss will occur.
         *
         * @return the RecoveryIntervall.
         * @see #setRecoveryInterval(long)
         */
        public long getRecoveryInterval()
        {
            return base.getSocketOpt(zmq.api.ZMQ.ZMQ_RECOVERY_IVL);
        }

        /**
         * The 'ZMQ_RECOVERY_IVL' option shall set the recovery interval for multicast transports
         * using the specified 'socket'. The recovery interval determines the maximum time in
         * seconds that a receiver can be absent from a multicast group before unrecoverable data
         * loss will occur.
         *
         * CAUTION: Exercise care when setting large recovery intervals as the data needed for
         * recovery will be held in memory. For example, a 1 minute recovery interval at a data rate
         * of 1Gbps requires a 7GB in-memory buffer. {Purpose of this Method}
         *
         * @param value recovery interval for multicast in milliseconds, default 10000
         * @return true if the option was set, otherwise false.
         * @see #getRecoveryInterval()
         */
        public boolean setRecoveryInterval(long value)
        {
            throw new UnsupportedOperationException(); // TODO
        }

        /**
         * The default behavior of REQ sockets is to rely on the ordering of messages
         * to match requests and responses and that is usually sufficient.
         * When this option is set to true, the REQ socket will prefix outgoing messages
         * with an extra frame containing a request id.
         * That means the full message is (request id, identity, 0, user frames…).
         * The REQ socket will discard all incoming messages that don't begin with these two frames.
         * See also ZMQ_REQ_RELAXED.
         *
         * @param correlate Whether to enable outgoing request ids.
         * @return true if the option was set, otherwise false
         * @see #getReqCorrelate()
         */
        public boolean setReqCorrelate(boolean correlate)
        {
            if (after(4, 0, 0)) {
                return setSocketOpt(zmq.api.ZMQ.ZMQ_REQ_CORRELATE, correlate ? 1 : 0);
            }
            return false;
        }

        /**
         * The default behavior of REQ sockets is to rely on the ordering of messages
         * to match requests and responses and that is usually sufficient.
         * When this option is set to true, the REQ socket will prefix outgoing messages
         * with an extra frame containing a request id.
         * That means the full message is (request id, identity, 0, user frames…).
         * The REQ socket will discard all incoming messages that don't begin with these two frames.
         *
         * @return state of the ZMQ_REQ_CORRELATE option.
         * @see #setReqCorrelate(boolean)
         */
        @Deprecated
        public boolean getReqCorrelate()
        {
            throw new UnsupportedOperationException(); // TODO
        }

        /**
         * By default, a REQ socket does not allow initiating a new request with zmq_send(3)
         * until the reply to the previous one has been received.
         * When set to true, sending another message is allowed and has the effect of disconnecting
         * the underlying connection to the peer from which the reply was expected,
         * triggering a reconnection attempt on transports that support it.
         * The request-reply state machine is reset and a new request is sent to the next available peer.
         * If set to true, also enable ZMQ_REQ_CORRELATE to ensure correct matching of requests and replies.
         * Otherwise a late reply to an aborted request can be reported as the reply to the superseding request.
         *
         * @param relaxed
         * @return true if the option was set, otherwise false
         * @see #getReqRelaxed()
         */
        public boolean setReqRelaxed(boolean relaxed)
        {
            if (after(4, 0, 0)) {
                return setSocketOpt(zmq.api.ZMQ.ZMQ_REQ_RELAXED, relaxed ? 1 : 0);
            }
            return false;
        }

        /**
         * By default, a REQ socket does not allow initiating a new request with zmq_send(3)
         * until the reply to the previous one has been received.
         * When set to true, sending another message is allowed and has the effect of disconnecting
         * the underlying connection to the peer from which the reply was expected,
         * triggering a reconnection attempt on transports that support it.
         * The request-reply state machine is reset and a new request is sent to the next available peer.
         * If set to true, also enable ZMQ_REQ_CORRELATE to ensure correct matching of requests and replies.
         * Otherwise a late reply to an aborted request can be reported as the reply to the superseding request.
         *
         * @return state of the ZMQ_REQ_RELAXED option.
         * @see #setReqRelaxed(boolean)
         */
        @Deprecated
        public boolean getReqRelaxed()
        {
            throw new UnsupportedOperationException(); // TODO
        }

        /**
         * @see #setMulticastLoop(boolean)
         *
         * @return the Multicast Loop.
         */
        @Deprecated
        public boolean hasMulticastLoop()
        {
            if (after(3, 0, 0)) {
                return false;
            }
            return base.getSocketOpt(zmq.api.ZMQ.ZMQ_MCAST_LOOP) != 0;
        }

        /**
         * The 'ZMQ_MCAST_LOOP' option shall control whether data sent via multicast transports
         * using the specified 'socket' can also be received by the sending host via loopback. A
         * value of zero disables the loopback functionality, while the default value of 1 enables
         * the loopback functionality. Leaving multicast loopback enabled when it is not required
         * can have a negative impact on performance. Where possible, disable 'ZMQ_MCAST_LOOP' in
         * production environments.
         *
         * @param multicastLoop
         */
        @Deprecated
        public boolean setMulticastLoop(boolean multicastLoop)
        {
            if (after(3, 0, 0)) {
                return false;
            }
            return base.setSocketOpt(zmq.api.ZMQ.ZMQ_MCAST_LOOP, multicastLoop ? 1 : 0);
        }

        /**
         * @see #setMulticastHops(long)
         *
         * @return the Multicast Hops.
         */
        public long getMulticastHops()
        {
            if (before(3, 0, 0)) {
                return 1;
            }
            return base.getSocketOpt(zmq.api.ZMQ.ZMQ_MULTICAST_HOPS);
        }

        /**
         * Sets the time-to-live field in every multicast packet sent from this socket.
         * The default is 1 which means that the multicast packets don't leave the local
         * network.
         *
         * @param value time-to-live field in every multicast packet, default 1
         */
        public boolean setMulticastHops(long value)
        {
            if (before(3, 0, 0)) {
                return false;
            }
            return base.setSocketOpt(zmq.api.ZMQ.ZMQ_MULTICAST_HOPS, value);
        }

        /**
         * Retrieve the timeout for recv operation on the socket.
         * If the value is 0, recv will return immediately,
         * with null if there is no message to receive.
         * If the value is -1, it will block until a message is available.
         * For all other values, it will wait for a message for that amount of time
         * before returning with a null and an EAGAIN error.
         *
         * @return the Receive Timeout  in milliseconds.
         * @see #setReceiveTimeOut(int)
         */
        public int getReceiveTimeOut()
        {
            if (before(2, 2, 0)) {
                return -1;
            }
            return (int) base.getSocketOpt(zmq.api.ZMQ.ZMQ_RCVTIMEO);
        }

        /**
         * Sets the timeout for receive operation on the socket. If the value is 0, recv
         * will return immediately, with null if there is no message to receive.
         * If the value is -1, it will block until a message is available. For all other
         * values, it will wait for a message for that amount of time before returning with
         * a null and an EAGAIN error.
         *
         * @param value Timeout for receive operation in milliseconds. Default -1 (infinite)
         * @return true if the option was set, otherwise false.
         * @see #getReceiveTimeOut()
         */
        public boolean setReceiveTimeOut(int value)
        {
            if (before(2, 2, 0)) {
                return false;
            }
            return setSocketOpt(zmq.api.ZMQ.ZMQ_RCVTIMEO, value);
        }

        /**
         * Retrieve the timeout for send operation on the socket.
         * If the value is 0, send will return immediately, with a false and an EAGAIN error if the message cannot be sent.
         * If the value is -1, it will block until the message is sent.
         * For all other values, it will try to send the message for that amount of time before returning with false and an EAGAIN error.
         *
         * @return the Send Timeout in milliseconds.
         * @see #setSendTimeOut(int)
         */
        public int getSendTimeOut()
        {
            if (before(2, 2, 0)) {
                return -1;
            }
            return (int) base.getSocketOpt(zmq.api.ZMQ.ZMQ_SNDTIMEO);
        }

        /**
         * Sets the timeout for send operation on the socket. If the value is 0, send
         * will return immediately, with a false if the message cannot be sent.
         * If the value is -1, it will block until the message is sent. For all other
         * values, it will try to send the message for that amount of time before
         * returning with false and an EAGAIN error.
         *
         * @param value Timeout for send operation in milliseconds. Default -1 (infinite)
         * @return true if the option was set, otherwise false.
         * @see #getSendTimeOut()
         */
        public boolean setSendTimeOut(int value)
        {
            if (before(2, 2, 0)) {
                return false;
            }
            return setSocketOpt(zmq.api.ZMQ.ZMQ_SNDTIMEO, value);
        }

        /**
        * Override SO_KEEPALIVE socket option (where supported by OS) to enable keep-alive packets for a socket
        * connection. Possible values are -1, 0, 1. The default value -1 will skip all overrides and do the OS default.
        *
        * @param value The value of 'ZMQ_TCP_KEEPALIVE' to turn TCP keepalives on (1) or off (0).
        * @return true if the option was set, otherwise false.
        */
        @Deprecated
        public boolean setTCPKeepAlive(long value)
        {
            return setTCPKeepAlive(Long.valueOf(value).intValue());
        }

        /**
         * @see #setTCPKeepAlive(long)
         *
         * @return the keep alive setting.
         */
        @Deprecated
        public long getTCPKeepAliveSetting()
        {
            return getTCPKeepAlive();
        }

        /**
         * Override TCP_KEEPCNT socket option (where supported by OS). The default value -1 will skip all overrides and
         * do the OS default.
         *
         * @param value The value of 'ZMQ_TCP_KEEPALIVE_CNT' defines the number of keepalives before death.
         * @return true if the option was set, otherwise false.
         */
        public boolean setTCPKeepAliveCount(long value)
        {
            if (after(3, 2, 0)) {
                return setSocketOpt(zmq.api.ZMQ.ZMQ_TCP_KEEPALIVE_CNT, Long.valueOf(value).intValue());
            }
            return false;
        }

        /**
         * @see #setTCPKeepAliveCount(long)
         *
         * @return the keep alive count.
         */
        public long getTCPKeepAliveCount()
        {
            if (after(3, 2, 0)) {
                return base.getSocketOpt(zmq.api.ZMQ.ZMQ_TCP_KEEPALIVE_CNT);
            }
            return 0;
        }

        /**
         * Override TCP_KEEPINTVL socket option (where supported by OS). The default value -1 will skip all overrides
         * and do the OS default.
         *
         * @param value The value of 'ZMQ_TCP_KEEPALIVE_INTVL' defines the interval between keepalives. Unit is OS
         *            dependent.
         * @return true if the option was set, otherwise false.
         */
        public boolean setTCPKeepAliveInterval(long value)
        {
            if (after(3, 2, 0)) {
                return setSocketOpt(zmq.api.ZMQ.ZMQ_TCP_KEEPALIVE_INTVL, Long.valueOf(value).intValue());
            }
            return false;
        }

        /**
         * @see #setTCPKeepAliveInterval(long)
         *
         * @return the keep alive interval.
         */
        public long getTCPKeepAliveInterval()
        {
            if (after(3, 2, 0)) {
                return base.getSocketOpt(zmq.api.ZMQ.ZMQ_TCP_KEEPALIVE_INTVL);
            }
            return 0;
        }

        /**
         * Override TCP_KEEPCNT (or TCP_KEEPALIVE on some OS) socket option (where supported by OS). The default value
         * -1 will skip all overrides and do the OS default.
         *
         * @param value The value of 'ZMQ_TCP_KEEPALIVE_IDLE' defines the interval between the last data packet sent
         *            over the socket and the first keepalive probe. Unit is OS dependent.
         * @return true if the option was set, otherwise false
         */
        public boolean setTCPKeepAliveIdle(long value)
        {
            if (after(3, 2, 0)) {
                return setSocketOpt(zmq.api.ZMQ.ZMQ_TCP_KEEPALIVE_IDLE, Long.valueOf(value).intValue());
            }
            return false;
        }

        /**
         * @see #setTCPKeepAliveIdle(long)
         *
         * @return the keep alive idle value.
         */
        public long getTCPKeepAliveIdle()
        {
            if (after(3, 2, 0)) {
                return base.getSocketOpt(zmq.api.ZMQ.ZMQ_TCP_KEEPALIVE_IDLE);
            }
            return 0;
        }

        /**
         * The ZMQ_SNDBUF option shall retrieve the underlying kernel transmit buffer size for the specified socket.
         * For details refer to your operating system documentation for the SO_SNDBUF socket option.
         *
         * @return the kernel send buffer size.
         * @see #setSendBufferSize(int)
         */
        public int getSendBufferSize()
        {
            return (int) base.getSocketOpt(zmq.api.ZMQ.ZMQ_SNDBUF);
        }

        /**
         * The 'ZMQ_SNDBUF' option shall set the underlying kernel transmit buffer size for the
         * 'socket' to the specified size in bytes. A value of zero means leave the OS default
         * unchanged. For details please refer to your operating system documentation for the
         * 'SO_SNDBUF' socket option.
         *
         * @param value underlying kernel transmit buffer size for the 'socket' in bytes
         *              A value of zero means leave the OS default unchanged.
         * @return true if the option was set, otherwise false
         * @deprecated this option uses long range, use {@link #setSendBufferSize(int)} instead
         * @see #getSendBufferSize()
         */
        @Deprecated
        public boolean setSendBufferSize(long value)
        {
            return setSendBufferSize(Long.valueOf(value).intValue());
        }

        /**
         * The 'ZMQ_SNDBUF' option shall set the underlying kernel transmit buffer size for the
         * 'socket' to the specified size in bytes. A value of zero means leave the OS default
         * unchanged. For details please refer to your operating system documentation for the
         * 'SO_SNDBUF' socket option.
         *
         * @param value underlying kernel transmit buffer size for the 'socket' in bytes
         *              A value of zero means leave the OS default unchanged.
         * @return true if the option was set, otherwise false
         * @see #getSendBufferSize()
         */
        public boolean setSendBufferSize(int value)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_SNDBUF, value);
        }

        /**
         * The ZMQ_RCVBUF option shall retrieve the underlying kernel receive buffer size for the specified socket.
         * For details refer to your operating system documentation for the SO_RCVBUF socket option.
         *
         * @return the kernel receive buffer size.
         * @see #setReceiveBufferSize(int)
         */
        public int getReceiveBufferSize()
        {
            return (int) base.getSocketOpt(zmq.api.ZMQ.ZMQ_RCVBUF);
        }

        /**
         * The 'ZMQ_RCVBUF' option shall set the underlying kernel receive buffer size for the
         * 'socket' to the specified size in bytes.
         * For details refer to your operating system documentation for the 'SO_RCVBUF'
         * socket option.
         *
         * @param value Underlying kernel receive buffer size for the 'socket' in bytes.
         *              A value of zero means leave the OS default unchanged.
         * @return true if the option was set, otherwise false
         * @deprecated this option uses long range, use {@link #setReceiveBufferSize(int)} instead
         * @see #getReceiveBufferSize()
         */
        @Deprecated
        public boolean setReceiveBufferSize(long value)
        {
            return setReceiveBufferSize(Long.valueOf(value).intValue());
        }

        /**
         * The 'ZMQ_RCVBUF' option shall set the underlying kernel receive buffer size for the
         * 'socket' to the specified size in bytes.
         * For details refer to your operating system documentation for the 'SO_RCVBUF'
         * socket option.
         *
         * @param value Underlying kernel receive buffer size for the 'socket' in bytes.
         *              A value of zero means leave the OS default unchanged.
         * @return true if the option was set, otherwise false
         * @see #getReceiveBufferSize()
         */
        public boolean setReceiveBufferSize(int value)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_RCVBUF, value);
        }

        /**
         * The 'ZMQ_RCVMORE' option shall return a boolean value indicating if the multi-part
         * message currently being read from the specified 'socket' has more message parts to
         * follow. If there are no message parts to follow or if the message currently being read is
         * not a multi-part message a value of zero shall be returned. Otherwise, a value of 1 shall
         * be returned.
         *
         * @return true if there are more messages to receive.
         */
        public boolean hasReceiveMore()
        {
            return base.getSocketOpt(zmq.api.ZMQ.ZMQ_RCVMORE) != 0;
        }

        /**
         * The 'ZMQ_FD' option shall retrieve file descriptor associated with the 0MQ
         * socket. The descriptor can be used to integrate 0MQ socket into an existing
         * event loop. It should never be used for anything else than polling -- such as
         * reading or writing. The descriptor signals edge-triggered IN event when
         * something has happened within the 0MQ socket. It does not necessarily mean that
         * the messages can be read or written. Check ZMQ_EVENTS option to find out whether
         * the 0MQ socket is readable or writeable.
         *
         * @return the underlying file descriptor.
         * @since 2.1.0
         */
        public SelectableChannel getFD()
        {
            if (before(2, 1, 0)) {
                return null;
            }
            return (SelectableChannel) base.getSocketOptx(zmq.api.ZMQ.ZMQ_FD);
        }

        /**
         * The 'ZMQ_EVENTS' option shall retrieve event flags for the specified socket.
         * If a message can be read from the socket ZMQ_POLLIN flag is set. If message can
         * be written to the socket ZMQ_POLLOUT flag is set.
         *
         * @return the mask of outstanding events.
         * @since 2.1.10
         */
        public int getEvents()
        {
            if (before(2, 1, 0)) {
                return Integer.MIN_VALUE;
            }
            return (int) base.getSocketOpt(zmq.api.ZMQ.ZMQ_EVENTS);
        }

        /**
         * The 'ZMQ_SUBSCRIBE' option shall establish a new message filter on a 'ZMQ_SUB' socket.
         * Newly created 'ZMQ_SUB' sockets shall filter out all incoming messages, therefore you
         * should call this option to establish an initial message filter.
         *
         * An empty 'option_value' of length zero shall subscribe to all incoming messages. A
         * non-empty 'option_value' shall subscribe to all messages beginning with the specified
         * prefix. Mutiple filters may be attached to a single 'ZMQ_SUB' socket, in which case a
         * message shall be accepted if it matches at least one filter.
         *
         * @param topic
         * @return true if the option was set, otherwise false
         */
        public boolean subscribe(byte[] topic)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_SUBSCRIBE, topic);
        }

        /**
         * The 'ZMQ_SUBSCRIBE' option shall establish a new message filter on a 'ZMQ_SUB' socket.
         * Newly created 'ZMQ_SUB' sockets shall filter out all incoming messages, therefore you
         * should call this option to establish an initial message filter.
         *
         * An empty 'option_value' of length zero shall subscribe to all incoming messages. A
         * non-empty 'option_value' shall subscribe to all messages beginning with the specified
         * prefix. Mutiple filters may be attached to a single 'ZMQ_SUB' socket, in which case a
         * message shall be accepted if it matches at least one filter.
         *
         * @param topic
         * @return true if the option was set, otherwise false
         */
        public boolean subscribe(String topic)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_SUBSCRIBE, topic);
        }

        /**
         * The 'ZMQ_UNSUBSCRIBE' option shall remove an existing message filter on a 'ZMQ_SUB'
         * socket. The filter specified must match an existing filter previously established with
         * the 'ZMQ_SUBSCRIBE' option. If the socket has several instances of the same filter
         * attached the 'ZMQ_UNSUBSCRIBE' option shall remove only one instance, leaving the rest in
         * place and functional.
         *
         * @param topic
         * @return true if the option was set, otherwise false
         */
        public boolean unsubscribe(byte[] topic)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_UNSUBSCRIBE, topic);
        }

        /**
         * The 'ZMQ_UNSUBSCRIBE' option shall remove an existing message filter on a 'ZMQ_SUB'
         * socket. The filter specified must match an existing filter previously established with
         * the 'ZMQ_SUBSCRIBE' option. If the socket has several instances of the same filter
         * attached the 'ZMQ_UNSUBSCRIBE' option shall remove only one instance, leaving the rest in
         * place and functional.
         *
         * @param topic
         * @return true if the option was set, otherwise false
         */
        public boolean unsubscribe(String topic)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_UNSUBSCRIBE, topic);
        }

        /**
         * Sets the limit threshold where messages of a given size will be allocated using Direct ByteBuffer.
         * It means that after this limit, there will be a slight penalty cost at the creation,
         * but the subsequent operations will be faster.
         * Set to 0 or negative to disable the threshold mechanism.
         * @param threshold the threshold to set for the size limit of messages. 0 or negative to disable this system.
         * @return true if the option was set, otherwise false.
         */
        public boolean setMsgAllocationHeapThreshold(int threshold)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_MSG_ALLOCATION_HEAP_THRESHOLD, threshold);
        }

        /**
         * Gets the limit threshold where messages of a given size will be allocated using Direct ByteBuffer.
         * It means that after this limit, there will be a slight penalty cost at the creation,
         * but the subsequent operations will be faster.
         * @return the threshold
         */
        public long getMsgAllocationHeapThreshold()
        {
            return base.getSocketOpt(zmq.api.ZMQ.ZMQ_MSG_ALLOCATION_HEAP_THRESHOLD);
        }

        /**
         * Sets a custom message allocator.
         * @param allocator the custom allocator.
         * @return true if the option was set, otherwise false.
         */
        // TODO
        //        public boolean setMsgAllocator(MsgAllocator allocator)
        //        {
        //            return setSocketOpt(zmq.api.ZMQ.ZMQ_MSG_ALLOCATOR, allocator);
        //        }

        /**
         * The ZMQ_CONNECT_RID option sets the peer id of the next host connected via the connect() call,
         * and immediately readies that connection for data transfer with the named id.
         * This option applies only to the first subsequent call to connect(),
         * calls thereafter use default connection behavior.
         * Typical use is to set this socket option ahead of each connect() attempt to a new host.
         * Each connection MUST be assigned a unique name. Assigning a name that is already in use is not allowed.
         * Useful when connecting ROUTER to ROUTER, or STREAM to STREAM, as it allows for immediate sending to peers.
         * Outbound id framing requirements for ROUTER and STREAM sockets apply.
         * The peer id should be from 1 to 255 bytes long and MAY NOT start with binary zero.
         *
         * @param rid the peer id of the next host.
         * @return true if the option was set, otherwise false.
         */
        public boolean setConnectRid(String rid)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_CONNECT_RID, rid);
        }

        /**
         * The ZMQ_CONNECT_RID option sets the peer id of the next host connected via the connect() call,
         * and immediately readies that connection for data transfer with the named id.
         * This option applies only to the first subsequent call to connect(),
         * calls thereafter use default connection behavior.
         * Typical use is to set this socket option ahead of each connect() attempt to a new host.
         * Each connection MUST be assigned a unique name. Assigning a name that is already in use is not allowed.
         * Useful when connecting ROUTER to ROUTER, or STREAM to STREAM, as it allows for immediate sending to peers.
         * Outbound id framing requirements for ROUTER and STREAM sockets apply.
         * The peer id should be from 1 to 255 bytes long and MAY NOT start with binary zero.
         *
         * @param rid the peer id of the next host.
         * @return true if the option was set, otherwise false.
         */
        public boolean setConnectRid(byte[] rid)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_CONNECT_RID, rid);
        }

        /**
         * Sets the raw mode on the ROUTER, when set to true.
         * When the ROUTER socket is in raw mode, and when using the tcp:// transport,
         * it will read and write TCP data without ØMQ framing.
         * This lets ØMQ applications talk to non-ØMQ applications.
         * When using raw mode, you cannot set explicit identities,
         * and the ZMQ_SNDMORE flag is ignored when sending data messages.
         * In raw mode you can close a specific connection by sending it a zero-length message (following the identity frame).
         *
         * @param raw true to set the raw mode on the ROUTER.
         * @return true if the option was set, otherwise false.
         */
        public boolean setRouterRaw(boolean raw)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_ROUTER_RAW, raw);
        }

        /**
         * When set to true, the socket will automatically send
         * an empty message when a new connection is made or accepted.
         * You may set this on REQ, DEALER, or ROUTER sockets connected to a ROUTER socket.
         * The application must filter such empty messages.
         * The ZMQ_PROBE_ROUTER option in effect provides the ROUTER application with an event signaling the arrival of a new peer.
         *
         * @param probe true to send automatically an empty message when a new connection is made or accepted.
         * @return true if the option was set, otherwise false.
         */
        public boolean setProbeRouter(boolean probe)
        {
            if (after(4, 0, 0)) {
                return setSocketOpt(zmq.api.ZMQ.ZMQ_PROBE_ROUTER, probe ? 1 : 0);
            }
            return false;
        }

        /**
         * Sets the ROUTER socket behavior when an unroutable message is encountered.
         * A value of false is the default and discards the message silently
         * when it cannot be routed or the peers SNDHWM is reached.
         * A value of true returns an EHOSTUNREACH error code if the message cannot be routed
         * or EAGAIN error code if the SNDHWM is reached and ZMQ_DONTWAIT was used.
         * Without ZMQ_DONTWAIT it will block until the SNDTIMEO is reached or a spot in the send queue opens up.
         *
         * @param mandatory A value of false is the default and discards the message silently when it cannot be routed.
         *                  A value of true returns an EHOSTUNREACH error code if the message cannot be routed.
         * @return true if the option was set, otherwise false.
         */
        public boolean setRouterMandatory(boolean mandatory)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_ROUTER_MANDATORY, mandatory ? 1 : 0);
        }

        /**
         * If two clients use the same identity when connecting to a ROUTER,
         * the results shall depend on the ZMQ_ROUTER_HANDOVER option setting.
         * If that is not set (or set to the default of false),
         * the ROUTER socket shall reject clients trying to connect with an already-used identity.
         * If that option is set to true, the ROUTER socket shall hand-over the connection to the new client and disconnect the existing one.
         *
         * @param handover A value of false, (default) the ROUTER socket shall reject clients trying to connect with an already-used identity
         *                  A value of true, the ROUTER socket shall hand-over the connection to the new client and disconnect the existing one
         * @return true if the option was set, otherwise false.
         */
        public boolean setRouterHandover(boolean handover)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_ROUTER_HANDOVER, handover);
        }

        /**
         * Sets the XPUB socket behavior on new subscriptions and unsubscriptions.
         *
         * @param verbose A value of false is the default and passes only new subscription messages to upstream.
         *                A value of true passes all subscription messages upstream.
         * @return true if the option was set, otherwise false.
         */
        public boolean setXpubVerbose(boolean verbose)
        {
            if (before(3, 2, 2)) {
                return false;
            }
            return setSocketOpt(zmq.api.ZMQ.ZMQ_XPUB_VERBOSE, verbose ? 1 : 0);
        }

        /**
         * Sets the XPUB socket behaviour to return error EAGAIN if SENDHWM is reached and the message could not be send.
         * A value of false is the default and drops the message silently when the peers SNDHWM is reached.
         * A value of true returns an EAGAIN error code if the SNDHWM is reached and ZMQ_DONTWAIT was used.
         *
         * @param noDrop
         * @return true if the option was set, otherwise false.
         */
        public boolean setXpubNoDrop(boolean noDrop)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_XPUB_NODROP, noDrop);
        }

        /**
         * @see #setIPv4Only (boolean)
         *
         * @return the IPV4ONLY
         * @deprecated use {@link #isIPv6()} instead (inverted logic: ipv4 = true <==> ipv6 = false)
         */
        @Deprecated
        public boolean getIPv4Only()
        {
            return !isIPv6();
        }

        /**
         * Retrieve the IPv6 option for the socket.
         * A value of true means IPv6 is enabled on the socket,
         * while false means the socket will use only IPv4.
         * When IPv6 is enabled the socket will connect to,
         * or accept connections from, both IPv4 and IPv6 hosts.
         *
         * @return the IPV6 configuration.
         * @see #setIPv6 (boolean)
         */
        public boolean isIPv6()
        {
            return (Boolean) base.getSocketOptx(zmq.api.ZMQ.ZMQ_IPV6);
        }

        /**
         * Retrieve the IPv6 option for the socket.
         * A value of true means IPv6 is enabled on the socket,
         * while false means the socket will use only IPv4.
         * When IPv6 is enabled the socket will connect to,
         * or accept connections from, both IPv4 and IPv6 hosts.
         *
         * @return the IPV6 configuration.
         * @see #setIPv6 (boolean)
         */
        public boolean getIPv6()
        {
            return isIPv6();
        }

        /**
         * The 'ZMQ_IPV4ONLY' option shall set the underlying native socket type.
         * An IPv6 socket lets applications connect to and accept connections from both IPv4 and IPv6 hosts.
         *
         * @param v4only A value of true will use IPv4 sockets, while the value of false will use IPv6 sockets
         * @return true if the option was set, otherwise false
         * @deprecated use {@link #setIPv6(boolean)} instead (inverted logic: ipv4 = true <==> ipv6 = false)
         */
        @Deprecated
        public boolean setIPv4Only(boolean v4only)
        {
            return setIPv6(!v4only);
        }

        /**
         * Set the IPv6 option for the socket.
         * A value of true means IPv6 is enabled on the socket, while false means the socket will use only IPv4.
         * When IPv6 is enabled the socket will connect to, or accept connections from, both IPv4 and IPv6 hosts.
         *
         * @param v6 A value of true will use IPv6 sockets, while the value of false will use IPv4 sockets
         * @return true if the option was set, otherwise false
         * @see #isIPv6()
         */
        public boolean setIPv6(boolean v6)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_IPV6, v6);
        }

        /**
         * @see #setTCPKeepAlive(int)
         *
         * @return the keep alive setting.
         */
        public long getTCPKeepAlive()
        {
            if (after(3, 2, 0)) {
                return base.getSocketOpt(zmq.api.ZMQ.ZMQ_TCP_KEEPALIVE);
            }
            return 0;
        }

        /**
         * Override SO_KEEPALIVE socket option (where supported by OS) to enable keep-alive packets for a socket
         * connection. Possible values are -1, 0, 1. The default value -1 will skip all overrides and do the OS default.
         *
         * @param optVal The value of 'ZMQ_TCP_KEEPALIVE' to turn TCP keepalives on (1) or off (0).
         * @return true if the option was set, otherwise false
         */
        public boolean setTCPKeepAlive(int optVal)
        {
            if (after(3, 2, 0)) {
                return setSocketOpt(zmq.api.ZMQ.ZMQ_TCP_KEEPALIVE, optVal);
            }
            return false;
        }

        /**
         * @see #setDelayAttachOnConnect(boolean)
         *
         * @deprecated use {@link #setImmediate(boolean)} instead (inverted logic: immediate = true <==> delay attach on connect = false)
         */
        @Deprecated
        public boolean getDelayAttachOnConnect()
        {
            return !isImmediate();
        }

        /**
         * Accept messages only when connections are made
         *
         * If set to true, will delay the attachment of a pipe on connect until the underlying connection
         * has completed. This will cause the socket to block if there are no other connections, but will
         * prevent queues from filling on pipes awaiting connection
         *
         * @param value The value of 'ZMQ_DELAY_ATTACH_ON_CONNECT'. Default false.
         * @return true if the option was set
         * @deprecated use {@link #setImmediate(boolean)} instead (warning, the boolean is inverted)
         */
        @Deprecated
        public boolean setDelayAttachOnConnect(boolean value)
        {
            return setImmediate(!value);
        }

        /**
         * Retrieve the state of the attach on connect value.
         * If false, will delay the attachment of a pipe on connect until the underlying connection has completed.
         * This will cause the socket to block if there are no other connections, but will prevent queues from filling on pipes awaiting connection.
         *
         * @see #setImmediate(boolean)
         */
        public boolean isImmediate()
        {
            if (after(3, 2, 0)) {
                return base.getSocketOpt(zmq.api.ZMQ.ZMQ_IMMEDIATE) != 0;
            }
            return false;
        }

        /**
         * Retrieve the state of the attach on connect value.
         * If false, will delay the attachment of a pipe on connect until the underlying connection has completed.
         * This will cause the socket to block if there are no other connections, but will prevent queues from filling on pipes awaiting connection.
         *
         * @see #setImmediate(boolean)
         */
        public boolean getImmediate()
        {
            return isImmediate();
        }

        /**
         * Accept messages immediately or only when connections are made
         *
         * By default queues will fill on outgoing connections even if the connection has not completed.
         * This can lead to "lost" messages on sockets with round-robin routing (REQ, PUSH, DEALER).
         * If this option is set to false, messages shall be queued only to completed connections.
         * This will cause the socket to block if there are no other connections,
         * but will prevent queues from filling on pipes awaiting connection.
         *
         * @param value The value of 'ZMQ_IMMEDIATE'. Default true.
         * @return true if the option was set, otherwise false.
         * @see #isImmediate()
         */
        public boolean setImmediate(boolean value)
        {
            if (after(3, 2, 0)) {
                return setSocketOpt(zmq.api.ZMQ.ZMQ_IMMEDIATE, value ? 1 : 0);
            }
            return false;
        }

        /**
         * Sets the SOCKS5 proxy address that shall be used by the socket for the TCP connection(s).
         * Does not support SOCKS5 authentication.
         * If the endpoints are domain names instead of addresses they shall not be resolved
         * and they shall be forwarded unchanged to the SOCKS proxy service
         * in the client connection request message (address type 0x03 domain name).
         *
         * @param proxy
         * @return true if the option was set, otherwise false.
         * @see #getSocksProxy()
         */
        public boolean setSocksProxy(String proxy)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_SOCKS_PROXY, proxy);
        }

        /**
         * Sets the SOCKS5 proxy address that shall be used by the socket for the TCP connection(s).
         * Does not support SOCKS5 authentication.
         * If the endpoints are domain names instead of addresses they shall not be resolved
         * and they shall be forwarded unchanged to the SOCKS proxy service
         * in the client connection request message (address type 0x03 domain name).
         *
         * @param proxy
         * @return true if the option was set, otherwise false.
         * @see #getSocksProxy()
         */
        public boolean setSocksProxy(byte[] proxy)
        {
            return setSocketOpt(zmq.api.ZMQ.ZMQ_SOCKS_PROXY, proxy);
        }

        /**
         * The ZMQ_SOCKS_PROXY option shall retrieve the SOCKS5 proxy address in string format.
         * The returned value MAY be empty.
         *
         * @return the SOCKS5 proxy address in string format
         * @see #setSocksProxy(byte[])
         */
        public String getSocksProxy()
        {
            return (String) base.getSocketOptx(zmq.api.ZMQ.ZMQ_SOCKS_PROXY);
        }

        /**
         * The ZMQ_LAST_ENDPOINT option shall retrieve the last endpoint bound for TCP and IPC transports.
         * The returned value will be a string in the form of a ZMQ DSN.
         * Note that if the TCP host is INADDR_ANY, indicated by a *, then the returned address will be 0.0.0.0 (for IPv4).
         */
        public String getLastEndpoint()
        {
            if (before(3, 2, 0)) {
                return null;
            }
            return (String) base.getSocketOptx(zmq.api.ZMQ.ZMQ_LAST_ENDPOINT);
        }

        /**
         * Sets the domain for ZAP (ZMQ RFC 27) authentication.
         * For NULL security (the default on all tcp:// connections),
         * ZAP authentication only happens if you set a non-empty domain.
         * For PLAIN and CURVE security, ZAP requests are always made, if there is a ZAP handler present.
         * See http://rfc.zeromq.org/spec:27 for more details.
         *
         * @param domain the domain of ZAP authentication
         * @return true if the option was set
         * @see #getZapDomain()
         */
        public boolean setZapDomain(String domain)
        {
            if (before(4, 1, 0)) {
                return false;
            }
            return setSocketOpt(zmq.api.ZMQ.ZMQ_ZAP_DOMAIN, domain);
        }

        /**
         * Sets the domain for ZAP (ZMQ RFC 27) authentication.
         * For NULL security (the default on all tcp:// connections),
         * ZAP authentication only happens if you set a non-empty domain.
         * For PLAIN and CURVE security, ZAP requests are always made, if there is a ZAP handler present.
         * See http://rfc.zeromq.org/spec:27 for more details.
         *
         * @param domain the domain of ZAP authentication
         * @return true if the option was set
         * @see #getZapDomain()
         */
        public boolean setZapDomain(byte[] domain)
        {
            if (before(4, 1, 0)) {
                return false;
            }
            return setSocketOpt(zmq.api.ZMQ.ZMQ_ZAP_DOMAIN, domain);
        }

        /**
         * The ZMQ_ZAP_DOMAIN option shall retrieve the last ZAP domain set for the socket.
         * The returned value MAY be empty.
         *
         * @return the domain of ZAP authentication
         * @see #setZapDomain(String)
         */
        public String getZapDomain()
        {
            if (before(4, 1, 0)) {
                return null;
            }
            return (String) base.getSocketOptx(zmq.api.ZMQ.ZMQ_ZAP_DOMAIN);
        }

        /**
         * Sets the domain for ZAP (ZMQ RFC 27) authentication.
         * For NULL security (the default on all tcp:// connections),
         * ZAP authentication only happens if you set a non-empty domain.
         * For PLAIN and CURVE security, ZAP requests are always made, if there is a ZAP handler present.
         * See http://rfc.zeromq.org/spec:27 for more details.
         *
         * @param domain the domain of ZAP authentication
         * @return true if the option was set
         * @see #getZapDomain()
         */
        public boolean setZAPDomain(String domain)
        {
            return setZapDomain(domain);
        }

        /**
         * Sets the domain for ZAP (ZMQ RFC 27) authentication.
         * For NULL security (the default on all tcp:// connections),
         * ZAP authentication only happens if you set a non-empty domain.
         * For PLAIN and CURVE security, ZAP requests are always made, if there is a ZAP handler present.
         * See http://rfc.zeromq.org/spec:27 for more details.
         *
         * @param domain the domain of ZAP authentication
         * @return true if the option was set
         * @see #getZapDomain()
         */
        public boolean setZAPDomain(byte[] domain)
        {
            return setZapDomain(domain);
        }

        /**
         * The ZMQ_ZAP_DOMAIN option shall retrieve the last ZAP domain set for the socket.
         * The returned value MAY be empty.
         *
         * @return the domain of ZAP authentication
         * @see #setZapDomain(String)
         */
        public String getZAPDomain()
        {
            return getZapDomain();
        }

        /**
         * Defines whether the socket will act as server for PLAIN security, see zmq_plain(7).
         * A value of true means the socket will act as PLAIN server.
         * A value of false means the socket will not act as PLAIN server,
         * and its security role then depends on other option settings.
         * Setting this to false shall reset the socket security to NULL.
         *
         * @param server true if the role of the socket should be server for PLAIN security.
         * @return true if the option was set, otherwise false.
         * @deprecated the naming is inconsistent with jzmq, please use {@link #setPlainServer(boolean)} instead
         * @see #isAsServerPlain()
         */
        @Deprecated
        public boolean setAsServerPlain(boolean server)
        {
            return setPlainServer(server);
        }

        /**
         * Defines whether the socket will act as server for PLAIN security, see zmq_plain(7).
         * A value of true means the socket will act as PLAIN server.
         * A value of false means the socket will not act as PLAIN server,
         * and its security role then depends on other option settings.
         * Setting this to false shall reset the socket security to NULL.
         *
         * @param server true if the role of the socket should be server for PLAIN security.
         * @return true if the option was set, otherwise false.
         * @see #isAsServerPlain()
         */
        public boolean setPlainServer(boolean server)
        {
            if (before(4, 0, 0)) {
                return false;
            }
            return setSocketOpt(zmq.api.ZMQ.ZMQ_PLAIN_SERVER, server);
        }

        /**
         * Returns the ZMQ_PLAIN_SERVER option, if any, previously set on the socket.
         *
         * @return true if the role of the socket should be server for the PLAIN mechanism.
         * @deprecated the naming is inconsistent with jzmq, please use {@link #getPlainServer()} instead
         * @see #setAsServerPlain(boolean)
         */
        @Deprecated
        public boolean isAsServerPlain()
        {
            return getPlainServer();
        }

        /**
         * Returns the ZMQ_PLAIN_SERVER option, if any, previously set on the socket.
         *
         * @return true if the role of the socket should be server for the PLAIN mechanism.
         * @deprecated the naming is inconsistent with jzmq, please use {@link #getPlainServer()} instead
         * @see #setAsServerPlain(boolean)
         */
        @Deprecated
        public boolean getAsServerPlain()
        {
            return getPlainServer();
        }

        /**
         * Returns the ZMQ_PLAIN_SERVER option, if any, previously set on the socket.
         *
         * @return true if the role of the socket should be server for the PLAIN mechanism.
         * @see #setAsServerPlain(boolean)
         */
        public boolean getPlainServer()
        {
            if (before(4, 0, 0)) {
                return false;
            }
            return base.getSocketOpt(zmq.api.ZMQ.ZMQ_PLAIN_SERVER) != 0;
        }

        /**
         * Sets the username for outgoing connections over TCP or IPC.
         * If you set this to a non-null value, the security mechanism used for connections shall be PLAIN, see zmq_plain(7).
         * If you set this to a null value, the security mechanism used for connections shall be NULL, see zmq_null(3).
         *
         * @param username the username to set.
         * @return true if the option was set, otherwise false.
         */
        public boolean setPlainUsername(String username)
        {
            if (before(4, 0, 0)) {
                return false;
            }
            return base.setSocketOpt(zmq.api.ZMQ.ZMQ_PLAIN_USERNAME, username);
        }

        /**
         * Sets the password for outgoing connections over TCP or IPC.
         * If you set this to a non-null value, the security mechanism used for connections
         * shall be PLAIN, see zmq_plain(7).
         * If you set this to a null value, the security mechanism used for connections shall be NULL, see zmq_null(3).
         *
         * @param password the password to set.
         * @return true if the option was set, otherwise false.
         */
        public boolean setPlainPassword(String password)
        {
            if (before(4, 0, 0)) {
                return false;
            }
            return base.setSocketOpt(zmq.api.ZMQ.ZMQ_PLAIN_PASSWORD, password);
        }

        /**
         * Sets the username for outgoing connections over TCP or IPC.
         * If you set this to a non-null value, the security mechanism used for connections shall be PLAIN, see zmq_plain(7).
         * If you set this to a null value, the security mechanism used for connections shall be NULL, see zmq_null(3).
         *
         * @param username the username to set.
         * @return true if the option was set, otherwise false.
         */
        public boolean setPlainUsername(byte[] username)
        {
            if (before(4, 0, 0)) {
                return false;
            }
            return base.setSocketOpt(zmq.api.ZMQ.ZMQ_PLAIN_USERNAME, username);
        }

        /**
         * Sets the password for outgoing connections over TCP or IPC.
         * If you set this to a non-null value, the security mechanism used for connections
         * shall be PLAIN, see zmq_plain(7).
         * If you set this to a null value, the security mechanism used for connections shall be NULL, see zmq_null(3).
         *
         * @param password the password to set.
         * @return true if the option was set, otherwise false.
         */
        public boolean setPlainPassword(byte[] password)
        {
            if (before(4, 0, 0)) {
                return false;
            }
            return base.setSocketOpt(zmq.api.ZMQ.ZMQ_PLAIN_PASSWORD, password);
        }

        /**
         * The ZMQ_PLAIN_USERNAME option shall retrieve the last username
         * set for the PLAIN security mechanism.
         *
         * @return the plain username.
         */
        public String getPlainUsername()
        {
            if (before(4, 0, 0)) {
                return null;
            }
            return (String) base.getSocketOptx(zmq.api.ZMQ.ZMQ_PLAIN_USERNAME);
        }

        /**
         * The ZMQ_PLAIN_PASSWORD option shall retrieve the last password
         * set for the PLAIN security mechanism.
         * The returned value MAY be empty.
         *
         * @return the plain password.
         */
        public String getPlainPassword()
        {
            if (before(4, 0, 0)) {
                return null;
            }
            return (String) base.getSocketOptx(zmq.api.ZMQ.ZMQ_PLAIN_PASSWORD);
        }

        /**
         * Defines whether the socket will act as server for CURVE security, see zmq_curve(7).
         * A value of true means the socket will act as CURVE server.
         * A value of false means the socket will not act as CURVE server,
         * and its security role then depends on other option settings.
         * Setting this to false shall reset the socket security to NULL.
         * When you set this you must also set the server's secret key using the ZMQ_CURVE_SECRETKEY option.
         * A server socket does not need to know its own public key.
         *
         * @param server true if the role of the socket should be server for CURVE mechanism
         * @return true if the option was set
         * @deprecated the naming is inconsistent with jzmq, please use {@link #setCurveServer(boolean)} instead
         * @see #isAsServerCurve()
         */
        @Deprecated
        public boolean setAsServerCurve(boolean server)
        {
            return setCurveServer(server);
        }

        /**
         * Defines whether the socket will act as server for CURVE security, see zmq_curve(7).
         * A value of true means the socket will act as CURVE server.
         * A value of false means the socket will not act as CURVE server,
         * and its security role then depends on other option settings.
         * Setting this to false shall reset the socket security to NULL.
         * When you set this you must also set the server's secret key using the ZMQ_CURVE_SECRETKEY option.
         * A server socket does not need to know its own public key.
         *
         * @param server true if the role of the socket should be server for CURVE mechanism
         * @return true if the option was set
         * @see #isAsServerCurve()
         */
        public boolean setCurveServer(boolean server)
        {
            if (before(4, 0, 0)) {
                return false;
            }
            return setSocketOpt(zmq.api.ZMQ.ZMQ_CURVE_SERVER, server);
        }

        /**
         * Tells if the socket will act as server for CURVE security.
         *
         * @return true if the role of the socket should be server for CURVE mechanism.
         * @deprecated the naming is inconsistent with jzmq, please use {@link #getCurveServer()} instead
         * @see #setAsServerCurve(boolean)
         */
        @Deprecated
        public boolean isAsServerCurve()
        {
            return getCurveServer();
        }

        /**
         * Tells if the socket will act as server for CURVE security.
         *
         * @return true if the role of the socket should be server for CURVE mechanism.
         * @see #setAsServerCurve(boolean)
         */
        public boolean getCurveServer()
        {
            if (before(4, 0, 0)) {
                return false;
            }
            return (boolean) base.getSocketOptx(zmq.api.ZMQ.ZMQ_CURVE_SERVER);
        }

        /**
         * Tells if the socket will act as server for CURVE security.
         *
         * @return true if the role of the socket should be server for CURVE mechanism.
         * @deprecated the naming is inconsistent with jzmq, please use {@link #getCurveServer()} instead
         * @see #setAsServerCurve(boolean)
         */
        @Deprecated
        public boolean getAsServerCurve()
        {
            return getCurveServer();
        }

        /**
         * Sets the socket's long term public key.
         * You must set this on CURVE client sockets, see zmq_curve(7).
         * You can provide the key as 32 binary bytes, or as a 40-character string
         * encoded in the Z85 encoding format.
         * The public key must always be used with the matching secret key.
         * To generate a public/secret key pair,
         * use {@link zmq.io.mechanism.curve.Curve#keypair()} or {@link zmq.io.mechanism.curve.Curve#keypairZ85()}.
         *
         * @param key the curve public key
         * @return true if the option was set, otherwise false
         * @see #getCurvePublicKey()
         */
        public boolean setCurvePublicKey(byte[] key)
        {
            if (before(4, 0, 0)) {
                return false;
            }
            return setSocketOpt(zmq.api.ZMQ.ZMQ_CURVE_PUBLICKEY, key);
        }

        /**
         * Sets the socket's long term server key.
         * You must set this on CURVE client sockets, see zmq_curve(7).
         * You can provide the key as 32 binary bytes, or as a 40-character string
         * encoded in the Z85 encoding format.
         * This key must have been generated together with the server's secret key.
         * To generate a public/secret key pair,
         * use {@link zmq.io.mechanism.curve.Curve#keypair()} or {@link zmq.io.mechanism.curve.Curve#keypairZ85()}.
         *
         * @param key the curve server key
         * @return true if the option was set, otherwise false
         * @see #getCurveServerKey()
         */
        public boolean setCurveServerKey(byte[] key)
        {
            if (before(4, 0, 0)) {
                return false;
            }
            return setSocketOpt(zmq.api.ZMQ.ZMQ_CURVE_SERVERKEY, key);
        }

        /**
         * Sets the socket's long term secret key.
         * You must set this on both CURVE client and server sockets, see zmq_curve(7).
         * You can provide the key as 32 binary bytes, or as a 40-character string
         * encoded in the Z85 encoding format.
         * To generate a public/secret key pair,
         * use {@link zmq.io.mechanism.curve.Curve#keypair()} or {@link zmq.io.mechanism.curve.Curve#keypairZ85()}.
         *
         * @param key the curve secret key
         * @return true if the option was set, otherwise false
         * @see #getCurveSecretKey()
         */
        public boolean setCurveSecretKey(byte[] key)
        {
            if (before(4, 0, 0)) {
                return false;
            }
            return setSocketOpt(zmq.api.ZMQ.ZMQ_CURVE_SECRETKEY, key);
        }

        /**
         * Retrieves the current long term public key for the socket in binary format of 32 bytes.
         *
         * @return key the curve public key
         * @see #setCurvePublicKey(byte[])
         */
        public byte[] getCurvePublicKey()
        {
            if (before(4, 0, 0)) {
                return null;
            }
            return (byte[]) base.getSocketOptx(zmq.api.ZMQ.ZMQ_CURVE_PUBLICKEY);
        }

        /**
         * Retrieves the current server key for the socket in binary format of 32 bytes.
         *
         * @return key the curve server key
         * @see #setCurveServerKey(byte[])
         */
        public byte[] getCurveServerKey()
        {
            if (before(4, 0, 0)) {
                return null;
            }
            return (byte[]) base.getSocketOptx(zmq.api.ZMQ.ZMQ_CURVE_SERVERKEY);
        }

        /**
         * Retrieves the current long term secret key for the socket in binary format of 32 bytes.
         *
         * @return key the curve secret key
         * @see #setCurveSecretKey(byte[])
         */
        public byte[] getCurveSecretKey()
        {
            if (before(4, 0, 0)) {
                return null;
            }
            return (byte[]) base.getSocketOptx(zmq.api.ZMQ.ZMQ_CURVE_SECRETKEY);
        }

        public boolean setGSSAPIServer(boolean server)
        {
            if (after(4, 1, 0)) {
                base.setSocketOpt(zmq.api.ZMQ.ZMQ_GSSAPI_SERVER, server);
                return true;
            }
            return false;
        }

        public boolean getGSSAPIServer()
        {
            if (after(4, 1, 0)) {
                return (boolean) base.getSocketOptx(zmq.api.ZMQ.ZMQ_GSSAPI_SERVER);
            }
            return false;
        }

        public boolean setGSSAPIPrincipal(byte[] principal)
        {
            if (after(4, 1, 0)) {
                base.setSocketOpt(zmq.api.ZMQ.ZMQ_GSSAPI_PRINCIPAL, principal);
                return true;
            }
            return false;
        }

        public byte[] getGSSAPIPrincipal()
        {
            if (after(4, 1, 0)) {
                return (byte[]) base.getSocketOptx(zmq.api.ZMQ.ZMQ_GSSAPI_PRINCIPAL);
            }
            return null;
        }

        public boolean setGSSAPIServicePrincipal(byte[] principal)
        {
            if (after(4, 1, 0)) {
                base.setSocketOpt(zmq.api.ZMQ.ZMQ_GSSAPI_SERVICE_PRINCIPAL, principal);
                return true;
            }
            return false;
        }

        public byte[] getGSSAPIServicePrincipal()
        {
            if (after(4, 1, 0)) {
                return (byte[]) base.getSocketOptx(zmq.api.ZMQ.ZMQ_GSSAPI_SERVICE_PRINCIPAL);
            }
            return null;
        }

        public boolean setGSSAPIPlainText(boolean plaintext)
        {
            if (after(4, 1, 0)) {
                base.setSocketOpt(zmq.api.ZMQ.ZMQ_GSSAPI_PLAINTEXT, plaintext);
                return true;
            }
            return false;
        }

        public boolean getGSSAPIPlainText()
        {
            if (after(4, 1, 0)) {
                return (boolean) base.getSocketOptx(zmq.api.ZMQ.ZMQ_GSSAPI_PLAINTEXT);
            }
            return false;
        }

        /**
         * The ZMQ_MECHANISM option shall retrieve the current security mechanism for the socket.
         *
         * @return the current mechanism.
         */
        public Mechanism getMechanism()
        {
            return Mechanism.of(PROVIDER.findMechanism(base.getSocketOptx(zmq.api.ZMQ.ZMQ_MECHANISM)));
        }

        /**
         * Bind to network interface. Start listening for new connections.
         *
         * @param addr
         *            the endpoint to bind to.
         * @return true if the socket was bound, otherwise false.
         */
        public boolean bind(String addr)
        {
            boolean rc = base.bind(addr);
            mayRaise();
            return rc;
        }

        /**
         * Bind to network interface to a random port. Start listening for new connections.
         * The main difference from bindToRandomPort is that it allows system to pick up the right port.
         * For this reason return type is a string and its responsibility of a caller to parse into
         * the right integer type (e.g. for vmci transport it should be long).
         *
         * @param addr the endpoint to bind to.
         */
        public int bindToSystemRandomPort(String addr)
        {
            if (before(3, 2, 0)) {
                throw new UnsupportedOperationException();
            }

            bind(String.format("%s:*", addr));
            String endpoint = getLastEndpoint();
            String port = endpoint.substring(endpoint.lastIndexOf(":") + 1);
            return Integer.parseInt(port);
        }

        /**
         * Bind to network interface to a random port. Start listening for new
         * connections.
         *
         * @param addr
         *            the endpoint to bind to.
         */
        public int bindToRandomPort(String addr)
        {
            return bindToRandomPort(addr, DYNFROM, DYNTO);
        }

        /**
         * Bind to network interface to a random port. Start listening for new
         * connections.
         *
         * @param addr
         *            the endpoint to bind to.
         * @param min
         *            The minimum port in the range of ports to try.
         * @param max
         *            The maximum port in the range of ports to try.
         */
        public int bindToRandomPort(String addr, int min, int max)
        {
            return bindToRandomPort(addr, min, max, 100);
        }

        /**
         * Bind to network interface to a random port. Start listening for new
         * connections.
         *
         * @param addr
         *            the endpoint to bind to.
         * @param min
         *            The minimum port in the range of ports to try.
         * @param max
         *            The maximum port in the range of ports to try.
         */
        public int bindToRandomPort(String addr, int min, int max, int maxTries)
        {
            int port;
            Random rand = new Random();
            for (int i = 0; i < maxTries; i++) {
                port = rand.nextInt(max - min + 1) + min;
                try {
                    if (base.bind(String.format("%s:%s", addr, port))) {
                        return port;
                    }
                }
                catch (ZMQException e) {
                    if (e.getErrorCode() != ZError.EADDRINUSE) {
                        throw e;
                    }
                    continue;
                }
            }
            throw new ZMQException("Could not bind socket to random port.", ZError.EADDRINUSE);
        }

        /**
         * Connects the socket to an endpoint and then accepts incoming connections on that endpoint.
         * <p/>
         * The endpoint is a string consisting of a transport :// followed by an address.
         * <br/>
         * The transport specifies the underlying protocol to use.
         * <br/>
         * The address specifies the transport-specific address to connect to.
         * <p/>
         * ØMQ provides the the following transports:
         * <ul>
         * <li>tcp - unicast transport using TCP</li>
         * <li>ipc - local inter-process communication transport</li>
         * <li>inproc - local in-process (inter-thread) communication transport</li>
         * </ul>
         * Every ØMQ socket type except ZMQ_PAIR supports one-to-many and many-to-one semantics.
         * The precise semantics depend on the socket type.
         * <p/>
         * For most transports and socket types the connection is not performed immediately but as needed by ØMQ.
         * <br/>
         * Thus a successful call to connect(String) does not mean that the connection was or could actually be established.
         * <br/>
         * Because of this, for most transports and socket types
         * the order in which a server socket is bound and a client socket is connected to it does not matter.
         * <br/>
         * The first exception is when using the inproc:// transport: you must call {@link #bind(String)} before calling connect().
         * <br/>
         * The second exception are ZMQ_PAIR sockets, which do not automatically reconnect to endpoints.
         * <p/>
         * Following a connect(), for socket types except for ZMQ_ROUTER, the socket enters its normal ready state.
         * <br/>
         * By contrast, following a {@link #bind(String)} alone, the socket enters a mute state
         * in which the socket blocks or drops messages according to the socket type.
         * <br/>
         * A ZMQ_ROUTER socket enters its normal ready state for a specific peer
         * only when handshaking is complete for that peer, which may take an arbitrary time.
         *
         * @param addr
         *            the endpoint to connect to.
         * @return true if the socket was connected, otherwise false.
         */
        public boolean connect(String addr)
        {
            boolean rc = base.connect(addr);
            mayRaise();
            return rc;
        }

        /**
         * Disconnect from remote application.
         *
         * @param addr
         *            the endpoint to disconnect from.
         * @return true if successful.
         */
        public boolean disconnect(String addr)
        {
            return base.termEndpoint(addr);
        }

        /**
         * Stop accepting connections on a socket.
         *
         * @param addr
         *            the endpoint to unbind from.
         * @return true if successful.
         */
        public boolean unbind(String addr)
        {
            return base.termEndpoint(addr);
        }

        /**
         * Queues a message created from data, so it can be sent.
         *
         * @param data the data to send. The data is either a single-part message by itself,
         * or the last part of a multi-part message.
         * @return true when it has been queued on the socket and ØMQ has assumed responsibility for the message.
         * This does not indicate that the message has been transmitted to the network.
         */
        public boolean send(String data)
        {
            return send(data.getBytes(CHARSET), 0);
        }

        /**
         * Queues a multi-part message created from data, so it can be sent.
         *
         * @param data the data to send. further message parts are to follow.
         * @return true when it has been queued on the socket and ØMQ has assumed responsibility for the message.
         * This does not indicate that the message has been transmitted to the network.
         */
        public boolean sendMore(String data)
        {
            return send(data.getBytes(CHARSET), zmq.api.ZMQ.ZMQ_SNDMORE);
        }

        /**
         * Queues a message created from data.
         *
         * @param data the data to send.
         * @param flags a combination (with + or |) of the flags defined below:
         * <ul>
         * <li>{@link org.zeromq.ZMQ#DONTWAIT DONTWAIT}:
         * For socket types ({@link org.zeromq.ZMQ#DEALER DEALER}, {@link org.zeromq.ZMQ#PUSH PUSH})
         * that block when there are no available peers (or all peers have full high-water mark),
         * specifies that the operation should be performed in non-blocking mode.
         * If the message cannot be queued on the socket, the method shall fail with errno set to EAGAIN.</li>
         * <li>{@link org.zeromq.ZMQ#SNDMORE SNDMORE}:
         * Specifies that the message being sent is a multi-part message,
         * and that further message parts are to follow.</li>
         * <li>0 : blocking send of a single-part message or the last of a multi-part message</li>
         * </ul>
         * @return true when it has been queued on the socket and ØMQ has assumed responsibility for the message.
         * This does not indicate that the message has been transmitted to the network.
         */
        public boolean send(String data, int flags)
        {
            return send(data.getBytes(CHARSET), flags);
        }

        /**
         * Queues a message created from data, so it can be sent.
         *
         * @param data the data to send. The data is either a single-part message by itself,
         * or the last part of a multi-part message.
         * @return true when it has been queued on the socket and ØMQ has assumed responsibility for the message.
         * This does not indicate that the message has been transmitted to the network.
         */
        public boolean send(byte[] data)
        {
            return send(data, 0);
        }

        /**
         * Queues a multi-part message created from data, so it can be sent.
         *
         * @param data the data to send. further message parts are to follow.
         * @return true when it has been queued on the socket and ØMQ has assumed responsibility for the message.
         * This does not indicate that the message has been transmitted to the network.
         */
        public boolean sendMore(byte[] data)
        {
            return send(data, zmq.api.ZMQ.ZMQ_SNDMORE);
        }

        /**
         * Queues a message created from data, so it can be sent.
         *
         * @param data the data to send.
         * @param flags a combination (with + or |) of the flags defined below:
         * <ul>
         * <li>{@link org.zeromq.ZMQ#DONTWAIT DONTWAIT}:
         * For socket types ({@link org.zeromq.ZMQ#DEALER DEALER}, {@link org.zeromq.ZMQ#PUSH PUSH})
         * that block when there are no available peers (or all peers have full high-water mark),
         * specifies that the operation should be performed in non-blocking mode.
         * If the message cannot be queued on the socket, the method shall fail with errno set to EAGAIN.</li>
         * <li>{@link org.zeromq.ZMQ#SNDMORE SNDMORE}:
         * Specifies that the message being sent is a multi-part message,
         * and that further message parts are to follow.</li>
         * <li>0 : blocking send of a single-part message or the last of a multi-part message</li>
         * </ul>
         * @return true when it has been queued on the socket and ØMQ has assumed responsibility for the message.
         * This does not indicate that the message has been transmitted to the network.
         */
        public boolean send(byte[] data, int flags)
        {
            return send(PROVIDER.msg(data), flags);
        }

        public boolean send(AMsg msg, int flags)
        {
            if (base.send(msg, flags)) {
                return true;
            }

            mayRaise();
            return false;
        }

        /**
         * Queues a message created from data, so it can be sent.
         *
         * @param data the data to send.
         * @param off the index of the first byte to be sent.
         * @param length the number of bytes to be sent.
         * @param flags a combination (with + or |) of the flags defined below:
         * <ul>
         * <li>{@link org.zeromq.ZMQ#DONTWAIT DONTWAIT}:
         * For socket types ({@link org.zeromq.ZMQ#DEALER DEALER}, {@link org.zeromq.ZMQ#PUSH PUSH})
         * that block when there are no available peers (or all peers have full high-water mark),
         * specifies that the operation should be performed in non-blocking mode.
         * If the message cannot be queued on the socket, the method shall fail with errno set to EAGAIN.</li>
         * <li>{@link org.zeromq.ZMQ#SNDMORE SNDMORE}:
         * Specifies that the message being sent is a multi-part message,
         * and that further message parts are to follow.</li>
         * <li>0 : blocking send of a single-part message or the last of a multi-part message</li>
         * </ul>
         * @return true when it has been queued on the socket and ØMQ has assumed responsibility for the message.
         * This does not indicate that the message has been transmitted to the network.
         */
        public boolean send(byte[] data, int off, int length, int flags)
        {
            byte[] copy = new byte[length];
            System.arraycopy(data, off, copy, 0, length);
            AMsg msg = PROVIDER.msg(copy);
            if (base.send(msg, flags)) {
                return true;
            }

            mayRaise();
            return false;
        }

        public boolean sendZeroCopy(ByteBuffer buf, int length, int flags)
        {
            buf.flip();
            AMsg msg = PROVIDER.msg(buf);
            if (base.send(msg, flags)) {
                return true;
            }

            mayRaise();
            return false;
        }

        /**
         * Queues a message created from data, so it can be sent.
         *
         * @param data ByteBuffer payload
         * @param flags a combination (with + or |) of the flags defined below:
         * <ul>
         * <li>{@link org.zeromq.ZMQ#DONTWAIT DONTWAIT}:
         * For socket types ({@link org.zeromq.ZMQ#DEALER DEALER}, {@link org.zeromq.ZMQ#PUSH PUSH})
         * that block when there are no available peers (or all peers have full high-water mark),
         * specifies that the operation should be performed in non-blocking mode.
         * If the message cannot be queued on the socket, the method shall fail with errno set to EAGAIN.</li>
         * <li>{@link org.zeromq.ZMQ#SNDMORE SNDMORE}:
         * Specifies that the message being sent is a multi-part message,
         * and that further message parts are to follow.</li>
         * <li>0 : blocking send of a single-part message or the last of a multi-part message</li>
         * </ul>
         * @return the number of bytes queued, -1 on error
         */
        public int sendByteBuffer(ByteBuffer data, int flags)
        {
            AMsg msg = PROVIDER.msg(data);
            if (base.send(msg, flags)) {
                return msg.size();
            }

            mayRaise();
            return -1;
        }

        /**
         * Receives a message.
         *
         * @return the message received, as an array of bytes; null on error.
         */
        public byte[] recv()
        {
            return recv(0);
        }

        /**
         * Receives a message.
         *
         * @param flags either:
         * <ul>
         * <li>{@link org.zeromq.ZMQ#DONTWAIT DONTWAIT}:
         * Specifies that the operation should be performed in non-blocking mode.
         * If there are no messages available on the specified socket,
         * the method shall fail with errno set to EAGAIN and return null.</li>
         * <li>0 : receive operation blocks until one message is successfully retrieved,
         * or stops when timeout set by {@link #setReceiveTimeOut(int)} expires.</li>
         * </ul>
         * @return the message received, as an array of bytes; null on error.
         */
        public byte[] recv(int flags)
        {
            AMsg msg = base.recv(flags);

            if (msg != null) {
                return msg.data();
            }

            mayRaise();
            return null;
        }

        /**
         * Receives a message in to a specified buffer.
         *
         * @param buffer
         *            byte[] to copy zmq message payload in to.
         * @param offset
         *            offset in buffer to write data
         * @param len
         *            max bytes to write to buffer.
         *            If len is smaller than the incoming message size,
         *            the message will be truncated.
         * @param flags either:
         * <ul>
         * <li>{@link org.zeromq.ZMQ#DONTWAIT DONTWAIT}:
         * Specifies that the operation should be performed in non-blocking mode.
         * If there are no messages available on the specified socket,
         * the method shall fail with errno set to EAGAIN and return null.</li>
         * <li>0 : receive operation blocks until one message is successfully retrieved,
         * or stops when timeout set by {@link #setReceiveTimeOut(int)} expires.</li>
         * </ul>
         * @return the number of bytes read, -1 on error
         */
        public int recv(byte[] buffer, int offset, int len, int flags)
        {
            AMsg msg = base.recv(flags);

            if (msg != null) {
                return msg.getBytes(0, buffer, offset, len);
            }

            return -1;
        }

        /**
         * Receives a message into the specified ByteBuffer.
         *
         * @param buffer the buffer to copy the zmq message payload into
         * @param flags either:
         * <ul>
         * <li>{@link org.zeromq.ZMQ#DONTWAIT DONTWAIT}:
         * Specifies that the operation should be performed in non-blocking mode.
         * If there are no messages available on the specified socket,
         * the method shall fail with errno set to EAGAIN and return null.</li>
         * <li>0 : receive operation blocks until one message is successfully retrieved,
         * or stops when timeout set by {@link #setReceiveTimeOut(int)} expires.</li>
         * </ul>
         * @return the number of bytes read, -1 on error
         */
        public int recvByteBuffer(ByteBuffer buffer, int flags)
        {
            AMsg msg = base.recv(flags);

            if (msg != null) {
                buffer.put(msg.buf());
                return msg.size();
            }

            mayRaise();
            return -1;
        }

        /**
         * Receive a message into the specified ByteBuffer
         *
         * @param buffer the buffer to copy the zmq message payload into
         * @param flags the flags to apply to the receive operation
         * @return the number of bytes read, -1 on error
         */
        public int recvZeroCopy(ByteBuffer buffer, int length, int flags)
        {
            AMsg msg = base.recv(flags);

            if (msg != null) {
                buffer.put(msg.buf());
                return msg.size();
            }

            mayRaise();
            return -1;
        }

        /**
         *
         * @return the message received, as a String object; null on no message.
         */
        public String recvStr()
        {
            return recvStr(0);
        }

        /**
         * Receives a message as a string.
         *
         * @param flags either:
         * <ul>
         * <li>{@link org.zeromq.ZMQ#DONTWAIT DONTWAIT}:
         * Specifies that the operation should be performed in non-blocking mode.
         * If there are no messages available on the specified socket,
         * the method shall fail with errno set to EAGAIN and return null.</li>
         * <li>0 : receive operation blocks until one message is successfully retrieved,
         * or stops when timeout set by {@link #setReceiveTimeOut(int)} expires.</li>
         * </ul>
         * @return the message received, as a String object; null on no message.
         */
        public String recvStr(int flags)
        {
            return recvStr(flags, CHARSET);
        }

        public String recvStr(Charset charset)
        {
            return recvStr(0, charset);
        }

        /**
        *
        * @param flags the flags to apply to the receive operation.
        * @return the message received, as a String object; null on no message.
        */
        public String recvStr(int flags, Charset charset)
        {
            byte[] msg = recv(flags);

            if (msg != null) {
                return new String(msg, charset);
            }

            return null;
        }

        /**
         * Start a monitoring socket where events can be received.
         *
         * Lets an application thread track socket events (like connects) on a ZeroMQ socket.
         * Each call to this method creates a {@link ZMQ#PAIR} socket and binds that to the specified inproc:// endpoint.
         * To collect the socket events, you must create your own PAIR socket, and connect that to the endpoint.
         * <br/>
         * Supports only connection-oriented transports, that is, TCP, IPC.
         *
         * @param addr the endpoint to receive events from. (must be inproc transport)
         * @param events the events of interest. A bitmask of the socket events you wish to monitor. To monitor all events, use the event value {@link ZMQ#EVENT_ALL}.
         * @return true if monitor socket setup is successful
         * @throws ZMQException
         */
        public boolean monitor(String addr, int events)
        {
            return base.monitor(addr, events);
        }

        private void mayRaise()
        {
            int errno = base.errno();
            if (errno != 0 && errno != ZError.EAGAIN) {
                throw new ZMQException(errno);
            }
        }

        public int errno()
        {
            return base.errno();
        }

        @Override
        public String toString()
        {
            return base.toString();
        }

        public Object getSocketOptx(int option)
        {
            return base.getSocketOptx(option);
        }
    }

    /**
     * Provides a mechanism for applications to multiplex input/output events in a level-triggered fashion over a set of sockets
     */
    public static class Poller implements Closeable
    {
        /**
         * For ØMQ sockets, at least one message may be received from the socket without blocking.
         * <br/>
         * For standard sockets this is equivalent to the POLLIN flag of the poll() system call
         * and generally means that at least one byte of data may be read from fd without blocking.
         */
        public static final int POLLIN  = zmq.api.ZMQ.ZMQ_POLLIN;
        /**
         * For ØMQ sockets, at least one message may be sent to the socket without blocking.
         * <br/>
         * For standard sockets this is equivalent to the POLLOUT flag of the poll() system call
         * and generally means that at least one byte of data may be written to fd without blocking.
         */
        public static final int POLLOUT = zmq.api.ZMQ.ZMQ_POLLOUT;
        /**
         * For standard sockets, this flag is passed through {@link zmq.ZMQ#poll(Selector, zmq.poll.PollItem[], long)} to the underlying poll() system call
         * and generally means that some sort of error condition is present on the socket specified by fd.
         * <br/>
         * For ØMQ sockets this flag has no effect if set in events, and shall never be returned in revents by {@link zmq.ZMQ#poll(Selector, zmq.poll.PollItem[], long)}.
         */
        public static final int POLLERR = zmq.api.ZMQ.ZMQ_POLLERR;

        private static final int SIZE_DEFAULT   = 32;
        private static final int SIZE_INCREMENT = 16;

        private final Selector selector;
        private final Context  context;

        private PollItem[] items;
        private int        next;
        private int        used;

        private long timeout;

        // When socket is removed from polling, store free slots here
        private LinkedList<Integer> freeSlots;

        /**
         * Class constructor.
         *
         * @param context
         *            a 0MQ context previously created.
         * @param size
         *            the number of Sockets this poller will contain.
         */
        protected Poller(Context context, int size)
        {
            assert (context != null);
            this.context = context;

            selector = context.selector();
            assert (selector != null);

            items = new PollItem[size];
            timeout = -1L;
            next = 0;

            freeSlots = new LinkedList<>();
        }

        /**
         * Class constructor.
         *
         * @param context
         *            a 0MQ context previously created.
         */
        protected Poller(Context context)
        {
            this(context, SIZE_DEFAULT);
        }

        @Override
        public void close()
        {
            context.close(selector);
        }

        /**
         * Register a Socket for polling on all events.
         *
         * @param socket
         *            the Socket we are registering.
         * @return the index identifying this Socket in the poll set.
         */
        public int register(Socket socket)
        {
            return register(socket, POLLIN | POLLOUT | POLLERR);
        }

        /**
         * Register a Channel for polling on all events.
         *
         * @param channel
         *            the Channel we are registering.
         * @return the index identifying this Channel in the poll set.
         */
        public int register(SelectableChannel channel)
        {
            return register(channel, POLLIN | POLLOUT | POLLERR);
        }

        /**
         * Register a Socket for polling on the specified events.
         *
         * Automatically grow the internal representation if needed.
         *
         * @param socket
         *            the Socket we are registering.
         * @param events
         *            a mask composed by XORing POLLIN, POLLOUT and POLLERR.
         * @return the index identifying this Socket in the poll set.
         */
        public int register(Socket socket, int events)
        {
            return register(new PollItem(socket, events));
        }

        /**
         * Register a Socket for polling on the specified events.
         *
         * Automatically grow the internal representation if needed.
         *
         * @param channel
         *            the Channel we are registering.
         * @param events
         *            a mask composed by XORing POLLIN, POLLOUT and POLLERR.
         * @return the index identifying this Channel in the poll set.
         */
        public int register(SelectableChannel channel, int events)
        {
            return register(new PollItem(channel, events));
        }

        /**
         * Register a Channel for polling on the specified events.
         *
         * Automatically grow the internal representation if needed.
         *
         * @param item
         *            the PollItem we are registering.
         * @return the index identifying this Channel in the poll set.
         */
        public int register(PollItem item)
        {
            int pos;

            if (!freeSlots.isEmpty()) {
                // If there are free slots in our array, remove one
                // from the free list and use it.
                pos = freeSlots.remove();
            }
            else {
                if (next >= items.length) {
                    PollItem[] nitems = new PollItem[items.length + SIZE_INCREMENT];
                    System.arraycopy(items, 0, nitems, 0, items.length);
                    items = nitems;
                }
                pos = next++;
            }

            items[pos] = item;
            used++;
            return pos;
        }

        public void unregister(PollItem item)
        {
            unregisterInternal(item);
        }

        /**
         * Unregister a Socket for polling on the specified events.
         *
         * @param socket
         *          the Socket to be unregistered
         */
        public void unregister(Socket socket)
        {
            unregisterInternal(socket);
        }

        /**
         * Unregister a Socket for polling on the specified events.
         *
         * @param channel
         *          the Socket to be unregistered
         */
        public void unregister(SelectableChannel channel)
        {
            unregisterInternal(channel);
        }

        /**
         * Unregister a Socket for polling on the specified events.
         *
         * @param obj the object to be unregistered
         */
        private void unregisterInternal(Object obj)
        {
            for (int i = 0; i < next; ++i) {
                PollItem item = items[i];
                if (item == null) {
                    continue;
                }
                // TODO
                if (item == obj || item.getSocket() == obj || item.getRawSocket() == obj) {
                    items[i] = null;

                    freeSlots.add(i);
                    --used;

                    break;
                }
            }
        }

        /**
         * Get the PollItem associated with an index.
         *
         * @param index
         *            the desired index.
         * @return the PollItem associated with that index (or null).
         */
        public PollItem getItem(int index)
        {
            if (index < 0 || index >= this.next) {
                return null;
            }
            return this.items[index];
        }

        /**
         * Get the socket associated with an index.
         *
         * @param index
         *            the desired index.
         * @return the Socket associated with that index (or null).
         */
        public Socket getSocket(int index)
        {
            if (index < 0 || index >= this.next) {
                return null;
            }
            return items[index].getSocket();
        }

        /**
         * Get the current poll timeout.
         *
         * @return the current poll timeout in milliseconds.
         * @deprecated Timeout handling has been moved to the poll() methods.
         */
        @Deprecated
        public long getTimeout()
        {
            return this.timeout;
        }

        /**
         * Set the poll timeout.
         *
         * @param timeout
         *            the desired poll timeout in milliseconds.
         * @deprecated Timeout handling has been moved to the poll() methods.
         */
        @Deprecated
        public void setTimeout(long timeout)
        {
            if (timeout >= -1L) {
                this.timeout = timeout;
            }
        }

        /**
         * Get the current poll set size.
         *
         * @return the current poll set size.
         */
        public int getSize()
        {
            return items.length;
        }

        /**
         * Get the index for the next position in the poll set size.
         *
         * @return the index for the next position in the poll set size.
         */
        public int getNext()
        {
            return this.next;
        }

        /**
         * Issue a poll call. If the poller's internal timeout value
         * has been set, use that value as timeout; otherwise, block
         * indefinitely.
         *
         * @return how many objects where signaled by poll ().
         */
        public int poll()
        {
            long tout = -1L;
            if (this.timeout > -1L) {
                tout = this.timeout;
            }
            return poll(tout);
        }

        /**
         * Issue a poll call, using the specified timeout value.
         * <p>
         * Since ZeroMQ 3.0, the timeout parameter is in <i>milliseconds<i>,
         * but prior to this the unit was <i>microseconds</i>.
         *
         * @param tout
         *            the timeout, as per zmq_poll ();
         *            if -1, it will block indefinitely until an event
         *            happens; if 0, it will return immediately;
         *            otherwise, it will wait for at most that many
         *            milliseconds/microseconds (see above).
         *
         * @see "http://api.zeromq.org/3-0:zmq-poll"
         *
         * @return how many objects where signaled by poll ()
         */
        public int poll(long tout)
        {
            if (tout < -1) {
                return 0;
            }
            if (items.length <= 0 || next <= 0) {
                return 0;
            }
            try {
                APollItem[] its = new APollItem[used];
                for (int idx = 0; idx < used; ++idx) {
                    its[idx] = items[idx].base;
                }
                return PROVIDER.poll(selector, its, used, tout);
            }
            catch (ZError.IOException e) {
                if (context.isTerminated()) {
                    return 0;
                }
                else {
                    throw (e);
                }
            }
        }

        /**
         * Check whether the specified element in the poll set was signaled for input.
         *
         * @param index
         *
         * @return true if the element was signaled.
         */
        public boolean pollin(int index)
        {
            if (index < 0 || index >= this.next) {
                return false;
            }

            return items[index].isReadable();
        }

        /**
         * Check whether the specified element in the poll set was signaled for output.
         *
         * @param index
         *
         * @return true if the element was signaled.
         */
        public boolean pollout(int index)
        {
            if (index < 0 || index >= this.next) {
                return false;
            }

            return items[index].isWritable();
        }

        /**
         * Check whether the specified element in the poll set was signaled for error.
         *
         * @param index
         *
         * @return true if the element was signaled.
         */
        public boolean pollerr(int index)
        {
            if (index < 0 || index >= this.next) {
                return false;
            }

            return items[index].isError();
        }

    }

    public static class PollItem
    {
        private final APollItem base;
        private final Socket    socket;

        public PollItem(Socket socket, int ops)
        {
            this.socket = socket;
            base = PROVIDER.pollItem(socket.base, ops);
        }

        public PollItem(SelectableChannel channel, int ops)
        {
            base = PROVIDER.pollItem(channel, ops);
            socket = null;
        }

        final APollItem base()
        {
            return base;
        }

        public final SelectableChannel getRawSocket()
        {
            return base.getRawSocket();
        }

        public final Socket getSocket()
        {
            return socket;
        }

        public final Socket socket()
        {
            return socket;
        }

        public final boolean isReadable()
        {
            return base.isReadable();
        }

        public final boolean isWritable()
        {
            return base.isWritable();
        }

        public final boolean isError()
        {
            return base.isError();
        }

        public final int readyOps()
        {
            return base.readyOps();
        }

        @Override
        public int hashCode()
        {
            return base.hashCode();
        }

        @Override
        public boolean equals(Object obj)
        {
            if (!(obj instanceof PollItem)) {
                return false;
            }

            PollItem target = (PollItem) obj;
            if (socket != null && socket == target.socket) {
                return true;
            }

            if (getRawSocket() != null && getRawSocket() == target.getRawSocket()) {
                return true;
            }

            return false;
        }

        public boolean hasEvent(int events)
        {
            return base.hasEvent(events);
        }
    }

    public enum Error
    {
        ENOTSUP(ZError.ENOTSUP),
        EPROTONOSUPPORT(ZError.EPROTONOSUPPORT),
        ENOBUFS(ZError.ENOBUFS),
        ENETDOWN(ZError.ENETDOWN),
        EADDRINUSE(ZError.EADDRINUSE),
        EADDRNOTAVAIL(ZError.EADDRNOTAVAIL),
        ECONNREFUSED(ZError.ECONNREFUSED),
        EINPROGRESS(ZError.EINPROGRESS),
        EHOSTUNREACH(ZError.EHOSTUNREACH),
        EMTHREAD(ZError.EMTHREAD),
        EFSM(ZError.EFSM),
        ENOCOMPATPROTO(ZError.ENOCOMPATPROTO),
        ETERM(ZError.ETERM),
        ENOTSOCK(ZError.ENOTSOCK),
        EAGAIN(ZError.EAGAIN);

        private final int code;

        Error(int code)
        {
            this.code = code;
        }

        public int getCode()
        {
            return code;
        }

        public static Error findByCode(int code)
        {
            for (Error e : Error.values()) {
                if (e.getCode() == code) {
                    return e;
                }
            }
            throw new IllegalArgumentException("Unknown " + Error.class.getName() + " enum code:" + code);
        }
    }

    @Deprecated
    public static boolean device(int type, Socket frontend, Socket backend)
    {
        return proxy(frontend, backend, null);
    }

    /**
     * Starts the built-in 0MQ proxy in the current application thread.
     * The proxy connects a frontend socket to a backend socket. Conceptually, data flows from frontend to backend.
     * Depending on the socket types, replies may flow in the opposite direction. The direction is conceptual only;
     * the proxy is fully symmetric and there is no technical difference between frontend and backend.
     *
     * Before calling ZMQ.proxy() you must set any socket options, and connect or bind both frontend and backend sockets.
     * The two conventional proxy models are:
     *
     * ZMQ.proxy() runs in the current thread and returns only if/when the current context is closed.
     * @param frontend ZMQ.Socket
     * @param backend ZMQ.Socket
     * @param capture If the capture socket is not NULL, the proxy shall send all messages, received on both
     *                frontend and backend, to the capture socket. The capture socket should be a
     *                ZMQ_PUB, ZMQ_DEALER, ZMQ_PUSH, or ZMQ_PAIR socket.
     */
    public static boolean proxy(Socket frontend, Socket backend, Socket capture)
    {
        if (getFullVersion() < makeVersion(3, 2, 2)) {
            throw new UnsupportedOperationException();
        }
        return PROVIDER.proxy(frontend.base, backend.base, capture != null ? capture.base : null, null);
    }

    public static boolean proxy(Socket frontend, Socket backend, Socket capture, Socket control)
    {
        return PROVIDER.proxy(
                              frontend.base,
                              backend.base,
                              capture == null ? null : capture.base,
                              control == null ? null : control.base);
    }

    public static int poll(Selector selector, PollItem[] items, long timeout)
    {
        return poll(selector, items, items.length, timeout);
    }

    public static int poll(Selector selector, PollItem[] items, int count, long timeout)
    {
        APollItem[] pollItems = new APollItem[count];
        for (int i = 0; i < count; i++) {
            pollItems[i] = items[i].base;
        }

        return poll(selector, pollItems, count, timeout);
    }

    public static int poll(Selector selector, APollItem[] items, int count, long timeout)
    {
        return PROVIDER.poll(selector, items, count, timeout);
    }

    /**
     * @return Major version number of the ZMQ library.
     */
    public static int getMajorVersion()
    {
        return PROVIDER.versionMajor();
    }

    /**
     * @return Major version number of the ZMQ library.
     */
    public static int getMinorVersion()
    {
        return PROVIDER.versionMinor();
    }

    /**
     * @return Major version number of the ZMQ library.
     */
    public static int getPatchVersion()
    {
        return PROVIDER.versionPatch();
    }

    public static boolean before(final int major, final int minor, final int patch)
    {
        return ZMQ.getFullVersion() < ZMQ.makeVersion(2, 1, 0);
    }

    public static boolean after(final int major, final int minor, final int patch)
    {
        return ZMQ.getFullVersion() >= ZMQ.makeVersion(2, 1, 0);
    }

    /**
     * @return Full version number of the ZMQ library used for comparing versions.
     */
    public static int getFullVersion()
    {
        return makeVersion(PROVIDER.versionMajor(), PROVIDER.versionMinor(), PROVIDER.versionPatch());
    }

    /**
     * @param major Version major component.
     * @param minor Version minor component.
     * @param patch Version patch component.
     *
     * @return Comparable single int version number.
     */
    public static int makeVersion(final int major, final int minor, final int patch)
    {
        return ((major) * 10000 + (minor) * 100 + (patch));
    }

    /**
     * @return String version number in the form major.minor.patch.
     */
    public static String getVersionString()
    {
        return String.format("%d.%d.%d", getMajorVersion(), getMinorVersion(), getPatchVersion());
    }

    /**
     * Inner class: Event.
     * Monitor socket event class
     */
    public static class Event
    {
        private final int    event;
        private final Object value;
        private final String address;

        public Event(int event, Object value, String address)
        {
            this.event = event;
            this.value = value;
            this.address = address;
        }

        public int getEvent()
        {
            return event;
        }

        public Object getValue()
        {
            return value;
        }

        public String getAddress()
        {
            return address;
        }

        /**
         * Receive an event from a monitor socket.
         * @param socket the socket
         * @param flags the flags to apply to the receive operation.
         * @return the received event or null if no message was received.
         * @throws ZMQException
         */
        public static Event recv(Socket socket, int flags)
        {
            AEvent e = PROVIDER.read(socket.base, flags);
            return e != null ? new Event(e.event(), e.argument(), e.address()) : null;
        }

        /**
         * Receive an event from a monitor socket.
         * Does a blocking recv.
         * @param socket the socket
         * @return the received event.
         * @throws ZMQException
         */
        public static Event recv(Socket socket)
        {
            return Event.recv(socket, 0);
        }
    }

    public static void sleep(long seconds)
    {
        sleep(seconds, TimeUnit.SECONDS);
    }

    public static void msleep(long milliseconds)
    {
        sleep(milliseconds, TimeUnit.MILLISECONDS);
    }

    public static void sleep(long amount, TimeUnit unit)
    {
        LockSupport.parkNanos(TimeUnit.NANOSECONDS.convert(amount, unit));
    }

    public static class Clock
    {
        private Clock()
        {
        }

        /**
         * High precision timestamp in microseconds.
         */
        public static long nowUS()
        {
            return TimeUnit.MICROSECONDS.convert(System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        /**
         * High precision timestamp in nanoseconds.
         */
        public static long nowNS()
        {
            return System.nanoTime();
        }

        /**
         * Low precision timestamp. In tight loops generating it can be
         * 10 to 100 times faster than the high precision timestamp.
         */
        public static long nowMS()
        {
            return System.currentTimeMillis();
        }

        public static long monoMS()
        {
            return TimeUnit.MILLISECONDS.convert(System.nanoTime(), TimeUnit.NANOSECONDS);
        }
    }

    @Draft
    public static final class Timers
    {
        /**
         * Opaque representation of a timer.
         */
        public static class Timer
        {
            private final TimerHandle delegate;

            private Timer(TimerHandle delegate)
            {
                this.delegate = delegate;
            }
        }

        /**
         * Called when the timer has been expired.
         */
        public static interface Handler extends ATimer.Handler
        {
        }

        private final ATimer timer = PROVIDER.timer();

        /**
         * Add timer to the set, timer repeats forever, or until cancel is called.
         * @param interval the interval of repetition in milliseconds.
         * @param handler the callback called at the expiration of the timer.
         * @param args the optional arguments for the handler.
         * @return an opaque handle for further cancel.
         */
        public Timer add(long interval, Handler handler, Object... args)
        {
            if (handler == null) {
                return null;
            }
            return new Timer(timer.add(interval, handler, args));
        }

        /**
         * Changes the interval of the timer.
         *
         * This method is slow, cancelling existing and adding a new timer yield better performance.
         * @param timer the timer to change the interval to.
         * @return true if set, otherwise false.
         */
        public boolean setInterval(Timer timer, long interval)
        {
            return this.timer.setInterval(timer.delegate, interval);
        }

        /**
         * Reset the timer.
         *
         * This method is slow, cancelling existing and adding a new timer yield better performance.
         * @param timer the timer to reset.
         * @return true if reset, otherwise false.
         */
        public boolean reset(Timer timer)
        {
            return this.timer.reset(timer.delegate);
        }

        /**
         * Cancel a timer.
         *
         * @param timer the timer to cancel.
         * @return true if cancelled, otherwise false.
         */
        public boolean cancel(Timer timer)
        {
            return this.timer.cancel(timer.delegate);
        }

        /**
         * Returns the time in millisecond until the next timer.
         *
         * @return the time in millisecond until the next timer.
         */
        public long timeout()
        {
            return timer.timeout();
        }

        /**
         * Execute the timers.
         *
         * @return the number of timers triggered.
         */
        public int execute()
        {
            return timer.execute();
        }

        /**
         * Sleeps until at least one timer can be executed and execute the timers.
         *
         * @return the number of timers triggered.
         */
        public int sleepAndExecute()
        {
            long timeout = timeout();
            while (timeout > 0) {
                ZMQ.msleep(timeout);
                timeout = timeout();
            }
            return execute();
        }
    }

    /**
     * Class that interfaces the generation of CURVE key pairs.
     *
     * The CURVE mechanism defines a mechanism for secure authentication and confidentiality for communications between a client and a server.
     * CURVE is intended for use on public networks.
     * The CURVE mechanism is defined by this document: http://rfc.zeromq.org/spec:25.
     * <p/>
     * <h1>Client and server roles</h1>
     * <p/>
     * A socket using CURVE can be either client or server, at any moment, but not both. The role is independent of bind/connect direction.
     * A socket can change roles at any point by setting new options. The role affects all connect and bind calls that follow it.
     * <p/>
     * To become a CURVE server, the application sets the {@link ZMQ.Socket#setAsServerCurve(boolean)} option on the socket,
     * and then sets the {@link ZMQ.Socket#setCurveSecretKey(byte[])} option to provide the socket with its long-term secret key.
     * The application does not provide the socket with its long-term public key, which is used only by clients.
     * <p/>
     * To become a CURVE client, the application sets the {@link ZMQ.Socket#setCurveServerKey(byte[])} option
     * with the long-term public key of the server it intends to connect to, or accept connections from, next.
     * The application then sets the {@link ZMQ.Socket#setCurvePublicKey(byte[])} and {@link ZMQ.Socket#setCurveSecretKey(byte[])} options with its client long-term key pair.
     * If the server does authentication it will be based on the client's long term public key.
     * <p/>
     * <h1>Key encoding</h1>
     * <p/>
     * The standard representation for keys in source code is either 32 bytes of base 256 (binary) data,
     * or 40 characters of base 85 data encoded using the Z85 algorithm defined by http://rfc.zeromq.org/spec:32.
     * The Z85 algorithm is designed to produce printable key strings for use in configuration files, the command line, and code.
     * There is a reference implementation in C at https://github.com/zeromq/rfc/tree/master/src.
     * <p/>
     * <h1>Test key values</h1>
     * <p/>
     * For test cases, the client shall use this long-term key pair (specified as hexadecimal and in Z85):
     * <ul>
     *  <li>public:
     *      <ul>
     *          <li>BB88471D65E2659B30C55A5321CEBB5AAB2B70A398645C26DCA2B2FCB43FC518</li>
     *          <li>Yne@$w-vo<fVvi]a<NY6T1ed:M$fCG*[IaLV{hID</li>
     *      </ul>
     *  </li>
     *  <li>secret:
     *      <ul>
     *          <li>7BB864B489AFA3671FBE69101F94B38972F24816DFB01B51656B3FEC8DFD0888</li>
     *          <li>D:)Q[IlAW!ahhC2ac:9*A}h:p?([4%wOTJ%JR%cs</li>
     *      </ul>
     *  </li>
     * </ul>
     * <br/>
     * And the server shall use this long-term key pair (specified as hexadecimal and in Z85):
     * <ul>
     *  <li>public:
     *      <ul>
     *          <li>54FCBA24E93249969316FB617C872BB0C1D1FF14800427C594CBFACF1BC2D652</li>
     *          <li>rq:rM>}U?@Lns47E1%kR.o@n%FcmmsL/@{H8]yf7</li>
     *      </ul>
     *  </li>
     *  <li>secret:
     *      <ul>
     *          <li>8E0BDD697628B91D8F245587EE95C5B04D48963F79259877B49CD9063AEAD3B7</li>
     *          <li>JTKVSB%%)wK0E.X)V>+}o?pNmC{O&4W4b!Ni{Lh6</li>
     *      </ul>
     *  </li>
     * </ul>
     */
    public static class Curve
    {
        private Curve()
        {
            // no instantiation
        }

        /**
         * A container for a public and a corresponding secret key
         */
        public static class KeyPair
        {
            /**
             * Z85-encoded public key.
             */
            public final String publicKey;

            /**
             * Z85-encoded secret key.
             */
            public final String secretKey;

            public KeyPair(final String publicKey, final String secretKey)
            {
                this.publicKey = publicKey;
                this.secretKey = secretKey;
            }
        }

        /**
         * Security credentials for CURVE mechanism.
         * Normal base 256 key is 32 bytes
         */
        public static final int KEYSIZE     = 32;
        /**
         * Security credentials for CURVE mechanism.
         * Key encoded using Z85 is 40 bytes
         */
        public static final int KEYSIZE_Z85 = 40;

        /**
         * Returns a newly generated random keypair consisting of a public key
         * and a secret key.
         *
         * <p>The keys are encoded using {@link #z85Encode}.</p>
         *
         * @return Randomly generated {@link KeyPair}
         */
        public static KeyPair generateKeyPair()
        {
            String[] keys = PROVIDER.keypairZ85();
            return new KeyPair(keys[0], keys[1]);
        }

        /**
         * The function shall decode given key encoded as Z85 string into byte array.
         * <br/>
         * The length of string shall be divisible by 5.
         * <p>The decoding shall follow the ZMQ RFC 32 specification.</p>
         *
         * @param key Key to be decoded
         * @return The resulting key as byte array
         */
        public static byte[] z85Decode(String key)
        {
            return PROVIDER.z85Decode(key);
        }

        /**
         * Encodes the binary block specified by data into a string.
         * <br/>
         * The size of the binary block must be divisible by 4.
         * <br/>
         * A 32-byte CURVE key is encoded as 40 ASCII characters plus a null terminator.
         * <br/>
         * The function shall encode the binary block specified into a string.
         *
         * <p>The encoding shall follow the ZMQ RFC 32 specification.</p>
         *
         * @param key Key to be encoded
         * @return The resulting key as String in Z85
         */
        public static String z85Encode(byte[] key)
        {
            return PROVIDER.z85Encode(key);
        }
    }

    public static AMsg msg(byte[] data)
    {
        return PROVIDER.msg(data);
    }

    public static AMetadata metadata()
    {
        return PROVIDER.metadata();
    }

    public static int findOpenPort() throws IOException
    {
        final ServerSocket tmpSocket = new ServerSocket(0, 0);
        try {
            return tmpSocket.getLocalPort();
        }
        finally {
            tmpSocket.close();
        }
    }
}
