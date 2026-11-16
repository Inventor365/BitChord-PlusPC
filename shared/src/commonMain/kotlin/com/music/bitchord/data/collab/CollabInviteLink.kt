package com.music.bitchord.data.collab

/**
 * Parses and formats collaborative playlist invite links across Android, Desktop, and Web:
 *  1. Official YouTube Music / YouTube collaborative invite links (zero-server native mode):
 *     - `https://music.youtube.com/playlist?list=PL...&jct=<token>`
 *     - `https://www.youtube.com/playlist?list=PL...&jct=<token>`
 *     - `https://youtube.com/playlist?list=PL...&jct=<token>`
 *     - `bitchord://collab/ytm/<playlistId>?jct=<token>`
 *  2. Self-hosted BitChord PlusPC backend invite links:
 *     - `https://<host>/collab/invite/bci_<token>`
 *     - `bitchord://collab/invite/bci_<token>?server=https://...`
 *     - `intent://collab/invite/bci_<token>?server=https://...#Intent;scheme=bitchord;end`
 *     - bare `bci_<token>`
 */
data class ParsedCollabInvite(
    val token: String,
    val serverOrigin: String? = null,
    val ytmPlaylistId: String? = null,
    val ytmJoinToken: String? = null,
) {
    val isYouTubeMusicInvite: Boolean
        get() = !ytmPlaylistId.isNullOrBlank() && !ytmJoinToken.isNullOrBlank()
}

object CollabInviteLink {

    private val TOKEN_REGEX = Regex("""^bci_[A-Za-z0-9_-]{16,128}$""")
    private val YTM_ID_REGEX = Regex("""^[A-Za-z0-9_-]{8,128}$""")
    private val JCT_REGEX = Regex("""^[A-Za-z0-9_+=/.-]{4,512}$""")
    private val EMBEDDED_URL_REGEX = Regex("""(?:https?://|bitchord://|intent://)\S+""", RegexOption.IGNORE_CASE)

    fun isValidToken(token: String): Boolean = TOKEN_REGEX.matches(token.trim())

    fun parse(raw: String?): ParsedCollabInvite? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        parseSingleCandidate(trimmed)?.let { return it }
        // Extract embedded URL when pasted from chat/share text ("Join my playlist: https://...")
        for (match in EMBEDDED_URL_REGEX.findAll(trimmed)) {
            val candidate = match.value.trimEnd('.', ',', ')', ']', '>', '"', '\'')
            parseSingleCandidate(candidate)?.let { return it }
        }
        return null
    }

    private fun parseSingleCandidate(input: String): ParsedCollabInvite? {
        if (isValidToken(input)) {
            return ParsedCollabInvite(token = input)
        }
        if (input.startsWith("ytm:")) {
            val parts = input.split(':', limit = 3)
            if (parts.size == 3 && YTM_ID_REGEX.matches(parts[1]) && JCT_REGEX.matches(parts[2])) {
                return ParsedCollabInvite(token = input, ytmPlaylistId = parts[1], ytmJoinToken = parts[2])
            }
        }

        val cleaned = input.substringBefore('#')
        val queryPart = cleaned.substringAfter('?', missingDelimiterValue = "")
        val pathAndOrigin = cleaned.substringBefore('?')

        // 1. Check for YouTube / YouTube Music / BitChord YTM collaborative playlist link (?list=PL...&jct=...)
        val listParam = parseQueryParam(queryPart, "list", plusAsSpace = false)?.removePrefix("VL")?.trim()
        val jctParam = parseQueryParam(queryPart, "jct", plusAsSpace = false)?.trim()
        if (!jctParam.isNullOrEmpty() && JCT_REGEX.matches(jctParam)) {
            if (pathAndOrigin.startsWith("bitchord://collab/ytm/", ignoreCase = true) ||
                pathAndOrigin.startsWith("intent://collab/ytm/", ignoreCase = true)
            ) {
                val pid = pathAndOrigin.substringAfterLast('/').removePrefix("VL").trim()
                if (YTM_ID_REGEX.matches(pid)) {
                    return ParsedCollabInvite(
                        token = "ytm:$pid:$jctParam",
                        ytmPlaylistId = pid,
                        ytmJoinToken = jctParam,
                    )
                }
            }
            if (!listParam.isNullOrEmpty() && YTM_ID_REGEX.matches(listParam) && isYouTubeHost(pathAndOrigin)) {
                return ParsedCollabInvite(
                    token = "ytm:$listParam:$jctParam",
                    ytmPlaylistId = listParam,
                    ytmJoinToken = jctParam,
                )
            }
        }
        // 2. Check for custom PlusPC backend bci_... invite links
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

    private fun isYouTubeHost(pathAndOrigin: String): Boolean {
        val lower = pathAndOrigin.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return false
        val host = lower.substringAfter("://").substringBefore('/').substringBefore(':')
        return host == "music.youtube.com" || host == "www.youtube.com" || host == "youtube.com" || host == "m.youtube.com"
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

    fun buildYouTubeMusicInviteUrl(playlistId: String, joinToken: String): String {
        val pid = playlistId.trim().removePrefix("VL")
        return "https://music.youtube.com/playlist?list=$pid&jct=${percentEncodeComponent(joinToken.trim())}"
    }

    fun buildBitChordYtmDeepLink(playlistId: String, joinToken: String): String {
        val pid = playlistId.trim().removePrefix("VL")
        return "bitchord://collab/ytm/$pid?jct=${percentEncodeComponent(joinToken.trim())}"
    }

    private fun parseQueryParam(query: String, key: String, plusAsSpace: Boolean = true): String? {
        if (query.isBlank()) return null
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val k = pair.substring(0, eq)
            if (k.equals(key, ignoreCase = true)) {
                return percentDecode(pair.substring(eq + 1), plusAsSpace = plusAsSpace)
            }
        }
        return null
    }

    private fun percentEncodeComponent(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (b in raw.encodeToByteArray()) {
            val ch = b.toInt() and 0xFF
            val unreserved = (ch in 'A'.code..'Z'.code) ||
                (ch in 'a'.code..'z'.code) ||
                (ch in '0'.code..'9'.code) ||
                ch == '-'.code || ch == '_'.code || ch == '.'.code || ch == '~'.code
            if (unreserved) {
                sb.append(ch.toChar())
            } else {
                sb.append('%')
                sb.append("0123456789ABCDEF"[ch ushr 4])
                sb.append("0123456789ABCDEF"[ch and 0x0F])
            }
        }
        return sb.toString()
    }

    private fun percentDecode(encoded: String, plusAsSpace: Boolean = true): String {
        val bytes = ArrayList<Byte>(encoded.length)
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            when {
                plusAsSpace && c == '+' -> {
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
