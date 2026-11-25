package dev.leandro.quicklydrop.lab.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpikeRecorderTest {

    @Test
    fun `records events in order with offset`() {
        val recorder = SpikeRecorder()

        recorder.record(1_000, "first")
        recorder.record(1_250, "second", "detail")

        val lines = recorder.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("+000000ms"))
        assertTrue(lines[0].contains("first"))
        assertTrue(lines[1].startsWith("+000250ms"))
        assertTrue(lines[1].contains("second"))
        assertTrue(lines[1].contains("detail"))
    }

    @Test
    fun `first event sets the time reference`() {
        val recorder = SpikeRecorder()

        recorder.record(50_000, "start")
        recorder.record(50_010, "end")

        assertTrue(recorder.lines()[0].startsWith("+000000ms"))
        assertTrue(recorder.lines()[1].startsWith("+000010ms"))
    }

    @Test
    fun `limits the number of events`() {
        val recorder = SpikeRecorder(maxEvents = 3)

        repeat(5) { recorder.record(it.toLong(), "event$it") }

        assertEquals(3, recorder.size)
        assertEquals("event2", recorder.snapshot().first().name)
        assertEquals("event4", recorder.snapshot().last().name)
    }

    @Test
    fun `clear resets events and reference`() {
        val recorder = SpikeRecorder()
        recorder.record(1_000, "a")

        recorder.clear()
        recorder.record(9_000, "b")

        assertEquals(1, recorder.size)
        assertTrue(recorder.lines().single().startsWith("+000000ms"))
    }

    @Test
    fun `event without detail leaves no extra space`() {
        val recorder = SpikeRecorder()

        val line = recorder.record(0, "short")

        assertEquals("+000000ms  short", line)
    }
}
