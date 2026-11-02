package dev.leandro.easydrop.lab.spike

/** Um evento do spike, com instante monotônico em milissegundos. */
data class SpikeEvent(
    val atMs: Long,
    val name: String,
    val detail: String = "",
)

/**
 * Acumula eventos do spike e os formata para logcat/arquivo.
 *
 * A classe é pura: o relógio e o [maxEvents] vêm de fora. O primeiro evento vira a
 * referência de tempo, para que as linhas mostrem o deslocamento desde o início.
 */
class SpikeRecorder(private val maxEvents: Int = 2_000) {

    private val events = ArrayDeque<SpikeEvent>()
    private var baseMs = 0L
    private var hasBase = false

    /** Registra o evento e devolve a linha formatada. */
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
