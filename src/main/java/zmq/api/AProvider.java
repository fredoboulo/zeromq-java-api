package zmq.api;

import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;

public interface AProvider
{
    AContext context(int ioThreads);

    AMsg msg(byte[] data);

    AMsg msg(ByteBuffer data);

    String[] keypairZ85();

    String z85Encode(byte[] key);

    byte[] z85Decode(String key);

    AMechanism findMechanism(Object mechanism);

    APollItem pollItem(ASocket socket, int ops);

    APollItem pollItem(SelectableChannel channel, int ops);

    int poll(Selector selector, APollItem[] items, int size, long timeout);

    boolean proxy(ASocket frontend, ASocket backend, ASocket capture, ASocket control);

    AMetadata metadata();

    AEvent read(ASocket socket, int flags);

    int versionMajor();

    int versionMinor();

    int versionPatch();

    ATimer timer();
}
