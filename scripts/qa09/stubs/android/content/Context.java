package android.content;

import java.io.File;

/** QA-only compile stub exposing only the context call the instrumentation makes. */
public class Context {
    public File getFilesDir() {
        return new File(".");
    }
}
