// DVR-03 minimal platform stubs for the scheduler's rearm path. Synthetic only.
package android.net

class Uri private constructor(val encoded: String) {
    override fun toString(): String = encoded

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
