package group.gnometrading.networking.websockets.drafts;

import group.gnometrading.annotations.VisibleForTesting;
import group.gnometrading.networking.websockets.HandshakeInput;
import group.gnometrading.networking.websockets.enums.HandshakeState;
import group.gnometrading.networking.websockets.frames.DataFrame;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/**
 * An abstract class used for completing handshakes and parsing data frames.
 */
public abstract class Draft {

    // TODO: What's the latency on this?
    protected final RandomGenerator secureRandom;
    private final DataFrame dataFrame;

    public Draft(DataFrame dataFrame) {
        this(dataFrame, new SecureRandom());
    }

    @VisibleForTesting
    public Draft(DataFrame dataFrame, RandomGenerator randomGenerator) {
        this.dataFrame = dataFrame;
        this.secureRandom = randomGenerator;
    }

    public DataFrame getDataFrame() {
        return this.dataFrame;
    }


    /**
     * Construct a byte array of the input of a handshake sent to a server.
     * @param input the input to encode
     * @return the encoded byte array
     */
    public abstract byte[] createHandshake(HandshakeInput input);

    /**
     * Given the ByteBuffer response from the server, parse the handshake response.
     * @param buffer the buffer to parse
     * @return the resulting HandshakeState. HandshakeState.MATCHED if successful
     */
    public abstract HandshakeState parseHandshake(ByteBuffer buffer);
}
