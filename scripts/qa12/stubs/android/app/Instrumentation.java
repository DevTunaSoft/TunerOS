package android.app;

import android.content.Context;
import android.os.Bundle;

/** Minimal QA-only stub; not shipped and not used by production code. */
public class Instrumentation {
    public void onCreate(Bundle arguments) { }
    public void start() { }
    public void finish(int resultCode, Bundle results) { }
    public Context getTargetContext() { return null; }
}
