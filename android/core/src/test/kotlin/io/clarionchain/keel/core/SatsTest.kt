package io.clarionchain.keel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SatsTest {
    @Test
    fun parseWholeSatsOnly() {
        assertEquals(Sats(2500), Sats.parse("2500"))
        assertFailsWith<IllegalArgumentException> { Sats.parse("1.5") }
        assertFailsWith<IllegalArgumentException> { Sats.parse("-1") }
        assertFailsWith<IllegalArgumentException> { Sats.parse(" ") }
    }

    @Test
    fun rejectsNegative() {
        assertFailsWith<IllegalArgumentException> { Sats(-1) }
    }

    @Test
    fun addExact() {
        assertEquals(Sats(3), Sats(1) + Sats(2))
        assertFailsWith<ArithmeticException> { Sats(Long.MAX_VALUE) + Sats(1) }
    }
}
