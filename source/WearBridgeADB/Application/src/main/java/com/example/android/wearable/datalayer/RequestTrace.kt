package dev.wearbrdgeadb

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File

/** Small opt-in, bounded trace for locating NORMAL request stalls. Never records command text. */
internal object RequestTrace {
    private const val PREFS = "wearbridge_diagnostics"
    private const val ENABLED = "request_trace_enabled"
    private const val TAG = "WearBridgeTracePhone"
    private const val MAX_BYTES = 128 * 1024L
    private val lock = Any()

    fun isEnabled(context: Context): Boolean = BuildConfig.REQUEST_TRACE_ENABLED && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        if (!BuildConfig.REQUEST_TRACE_ENABLED) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
    }

    fun record(context: Context, requestId: String, stage: String, detail: String = "") {
        if (!isEnabled(context)) return
        val clean = detail.replace('\n', ' ').replace('\r', ' ').take(240)
        val line = "${System.currentTimeMillis()} +${SystemClock.elapsedRealtime()}ms $requestId $stage${if (clean.isEmpty()) "" else " $clean"}\n"
        Log.i(TAG, line.trimEnd())
        synchronized(lock) {
            try {
                val file = File(context.filesDir, "request-trace.log")
                if (file.length() > MAX_BYTES) {
                    val bytes = file.readBytes()
                    val start = (bytes.size - (MAX_BYTES / 2).toInt()).coerceAtLeast(0)
                    val tail = bytes.copyOfRange(start, bytes.size)
                    file.writeBytes(tail)
                }
                file.appendText(line, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "Could not persist trace", e)
            }
        }
    }

    fun read(context: Context): String = synchronized(lock) {
        try {
            File(context.filesDir, "request-trace.log").takeIf { it.isFile }?.readText(Charsets.UTF_8)
                ?.lineSequence()?.toList()?.takeLast(80)?.joinToString("\n").orEmpty()
        } catch (e: Exception) {
            "Trace read failed: ${e.javaClass.simpleName}"
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            try {
                File(context.filesDir, "request-trace.log").writeText("", Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear trace", e)
            }
        }
    }
}
