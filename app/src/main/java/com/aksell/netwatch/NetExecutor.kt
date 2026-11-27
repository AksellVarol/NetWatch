package com.aksell.netwatch

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

// One managed executor for ALL network and heavy work in the app (v2.6).
// - Fixed small pool (4 threads): no unbounded thread creation and no new
//   Thread per 5-second tick anywhere in the app.
// - Every task runs off the main thread; results come back via runOnMain.
// - Callers keep the returned Future to cancel a running check (Stop button,
//   leaving a screen, starting a new check while one runs).
object NetExecutor {
    private val pool: ExecutorService = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "netwatch-net").apply { isDaemon = true }
    }
    // Dedicated single-thread pool for backup/restore file I/O + parsing
    // (v2.9.4, P0-2): a long restore must never starve the 4 network
    // threads or run on the main thread. Serialises restores by construction.
    private val restorePool: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "netwatch-restore").apply { isDaemon = true }
    }
    fun submitRestore(task: () -> Unit): Future<*> = restorePool.submit(task)
    // Lazy: creating the main-thread Handler touches Looper.getMainLooper(),
    // which must only happen on a real device. Background-only callers
    // (submit/cancel) never pay for it, and JVM unit tests can use the pool.
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun submit(task: () -> Unit): Future<*> = pool.submit(task)
    // Execute a pre-built Runnable (e.g. a FutureTask whose identity must be
    // known BEFORE it is submitted — see NetworkEngine.submitSingle). The
    // pool still owns the thread; nothing here creates a new thread.
    fun execute(r: Runnable) { pool.execute(r) }
    fun <T> submitCallable(task: () -> T): Future<T> = pool.submit(Callable(task))
    fun runOnMain(task: () -> Unit) { mainHandler.post(task) }
    fun cancel(f: Future<*>?) { try { f?.cancel(true) } catch (e: Exception) {} }
}
