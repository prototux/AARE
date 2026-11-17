package net.prototux.aare.cert

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

/**
 * Fetches the phone certificate and key extracted from the latest Android Auto app, as published
 * by github.com/iscle/android-auto-certificates (the same ones `extract_key.py` gets from an APK).
 *
 * The repository is a third party, so what it serves is only accepted when the chain ends at
 * Google's "Google Automotive Link" CA (pinned below, valid until 2044) and the key matches the
 * certificate: anything else couldn't pass a head unit's check anyway.
 */
object CertificateDownload {

    const val SOURCE = "https://github.com/iscle/android-auto-certificates"
    private const val BASE = "https://raw.githubusercontent.com/iscle/android-auto-certificates/main/android-auto/latest"

    /** SHA-256 of the Google Automotive Link CA certificate that head units trust. */
    internal const val GAL_CA_SHA256 = "49e52efc13ad2ed09f204c3b10698bd84bb7105f510558aa14b8119a5c4ad17f"

    class Downloaded(val version: String, val certPem: String, val keyPem: String)

    /** The repository or its files are gone: deleted, renamed, made private or taken down (DMCA: 451). */
    class Unavailable(val code: Int) : IOException("certificate repository unavailable (HTTP $code)")

    /** The download arrived but failed [check]. */
    class Invalid(message: String) : Exception(message)

    /** Blocking: call it off the main thread. Throws [Unavailable], another [IOException] or [Invalid]. */
    fun fetch(): Downloaded {
        val version = latestVersion()
        val cert = get("$BASE/certificate.pem")
        val key = get("$BASE/private-key.pem")
        runCatching { check(cert, key) }.onFailure { throw Invalid(it.message ?: it.toString()) }
        return Downloaded(version, cert, key)
    }

    /** The version the repository serves now (the Android Auto build it comes from), without the files. Blocking. */
    fun latestVersion(): String = JSONObject(get("$BASE/metadata.json")).optString("version").ifBlank { "latest" }

    /** When the leaf certificate of [certPem] expires. */
    internal fun notAfter(certPem: String): Date =
        (CertificateFactory.getInstance("X.509").generateCertificate(certPem.byteInputStream()) as X509Certificate).notAfter

    /** Checks that [certPem] is issued by the CA with SHA-256 [caSha256] and that [keyPem] is its key. */
    internal fun check(certPem: String, keyPem: String, caSha256: String = GAL_CA_SHA256) {
        val chain = CertificateFactory.getInstance("X.509")
            .generateCertificates(certPem.byteInputStream()).map { it as X509Certificate }
        val leaf = chain.firstOrNull() ?: error("no certificate in the download")
        val ca = chain.firstOrNull { sha256(it.encoded) == caSha256 }
            ?: error("the downloaded certificate isn't issued by Google Automotive Link")
        runCatching { leaf.verify(ca.publicKey) }.getOrElse { error("the downloaded certificate isn't signed by Google Automotive Link") }
        val key = parseKey(keyPem)
        val alg = if (key.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"
        val probe = "AARE".toByteArray()
        val sig = Signature.getInstance(alg).apply { initSign(key); update(probe) }.sign()
        val matches = Signature.getInstance(alg).apply { initVerify(leaf.publicKey); update(probe) }.verify(sig)
        require(matches) { "the downloaded key doesn't match the certificate" }
    }

    // java.util.Base64 rather than CertificateStore.parsePkcs8 (android.util), so unit tests can run it.
    private fun parseKey(pem: String): PrivateKey {
        val der = Base64.getMimeDecoder().decode(pem.lines().filter { !it.startsWith("-----") }.joinToString(""))
        val spec = PKCS8EncodedKeySpec(der)
        return runCatching { KeyFactory.getInstance("RSA").generatePrivate(spec) }
            .getOrElse { KeyFactory.getInstance("EC").generatePrivate(spec) }
    }

    internal fun sha256(der: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    /** Null for a success; 404/410/451 mean the files are gone, anything else may be temporary. */
    internal fun statusError(code: Int): IOException? = when (code) {
        HttpURLConnection.HTTP_OK -> null
        HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_GONE, 451 -> Unavailable(code)
        else -> IOException("HTTP $code")
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "AARE")
        try {
            statusError(c.responseCode)?.let { throw it }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
