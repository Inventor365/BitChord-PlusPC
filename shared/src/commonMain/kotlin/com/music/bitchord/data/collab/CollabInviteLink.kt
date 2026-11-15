package com.music.bitchord.data.collab

/**
 * Parses and formats collaborative playlist invite links across Android, Desktop, and Web:
 *  - `https://<host>/collab/invite/bci_<token>`
 *  - `bitchord://collab/invite/bci_<token>?server=https://...`
 *  - `intent://collab/invite/bci_<token>?server=https://...#Intent;scheme=bitchord;end`
 *  - bare `bci_<token>` pasted into a "Join Collaborative Playlist" input.
 */
data class ParsedCollabInvite(
    val token: String,
    val serverOrigin: String? = null,
)

object CollabInviteLink {

    private val TOKEN_REGEX = Regex("""^bci_[A-Za-z0-9_-]{16,128}$""")

    fun isValidToken(token: String): Boolean = TOKEN_REGEX.matches(token.trim())

    fun parse(raw: String?): ParsedCollabInvite? {
        val input = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (isValidToken(input)) {
            return ParsedCollabInvite(token = input)
        }

        val cleaned = input.substringBefore('#')
        val queryPart = cleaned.substringAfter('?', missingDelimiterValue = "")
        val pathAndOrigin = cleaned.substringBefore('?')

        val queryServer = parseQueryParam(queryPart, "server")?.let(::normalizeServerOrigin)

        if (pathAndOrigin.startsWith("bitchord://collab/invite/", ignoreCase = true) ||
            pathAndOrigin.startsWith("intent://collab/invite/", ignoreCase = true)
        ) {
            val token = pathAndOrigin.substringAfterLast('/').trim()
            if (!isValidToken(token)) return null
            return ParsedCollabInvite(token = token, serverOrigin = queryServer)
        }

        val markerIndex = pathAndOrigin.lowercase().indexOf("/collab/invite/")
        if (markerIndex >= 0) {
            val originCandidate = pathAndOrigin.substring(0, markerIndex)
            val rest = pathAndOrigin.substring(markerIndex + "/collab/invite/".length)
            val token = rest.substringBefore('/').trim()
            if (!isValidToken(token)) return null
            val origin = normalizeServerOrigin(originCandidate) ?: queryServer
            return ParsedCollabInvite(token = token, serverOrigin = origin)
        }

        return null
    }

    fun normalizeServerOrigin(raw: String?): String? {
        val trimmed = raw?.trim()?.trimEnd('/') ?: return null
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        val isHttps = lower.startsWith("https://")
        val isLocalHttp = lower.startsWith("http://localhost") ||
            lower.startsWith("http://127.0.0.1") ||
            lower.startsWith("http://[::1]") ||
            lower.startsWith("http://10.0.2.2")
        if (!isHttps && !isLocalHttp) return null
        val afterScheme = trimmed.substringAfter("://")
        val authority = afterScheme.substringBefore('/')
        if (authority.isBlank() || ' ' in authority || '@' in authority) return null
        val scheme = trimmed.substringBefore("://").lowercase()
        return "$scheme://$authority"
    }

    fun buildShareUrl(serverOrigin: String, token: String): String {
        val normalized = normalizeServerOrigin(serverOrigin) ?: serverOrigin.trim().trimEnd('/')
        return "$normalized/collab/invite/${token.trim()}"
    }

    private fun parseQueryParam(query: String, key: String): String? {
        if (query.isBlank()) return null
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val k = pair.substring(0, eq)
            if (k.equals(key, ignoreCase = true)) {
                return percentDecode(pair.substring(eq + 1))
            }
        }
        return null
    }

    private fun percentDecode(encoded: String): String {
        val bytes = ArrayList<Byte>(encoded.length)
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            when {
                c == '+' -> {
                    bytes.add(' '.code.toByte())
                    i++
                }
                c == '%' && i + 2 < encoded.length -> {
                    val hex = encoded.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        bytes.add(hex.toByte())
                        i += 3
                    } else {
                        bytes.add(c.code.toByte())
                        i++
                    }
                }
                else -> {
                    for (b in c.toString().encodeToByteArray()) bytes.add(b)
                    i++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }
}
