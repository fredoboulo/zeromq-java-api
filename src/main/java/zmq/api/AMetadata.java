package zmq.api;

import java.nio.ByteBuffer;
import java.util.Set;

public interface AMetadata
{
    /**
     * Call backs during parsing process
     */
    public interface ParseListener
    {
        /**
         * Called when a property has been parsed.
         * @param name the name of the property.
         * @param value the value of the property.
         * @param valueAsString the value in a string representation.
         * @return 0 to continue the parsing process, any other value to interrupt it.
         */
        int parsed(String name, byte[] value, String valueAsString);
    }

    Set<String> keySet();

    String get(String key);

    void set(String key, String value);

    byte[] bytes();

    int read(ByteBuffer buffer, int index, ParseListener listener);
}
