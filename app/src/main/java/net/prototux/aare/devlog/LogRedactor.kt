package net.prototux.aare.devlog

/**
 * Masks personal data in one log line, best effort.
 *
 * [known] maps a literal value the app itself knows (a Bluetooth name, a car name, an SSID) to its
 * placeholder; it is applied first, in a single pass and longest value first, so a placeholder is
 * never rewritten by a shorter value. A known value is masked only where no letter or digit touches
 * it, so a car named "500" leaves "1500" alone; the residual is a value glued to letters or digits
 * (`i20RRwireless`), which stays visible. The format rules that follow never unmask: a placeholder
 * produced by an earlier rule is kept, or absorbed whole into a later, broader mask.
 */
internal class LogRedactor(known: Map<String, String> = emptyMap()) {
    private val knownValues: Map<String, String> = known.filterKeys { it.length >= MIN_KNOWN }
    private val knownRegex: Regex? = knownValues.keys
        .sortedByDescending { it.length }
        .takeIf { it.isNotEmpty() }
        ?.let { keys -> Regex("""(?<![\p{L}\p{N}])(?:${keys.joinToString("|") { Regex.escape(it) }})(?![\p{L}\p{N}])""") }

    fun redact(line: String): String {
        var s = line
        knownRegex?.let { re -> s = re.replace(s) { knownValues.getValue(it.value) } }
        return rules.fold(s) { acc, rule -> rule(acc) }
    }

