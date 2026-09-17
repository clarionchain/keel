package io.clarionchain.keel

import io.clarionchain.keel.core.ArkSendMachine
import io.clarionchain.keel.core.ArkSendPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class RedactionSmokeTest {
    @Test
    fun timeoutDoesNotFail() {
        assertEquals(
            ArkSendPhase.RECONCILING,
            ArkSendMachine.timeoutIsNotFailure(ArkSendPhase.SUBMITTING),
        )
    }
}
