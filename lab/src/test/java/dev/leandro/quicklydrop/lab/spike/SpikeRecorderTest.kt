package dev.leandro.quicklydrop.lab.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpikeRecorderTest {

    @Test
    fun `registra eventos em ordem com deslocamento`() {
        val recorder = SpikeRecorder()

        recorder.record(1_000, "primeiro")
        recorder.record(1_250, "segundo", "detalhe")

        val lines = recorder.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("+000000ms"))
        assertTrue(lines[0].contains("primeiro"))
        assertTrue(lines[1].startsWith("+000250ms"))
        assertTrue(lines[1].contains("segundo"))
        assertTrue(lines[1].contains("detalhe"))
    }

    @Test
    fun `primeiro evento define a referencia de tempo`() {
        val recorder = SpikeRecorder()

        recorder.record(50_000, "inicio")
        recorder.record(50_010, "fim")

        assertTrue(recorder.lines()[0].startsWith("+000000ms"))
        assertTrue(recorder.lines()[1].startsWith("+000010ms"))
    }

    @Test
    fun `limita a quantidade de eventos`() {
        val recorder = SpikeRecorder(maxEvents = 3)

        repeat(5) { recorder.record(it.toLong(), "evento$it") }

        assertEquals(3, recorder.size)
        assertEquals("evento2", recorder.snapshot().first().name)
        assertEquals("evento4", recorder.snapshot().last().name)
    }

    @Test
    fun `clear zera eventos e referencia`() {
        val recorder = SpikeRecorder()
        recorder.record(1_000, "a")

        recorder.clear()
        recorder.record(9_000, "b")

        assertEquals(1, recorder.size)
        assertTrue(recorder.lines().single().startsWith("+000000ms"))
    }

    @Test
    fun `evento sem detalhe nao deixa espaco extra`() {
        val recorder = SpikeRecorder()

        val line = recorder.record(0, "curto")

        assertEquals("+000000ms  curto", line)
    }
}
