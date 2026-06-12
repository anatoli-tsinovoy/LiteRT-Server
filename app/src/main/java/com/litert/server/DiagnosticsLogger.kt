package com.litert.server

import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

object DiagnosticsLogger {
    private const val TAG = "LiteRTDiagnostics"
    private const val FILE_NAME = "litert-diagnostics.log"
    private const val OLD_FILE_NAME = "litert-diagnostics.old.log"
    private const val STATE_PREFS = "litert_diagnostics_state"
    private const val KEY_ACTIVE_OPERATION = "active_operation"
    private const val KEY_ACTIVE_STARTED_AT = "active_started_at"
    private const val KEY_ACTIVE_MEMORY = "active_memory"
    private const val MAX_BYTES = 512 * 1024L

    private val lock = Any()
    private val initialized = AtomicBoolean(false)
    private var appContext: Context? = null
    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (initialized.compareAndSet(false, true)) {
            previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                error(
                    tag = "Uncaught",
                    message = "Uncaught exception on thread=${thread.name}",
                    throwable = throwable
                )
                previousUncaughtHandler?.uncaughtException(thread, throwable)
            }
            reportInterruptedOperation()
            event("App", "Diagnostics initialized; ${deviceSummary()}; ${memorySummary()}")
        }
    }

    fun event(tag: String, message: String) {
        Log.i(tag, message)
        append("I", tag, message, null)
    }

    fun beginOperation(name: String) {
        synchronized(lock) {
            val prefs = statePrefs() ?: return
            prefs.edit()
                .putString(KEY_ACTIVE_OPERATION, name)
                .putLong(KEY_ACTIVE_STARTED_AT, System.currentTimeMillis())
                .putString(KEY_ACTIVE_MEMORY, memorySummary())
                .commit()
            appendLocked("I", "Operation", "begin $name", null)
        }
    }

    fun endOperation(name: String) {
        synchronized(lock) {
            val prefs = statePrefs() ?: return
            if (prefs.getString(KEY_ACTIVE_OPERATION, null) == name) {
                prefs.edit()
                    .remove(KEY_ACTIVE_OPERATION)
                    .remove(KEY_ACTIVE_STARTED_AT)
                    .remove(KEY_ACTIVE_MEMORY)
                    .commit()
            }
            appendLocked("I", "Operation", "end $name", null)
        }
    }

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
        append("W", tag, message, throwable)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
        append("E", tag, message, throwable)
    }

    fun readLog(context: Context): String {
        initialize(context)
        synchronized(lock) {
            val dir = diagnosticsDir() ?: return "Diagnostics logger is not initialized."
            val oldFile = File(dir, OLD_FILE_NAME)
            val file = File(dir, FILE_NAME)
            val builder = StringBuilder()
            if (oldFile.isFile) builder.append(oldFile.readText()).append('\n')
            if (file.isFile) builder.append(file.readText())
            return builder.toString().ifBlank { "No diagnostics recorded yet." }
        }
    }

    fun clear(context: Context) {
        initialize(context)
        synchronized(lock) {
            val dir = diagnosticsDir() ?: return
            File(dir, OLD_FILE_NAME).delete()
            File(dir, FILE_NAME).delete()
            appendLocked("I", "Diagnostics", "Diagnostics cleared; ${deviceSummary()}; ${memorySummary()}", null)
        }
    }

    fun memorySummary(): String {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val nativeHeap = Debug.getNativeHeapAllocatedSize()
        return "heapUsed=${used / 1024 / 1024}MiB heapMax=${runtime.maxMemory() / 1024 / 1024}MiB nativeHeap=${nativeHeap / 1024 / 1024}MiB"
    }

    fun deviceSummary(): String =
        "sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER} ${Build.MODEL} abi=${Build.SUPPORTED_ABIS.joinToString()}"

    private fun append(level: String, tag: String, message: String, throwable: Throwable?) {
        synchronized(lock) {
            appendLocked(level, tag, message, throwable)
        }
    }

    private fun appendLocked(level: String, tag: String, message: String, throwable: Throwable?) {
        try {
            val dir = diagnosticsDir() ?: return
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, FILE_NAME)
            rotateIfNeeded(file)
            file.appendText(buildLine(level, tag, message, throwable))
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write diagnostics log", t)
        }
    }

    private fun buildLine(level: String, tag: String, message: String, throwable: Throwable?): String {
        val safeMessage = message.replace('\n', ' ').replace('\r', ' ')
        val stack = throwable?.let { "\n" + it.stackTraceString() }.orEmpty()
        return "${System.currentTimeMillis()} $level/$tag: $safeMessage; ${memorySummary()}$stack\n"
    }

    private fun Throwable.stackTraceString(): String {
        val writer = StringWriter()
        printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun reportInterruptedOperation() {
        synchronized(lock) {
            val prefs = statePrefs() ?: return
            val operation = prefs.getString(KEY_ACTIVE_OPERATION, null) ?: return
            val startedAt = prefs.getLong(KEY_ACTIVE_STARTED_AT, 0L)
            val startMemory = prefs.getString(KEY_ACTIVE_MEMORY, "unknown")
            appendLocked(
                "W",
                "NativeCrash",
                "Previous process ended before completing operation=$operation startedAt=$startedAt startMemory=$startMemory. " +
                    "If there is no Kotlin exception after that operation in the prior log, suspect native abort, SIGSEGV, GPU driver fault, or OOM; Android tombstone/logcat has the exact signal.",
                null
            )
            prefs.edit()
                .remove(KEY_ACTIVE_OPERATION)
                .remove(KEY_ACTIVE_STARTED_AT)
                .remove(KEY_ACTIVE_MEMORY)
                .commit()
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (file.isFile && file.length() > MAX_BYTES) {
            val oldFile = File(file.parentFile, OLD_FILE_NAME)
            oldFile.delete()
            file.renameTo(oldFile)
        }
    }

    private fun statePrefs() = appContext?.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    private fun diagnosticsDir(): File? = appContext?.let { File(it.filesDir, "diagnostics") }
}
