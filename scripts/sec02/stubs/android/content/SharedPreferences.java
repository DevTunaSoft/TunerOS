package android.content;

/** SEC-02 QA-only Android stub: the contract SecureSourceStore uses. */
public interface SharedPreferences {
    String getString(String key, String defValue);

    java.util.Map<String, ?> getAll();

    Editor edit();

    interface Editor {
        Editor putString(String key, String value);

        Editor remove(String key);

        boolean commit();
    }
}
