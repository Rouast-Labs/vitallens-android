package com.rouast.vitallens.ui

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** A manual clock and scheduler, so that the hold logic is tested without real waiting. */
private class FakeClock {
    var time = 0L
    private data class Scheduled(val id: Int, val at: Long, val action: () -> Unit)
    private val scheduled = mutableListOf<Scheduled>()
    private var nextId = 0

    fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
        val id = nextId++
        scheduled.add(Scheduled(id, time + delayMs, action))
        return { scheduled.removeAll { it.id == id } }
    }

    fun advance(ms: Long) {
        val target = time + ms
        while (true) {
            val next = scheduled.filter { it.at <= target }.minByOrNull { it.at } ?: break
            time = next.at
            scheduled.removeAll { it.id == next.id }
            next.action()
        }
        time = target
    }
}

class MessageHoldTest {

    private lateinit var clock: FakeClock
    private val shown = mutableListOf<String>()
    private val forced = mutableListOf<Boolean>()
    private lateinit var hold: MessageHold

    @Before
    fun setUp() {
        clock = FakeClock()
        shown.clear()
        forced.clear()
        hold = MessageHold(
            minMs = 3_000,
            now = { clock.time },
            schedule = { delayMs, action -> clock.schedule(delayMs, action) },
            apply = { text, force -> shown.add(text); forced.add(force) },
        )
    }

    @Test
    fun `shows messages immediately when nothing is held`() {
        hold.show("a")
        hold.show("b")
        assertEquals(listOf("a", "b"), shown)
    }

    @Test
    fun `a held message is not replaced before the minimum time`() {
        hold.show("Low confidence", hold = true)
        clock.advance(500)
        hold.show("Scanning")
        assertEquals(listOf("Low confidence"), shown)
        clock.advance(2_400)
        assertEquals(listOf("Low confidence"), shown)
        clock.advance(200)
        assertEquals(listOf("Low confidence", "Scanning"), shown)
    }

    @Test
    fun `only the latest waiting message is shown`() {
        hold.show("Low confidence", hold = true)
        hold.show("b")
        hold.show("c")
        clock.advance(3_001)
        assertEquals(listOf("Low confidence", "c"), shown)
    }

    @Test
    fun `a waiting message that equals the held one is dropped`() {
        hold.show("Low confidence", hold = true)
        hold.show("Scanning")
        hold.show("Low confidence", hold = true)
        clock.advance(3_001)
        assertEquals(listOf("Low confidence"), shown)
    }

    @Test
    fun `re-showing the held message does not extend the hold`() {
        hold.show("Low confidence", hold = true)
        clock.advance(2_000)
        hold.show("Low confidence", hold = true)
        clock.advance(1_100)
        hold.show("Scanning")
        assertEquals("Scanning", shown.last())
    }

    @Test
    fun `a waiting held message gets its own full hold`() {
        hold.show("A", hold = true)
        hold.show("B", hold = true)
        clock.advance(3_001)
        assertEquals(listOf("A", "B"), shown)
        hold.show("C")
        assertEquals(listOf("A", "B"), shown)
        clock.advance(3_001)
        assertEquals(listOf("A", "B", "C"), shown)
    }

    @Test
    fun `immediate drops the hold and the waiting message`() {
        hold.show("Low confidence", hold = true)
        hold.show("Scanning")
        hold.show("Issue", immediate = true)
        clock.advance(5_000)
        assertEquals(listOf("Low confidence", "Issue"), shown)
    }

    @Test
    fun `release lets the next message through`() {
        hold.show("Low confidence", hold = true)
        hold.release()
        hold.show("Scanning")
        assertEquals(listOf("Low confidence", "Scanning"), shown)
    }

    @Test
    fun `force is passed through, also for waiting messages`() {
        hold.show("A", hold = true)
        hold.show("B", force = true)
        clock.advance(3_001)
        assertEquals(listOf("A", "B"), shown)
        assertEquals(listOf(false, true), forced)
    }
}
