package com.kyra.iptv.player

import com.kyra.iptv.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelQueueTest {
    private fun ch(id: String) = Channel(id = id, name = "Canal $id", streamUrl = "http://h/$id")
    private val list = listOf(ch("a"), ch("b"), ch("c"))

    @Test fun startsAtGivenIndexAndClampsOutOfRange() {
        assertEquals("b", ChannelQueue(list, 1).current?.id)
        assertEquals("a", ChannelQueue(list, -5).current?.id)
        assertEquals("c", ChannelQueue(list, 99).current?.id)
    }

    @Test fun nextAndPreviousWrapAround() {
        val q = ChannelQueue(list, 2)
        assertEquals("a", q.next()?.id)
        assertEquals(0, q.index)
        assertEquals("c", q.previous()?.id)
        assertEquals("b", q.previous()?.id)
    }

    @Test fun startingAtFindsChannelByIdOrFallsBackToFirst() {
        assertEquals("c", ChannelQueue.startingAt(list, "c").current?.id)
        assertEquals("a", ChannelQueue.startingAt(list, "zzz").current?.id)
        assertEquals("a", ChannelQueue.startingAt(list, null).current?.id)
    }

    @Test fun emptyQueueIsSafe() {
        val q = ChannelQueue(emptyList())
        assertNull(q.current)
        assertNull(q.next())
        assertNull(q.previous())
        assertFalse(q.hasMultiple)
    }

    @Test fun singleChannelStaysOnItself() {
        val q = ChannelQueue(listOf(ch("x")))
        assertFalse(q.hasMultiple)
        assertEquals("x", q.next()?.id)
        assertEquals("x", q.previous()?.id)
        assertTrue(ChannelQueue(list).hasMultiple)
    }
}
