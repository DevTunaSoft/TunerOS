package android.net

/** Host-only signature mirror. [rawText] intentionally renders the marker if a diagnostic logs the URI. */
data class Uri(
    val scheme: String?,
    val authority: String?,
    val rawText: String = "",
) {
    override fun toString(): String = rawText.ifEmpty { "${scheme ?: ""}://${authority ?: ""}" }
}
