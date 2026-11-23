package android.content;

/** SEC-02 QA-only Android stub. Never shipped in the app. */
public class Context {
    public static final int MODE_PRIVATE = 0;

    public SharedPreferences getSharedPreferences(String name, int mode) {
        throw new UnsupportedOperationException("SEC-02 QA stub");
    }

    public void deleteSharedPreferences(String name) {
        throw new UnsupportedOperationException("SEC-02 QA stub");
    }

    public java.io.File getFilesDir() {
        throw new UnsupportedOperationException("SEC-02 QA stub");
    }
}
