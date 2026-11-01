package net.prototux.aare.cert

import android.content.Context
import android.net.Uri
import android.util.Base64
import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.cert.SelfSignedCertificate
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * The phone certificates offered to head units, in order.
 *
 * Head units built on Google's receiver library only accept a phone certificate issued by
 * Google; open-source and some OEM head units accept any. Release builds carry AARE's own built-in
 * certificate, we always have a generated self-signed one, and the user may import others (PEM
 * certificate chain + PKCS#8 key, or a PKCS#12 file).
 * When a head unit rejects the certificate during the handshake, the next connection offers the
 * next one, the way a TLS server can't know in advance what a client trusts.
 */
class CertificateStore(private val context: Context) {

    class Entry(val name: String, val identity: PhoneIdentity, val removable: Boolean = true) {
        val subject: String get() = identity.certificate.subjectX500Principal.name
        val notAfter get() = identity.certificate.notAfter
        val selfSigned: Boolean get() = identity.certificate.subjectX500Principal == identity.certificate.issuerX500Principal
    }

    private val dir = File(context.filesDir, "certs").apply { mkdirs() }

    /**
     * All usable identities: imported ones first (they're the reason to import), then the one built
     * into this build of AARE (release builds), then the generated one, which always works.
     */
    fun entries(): List<Entry> {
        val imported = dir.listFiles { f -> f.isDirectory && f.name != "_generated" }.orEmpty().sortedBy { it.name }.mapNotNull { load(it) }
        return imported + listOfNotNull(builtIn) + Entry(GENERATED, generated(), removable = false)
    }

    /** AARE's own certificate, from the release build's assets; null in builds made without it. */
    private val builtIn: Entry? by lazy {
        runCatching {
            val chain = context.assets.open("$BUILT_IN_DIR/cert.pem").use { input ->
                CertificateFactory.getInstance("X.509").generateCertificates(input).map { it as X509Certificate }
            }
            val key = parsePkcs8(context.assets.open("$BUILT_IN_DIR/key.pem").bufferedReader().use { it.readText() })
            Entry(BUILT_IN, PhoneIdentity(chain.first(), key, chain.drop(1)), removable = false)
        }.getOrNull()
    }

    /** Identity to offer for attempt [index] (wraps around). */
    fun pick(index: Int): Entry {
        val all = entries()
        return all[Math.floorMod(index, all.size)]
    }

    @Synchronized
    private fun generated(): PhoneIdentity {
        val d = File(dir, "_generated")
        load(d)?.let { return it.identity }
        val g = SelfSignedCertificate.generate("AARE ${android.os.Build.MODEL}")
        save(d, listOf(g.certificate), g.keyPair.private)
        return PhoneIdentity(g.certificate, g.keyPair.private)
    }

    /** Imports a PEM certificate (chain) and a PEM PKCS#8 private key. */
    fun importPem(name: String, certPem: String, keyPem: String): Entry {
        val certs = CertificateFactory.getInstance("X.509")
            .generateCertificates(certPem.byteInputStream()).map { it as X509Certificate }
        require(certs.isNotEmpty()) { "no certificate in the file" }
        val key = parsePkcs8(keyPem)
        val d = newDir(name)
        save(d, certs, key)
        return load(d)!!
    }

    /** Imports a PKCS#12 bundle. */
    fun importPkcs12(name: String, uri: Uri, password: CharArray): Entry {
        val ks = KeyStore.getInstance("PKCS12")
        context.contentResolver.openInputStream(uri)!!.use { ks.load(it, password) }
        val alias = ks.aliases().toList().first { ks.isKeyEntry(it) }
        val key = ks.getKey(alias, password) as PrivateKey
        val chain = ks.getCertificateChain(alias).map { it as X509Certificate }
        val d = newDir(name)
        save(d, chain, key)
        return load(d)!!
    }

    fun remove(entry: Entry) {
        dir.listFiles().orEmpty().firstOrNull { load(it)?.name == entry.name && it.name != "_generated" }?.deleteRecursively()
    }

    private fun newDir(name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9 ._-]"), "_").take(40).ifBlank { "imported" }
        var d = File(dir, safe)
        var n = 2
        while (d.exists()) d = File(dir, "$safe ($n)").also { n++ }
        return d.apply { mkdirs() }
    }

    private fun save(d: File, chain: List<X509Certificate>, key: PrivateKey) {
        d.mkdirs()
        File(d, "chain.pem").writeText(chain.joinToString("") { pem("CERTIFICATE", it.encoded) })
        File(d, "key.pem").writeText(pem("PRIVATE KEY", key.encoded))
    }

    private fun load(d: File): Entry? = runCatching {
        val chain = CertificateFactory.getInstance("X.509")
            .generateCertificates(File(d, "chain.pem").inputStream()).map { it as X509Certificate }
        val key = parsePkcs8(File(d, "key.pem").readText())
        Entry(if (d.name == "_generated") GENERATED else d.name, PhoneIdentity(chain.first(), key, chain.drop(1)), removable = d.name != "_generated")
    }.getOrNull()

    companion object {
        private const val GENERATED = "Generated (self-signed)"
        private const val BUILT_IN = "AARE (built in)"
        private const val BUILT_IN_DIR = "phone_identity"

        fun pem(type: String, der: ByteArray) =
            "-----BEGIN $type-----\n" + Base64.encodeToString(der, Base64.NO_WRAP).chunked(64).joinToString("\n") + "\n-----END $type-----\n"

        fun parsePkcs8(pem: String): PrivateKey {
            require(!pem.contains("BEGIN RSA PRIVATE KEY")) { "PKCS#1 key: convert it with `openssl pkcs8 -topk8 -nocrypt`" }
            val body = pem.lines().filter { !it.startsWith("-----") }.joinToString("")
            val der = Base64.decode(body, Base64.DEFAULT)
            val spec = PKCS8EncodedKeySpec(der)
            return runCatching { KeyFactory.getInstance("RSA").generatePrivate(spec) }
                .getOrElse { KeyFactory.getInstance("EC").generatePrivate(spec) }
        }
    }
}
