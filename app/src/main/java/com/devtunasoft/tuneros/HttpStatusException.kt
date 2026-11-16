package com.devtunasoft.tuneros

/**
 * A non-2xx answer from a provider. It keeps the status code, so a caller can treat a 404 differently; live DASH
 * waits for a segment the origin has not published yet. The message carries no URL and no response body.
 */
internal class HttpStatusException(val code: Int) : IllegalStateException("HTTP status $code")
