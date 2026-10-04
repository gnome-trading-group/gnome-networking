package group.gnometrading.networking.sockets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two copies of the native library in one process each carry their own BoringSSL, and TLS objects passed between
 * them crash the JVM. Runs in a fresh JVM, since the test JVM has long since loaded both socket classes.
 */
class NativeLibraryLoadingIT {

    @Test
    @Timeout(60)
    void bothSocketClassesShareOneCopyOfTheLibrary(@TempDir Path tmp) throws Exception {
        final String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final Process process = new ProcessBuilder(
                        java,
                        "--add-exports",
                        "java.base/sun.nio.ch=ALL-UNNAMED",
                        "-Djava.io.tmpdir=" + tmp,
                        "-cp",
                        System.getProperty("java.class.path"),
                        LoadBoth.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(tmp.resolve("output.txt").toFile())
                .start();
        process.waitFor(50, TimeUnit.SECONDS);
        assertEquals(0, process.exitValue(), Files.readString(tmp.resolve("output.txt")));

        try (Stream<Path> files = Files.list(tmp)) {
            final long copies = files.map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.startsWith("libNativeSockets"))
                    .count();
            assertEquals(1, copies, "native library copies extracted");
        }
    }

    static final class LoadBoth {
        public static void main(String[] args) throws Exception {
            Class.forName(NativeSSLSocket.class.getName(), true, LoadBoth.class.getClassLoader());
            Class.forName(NativeSocket.class.getName(), true, LoadBoth.class.getClassLoader());
            System.out.println("loaded from " + new File(".").getAbsolutePath());
        }
    }
}
