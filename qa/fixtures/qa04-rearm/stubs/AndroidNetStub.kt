// QA-04 JVM harness stub for android.net.Uri, as used by the real alarm intent.
package android.net

class Uri private constructor(private val text: String) {
    override fun toString(): String = text

    class Builder {
        private var scheme = ""
        private var authority = ""
        private val paths = ArrayList<String>()

        fun scheme(value: String): Builder { scheme = value; return this }
        fun authority(value: String): Builder { authority = value; return this }
        fun appendPath(value: String): Builder { paths += value; return this }
        fun build(): Uri = Uri("$scheme://$authority/${paths.joinToString("/")}")
    }
}
