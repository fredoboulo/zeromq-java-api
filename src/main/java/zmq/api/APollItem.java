package zmq.api;

import java.nio.channels.SelectableChannel;

public interface APollItem
{
    SelectableChannel getRawSocket();

    boolean isReadable();

    boolean isWritable();

    boolean isError();

    int readyOps();

    boolean hasEvent(int events);

    ASocket getSocket();
}
