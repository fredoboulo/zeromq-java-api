package zmq.api;

public interface AEvent
{
    int event();

    Object argument();

    String address();
}
