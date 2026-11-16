package com.music.bitchord.data.collab

/**
 * What a failed collaborative-playlist call should say in a dialog.
 *
 * The HTTP client's exceptions carry the whole exchange in their message — method, URL and
 * the response body verbatim — which is how a join refusal once reached the screen as
 * `Client request(POST https://music.youtube.com/youtubei/v1/browse/edit_playlist…) invalid:
 * 401. Text: "{ "error": { … } }"`. This keeps the one sentence in there that was written
 * for a person, and drops the rest.
 */
fun Throwable.toCollabUserMessage(fallback: String = "Something went wrong. Try again in a moment."): String =
    collabUserMessage(message, fallback)

/** [toCollabUserMessage] for a message already detached from its exception. */
fun collabUserMessage(rawMessage: String?, fallback: String = "Something went wrong. Try again in a moment."): String {
    val raw = rawMessage?.trim().orEmpty()
    if (raw.isEmpty()) return fallback
    if (!looksLikeRawHttpFailure(raw)) return raw

    val status = HTTP_STATUS.find(raw)?.groupValues?.get(1)?.toIntOrNull()
        ?: JSON_CODE.find(raw)?.groupValues?.get(1)?.toIntOrNull()
    val serverSentence = JSON_MESSAGE.find(raw)?.groupValues?.get(1)
        ?.replace("\\\"", "\"")
        ?.replace("\\n", " ")
        ?.replace("\\/", "/")
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.startsWith("{") }

    val service = if (raw.contains("youtube", ignoreCase = true)) "YouTube Music" else "The server"
    return when {
        serverSentence != null && status != null -> "$service: $serverSentence (HTTP $status)"
        serverSentence != null -> "$service: $serverSentence"
        status == 401 -> "$service says this account isn't signed in or isn't allowed to do that (HTTP 401)."
        status == 403 -> "$service refused the request (HTTP 403)."
        status == 404 -> "$service couldn't find that playlist (HTTP 404)."
        status == 429 -> "$service is rate-limiting requests. Wait a moment and try again."
        status != null && status >= 500 -> "$service is having trouble right now (HTTP $status). Try again in a moment."
        status != null -> "$service refused the request (HTTP $status)."
        else -> fallback
    }
}

private fun looksLikeRawHttpFailure(raw: String): Boolean =
    raw.startsWith("Client request(") ||
        raw.startsWith("Server error(") ||
        raw.startsWith("Unhandled redirect") ||
        (raw.contains("/youtubei/v1/") && raw.contains("Text:")) ||
        (raw.contains("\"error\"") && raw.contains("\"code\""))

private val HTTP_STATUS = Regex("""(?:invalid:|:)\s*(\d{3})\b[^"]{0,60}?Text:""")
private val JSON_CODE = Regex(""""code"\s*:\s*(\d{3})""")
private val JSON_MESSAGE = Regex(""""message"\s*:\s*"((?:[^"\\]|\\.)*)"""")
