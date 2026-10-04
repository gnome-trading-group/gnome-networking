package group.gnometrading.networking.websockets.enums;

public enum Opcode {
    CONTINUOUS(0x0), TEXT(0x1), BINARY(0x2), CLOSING(0x8), PING(0x9), PONG(0xA);

    public final int code;

    private static final Opcode[] BY_CODE = new Opcode[16];

    static {
        for (Opcode opcode : values()) {
            BY_CODE[opcode.code] = opcode;
        }
    }

    Opcode(int code) {
        this.code = code;
    }

    public int getCode() {
        return this.code;
    }

    public static Opcode fromByte(byte input) {
        /*
          0 1 2 3 4 5 6 7
         +-+-+-+-+-------+
         |F|R|R|R| opcode|
         |I|S|S|S|  (4)  |
         |N|V|V|V|       |
         | |1|2|3|       |
         +-+-+-+-+-------+
         */
        // A table lookup, since values() would clone the array on every frame.
        final Opcode opcode = BY_CODE[input & 0b00001111];
        if (opcode == null) {
            throw new IllegalArgumentException("Invalid opcode supplied");
        }
        return opcode;
    }
}
