package group.gnometrading.networking.sockets;

/** What a non-blocking look at a connected socket found. */
public enum SocketProbe {
    /** The peer closed the connection, or it failed. */
    DEAD,
    /** Open, with nothing waiting to be read. */
    IDLE,
    /** Open, with bytes waiting to be read. */
    READABLE
}
