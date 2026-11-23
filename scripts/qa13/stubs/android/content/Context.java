package android.content;

import java.io.File;

/** Minimal QA-only stub for targetContext.filesDir. */
public class Context {
    public File getFilesDir() { return new File("."); }
}
