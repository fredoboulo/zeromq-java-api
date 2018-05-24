package zmq.api;

import java.io.Closeable;

public interface ASocket extends Closeable
{
    @Override
    void close();

    int errno();

    boolean setSocketOpt(int option, Object value);

    long getSocketOpt(int option);

    Object getSocketOptx(int option);

    boolean bind(String addr);

    boolean connect(String addr);

    boolean termEndpoint(String addr);

    boolean send(AMsg msg, int flags);

    AMsg recv(int flags);

    boolean monitor(String addr, int events);
}
