package zmq.api;

import java.nio.ByteBuffer;

public interface AMsg
{
    int size();

    byte[] data();

    int getBytes(int index, byte[] buffer, int offset, int len);

    ByteBuffer buf();

    AMsg copy();
}
