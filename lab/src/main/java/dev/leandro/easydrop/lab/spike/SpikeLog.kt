package dev.leandro.easydrop.lab.spike

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Log do spike: logcat (tag `EasyDropSpike`) e arquivo em `filesDir/spike.log`.
 *
 * O arquivo importa porque o processo pode ser morto entre o toque e a coleta; `adb
 * logcat -d` perde o buffer em alguns cenários. O formato é o mesmo nos dois destinos.
 */
object SpikeLog {

    const val TAG = "EasyDropSpike"
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

    /** Repete o log no logcat, para coleta por `adb logcat -d -s EasyDropSpike`. */
    fun dump() {
        lines().forEach { Log.i(TAG, it) }
    }

    fun clear(context: Context?) {
        synchronized(lock) {
            recorder.clear()
            if (context != null) runCatching { file(context).delete() }
        }
        Log.i(TAG, "log limpo")
    }

    private fun append(context: Context, line: String) {
        runCatching {
            val file = file(context)
            if (file.length() > MAX_FILE_BYTES) {
                file.writeText("--- log truncado ---\n")
            }
            file.appendText(line + "\n")
        }
    }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
