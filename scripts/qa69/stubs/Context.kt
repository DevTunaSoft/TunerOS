package android.content

/** Minimal platform stand-in used only by the offline host gate. */
open class Context {
    val applicationContext: Context get() = this
}
