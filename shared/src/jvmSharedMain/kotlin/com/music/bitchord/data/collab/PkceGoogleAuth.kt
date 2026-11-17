package com.music.bitchord.data.collab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Standards-compliant OAuth 2.0 Authorization Code + PKCE (S256) helper for Google OpenID Connect.
 *
 * Uses pure `java.net.ServerSocket` bound to `127.0.0.1:0` so it runs identically on both Android
 * (under R8 minification, with zero `com.sun.net.httpserver` dependency) and Desktop JVMs.
 */
data class PkceChallenge(
    val codeVerifier: String,
    val codeChallenge: String,
    val state: String,
)

data class LoopbackAuthCallback(
    val code: String,
    val state: String,
    val redirectUri: String,
)

object PkceGoogleAuth {

    private const val GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    private val secureRandom = SecureRandom()

    fun generateChallenge(): PkceChallenge {
        val verifierBytes = ByteArray(32).also(secureRandom::nextBytes)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        val stateBytes = ByteArray(16).also(secureRandom::nextBytes)
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes)
        return PkceChallenge(
            codeVerifier = verifier,
            codeChallenge = challenge,
            state = state,
        )
    }

    fun buildAuthorizationUrl(
        clientId: String,
        redirectUri: String,
        challenge: PkceChallenge,
    ): String {
        val params = listOf(
            "client_id" to clientId.trim(),
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to "openid email profile",
            "code_challenge" to challenge.codeChallenge,
            "code_challenge_method" to "S256",
            "state" to challenge.state,
            "prompt" to "select_account",
        )
        val query = params.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$GOOGLE_AUTH_ENDPOINT?$query"
    }

    /**
     * Binds a temporary loopback socket on `127.0.0.1:0`, opens [openBrowser] with the Google
     * OIDC authorization URL, awaits `/oauth2callback`, validates CSRF `state`, and closes cleanly.
     */
    suspend fun awaitLoopbackAuthorization(
        clientId: String,
        timeoutMs: Long = 180_000L,
        openBrowser: (String) -> Unit,
    ): Pair<PkceChallenge, LoopbackAuthCallback> = withContext(Dispatchers.IO) {
        require(clientId.isNotBlank()) { "Configure a Google OAuth Client ID before signing in." }
        val challenge = generateChallenge()
        val deferred = CompletableDeferred<LoopbackAuthCallback>()
        val serverSocket = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val port = serverSocket.localPort
        val redirectUri = "http://127.0.0.1:$port/oauth2callback"

        val acceptJob = launch(Dispatchers.IO) {
            while (!deferred.isCompleted && !serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: SocketException) {
                    break
                } catch (t: Throwable) {
                    if (!deferred.isCompleted) deferred.completeExceptionally(t)
                    break
                }
                socket.use { client ->
                    client.soTimeout = 5_000
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    val requestLine = reader.readLine().orEmpty()
                    while (true) {
                        val header = reader.readLine()
                        if (header.isNullOrEmpty()) break
                    }
                    val target = requestLine.split(' ').getOrNull(1).orEmpty()
                    if (!target.startsWith("/oauth2callback")) {
                        writeHttpResponse(client.getOutputStream(), 404, htmlPage("Not Found", "Return to Sonara."))
                        return@use
                    }
                    val rawQuery = target.substringAfter('?', missingDelimiterValue = "")
                    val params = parseQuery(rawQuery)
                    val error = params["error"]
                    val code = params["code"]
                    val state = params["state"]
                    when {
                        !error.isNullOrBlank() -> {
                            writeHttpResponse(client.getOutputStream(), 400, htmlPage("Sign-in cancelled", "You can close this tab and return to Sonara."))
                            deferred.completeExceptionally(IllegalStateException("Google sign-in cancelled ($error)."))
                        }
                        code.isNullOrBlank() || state != challenge.state -> {
                            writeHttpResponse(client.getOutputStream(), 400, htmlPage("Invalid callback", "State verification failed. Return to Sonara and try again."))
                            deferred.completeExceptionally(IllegalStateException("Invalid OAuth callback state."))
                        }
                        else -> {
                            writeHttpResponse(client.getOutputStream(), 200, htmlPage("Signed in to Sonara", "Google identity verified. You can close this tab and return to Sonara."))
                            deferred.complete(LoopbackAuthCallback(code = code, state = state, redirectUri = redirectUri))
                        }
                    }
                }
            }
        }

        try {
            val authUrl = buildAuthorizationUrl(clientId = clientId, redirectUri = redirectUri, challenge = challenge)
            openBrowser(authUrl)
            val callback = withTimeout(timeoutMs) { deferred.await() }
            challenge to callback
        } finally {
            runCatching { serverSocket.close() }
            acceptJob.cancel()
        }
    }

    private fun writeHttpResponse(out: java.io.OutputStream, status: Int, bodyHtml: String) {
        val bytes = bodyHtml.toByteArray(StandardCharsets.UTF_8)
        val statusText = if (status == 200) "OK" else "Bad Request"
        val headers = "HTTP/1.1 $status $statusText\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(StandardCharsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null
            else {
                val k = URLDecoder.decode(part.substring(0, idx), "UTF-8")
                val v = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                k to v
            }
        }.toMap()
    }

    private fun htmlPage(title: String, body: String): String = """
        <!doctype html>
        <html lang="en">
        <head><meta charset="utf-8"><title>$title</title>
        <style>
          body { background:#0c0c10; color:#f5f5f7; font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
                 display:flex; align-items:center; justify-content:center; height:100vh; margin:0; }
          .card { background:#18181f; border:1px solid rgba(255,255,255,0.1); border-radius:18px; padding:32px 36px; max-width:420px; text-align:center; }
          h1 { font-size:20px; margin:0 0 10px; }
          p { font-size:14px; color:rgba(245,245,247,0.68); margin:0; line-height:1.5; }
        </style></head>
        <body><div class="card"><h1>$title</h1><p>$body</p></div></body></html>
    """.trimIndent()
}
