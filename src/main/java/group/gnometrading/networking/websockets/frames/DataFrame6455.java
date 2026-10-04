package group.gnometrading.networking.websockets.frames;

import group.gnometrading.networking.websockets.enums.Opcode;

import java.nio.ByteBuffer;
import java.util.Random;

public class DataFrame6455 implements DataFrame {

    private static final ByteBuffer DEFAULT_MASK_KEY = ByteBuffer.allocate(4);
    // Leaves room for the largest frame header without overflowing an int length.
    private static final int MAX_PAYLOAD_LENGTH = Integer.MAX_VALUE - 16;

    static {
        DEFAULT_MASK_KEY.putInt(new Random().nextInt());
    }

    private ByteBuffer buffer;
    private int limit;
    private int offset;
    private final ByteBuffer maskKey = ByteBuffer.allocate(4);

    public DataFrame6455() {
        this(DEFAULT_MASK_KEY.getInt(0));
    }

    public DataFrame6455(int maskKey) {
        this.maskKey.putInt(maskKey);
    }

    @Override
    public DataFrame wrap(ByteBuffer buffer, int offset, int n) {
        this.buffer = buffer;
        this.limit = n;
        this.offset = offset;
        return this;
    }

    @Override
    public Opcode getOpcode() {
        return Opcode.fromByte(this.buffer.get(offset));
    }

    private void writeInt(int input, int numBytes) {
        for (int i = numBytes - 1; i >= 0; i--) {
            buffer.put((byte) (input >>> (i * 8)));
        }
    }

    private void writeLong(long input, int numBytes) {
        for (int i = numBytes - 1; i >= 0; i--) {
            buffer.put((byte) (input >>> (i * 8)));
        }
    }

    @Override
    public void encode(Opcode opcode, ByteBuffer payload) {
        // No support for fragmented frames
        this.buffer.put((byte) (0b10000000 | opcode.code));

        // Since we're the client, we will always mask
        byte mask = (byte) 0b10000000;

        int payloadLength = payload.remaining();
        if (payloadLength >= (2 << 15)) {
            this.buffer.put((byte) (mask | 127));
            writeLong(payloadLength, 8);
        } else if (payloadLength > 125) {
            this.buffer.put((byte) (mask | 126));
            writeInt(payloadLength, 2);
        } else {
            this.buffer.put((byte) (mask | payloadLength));
        }

        this.buffer.putInt(maskKey.getInt(0));

        final int payloadStart = payload.position();
        for (int i = 0; i < payloadLength; i++) {
            this.buffer.put((byte) (payload.get(payloadStart + i) ^ maskKey.get(i % 4)));
        }
    }

    private boolean fin() {
        return (this.buffer.get(offset) & 0b10000000) == 0b10000000;
    }

    private boolean masked() {
        return (this.buffer.get(offset + 1) & 0b10000000) == 0b10000000;
    }

    private int getPayloadLengthOctets() {
        int length = this.buffer.get(offset + 1) & 0b01111111;
        if (length == 126) {
            return 3;
        } else if (length == 127) {
            return 9;
        } else {
            return 1;
        }
    }

    private int readInt(int idx, int numBytes) {
        int value = 0;
        for (int i = 0; i < numBytes; i++) {
            value = (value << 8) | (buffer.get(idx + i) & 0xFF);
        }
        return value;
    }

    private int getPayloadLength() {
        int length = this.buffer.get(offset + 1) & 0b01111111;
        if (length == 126) {
            return readInt(offset + 2, 2);
        } else if (length == 127) {
            long longLength = 0;
            for (int i = 0; i < 8; i++) {
                longLength = (longLength << 8) | (buffer.get(offset + 2 + i) & 0xFF);
            }
            // Anything too large to buffer is reported as the largest length, which no read buffer can hold, so
            // the frame is rejected as an overflow rather than misread through a truncated or negative length.
            return longLength < 0 || longLength > MAX_PAYLOAD_LENGTH ? MAX_PAYLOAD_LENGTH : (int) longLength;
        }
        return length;
    }

    private int getMaskingKey() {
        int index = offset + 1 + this.getPayloadLengthOctets();
        return this.buffer.getInt(index);
    }

    @Override
    public boolean isMasked() {
        return this.masked();
    }

    @Override
    public int payloadOffset() {
        return offset + 1 + this.getPayloadLengthOctets() + (this.masked() ? 4 : 0);
    }

    @Override
    public int payloadLength() {
        return this.getPayloadLength();
    }

    @Override
    public void copyPayloadData(final ByteBuffer other) {
        int index = offset + 1 + this.getPayloadLengthOctets() + (this.masked() ? 4 : 0);
        int payloadLength = this.getPayloadLength();

        if (this.masked()) {
            int key = this.getMaskingKey();
            for (int i = 0; i < payloadLength; i++) {
                byte mask = (byte) ((key >> (8 * (3 - (i % 4)))) & 0xFF);
                other.put((byte) (this.buffer.get(i + index) ^ mask));
            }
        } else {
            for (int i = 0; i < payloadLength; i++) {
                other.put(this.buffer.get(i + index));
            }
        }
    }

    @Override
    public int length() {
        int length = 1 + this.getPayloadLengthOctets();

        if (this.masked()) {
            length += 4;
        }

        return length + this.getPayloadLength();
    }

    @Override
    public boolean isFragment() {
        return !this.fin();
    }

    @Override
    public boolean isIncomplete() {
        int requiredOctets = 1;
        if (this.limit <= requiredOctets) {
            return true;
        }

        requiredOctets += this.getPayloadLengthOctets();
        if (this.limit < requiredOctets) {
            return true;
        }

        requiredOctets += this.masked() ? 4 : 0;
        if (this.limit < requiredOctets) {
            return true;
        }

        requiredOctets += this.getPayloadLength();
        return this.limit < requiredOctets;
    }

    @Override
    public boolean hasCompleteHeader() {
        int requiredOctets = 1;
        if (this.limit <= requiredOctets) {
            return false;
        }

        requiredOctets += this.getPayloadLengthOctets();
        if (this.limit < requiredOctets) {
            return false;
        }

        requiredOctets += this.masked() ? 4 : 0;
        if (this.limit < requiredOctets) {
            return false;
        }

        return true;
    }
}
