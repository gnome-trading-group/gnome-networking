package group.gnometrading.networking.sockets;

import group.gnometrading.resources.LibraryLoader;
import java.io.IOException;

/**
 * Loads the native library (sockets, TLS and signing) exactly once, for every class with natives in it. Each load extracts a fresh
 * copy of the library, and a second copy brings its own BoringSSL: the JVM may then bind some natives to each,
 * handing TLS objects made by one copy to the other, which crashes.
 */
public final class NativeSockets {

    static {
        try {
            LibraryLoader.loadLibrary("NativeSockets");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private NativeSockets() {}

    /** Returns once the library is loaded; the class's own initialization does the work. */
    public static void load() {}
}
