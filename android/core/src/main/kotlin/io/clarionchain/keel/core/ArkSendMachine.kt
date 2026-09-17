package io.clarionchain.keel.core

enum class ArkSendPhase {
    IDLE,
    PARSING,
    INVALID,
    AMOUNT_ENTRY,
    QUOTING,
    CONFIRM,
    AUTHENTICATING,
    SUBMITTING,
    RECONCILING,
    SUCCEEDED,
    FAILED_RETRYABLE,
    /** Definitive failure: selected/rejected VTXO is expired. Only recovery (exit) helps. */
    FAILED_RECOVERY,
    CANCELED,
}

class IllegalPhaseTransition(message: String) : IllegalStateException(message)

object ArkSendMachine {
    fun transition(from: ArkSendPhase, to: ArkSendPhase): ArkSendPhase {
        val allowed = allowedTargets(from)
        if (to !in allowed) {
            throw IllegalPhaseTransition("cannot move $from -> $to")
        }
        return to
    }

    fun timeoutIsNotFailure(from: ArkSendPhase): ArkSendPhase {
        return when (from) {
            ArkSendPhase.SUBMITTING, ArkSendPhase.RECONCILING -> ArkSendPhase.RECONCILING
            else -> from
        }
    }

    private fun allowedTargets(from: ArkSendPhase): Set<ArkSendPhase> = when (from) {
        ArkSendPhase.IDLE -> setOf(ArkSendPhase.PARSING)
        ArkSendPhase.PARSING -> setOf(ArkSendPhase.INVALID, ArkSendPhase.AMOUNT_ENTRY, ArkSendPhase.QUOTING)
        ArkSendPhase.INVALID -> setOf(ArkSendPhase.IDLE, ArkSendPhase.PARSING)
        ArkSendPhase.AMOUNT_ENTRY -> setOf(ArkSendPhase.QUOTING, ArkSendPhase.IDLE)
        ArkSendPhase.QUOTING -> setOf(ArkSendPhase.CONFIRM, ArkSendPhase.INVALID, ArkSendPhase.IDLE, ArkSendPhase.FAILED_RECOVERY)
        ArkSendPhase.CONFIRM -> setOf(ArkSendPhase.AUTHENTICATING, ArkSendPhase.IDLE)
        ArkSendPhase.AUTHENTICATING -> setOf(ArkSendPhase.SUBMITTING, ArkSendPhase.CONFIRM, ArkSendPhase.CANCELED)
        ArkSendPhase.SUBMITTING -> setOf(ArkSendPhase.RECONCILING, ArkSendPhase.SUCCEEDED, ArkSendPhase.FAILED_RETRYABLE, ArkSendPhase.FAILED_RECOVERY)
        ArkSendPhase.RECONCILING -> setOf(ArkSendPhase.SUCCEEDED, ArkSendPhase.FAILED_RETRYABLE, ArkSendPhase.RECONCILING, ArkSendPhase.FAILED_RECOVERY)
        ArkSendPhase.SUCCEEDED -> setOf(ArkSendPhase.IDLE)
        ArkSendPhase.FAILED_RETRYABLE -> setOf(ArkSendPhase.QUOTING, ArkSendPhase.IDLE)
        ArkSendPhase.FAILED_RECOVERY -> setOf(ArkSendPhase.IDLE)
        ArkSendPhase.CANCELED -> setOf(ArkSendPhase.IDLE)
    }
}
