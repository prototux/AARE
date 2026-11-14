package net.prototux.aare.home

/**
 * Keeps secrets out of the connection log, which users copy into bug reports: header values that
 * carry credentials, passwords and tokens in URLs, and webhook ids (anyone who knows one can call
 * the webhook).
 */
object Redact {
    const val MASK = "***"

    private val SECRET_WORDS = listOf("auth", "token", "key", "secret", "password", "passwd", "cookie", "session", "signature")

    /** A header or query parameter whose value is a credential. */
    fun isSecretName(name: String): Boolean {
        val n = name.lowercase()
        return SECRET_WORDS.any { it in n }
    }

    fun header(h: Header): String = "${h.name}: ${if (isSecretName(h.name)) MASK else h.value}"

    /** [url] with its user info, secret query values and webhook id masked. */
    fun url(url: String): String {
        var u = url
        // user:password@host
        u = u.replace(Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@?#]*@"), "$1$MASK@")
        u = webhooks(u)
        val q = u.indexOf('?')
        if (q >= 0) {
            val fragment = u.indexOf('#', q).let { if (it < 0) u.length else it }
            val params = u.substring(q + 1, fragment).split('&').joinToString("&") { p ->
                val eq = p.indexOf('=')
                if (eq > 0 && isSecretName(p.substring(0, eq))) p.substring(0, eq + 1) + MASK else p
            }
            u = u.substring(0, q + 1) + params + u.substring(fragment)
        }
        return u
    }

    /** Webhook ids (`/api/webhook/<id>`) anywhere in [text] masked. */
    fun webhooks(text: String): String = text.replace(WEBHOOK, "$1$MASK")

    private val WEBHOOK = Regex("(/api/webhook/)[^/?#\\s]+")

    /** Any occurrence of the known [secrets] in [text] (an error message quoting a URL…), masked. */
    fun text(text: String, secrets: Collection<String>): String =
        secrets.filter { it.length >= 4 }.sortedByDescending { it.length }.fold(text) { t, s -> t.replace(s, MASK) }
}
