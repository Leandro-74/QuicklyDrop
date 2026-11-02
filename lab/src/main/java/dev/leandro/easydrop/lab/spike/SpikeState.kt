package dev.leandro.easydrop.lab.spike

/** Fotografia do estado do processo no instante do evento. */
data class SpikeSnapshot(
    val pid: Int,
    val processAgeMs: Long,
    val activityVisible: Boolean,
    val fgsRunning: Boolean,
    val interactive: Boolean,
    val keyguardLocked: Boolean,
    val secureNfc: Boolean?,
)

/** Formatação dos eventos. Separada do Android para poder ser testada em JVM. */
object SpikeState {

    fun describe(snapshot: SpikeSnapshot): String = buildString {
        append("pid=").append(snapshot.pid)
        append(" age=").append(snapshot.processAgeMs).append("ms")
        append(" act=").append(if (snapshot.activityVisible) "on" else "off")
        append(" fgs=").append(if (snapshot.fgsRunning) "on" else "off")
        append(" screen=").append(if (snapshot.interactive) "on" else "off")
        append(" lock=").append(if (snapshot.keyguardLocked) "on" else "off")
        append(" secureNfc=").append(
            when (snapshot.secureNfc) {
                true -> "on"
                false -> "off"
                null -> "?"
            },
        )
    }
}

/** Traduz falhas do Android para uma classificação curta e estável. */
object SpikeFailure {

    fun foregroundService(className: String): String = when {
        className.contains("ForegroundServiceStartNotAllowed") -> "BLOQUEADO (FGS em background)"
        className.contains("ForegroundServiceDidNotStartInTime") -> "BLOQUEADO (FGS não iniciou)"
        className.contains("SecurityException") -> "BLOQUEADO (permissão)"
        className.contains("IllegalStateException") -> "BLOQUEADO (estado)"
        else -> "ERRO ($className)"
    }

    fun p2p(reason: Int): String = when (reason) {
        0 -> "ERROR"
        1 -> "P2P_UNSUPPORTED"
        2 -> "BUSY"
        else -> "código $reason"
    }
}
