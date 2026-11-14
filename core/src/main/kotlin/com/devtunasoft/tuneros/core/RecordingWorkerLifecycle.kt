package com.devtunasoft.tuneros.core

import java.util.concurrent.ExecutorService

/**
 * Finish accepted recording work before closing its persistence resources.
 * Uses the existing single-thread executor; never waits on Android's main thread.
 *
 * Only call from the service's terminal lifecycle path, after stopping new submissions.
 */
object RecordingWorkerLifecycle {
    fun shutdownAfterPendingWork(worker: ExecutorService, cleanup: () -> Unit) {
        worker.execute(cleanup)
        worker.shutdown()
    }
}