    private companion object {
        const val MIN_KNOWN = 3

        val COLON_TOKEN = Regex("""(?<![0-9A-Za-z])[0-9A-Za-z]+(?::[0-9A-Za-z]*)+""")
        val IPV4_MAPPED = Regex("""(?i)(?<![0-9a-f:])::ffff:(?:\d{1,3}\.){3}\d{1,3}(?![\d.])""")
        val IPV6_TOKEN = Regex("""(?<![0-9A-Za-z:])[0-9A-Fa-f:]{3,}(?![0-9A-Za-z:])""")
        val IPV4 = Regex("""(?<![\d.])((?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){2})(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.)(?:25[0-5]|2[0-4]\d|1?\d?\d)(?!\d|\.\d|=#)""")
        val SSID = Regex(
            """(?i)(?<![\w])(ssid=|wireless: |network |on |add |joining |joined |join |connected to |bssid |wifi )".*"""",
        )
        val SHA256 = Regex("""(?i)(?<![0-9a-f])([0-9a-f]{8})[0-9a-f]{56}(?![0-9a-f])""")
        val USB_KEY = Regex("""usb:([^|]{0,64})\|([^|]{0,64})\|[^\s:]*""")
        val DN = Regex("""\b(CN|SERIALNUMBER|UID|EMAILADDRESS|OID\.[\d.]+)=(?:\\.|[^,\\])+""")
        val TRACK = Regex("""\b(title|artist|album)=.*?(?=, \w+=|\)\s*$|$)""")
        val DER_HEX = Regex("""(?<==)#[0-9A-Fa-f]+""")
        val AUTO_REPLY = Regex("""(driving: auto-replied to ).+$""")
        val HEAD_UNIT_NAME = Regex("""(head unit: [^\[]*\[).+(\] services: )""")
        val URI = Regex("""\b((?:content|https?|file)://)([^/\s?#]*)(\S*)""")
        val CREDENTIALS = Regex(
            """(?i)(?<!<)\b(password|passphrase|psk|serial|instance_?id|android_id|imei|imsi)\b(?=["'=:#\-(/,\s])(["']?+\s*+[=:#\-(/,]?+\s*+["']?+)""" +
                """(?:(?<=["'])(?!<)[^"']+|[^\s"'<][^\s"']*)""",
        )
        val COORD_KEY = Regex("""(?i)\b(lat(?:itude)?|lon(?:gitude)?|lng)=-?\d{1,3}(?:\.\d+)?""")
        val COORD_PAIR = Regex("""(?<![\d.])-?\d{1,3}\.\d{4,},\s?-?\d{1,3}\.\d{4,}""")
        val PHONE = Regex("""\+\d[\d ().-]{6,}\d""")
        val EMAIL = Regex("""(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
        val HOME = Regex("""(?<=/(?:home|Users)/)[^/\s]+""")
        val STORAGE = Regex("""/storage/[0-9A-F]{4}-[0-9A-F]{4}""")

        private fun isOctet(p: String) = p.length == 2 && p.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        /** Masks every run of exactly six colon-joined hex octets, keeping the first three. */
        private fun maskMacs(token: String): String {
            val parts = token.split(":").toMutableList()
            var i = 0
            while (i < parts.size) {
                if (!isOctet(parts[i])) { i++; continue }
                var end = i
                while (end < parts.size && isOctet(parts[end])) end++
                if (end - i == 6) for (k in i + 3 until end) parts[k] = "xx"
                i = end
            }
            return parts.joinToString(":")
        }

        private fun isIpv6(token: String): Boolean {
            val parts = token.split("::")
            fun groups(s: String) = if (s.isEmpty()) emptyList() else s.split(":")
            fun ok(g: List<String>) = g.all { it.length in 1..4 }
            return when (parts.size) {
                1 -> groups(token).let { it.size == 8 && ok(it) }
                2 -> {
                    val all = groups(parts[0]) + groups(parts[1])
                    all.isNotEmpty() && all.size <= 7 && ok(all)
                }
                else -> false
            }
        }

        val rules: List<(String) -> String> = listOf(
            // First: the display name may hold text that a later rule would eat up to the end of the line.
            { s -> HEAD_UNIT_NAME.replace(s) { it.groupValues[1] + "<name>" + it.groupValues[2] } },
            { s -> COLON_TOKEN.replace(s) { maskMacs(it.value) } },
            { s -> IPV4_MAPPED.replace(s, "<ipv6>") },
            { s ->
                if (!s.contains("::") && s.count { it == ':' } < 7) s
                else IPV6_TOKEN.replace(s) { if (isIpv6(it.value)) "<ipv6>" else it.value }
            },
            { s ->
                IPV4.replace(s) {
                    if (it.value == "0.0.0.0" || it.value.startsWith("127.")) it.value
                    else it.groupValues[1] + "x.x"
                }
            },
            { s -> SSID.replace(s) { it.groupValues[1] + "\"<ssid>\"" } },
            { s -> SHA256.replace(s) { it.groupValues[1] + "…" } },
            { s -> USB_KEY.replace(s) { "usb:${it.groupValues[1]}|${it.groupValues[2]}|<serial>" } },
            { s -> DN.replace(s) { it.groupValues[1] + "=<x>" } },
            { s -> DER_HEX.replace(s, "#<x>") },
            { s -> TRACK.replace(s) { it.groupValues[1] + "=<" + it.groupValues[1] + ">" } },
            { s -> AUTO_REPLY.replace(s) { it.groupValues[1] + "<name>" } },
            { s ->
                URI.replace(s) {
                    val (scheme, authority, rest) = it.destructured
                    when {
                        authority == "github.com" || rest.isEmpty() -> it.value
                        else -> "$scheme$authority/<path>"
                    }
                }
            },
            { s -> CREDENTIALS.replace(s) { it.groupValues[1] + it.groupValues[2] + "<x>" } },
            { s ->
                COORD_KEY.replace(s) {
                    val key = it.groupValues[1]
                    key + "=" + if (key.startsWith("lat", ignoreCase = true)) "<lat>" else "<lon>"
                }
            },
            { s -> COORD_PAIR.replace(s, "<lat>, <lon>") },
            { s -> PHONE.replace(s, "<phone>") },
            { s -> EMAIL.replace(s, "<email>") },
            { s -> HOME.replace(s, "<user>") },
            { s -> STORAGE.replace(s, "/storage/<volume>") },
        )
    }
}
