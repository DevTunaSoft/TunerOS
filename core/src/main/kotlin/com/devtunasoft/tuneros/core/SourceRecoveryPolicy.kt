package com.devtunasoft.tuneros.core

/**
 * Pure decision rules for restoring previously validated sources after an
 * Android process restart. No URLs are logged, stored or fetched here.
 *
 * An active import or unsaved user edit always wins over background recovery.
 */
object SourceRecoveryPolicy {
    fun shouldRestore(
        savedUrl: String?,
        displayedUrl: String,
        hasLoadedData: Boolean,
        hasActiveImport: Boolean,
    ): Boolean =
        !savedUrl.isNullOrBlank() && savedUrl == displayedUrl &&
            !hasLoadedData && !hasActiveImport

    /** Ignore a result once a newer import request has superseded it. */
    fun mayCommit(validResult: Boolean, requestEpoch: Int, latestEpoch: Int): Boolean =
        validResult && requestEpoch == latestEpoch
}
