package dev.leandro.quicklydrop.lab.spike

/** A spike event, with a monotonic instant in milliseconds. */
data class SpikeEvent(
    val atMs: Long,
    val name: String,
    val detail: String = "",
)

/**
 * Accumulates spike events and formats them for logcat/file.
 *
 * The class is pure: the clock and [maxEvents] come from outside. The first event becomes
 * the time reference, so the lines show the offset since the beginning.
 */
class SpikeRecorder(private val maxEvents: Int = 2_000) {

    private val events = ArrayDeque<SpikeEvent>()
    private var baseMs = 0L
    private var hasBase = false

    /** Records the event and returns the formatted line. */
    fun record(nowMs: Long, name: String, detail: String = ""): String {
        if (!hasBase) {
            baseMs = nowMs
            hasBase = true
        }
        val event = SpikeEvent(nowMs, name, detail)
        events.addLast(event)
        while (events.size > maxEvents) events.removeFirst()
        return format(event)
    }

    fun snapshot(): List<SpikeEvent> = events.toList()

    fun lines(): List<String> = events.map(::format)

    fun format(event: SpikeEvent): String {
        val offset = if (hasBase) event.atMs - baseMs else 0
        val suffix = if (event.detail.isEmpty()) "" else "  ${event.detail}"
        return "+${offset.toString().padStart(6, '0')}ms  ${event.name}$suffix"
    }

    fun clear() {
        events.clear()
        baseMs = 0L
        hasBase = false
    }

    val size: Int get() = events.size
}
