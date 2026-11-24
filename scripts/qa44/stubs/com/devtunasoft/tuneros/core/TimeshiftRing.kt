package com.devtunasoft.tuneros.core

import java.io.OutputStream

/** Host-only signature mirror of the production TimeshiftRing contract; no product source is edited. */
interface TimeshiftRing {
    fun create(fileName: String): OutputStream
    fun delete(fileName: String)
    fun replacePlaylist(text: String)
}
