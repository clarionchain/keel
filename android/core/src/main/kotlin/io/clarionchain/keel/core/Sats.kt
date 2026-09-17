package io.clarionchain.keel.core

@JvmInline
value class Sats(val value: Long) : Comparable<Sats> {
    init {
        require(value >= 0L) { "sats must be non-negative" }
    }

    operator fun plus(other: Sats): Sats = Sats(Math.addExact(value, other.value))

    operator fun minus(other: Sats): Sats {
        require(value >= other.value) { "insufficient sats" }
        return Sats(value - other.value)
    }

    override fun compareTo(other: Sats): Int = value.compareTo(other.value)

    companion object {
        val ZERO: Sats = Sats(0L)

        fun parse(raw: String): Sats {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "amount is empty" }
            require(trimmed.all { it in '0'..'9' }) { "amount must be whole sats" }
            return Sats(trimmed.toLong())
        }
    }
}
