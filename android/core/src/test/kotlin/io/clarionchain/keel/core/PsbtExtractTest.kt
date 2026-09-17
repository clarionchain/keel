package io.clarionchain.keel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PsbtExtractTest {

    /**
     * Generated with rust-bitcoin 0.32.9 (the exact version bark 0.6.2 uses):
     * a 2-input PSBT with witness_utxo set (as bark's drain_exits does) and
     * finalized taproot witnesses — one key-path-style (1 item), one
     * script-path-style (3 items). TX_HEX is rust-bitcoin's own extract_tx
     * output, so this pins byte-for-byte compatibility.
     */
    @Test
    fun extractsFinalTransactionFromDrainStylePsbt() {
        val psbt =
            "cHNidP8BAGYCAAAAAhERERERERERERERERERERERERERERERERERERERERERAAAAAAAMAACAIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIBAAAAAAwAAIABrCYAAAAAAAABUWjuDQAAAQEriBMAAAAAAAAiUSBCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQgEIQgFAq6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6urqwABAStsFQAAAAAAACJRIENDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDAQhoA0DNzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3NAyARrCHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcEAAA=="
        val expected =
            "02000000000102111111111111111111111111111111111111111111111111111111111111111100000000000c000080222222222222222222222222222222222222222222222222222222222222222201000000000c00008001ac2600000000000001510140abababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababab0340cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd032011ac21c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c168ee0d00"
        assertEquals(expected, PsbtExtract.extractTxHex(psbt))
    }

    @Test
    fun rejectsGarbage() {
        assertFailsWith<IllegalArgumentException> { PsbtExtract.extractTxHex("not base64!!!") }
        assertFailsWith<Exception> { PsbtExtract.extractTxHex("aGVsbG8=") } // valid b64, not a PSBT
    }

    @Test
    fun rejectsUnfinalizedPsbt() {
        // Same shape as the valid vector but with the final witnesses removed:
        // extraction must fail loudly rather than broadcast a witness-less tx.
        val psbt =
            "cHNidP8BAGYCAAAAAhERERERERERERERERERERERERERERERERERERERERERAAAAAAAMAACAIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIBAAAAAAwAAIABrCYAAAAAAAABUWjuDQAAAQEriBMAAAAAAAAiUSBCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQgABAStsFQAAAAAAACJRIENDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDAAA="
        assertFailsWith<IllegalArgumentException> { PsbtExtract.extractTxHex(psbt) }
    }
}
