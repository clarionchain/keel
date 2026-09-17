package io.clarionchain.keel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArkSendMachineTest {
    @Test
    fun happyPath() {
        var phase = ArkSendPhase.IDLE
        phase = ArkSendMachine.transition(phase, ArkSendPhase.PARSING)
        phase = ArkSendMachine.transition(phase, ArkSendPhase.AMOUNT_ENTRY)
        phase = ArkSendMachine.transition(phase, ArkSendPhase.QUOTING)
        phase = ArkSendMachine.transition(phase, ArkSendPhase.CONFIRM)
        phase = ArkSendMachine.transition(phase, ArkSendPhase.AUTHENTICATING)
        phase = ArkSendMachine.transition(phase, ArkSendPhase.SUBMITTING)
        phase = ArkSendMachine.transition(phase, ArkSendPhase.RECONCILING)
        phase = ArkSendMachine.transition(phase, ArkSendPhase.SUCCEEDED)
        assertEquals(ArkSendPhase.SUCCEEDED, phase)
    }

    @Test
    fun timeoutStaysReconciling() {
        assertEquals(
            ArkSendPhase.RECONCILING,
            ArkSendMachine.timeoutIsNotFailure(ArkSendPhase.SUBMITTING),
        )
    }

    @Test
    fun illegalTransitionRejected() {
        assertFailsWith<IllegalPhaseTransition> {
            ArkSendMachine.transition(ArkSendPhase.IDLE, ArkSendPhase.SUCCEEDED)
        }
    }

    @Test
    fun expiredVtxoRejectionMovesToRecoveryFromSubmitting() {
        var phase = ArkSendPhase.IDLE
        phase = ArkSendMachine.transition(phase, ArkSendPhase.PARSING)
        phase = ArkSendMachine.transition(phase, ArkSendPhase.AMOUNT_ENTRY)
        phase = ArkSendMachine.transition(phase, ArkSendPhase.QUOTING)
        phase = ArkSendMachine.transition(phase, ArkSendPhase.CONFIRM)
        phase = ArkSendMachine.transition(phase, ArkSendPhase.AUTHENTICATING)
        phase = ArkSendMachine.transition(phase, ArkSendPhase.SUBMITTING)
        phase = ArkSendMachine.transition(phase, ArkSendPhase.FAILED_RECOVERY)
        assertEquals(ArkSendPhase.FAILED_RECOVERY, phase)
    }

    @Test
    fun reconcilingCanBeReclassifiedAsRecovery() {
        assertEquals(
            ArkSendPhase.FAILED_RECOVERY,
            ArkSendMachine.transition(ArkSendPhase.RECONCILING, ArkSendPhase.FAILED_RECOVERY),
        )
    }

    @Test
    fun recoveryOnlyExitsToIdle() {
        assertEquals(ArkSendPhase.IDLE, ArkSendMachine.transition(ArkSendPhase.FAILED_RECOVERY, ArkSendPhase.IDLE))
        assertFailsWith<IllegalPhaseTransition> {
            ArkSendMachine.transition(ArkSendPhase.FAILED_RECOVERY, ArkSendPhase.SUBMITTING)
        }
    }

    @Test
    fun definiteFailureIsRetryableNotReconciling() {
        assertEquals(
            ArkSendPhase.FAILED_RETRYABLE,
            ArkSendMachine.transition(ArkSendPhase.SUBMITTING, ArkSendPhase.FAILED_RETRYABLE),
        )
    }
}

class SendErrorTest {
    @Test
    fun timeoutIsUncertainAndServerMismatchIsNot() {
        assertTrue(SendError.isUncertain("Lightning payment timed out"))
        assertFalse(SendError.isUncertain("Ark address is for different server"))
    }

    @Test
    fun serverMismatchMapsToSameServerRule() {
        val msg = SendError.friendly("invalid arkoor address: Ark address is for different server")
        assertTrue("ark.signet.2nd.dev" in msg)
    }

    @Test
    fun recentOutboundIgnoresReceivesAndOldSends() {
        val since = 1_000L
        val hit = SendError.OutboundMovement("arkoor", -1000, 2_000L)
        val old = SendError.OutboundMovement("arkoor", -1000, 500L)
        val recv = SendError.OutboundMovement("arkoor", 1000, 2_000L)
        assertTrue(SendError.recentOutboundFound(listOf(hit), 1000, since))
        assertFalse(SendError.recentOutboundFound(listOf(old), 1000, since))
        assertFalse(SendError.recentOutboundFound(listOf(recv), 1000, since))
    }
}

class BackupRollbackTest {
    private fun manifest(generation: Long) = BackupManifest(
        schemaVersion = 1,
        network = BitcoinNetwork.SIGNET,
        walletFingerprint = "deadbeef",
        generation = generation,
        createdAtEpochMs = 1L,
        barkBindingVersion = "0.22.0+bark-0.6.2",
        appVersion = "0.1.0",
        contentSha256 = "a".repeat(64),
    )

    @Test
    fun olderBackupCannotReplaceNewerLiveState() {
        assertFalse(BackupRollback.canReplace(5L, manifest(4L)))
        assertTrue(BackupRollback.canReplace(5L, manifest(5L)))
        assertTrue(BackupRollback.canReplace(null, manifest(1L)))
    }
}
