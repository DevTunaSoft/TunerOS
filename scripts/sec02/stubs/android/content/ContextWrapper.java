package android.content;

/** SEC-02 QA-only Android stub used to type-check instrumentation on the JVM. */
public class ContextWrapper extends Context {
    private final Context base;

    public ContextWrapper(Context base) {
        this.base = base;
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return base.getSharedPreferences(name, mode);
    }

    @Override
    public void deleteSharedPreferences(String name) {
        base.deleteSharedPreferences(name);
    }

    @Override
    public java.io.File getFilesDir() {
        return base.getFilesDir();
    }
}
