package dev.leandro.quicklydrop.lab.spike

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Spike log: logcat (tag `QuicklyDropSpike`) and a file at `filesDir/spike.log`.
 *
 * The file matters because the process may be killed between the tap and the collection;
 * `adb logcat -d` loses the buffer in some scenarios. The format is the same in both
 * destinations.
 */
object SpikeLog {

    const val TAG = "QuicklyDropSpike"
    private const val FILE_NAME = "spike.log"
    private const val MAX_FILE_BYTES = 256 * 1024

    private val recorder = SpikeRecorder()
    private val lock = Any()

    fun event(context: Context?, name: String, detail: String = "") {
        val now = SystemClock.uptimeMillis()
        val line = synchronized(lock) {
            val line = recorder.record(now, name, detail)
            Log.i(TAG, line)
            if (context != null) append(context, line)
            line
        }
    }

    fun lines(): List<String> = synchronized(lock) { recorder.lines() }

    /** Repeats the log in logcat, for collection with `adb logcat -d -s QuicklyDropSpike`. */
    fun dump() {
        lines().forEach { Log.i(TAG, it) }
    }

    fun clear(context: Context?) {
        synchronized(lock) {
            recorder.clear()
            if (context != null) runCatching { file(context).delete() }
        }
        Log.i(TAG, "log cleared")
    }

    private fun append(context: Context, line: String) {
        runCatching {
            val file = file(context)
            if (file.length() > MAX_FILE_BYTES) {
                file.writeText("--- log truncated ---\n")
            }
            file.appendText(line + "\n")
        }
    }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
