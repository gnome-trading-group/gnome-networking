package group.gnometrading.networking.sockets;

public enum SocketState {
    UNCONNECTED,
    CONNECTED,
    /**
     * Still open, but a write failed part way: TLS holds the unsent remainder and would splice it onto the next
     * write, and a plain socket has sent a fragment, so nothing more can be sent intact until it reconnects.
     * Reads still return what has already arrived, then end of stream.
     */
    BROKEN
}
