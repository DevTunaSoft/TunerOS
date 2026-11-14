package com.devtunasoft.tuneros.core

/**
 * Holds at most one in-flight resource (for example an HTTP connection) so another thread
 * can abort it, including while it is still blocked in connect or read.
 *
 * If [abortCurrent] has already run, [register] aborts the new value immediately and
 * returns false, so a request that starts just after cancellation cannot escape it.
 * Call [reset] before starting a new unit of work that should be abortable again.
 */
class AbortableSlot<T : Any>(private val abort: (T) -> Unit) {
    private val lock = Any()
    private var current: T? = null
    private var aborted = false

    fun register(value: T): Boolean {
        synchronized(lock) {
            if (!aborted) {
                current = value
                return true
            }
        }
        abort(value)
        return false
    }

    /** Forgets [value] only if it is still the registered one; never aborts it. */
    fun release(value: T) {
        synchronized(lock) {
            if (current === value) current = null
        }
    }

    /** Marks the slot cancelled and aborts whatever is currently registered. */
    fun abortCurrent() {
        val victim = synchronized(lock) {
            aborted = true
            current.also { current = null }
        }
        victim?.let(abort)
    }

    /** Clears the registration and the cancelled state for the next unit of work. */
    fun reset() {
        synchronized(lock) {
            aborted = false
            current = null
        }
    }
}
