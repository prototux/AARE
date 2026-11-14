package net.prototux.aare.aap

import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import javax.net.ssl.SSLException

/**
 * The head unit turned down the phone's certificate (see [AapSession.certificateRefusal]).
 *
 * Head units refuse in three ways: AUTH_COMPLETE with a failure status, a TLS alert or close
 * during the handshake, or by just hanging up mid-handshake. The last one looks the same as a
 * cable pulled at that moment, so [explicit] tells the two apart for whoever decides what to offer
 * next.
 */
class CertificateRefusal(
    /** AUTH_COMPLETE's status ([MessageStatus] number), or 0 if the head unit sent none. */
    val authStatus: Int,
    /** The head unit said it refused (failure status, TLS alert or TLS close), not only hung up. */
    val explicit: Boolean,
) {
    /** The car thinks the certificate's validity hasn't started yet: usually its clock is behind. */
    val notYetValid: Boolean get() = authStatus == MessageStatus.STATUS_AUTHENTICATION_FAILURE_CERT_NOT_YET_VALID_VALUE

    /** The car thinks the certificate has expired: it has, or the car's clock is ahead. */
    val expired: Boolean get() = authStatus == MessageStatus.STATUS_AUTHENTICATION_FAILURE_CERT_EXPIRED_VALUE

    override fun toString() = when {
        authStatus != 0 -> "refused (status $authStatus)"
        explicit -> "refused during the TLS handshake"
        else -> "hung up during the TLS handshake"
    }

    companion object {
        /**
         * Whether [error] (what ended a session during the TLS handshake) is the head unit's own
         * answer: a TLS alert it sent, or the TLS close it sent, rather than a link that went away.
         */
        fun saidByHeadUnit(error: Throwable?): Boolean = generateSequence(error) { it.cause }.any { e ->
            (e is SSLException && e.message.orEmpty().contains("alert", ignoreCase = true)) ||
                (e is AapException && e.message.orEmpty().startsWith("TLS closed"))
        }
    }
}
