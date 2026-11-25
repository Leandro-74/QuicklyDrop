package dev.leandro.quicklydrop.lab.spike

import android.content.Context

/** Persisted spike configuration, to survive process death. */
data class SpikeConfig(
    val payload: String?,
    val fgsOnTap: Boolean,
    val groupOnTap: Boolean,
)

/**
 * Stores the payload and options in `SharedPreferences`.
 *
 * The HCE service may be created in a new process after a `force-stop` or after the
 * system kills the app. Persisting the payload allows measuring exactly that scenario;
 * the product's real keys never go to disk, but the spike is a test tool.
 */
object SpikeStore {

    private const val PREFS = "spike_lifecycle"
    private const val KEY_PAYLOAD = "payload"
    private const val KEY_FGS = "fgs"
    private const val KEY_GROUP = "group"

    fun arm(context: Context, payload: String, fgsOnTap: Boolean, groupOnTap: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PAYLOAD, payload)
            .putBoolean(KEY_FGS, fgsOnTap)
            .putBoolean(KEY_GROUP, groupOnTap)
            .apply()
    }

    fun disarm(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PAYLOAD)
            .apply()
    }

    fun config(context: Context): SpikeConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SpikeConfig(
            payload = prefs.getString(KEY_PAYLOAD, null),
            fgsOnTap = prefs.getBoolean(KEY_FGS, true),
            groupOnTap = prefs.getBoolean(KEY_GROUP, true),
        )
    }
}
