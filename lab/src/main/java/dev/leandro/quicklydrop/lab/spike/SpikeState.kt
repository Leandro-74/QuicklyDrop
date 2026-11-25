package dev.leandro.quicklydrop.lab.spike

/** Snapshot of the process state at the instant of the event. */
data class SpikeSnapshot(
    val pid: Int,
    val processAgeMs: Long,
    val activityVisible: Boolean,
    val fgsRunning: Boolean,
    val interactive: Boolean,
    val keyguardLocked: Boolean,
    val secureNfc: Boolean?,
)

/** Event formatting. Kept separate from Android so it can be tested on the JVM. */
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

/** Translates Android failures into a short, stable classification. */
object SpikeFailure {

    fun foregroundService(className: String): String = when {
        className.contains("ForegroundServiceStartNotAllowed") -> "BLOCKED (FGS in background)"
        className.contains("ForegroundServiceDidNotStartInTime") -> "BLOCKED (FGS did not start)"
        className.contains("SecurityException") -> "BLOCKED (permission)"
        className.contains("IllegalStateException") -> "BLOCKED (state)"
        else -> "ERROR ($className)"
    }

    fun p2p(reason: Int): String = when (reason) {
        0 -> "ERROR"
        1 -> "P2P_UNSUPPORTED"
        2 -> "BUSY"
        else -> "code $reason"
    }
}
