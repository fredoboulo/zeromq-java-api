package zmq.api;

import java.nio.charset.Charset;

public interface ZMQ
{
    /*  Context options  */
    int ZMQ_IO_THREADS  = 1;
    int ZMQ_MAX_SOCKETS = 2;

    /*  Default for new contexts                                                  */
    int ZMQ_IO_THREADS_DFLT  = 1;
    int ZMQ_MAX_SOCKETS_DFLT = 1024;

    /******************************************************************************/
    /*  0MQ socket definition.                                                    */
    /******************************************************************************/

    /*  Socket types.                                                             */
    int ZMQ_PAIR   = 0;
    int ZMQ_PUB    = 1;
    int ZMQ_SUB    = 2;
    int ZMQ_REQ    = 3;
    int ZMQ_REP    = 4;
    int ZMQ_DEALER = 5;
    int ZMQ_ROUTER = 6;
    int ZMQ_PULL   = 7;
    int ZMQ_PUSH   = 8;
    int ZMQ_XPUB   = 9;
    int ZMQ_XSUB   = 10;
    int ZMQ_STREAM = 11;

    /*  Deprecated aliases                                                        */
    @Deprecated
    int ZMQ_XREQ = ZMQ_DEALER;
    @Deprecated
    int ZMQ_XREP = ZMQ_ROUTER;

    int ZMQ_CUSTOM_OPTION = 1000;

    /*  Socket options.                                                           */
    int ZMQ_HWM                 = 1;
    int ZMQ_SWAP                = 3;
    int ZMQ_AFFINITY            = 4;
    int ZMQ_IDENTITY            = 5;
    int ZMQ_SUBSCRIBE           = 6;
    int ZMQ_UNSUBSCRIBE         = 7;
    int ZMQ_RATE                = 8;
    int ZMQ_RECOVERY_IVL        = 9;
    int ZMQ_MCAST_LOOP          = 10;
    int ZMQ_SNDBUF              = 11;
    int ZMQ_RCVBUF              = 12;
    int ZMQ_RCVMORE             = 13;
    int ZMQ_FD                  = 14;
    int ZMQ_EVENTS              = 15;
    int ZMQ_TYPE                = 16;
    int ZMQ_LINGER              = 17;
    int ZMQ_RECONNECT_IVL       = 18;
    int ZMQ_BACKLOG             = 19;
    int ZMQ_RECONNECT_IVL_MAX   = 21;
    int ZMQ_MAXMSGSIZE          = 22;
    int ZMQ_SNDHWM              = 23;
    int ZMQ_RCVHWM              = 24;
    int ZMQ_MULTICAST_HOPS      = 25;
    int ZMQ_RCVTIMEO            = 27;
    int ZMQ_SNDTIMEO            = 28;
    int ZMQ_LAST_ENDPOINT       = 32;
    int ZMQ_ROUTER_MANDATORY    = 33;
    int ZMQ_TCP_KEEPALIVE       = 34;
    int ZMQ_TCP_KEEPALIVE_CNT   = 35;
    int ZMQ_TCP_KEEPALIVE_IDLE  = 36;
    int ZMQ_TCP_KEEPALIVE_INTVL = 37;
    int ZMQ_IMMEDIATE           = 39 + ZMQ_CUSTOM_OPTION; // for compatibility with ZMQ_DELAY_ATTACH_ON_CONNECT
    int ZMQ_XPUB_VERBOSE        = 40;
    int ZMQ_ROUTER_RAW          = 41;
    int ZMQ_IPV6                = 42;
    int ZMQ_MECHANISM           = 43;
    int ZMQ_PLAIN_SERVER        = 44;
    int ZMQ_PLAIN_USERNAME      = 45;
    int ZMQ_PLAIN_PASSWORD      = 46;
    int ZMQ_CURVE_SERVER        = 47;
    int ZMQ_CURVE_PUBLICKEY     = 48;
    int ZMQ_CURVE_SECRETKEY     = 49;
    int ZMQ_CURVE_SERVERKEY     = 50;
    int ZMQ_PROBE_ROUTER        = 51;
    int ZMQ_REQ_CORRELATE       = 52;
    int ZMQ_REQ_RELAXED         = 53;
    int ZMQ_CONFLATE            = 54;
    int ZMQ_ZAP_DOMAIN          = 55;
    // TODO: more constants
    int ZMQ_ROUTER_HANDOVER          = 56;
    int ZMQ_TOS                      = 57;
    int ZMQ_CONNECT_RID              = 61;
    int ZMQ_GSSAPI_SERVER            = 62;
    int ZMQ_GSSAPI_PRINCIPAL         = 63;
    int ZMQ_GSSAPI_SERVICE_PRINCIPAL = 64;
    int ZMQ_GSSAPI_PLAINTEXT         = 65;
    int ZMQ_HANDSHAKE_IVL            = 66;
    int ZMQ_SOCKS_PROXY              = 67;
    int ZMQ_XPUB_NODROP              = 69;
    int ZMQ_BLOCKY                   = 70;
    int ZMQ_HEARTBEAT_IVL            = 75;
    int ZMQ_HEARTBEAT_TTL            = 76;
    int ZMQ_HEARTBEAT_TIMEOUT        = 77;
    @Deprecated
    int ZMQ_XPUB_VERBOSE_UNSUBSCRIBE = 78;

