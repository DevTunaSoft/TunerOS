package android.util;

/**
 * SEC-02 QA-only Android stub. Counts decoded characters so the verifier can
 * prove oversized protected records are rejected before any decode allocation.
 */
public final class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_WRAP = 2;

    public static int decodeCalls = 0;
    public static int maxDecodedChars = 0;

    private Base64() {
    }

    public static byte[] decode(String encoded, int flags) {
        decodeCalls++;
        if (encoded.length() > maxDecodedChars) maxDecodedChars = encoded.length();
        return java.util.Base64.getDecoder().decode(encoded);
    }

    public static String encodeToString(byte[] input, int flags) {
        return java.util.Base64.getEncoder().encodeToString(input);
    }
}
