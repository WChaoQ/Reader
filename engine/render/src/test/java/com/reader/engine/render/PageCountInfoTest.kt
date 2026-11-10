package com.reader.engine.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PageCountInfoTest {
    @Test
    fun exactAndProgressiveCountsRemainDistinct() {
        val exact = PageCountInfo(exact = 12)
        val progressive = PageCountInfo(exact = null, estimated = 20, available = 4)

        assertEquals(12, exact.display)
        assertEquals(12, exact.available)
        assertEquals(20, progressive.display)
        assertEquals(4, progressive.available)
        assertEquals(false, progressive.isExact)
    }

    @Test
    fun rejectsNegativeOrImpossibleCounts() {
        assertThrows(IllegalArgumentException::class.java) { PageCountInfo(exact = -1) }
        assertThrows(IllegalArgumentException::class.java) { PageCountInfo(estimated = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            PageCountInfo(exact = 2, available = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageCountInfo(exact = null, estimated = 2, available = 3)
        }
    }
}