    /* Custom options */
    @Deprecated
    int ZMQ_ENCODER                       = ZMQ_CUSTOM_OPTION + 1;
    @Deprecated
    int ZMQ_DECODER                       = ZMQ_CUSTOM_OPTION + 2;
    int ZMQ_MSG_ALLOCATOR                 = ZMQ_CUSTOM_OPTION + 3;
    int ZMQ_MSG_ALLOCATION_HEAP_THRESHOLD = ZMQ_CUSTOM_OPTION + 4;
    int ZMQ_HEARTBEAT_CONTEXT             = ZMQ_CUSTOM_OPTION + 5;

    /*  Message options                                                           */
    int ZMQ_MORE = 1;

    /*  Send/recv options.                                                        */
    int ZMQ_DONTWAIT = 1;
    int ZMQ_SNDMORE  = 2;

    /*  Deprecated aliases                                                        */
    @Deprecated
    int ZMQ_TCP_ACCEPT_FILTER       = 38;
    @Deprecated
    int ZMQ_IPV4ONLY                = 31;
    @Deprecated
    int ZMQ_DELAY_ATTACH_ON_CONNECT = 39;
    @Deprecated
    int ZMQ_NOBLOCK                 = ZMQ_DONTWAIT;
    @Deprecated
    int ZMQ_FAIL_UNROUTABLE         = ZMQ_ROUTER_MANDATORY;
    @Deprecated
    int ZMQ_ROUTER_BEHAVIOR         = ZMQ_ROUTER_MANDATORY;

    /******************************************************************************/
    /*  0MQ socket events and monitoring                                          */
    /******************************************************************************/

    /*  Socket transport events (tcp and ipc only)                                */
    int ZMQ_EVENT_CONNECTED          = 1;
    int ZMQ_EVENT_CONNECT_DELAYED    = 1 << 1;
    int ZMQ_EVENT_CONNECT_RETRIED    = 1 << 2;
    int ZMQ_EVENT_LISTENING          = 1 << 3;
    int ZMQ_EVENT_BIND_FAILED        = 1 << 4;
    int ZMQ_EVENT_ACCEPTED           = 1 << 5;
    int ZMQ_EVENT_ACCEPT_FAILED      = 1 << 6;
    int ZMQ_EVENT_CLOSED             = 1 << 7;
    int ZMQ_EVENT_CLOSE_FAILED       = 1 << 8;
    int ZMQ_EVENT_DISCONNECTED       = 1 << 9;
    int ZMQ_EVENT_MONITOR_STOPPED    = 1 << 10;
    int ZMQ_EVENT_HANDSHAKE_PROTOCOL = 1 << 15;
    int ZMQ_EVENT_ALL                = 0xffff;

    int ZMQ_POLLIN  = 1;
    int ZMQ_POLLOUT = 2;
    int ZMQ_POLLERR = 4;

    @Deprecated
    int ZMQ_STREAMER  = 1;
    @Deprecated
    int ZMQ_FORWARDER = 2;
    @Deprecated
    int ZMQ_QUEUE     = 3;

    byte[] MESSAGE_SEPARATOR = new byte[0];

    byte[] SUBSCRIPTION_ALL = new byte[0];

    Charset CHARSET = Charset.forName("UTF-8");

    byte[] PROXY_PAUSE     = "PAUSE".getBytes(ZMQ.CHARSET);
    byte[] PROXY_RESUME    = "RESUME".getBytes(ZMQ.CHARSET);
    byte[] PROXY_TERMINATE = "TERMINATE".getBytes(ZMQ.CHARSET);

    //  Security credentials for CURVE mechanism
    //  Normal base 256 key is 32 bytes
    int CURVE_KEYSIZE = 32;
    //  Key encoded using Z85 is 40 bytes
    int CURVE_KEYSIZE_Z85 = 40;

    // disable checkstyle error
    @Override
    String toString();
}
