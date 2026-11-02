package dev.leandro.easydrop.lab.spike

import android.content.Context

/** Configuração persistida do spike, para sobreviver à morte do processo. */
data class SpikeConfig(
    val payload: String?,
    val fgsOnTap: Boolean,
    val groupOnTap: Boolean,
)

/**
 * Guarda o payload e as opções em `SharedPreferences`.
 *
 * O serviço HCE pode ser criado em um processo novo depois de um `force-stop` ou de o
 * sistema matar o app. Persistir o payload permite medir exatamente esse cenário; as
 * chaves reais do produto nunca vão para disco, mas o spike é uma ferramenta de teste.
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
