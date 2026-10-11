package android.util;

/**
 * Desktop stand-in for android.util.Base64, for the shared Android files
 * (TeamService). Only what they use: NO_WRAP standard Base64.
 */
public final class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int URL_SAFE = 8;

    private Base64() {}

    public static String encodeToString(byte[] input, int flags) {
        java.util.Base64.Encoder encoder = (flags & URL_SAFE) != 0
                ? java.util.Base64.getUrlEncoder() : java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) encoder = encoder.withoutPadding();
        String text = encoder.encodeToString(input);
        // Android wraps at 76 unless NO_WRAP; nothing shared relies on wrapping.
        return text;
    }

    public static byte[] decode(String input, int flags) {
        String clean = input.replaceAll("\\s", "");
        return (flags & URL_SAFE) != 0
                ? java.util.Base64.getUrlDecoder().decode(clean)
                : java.util.Base64.getDecoder().decode(clean);
    }
}
