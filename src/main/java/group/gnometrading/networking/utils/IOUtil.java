package group.gnometrading.networking.utils;

public class IOUtil {
    public static int normalize(final int n) {
        return (n == -2 || n == -3) ? 0 : n;
    }
}
