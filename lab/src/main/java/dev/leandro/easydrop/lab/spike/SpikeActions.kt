package dev.leandro.easydrop.lab.spike

import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.leandro.easydrop.lab.P2pConfig

/**
 * Ações disparadas pelo toque NFC.
 *
 * Cada tentativa é registrada com o resultado ou com a exceção. O objetivo é separar
 * "o Android chamou o HCE" de "o app conseguiu iniciar um serviço em primeiro plano" e
 * de "o app conseguiu criar o grupo P2P", que são permissões diferentes.
 */
object SpikeActions {

    private const val GROUP_SSID = "DIRECT-ED-SPIKE"
    private const val GROUP_PASS = "spike123456"
    private const val GROUP_FREQUENCY = 5745

    private val handler = Handler(Looper.getMainLooper())
    private var channel: WifiP2pManager.Channel? = null

    fun startForeground(context: Context, source: String) {
        if (SpikePresence.fgsRunning) {
            SpikeLog.event(context, "$source.fgs", "já ativo")
            return
        }

        SpikeLog.event(context, "$source.fgs", "tentando startForegroundService")
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SpikeForegroundService::class.java).putExtra("source", source),
            )
            SpikeLog.event(context, "$source.fgs", "startForegroundService aceito")
        } catch (e: Exception) {
            SpikeLog.event(
                context,
                "$source.fgs",
                "${SpikeFailure.foregroundService(e.javaClass.name)}: ${e.message}",
            )
        }
    }

    fun createGroup(context: Context, source: String) {
        val manager = context.getSystemService(WifiP2pManager::class.java)
        if (manager == null) {
            SpikeLog.event(context, "$source.p2p", "Wi-Fi Direct indisponível")
            return
        }

        val existing = channel
        val local = existing ?: manager.initialize(context, Looper.getMainLooper(), null)
        if (local == null) {
            SpikeLog.event(context, "$source.p2p", "canal indisponível")
            return
        }
        channel = local

        SpikeLog.event(context, "$source.p2p", "tentando createGroup em $GROUP_FREQUENCY MHz")
        val started = SystemClock.uptimeMillis()

        // Um grupo velho sobrevive ao force-stop; remover antes deixa o teste repetível.
        try {
            manager.removeGroup(
                local,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = create(manager, local, context, source, started)
                    override fun onFailure(reason: Int) = create(manager, local, context, source, started)
                },
            )
        } catch (e: SecurityException) {
            SpikeLog.event(context, "$source.p2p", "BLOQUEADO (permissão): ${e.message}")
        }
    }

    private fun create(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        context: Context,
        source: String,
        started: Long,
    ) {
        handler.postDelayed(
            {
                try {
                    manager.createGroup(
                        channel,
                        P2pConfig.group(GROUP_SSID, GROUP_PASS, GROUP_FREQUENCY),
                        object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                val elapsed = SystemClock.uptimeMillis() - started
                                SpikeLog.event(context, "$source.p2p", "grupo criado em ${elapsed}ms")
                            }

                            override fun onFailure(reason: Int) {
                                SpikeLog.event(
                                    context,
                                    "$source.p2p",
                                    "falhou: ${SpikeFailure.p2p(reason)}",
                                )
                            }
                        },
                    )
                } catch (e: SecurityException) {
                    SpikeLog.event(context, "$source.p2p", "BLOQUEADO (permissão): ${e.message}")
                }
            },
            300,
        )
    }
}
