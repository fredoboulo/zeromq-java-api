package zmq.api;

import java.nio.channels.Selector;

public interface AContext
{
    boolean isAlive();

    boolean setOption(int option, int value);

    int getOption(int option);

    void terminate();

    Selector createSelector();

    boolean closeSelector(Selector selector);

    ASocket createSocket(int type);
}
