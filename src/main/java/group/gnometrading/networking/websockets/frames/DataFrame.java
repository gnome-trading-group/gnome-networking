package group.gnometrading.networking.websockets.frames;

import group.gnometrading.networking.websockets.enums.Opcode;

import java.nio.ByteBuffer;

/**
 * A generic interface used for encoding and decoding WebSocket data frames.
 */
public interface DataFrame {
    /**
     * Wrap a buffer from offset `offset`. Used for encoding and decoding message frames.
     * Assumes that the input ByteBuffer has at least size >= (offset + n).
     * @param buffer the buffer to wrap
     * @param offset the offset to index from
     * @param n the size in bytes of the frame -- used for DataFrame#isIncomplete
     * @return the class instance
     */
    DataFrame wrap(ByteBuffer buffer, int offset, int n);

    default DataFrame wrap(ByteBuffer buffer, int offset) {
        return wrap(buffer, offset, buffer.remaining());
    }

    default DataFrame wrap(ByteBuffer buffer) {
        return wrap(buffer, buffer.position(), buffer.remaining());
    }

    /**
     * @return true if the message frame is not complete
     */
    boolean isIncomplete();

    /**
     * @return true if the frame has the entire header but the body may or may not be present
     */
    boolean hasCompleteHeader();


    /**
     * @return true if the message frame is a fragment to be followed with more frames
     */
    boolean isFragment();


    /**
     * @return the length in bytes of the entire message frame
     */
    int length();


    /**
     * @return the opcode parsed from the byte buffer
     */
    Opcode getOpcode();

    /**
     * Copy the payload data into a ByteBuffer.
     */
    void copyPayloadData(ByteBuffer other);


    /**
     * Write the opcode, payload, and accompanying metadata into the wrapped ByteBuffer.
     * @param opcode the opcode to encode
     * @param payload the payload to encode
     */
    default void encode(Opcode opcode, byte[] payload) {
        encode(opcode, ByteBuffer.wrap(payload));
    }

    /**
     * Write the opcode, payload, and accompanying metadata into the wrapped ByteBuffer.
     * @param opcode the opcode to encode
     * @param payload the payload to encode
     */
    void encode(Opcode opcode, ByteBuffer payload);
}
