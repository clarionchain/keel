package io.clarionchain.keel.core

/**
 * Minimal BIP174 reader that extracts the final transaction from a FULLY
 * FINALIZED PSBT (every input carries final_script_witness). bark's
 * `drain_exits` returns exactly such a PSBT, but the Kotlin binding exposes
 * no `extract_tx` — this is the last step before broadcasting an exit claim.
 *
 * Strict by design: throws on anything outside that one shape.
 */
object PsbtExtract {

    private val MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()) // "psbt" + 0xff

    private class Reader(val buf: ByteArray) {
        var pos = 0

        fun byte(): Int {
            require(pos < buf.size) { "unexpected end of data" }
            return buf[pos++].toInt() and 0xFF
        }

        fun bytes(n: Int): ByteArray {
            require(n >= 0 && pos + n <= buf.size) { "unexpected end of data" }
            val out = buf.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        fun u32le(): Long {
            val b = bytes(4)
            return (b[0].toLong() and 0xFF) or
                ((b[1].toLong() and 0xFF) shl 8) or
                ((b[2].toLong() and 0xFF) shl 16) or
                ((b[3].toLong() and 0xFF) shl 24)
        }

        /** BIP174/compactSize unsigned varint. */
        fun varint(): Long {
            return when (val first = byte()) {
                0xFD -> bytes(2).let { (it[0].toLong() and 0xFF) or ((it[1].toLong() and 0xFF) shl 8) }
                0xFE -> u32le()
                0xFF -> bytes(8).foldRight(0L) { b, acc -> (acc shl 8) or (b.toLong() and 0xFF) }
                else -> first.toLong()
            }
        }

        fun varBytes(): ByteArray {
            val n = varint()
            require(n <= Int.MAX_VALUE) { "length too large" }
            return bytes(n.toInt())
        }
    }

    private class Writer {
        val out = mutableListOf<Byte>()
        fun byte(v: Int) { out.add(v.toByte()) }
        fun bytes(b: ByteArray) { out.addAll(b.toList()) }
        fun varint(v: Long) {
            when {
                v < 0xFD -> byte(v.toInt())
                v <= 0xFFFF -> { byte(0xFD); byte(v.toInt()); byte((v shr 8).toInt()) }
                v <= 0xFFFF_FFFF -> {
                    byte(0xFE)
                    byte(v.toInt()); byte((v shr 8).toInt()); byte((v shr 16).toInt()); byte((v shr 24).toInt())
                }
                else -> error("varint too large")
            }
        }
        fun varBytes(b: ByteArray) { varint(b.size.toLong()); bytes(b) }
        fun toByteArray() = out.toByteArray()
    }

    private data class UnsignedTx(
        val version: ByteArray,          // 4 bytes, little-endian, kept verbatim
        val inputs: List<Pair<ByteArray, ByteArray>>, // (prevout 36 bytes verbatim, sequence 4 bytes LE)
        val outputs: List<ByteArray>,    // each: 8-byte value LE + varint len + script, kept verbatim
        val locktime: ByteArray,         // 4 bytes LE
    )

    /** Returns the final witness transaction as hex. */
    fun extractTxHex(psbtBase64: String): String {
        val raw = try {
            java.util.Base64.getDecoder().decode(psbtBase64)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("invalid base64 PSBT", e)
        }
        val r = Reader(raw)
        require(raw.size >= MAGIC.size && MAGIC.indices.all { raw[it] == MAGIC[it] }) { "not a PSBT" }
        r.pos = MAGIC.size

        // Global map: find the unsigned tx (key type 0x00).
        var unsignedTxBytes: ByteArray? = null
        while (true) {
            val keyLen = r.varint()
            if (keyLen == 0L) break // map separator
            val key = r.bytes(keyLen.toInt())
            val value = r.varBytes()
            if (key.size == 1 && key[0].toInt() == 0x00) unsignedTxBytes = value
        }
        val unsigned = parseUnsignedTx(Reader(unsignedTxBytes ?: throw IllegalArgumentException("PSBT has no unsigned tx")))

        // One input map per unsigned-tx input: collect final_script_witness
        // (BIP174 input key type 0x08; 0x07 is final_script_sig).
        val witnesses = ArrayList<ByteArray>(unsigned.inputs.size)
        repeat(unsigned.inputs.size) {
            var witness: ByteArray? = null
            while (true) {
                val keyLen = r.varint()
                if (keyLen == 0L) break
                val key = r.bytes(keyLen.toInt())
                val value = r.varBytes()
                if (key.size == 1 && key[0].toInt() == 0x08) witness = value
            }
            witnesses.add(witness ?: throw IllegalArgumentException("PSBT input is not finalized (no final witness)"))
        }

        val w = Writer()
        w.bytes(unsigned.version)
        w.byte(0x00); w.byte(0x01) // segwit marker + flag
        w.varint(unsigned.inputs.size.toLong())
        unsigned.inputs.forEach { (prevout, sequence) ->
            w.bytes(prevout)
            w.varint(0) // empty scriptSig
            w.bytes(sequence)
        }
        w.varint(unsigned.outputs.size.toLong())
        unsigned.outputs.forEach { w.bytes(it) }
        witnesses.forEach { w.bytes(it) } // witness stacks are already serialized (count + items)
        w.bytes(unsigned.locktime)
        return w.toByteArray().joinToString("") { "%02x".format(it) }
    }

    private fun parseUnsignedTx(r: Reader): UnsignedTx {
        val version = r.bytes(4)
        val inputCount = r.varint()
        require(inputCount in 1..100_000) { "bad input count" }
        val inputs = (0 until inputCount).map {
            val prevout = r.bytes(36)
            val scriptLen = r.varint()
            require(scriptLen == 0L) { "unsigned tx must have empty scriptSigs" }
            val sequence = r.bytes(4)
            prevout to sequence
        }
        val outputCount = r.varint()
        require(outputCount in 0..100_000) { "bad output count" }
        val outputs = (0 until outputCount).map {
            // Keep the whole output (value + varint len + script) verbatim for re-serialization.
            val value = r.bytes(8)
            val scriptLen = r.varint()
            val script = r.bytes(scriptLen.toInt())
            val w = Writer()
            w.bytes(value); w.varint(scriptLen); w.bytes(script)
            w.toByteArray()
        }
        val locktime = r.bytes(4)
        return UnsignedTx(version, inputs, outputs, locktime)
    }
}
