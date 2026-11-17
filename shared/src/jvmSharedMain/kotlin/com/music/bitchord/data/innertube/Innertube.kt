package com.music.bitchord.data.innertube

import com.music.bitchord.auth.normalizeDataSyncId
import com.music.bitchord.data.DebugLog as Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.PlaylistMove
import com.music.bitchord.data.model.PlaylistPrivacy
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * Minimal Innertube (youtubei) client.
 *
 * WEB_REMIX against music.youtube.com for browse/search/library. It returns
 * the full YT Music shelf layout and honours the signed-in session. Stream
 * URLs are not fetched here; see [InnerTubeXResolver].
 *
 * Authenticated requests are signed with Google's SAPISIDHASH scheme derived
 * from the stored cookie; no long-lived token is ever minted or stored.
 */
object Innertube {
    /**
     * The app's own language when the platform lets the listener pick one
     * separately from the system's — the phone's per-app locale. Null means
     * follow the JVM default.
     */
    @Volatile
    var appLanguage: () -> String? = { null }

    val currentLocaleTag: String
        get() {
            val raw = appLanguage()?.ifEmpty { null }
                ?: Locale.getDefault().toLanguageTag().ifEmpty { "en" }
            return com.music.bitchord.data.LocaleTags.normalizeAppTag(raw)
        }

    val currentLanguage: String
        get() = com.music.bitchord.data.LocaleTags.ytHl(currentLocaleTag)

    val currentRegion: String
        get() = com.music.bitchord.data.LocaleTags.ytGl(currentLocaleTag)

    /** [currentRegion] for search, where null lets YouTube place us — see [LocaleTags.ytSearchGl]. */
    private val searchRegion: String?
        get() = com.music.bitchord.data.LocaleTags.ytSearchGl(currentLocaleTag)

    private val acceptLanguageHeader: String
        get() = com.music.bitchord.data.LocaleTags.acceptLanguage(currentLocaleTag)

    private const val MUSIC_BASE = "https://music.youtube.com/youtubei/v1"
    private const val MUSIC_ORIGIN = "https://music.youtube.com"
    private const val YOUTUBE_ORIGIN = "https://www.youtube.com"

    /** Test-only endpoint overrides so simulated Innertube servers can exercise every request path. */
    @Volatile internal var musicBaseUrlForTesting: String? = null
    @Volatile internal var musicOriginForTesting: String? = null
    @Volatile internal var settleRetryDelayMs: Long = 2_500L

    private val musicBaseUrl: String get() = musicBaseUrlForTesting ?: MUSIC_BASE
    private val musicOriginUrl: String get() = musicOriginForTesting ?: MUSIC_ORIGIN
    private val youtubeWebBaseUrl: String get() = musicBaseUrlForTesting ?: "$YOUTUBE_ORIGIN/youtubei/v1"
    private val youtubeWebOriginUrl: String get() = musicOriginForTesting ?: YOUTUBE_ORIGIN
    /**
     * Fallback WEB_REMIX version, used until [SessionScope] reads the live one
     * out of the music.youtube.com shell. Only a starting point: the real
     * version moves every few days, and the one that matters is the one
     * [webRemixVersion] reports.
     */
    private const val WEB_REMIX_VERSION = "1.20250101.01.00"
    private const val WEB_REMIX_CLIENT_ID = "67"

    /** The longest a read waits before asking YouTube again, whatever Retry-After says. */
    private const val MAX_READ_BACKOFF_MS = 6_000L
    private const val TAG = "BitChord"

    /** Session cookie captured by the login WebView; null = browse as guest. */
    var cookie: String? = null
        set(value) {
            if (field != value) {
                // Both belong to the session that just left. A scope kept across
                // a sign-in would credit the new account's plays to the old one,
                // and a visitor id minted under the old session is not bound to
                // the new one — see [SessionScope] and [visitorData].
                scope = null
                visitorData = null
                visitorDataIsSessionBound = false
                // The chosen channel belonged to the account that just left.
                // A `dataSyncId` from one login sent under another's cookie is
                // answered with 401 on every request, so it goes with it.
                channelOverride = null
            }
            field = value
        }

    /**
     * Google's per-session visitor id.
     *
     * Far more load-bearing than "an id for stats". A `player` request that
     * carries no visitor id is treated as a client with no session at all, and
     * Google answers it in one of two ways: the honest one, `LOGIN_REQUIRED` /
     * "Sign in to confirm you're not a bot", or the quiet one — a perfectly
     * ordinary-looking response whose stream URLs serve a byte to anything that
     * asks and then refuse every real read with 403. The second is what
     * "it loads and then doesn't play" is made of.
     *
     * So it is fetched deliberately by [ensureVisitorData] rather than being
     * hoped for: browse responses carry one only sometimes, and a session that
     * never happened to see one would silently never play anything.
     */
    @Volatile
    private var visitorData: String? = null

    /**
     * Whether [visitorData] came from the signed-in shell rather than being
     * minted anonymously.
     *
     * A session-bound id outranks an anonymous one and must not be replaced by
     * it. Both are fetched near startup and nothing orders them, so without this
     * the better id was lost to whichever request happened to finish second.
     */
    @Volatile
    private var visitorDataIsSessionBound = false

    /**
     * A visitor id for this session, minting one if there isn't one yet.
     *
     * @param refresh discard the current id and take a fresh one — worth doing
     *   exactly once when a request comes back accusing us of being a bot,
     *   since an id can be burned while the session around it is fine.
     */
    suspend fun ensureVisitorData(refresh: Boolean = false): String? {
        if (!refresh && visitorData != null) return visitorData
        runCatching { fetchVisitorData() }
            .onFailure { Log.w(TAG, "could not mint a visitor id: ${it.message}") }
            .getOrNull()
            ?.let {
                if (refresh || !visitorDataIsSessionBound) {
                    visitorData = it
                    visitorDataIsSessionBound = false
                }
            }
        return visitorData
    }

    /**
     * The service worker bootstrap the web player loads before anything else,
     * which is where a fresh visitor id comes from without needing a page.
     * It answers with an anti-hijacking prefix and then plain nested arrays,
     * so the id is found by shape rather than by a path that would rot.
     */
    private suspend fun fetchVisitorData(): String? {
        val body = client.get("${musicOriginForTesting ?: YOUTUBE_ORIGIN}/sw.js_data") {
            header("User-Agent", WEB_USER_AGENT)
        }.bodyAsText()
        val payload = Json.parseToJsonElement(body.substringAfter("\n", body.drop(5)))
        return findVisitorData(payload)
    }

    private fun findVisitorData(element: JsonElement): String? = when (element) {
        is JsonArray -> element.firstNotNullOfOrNull { findVisitorData(it) }
        is JsonPrimitive -> element.contentOrNull?.takeIf { VISITOR_DATA.matches(it) }
        else -> null
    }

    /** Protobuf-in-base64; always this shape, and nothing else in there is. */
    private val VISITOR_DATA = Regex("""Cg[A-Za-z0-9_%-]{40,}""")

    // ---- Which account is this, exactly -------------------------------------

    /**
     * Who the session cookie actually acts as, and which client version it acts
     * with — read out of the signed-in music.youtube.com shell.
     *
     * A cookie is not an account. One Google login carries every account the
     * browser has ever signed into, plus every brand channel hanging off them,
     * and *nothing in the cookie says which one is meant*. The web client
     * resolves that from its page config and then says so on every request. An
     * app that skips this step is not making an ambiguous request — it is
     * making a request about the first account in the jar, whoever that is.
     *
     * That is the whole of "history works for me and not for them": for a
     * listener whose YouTube Music account *is* the first one, guessing is
     * indistinguishable from asking. For anyone with two Google accounts, or a
     * brand channel — the account YouTube Music itself pushes you onto when you
     * have one — every play was being credited to the wrong identity, so their
     * own history stayed empty no matter how many pings went out successfully.
     *
     * @param dataSyncId the account, as `context.user.onBehalfOfUser`. Only
     *   ever taken from a shell that reported itself signed in: Google answers
     *   an `onBehalfOfUser` it cannot tie to a session with 401, so a guessed
     *   value would break every request in the app rather than just history.
     * @param pageId the brand channel, as `X-Goog-PageId` — the header the
     *   stats endpoints ask for by name as `PLUS_PAGE_ID`. Absent for a plain
     *   personal account, which is why it is nullable rather than defaulted.
     * @param authUser which entry in the cookie jar, as `X-Goog-AuthUser`.
     *   Hardcoded `0` before this, which is the same guess by another name.
     */
    private class SessionScope(
        val dataSyncId: String?,
        val pageId: String?,
        val authUser: String,
        val clientVersion: String?,
    )

    @Volatile
    private var scope: SessionScope? = null

    private val scopeLock = Mutex()

    /** A channel the listener picked, standing in for the shell's default. */
    class ChannelSelection(
        val pageId: String?,
        val dataSyncId: String?,
        /**
         * Which Google account in the cookie jar the channel belongs to. Null
         * leaves the shell's own answer alone — a brand channel sits under the
         * account that owns it, so this only differs when the listener switched
         * to a channel of a *different* signed-in Google account.
         */
        val authUser: String? = null,
    )

    /**
     * The channel to act as, when the listener has said which.
     *
     * [fetchSessionScope] can only report the one music.youtube.com serves by
     * default, and for an account whose music lives on a brand channel that is
     * the wrong one — the library, the likes and the history all belong to the
     * other identity. Once a channel has been chosen it outranks the shell
     * completely, including when what it chose is the account's own channel and
     * the shell is the one offering a brand page.
     */
    @Volatile
    private var channelOverride: ChannelSelection? = null

    /**
     * Act as this channel from now on; both null goes back to the shell's
     * default. Takes effect on the next request — nothing is cached from it.
     */
    fun selectChannel(pageId: String?, dataSyncId: String?, authUser: String? = null) {
        channelOverride = if (pageId == null && dataSyncId == null) {
            null
        } else {
            ChannelSelection(pageId, dataSyncId, authUser)
        }
        Log.d(
            TAG,
            "acting as channel pageId=${pageId ?: "none"} authUser=${authUser ?: "as-is"} " +
                "(override=${channelOverride != null})",
        )
    }

    /**
     * Takes the session scope from a page the listener was actually looking at,
     * rather than working it out later from a fetch of our own.
     *
     * The shell fetch in [fetchSessionScope] can only ever report the channel
     * music.youtube.com serves this app by default, and the whole reason the
     * in-app browser exists is that the listener has just told it, by hand,
     * that they want a different one. That answer is written into the page's
     * own `ytcfg`, so it is read from there and adopted whole.
     *
     * Adopting also settles [ensureSessionScope] — a scope already in hand is
     * not refetched — so the shell cannot quietly overwrite the choice with its
     * default on the next request.
     *
     * A page that reported itself signed out is ignored apart from its client
     * version: its `DATASYNC_ID` belongs to no account, and sending one Google
     * cannot tie to the session is answered with 401 on every request.
     */
    fun adoptSessionScope(
        pageId: String?,
        dataSyncId: String?,
        authUser: String?,
        visitorData: String?,
        clientVersion: String?,
        loggedIn: Boolean,
    ) {
        val version = clientVersion ?: scope?.clientVersion
        if (!loggedIn) {
            Log.w(TAG, "captured page was signed out; not scoping requests to it")
            scope = version?.let { SessionScope(null, null, "0", it) }
            return
        }
        scope = SessionScope(
            dataSyncId = dataSyncId?.takeIf { it.isNotBlank() },
            pageId = pageId?.takeIf { it.isNotBlank() },
            authUser = authUser?.takeIf { it.isNotBlank() } ?: "0",
            clientVersion = version,
        )
        // The page's own visitor id, bound to this session — strictly better
        // than the anonymous one [fetchVisitorData] mints. See [visitorData].
        visitorData?.takeIf { it.isNotBlank() }?.let {
            this.visitorData = it
            visitorDataIsSessionBound = true
        }
        Log.d(TAG, "adopted page scope: pageId=${pageId ?: "none"} authUser=${authUser ?: "0"}")
    }

    /** The brand channel to send, chosen one first. */
    private fun pageIdFor(session: SessionScope?): String? =
        (channelOverride ?: return session?.pageId).pageId

    /**
     * The account to send as `onBehalfOfUser`, chosen one first.
     *
     * No falling back to the shell's value once a channel has been chosen: the
     * shell's id names the default identity, and pairing it with another
     * channel's [pageId] describes an account/page combination that doesn't
     * exist.
     */
    private fun dataSyncIdFor(session: SessionScope?): String? =
        (channelOverride ?: return session?.dataSyncId).dataSyncId

    /** Which account in the cookie jar, chosen channel's first. */
    private fun authUserFor(session: SessionScope?): String =
        channelOverride?.authUser ?: session?.authUser ?: "0"

    /**
     * The WEB_REMIX version to claim, live if the shell has been read.
     *
     * Worth taking from the shell rather than pinning: the stats pings carry it
     * as `cver`, and a version Google has never shipped is a standing invitation
     * to be treated as something other than a music client.
     */
    private val webRemixVersion: String
        get() = scope?.clientVersion ?: WEB_REMIX_VERSION

    /**
     * Reads the session scope, once per cookie, before anything that depends on
     * being the right account.
     *
     * Cheap to be wrong about and expensive to skip, so it fails open: a shell
     * that cannot be fetched or parsed leaves [scope] null and every request
     * behaves exactly as it did before. What it must never do is invent a
     * [SessionScope.dataSyncId] — see that field.
     */
    suspend fun ensureSessionScope() {
        val session = cookie ?: return
        if (scope != null) return
        scopeLock.withLock {
            if (scope != null || cookie != session) return
            runCatching { fetchSessionScope(session) }
                .onFailure { Log.w(TAG, "could not read the session scope: ${it.message}") }
                .getOrNull()
                ?.let { fresh ->
                    // A login/profile switch can happen while the shell is in
                    // flight. Never install the old cookie's answer under the
                    // new one: that is a guaranteed 401 and, worse, can credit
                    // a play to the profile that just left.
                    if (cookie != session) {
                        Log.d(TAG, "discarding a session scope from an account that is no longer active")
                        return@let
                    }
                    scope = fresh
                    channelOverride?.let { selected ->
                        if (selected.pageId != fresh.pageId || selected.dataSyncId != fresh.dataSyncId) {
                            Log.w(TAG, "server shell identity differs from selected profile; retaining override")
                        }
                    }
                    Log.d(
                        TAG,
                        "session scope: authUser=${fresh.authUser} " +
                            "pageId=${fresh.pageId ?: "none"} " +
                            "dataSyncId=${if (fresh.dataSyncId != null) "present" else "none"} " +
                            "cver=${fresh.clientVersion ?: WEB_REMIX_VERSION}",
                    )
                }
        }
    }

    /** Re-read request context once after an authenticated rejection. */
    suspend fun refreshSessionScope() {
        if (cookie == null) return
        scopeLock.withLock { scope = null }
        ensureSessionScope()
    }

    /**
     * The music.youtube.com shell, fetched with the session, for its `ytcfg`.
     *
     * Read by regex rather than by evaluating the config blob: it is one script
     * assignment among hundreds of kilobytes of app JavaScript, and the four
     * values wanted are flat strings in it. A key that moves reads as absent,
     * which is the same as not having asked.
     */
    private suspend fun fetchSessionScope(session: String): SessionScope? {
        val html = client.get("$musicOriginUrl/") {
            header("User-Agent", WEB_USER_AGENT)
            header("Accept-Language", acceptLanguageHeader)
            header("Cookie", session)
            sapisidFrom(session)?.let { header("Authorization", sapisidHash(it)) }
        }.bodyAsText()

        // The one value that must not be guessed. A shell that says it is
        // signed out either has a dead cookie or was served to nobody in
        // particular; either way its DATASYNC_ID belongs to no account, and
        // sending it would 401 every request in the app.
        val signedIn = CONFIG_LOGGED_IN.find(html)?.groupValues?.get(1) == "true"
        val clientVersion = CONFIG_CLIENT_VERSION.find(html)?.groupValues?.get(1)
        if (!signedIn) {
            Log.w(TAG, "music.youtube.com served a signed-out shell; not scoping requests")
            // Still worth the client version — that part is true either way.
            return clientVersion?.let { SessionScope(null, null, "0", it) }
        }

        val pageId = CONFIG_PAGE_ID.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val dataSyncId = pageId ?: normalizeDataSyncId(
            CONFIG_DATASYNC_ID.find(html)?.groupValues?.get(1),
        )
        val authUser = CONFIG_SESSION_INDEX.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

        // The shell's own visitor id, which is bound to this session. Strictly
        // better than the anonymous one [fetchVisitorData] mints: the stats
        // pings are attributed against the visitor the player response was
        // issued to, so a signed-in play reported under an anonymous id is a
        // play reported about nobody.
        CONFIG_VISITOR_DATA.find(html)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                visitorData = it
                visitorDataIsSessionBound = true
            }

        return SessionScope(dataSyncId, pageId, authUser ?: "0", clientVersion)
    }

    private val CONFIG_LOGGED_IN = Regex(""""LOGGED_IN"\s*:\s*(true|false)""")
    private val CONFIG_DATASYNC_ID = Regex(""""DATASYNC_ID"\s*:\s*"([^"]+)"""")
    private val CONFIG_PAGE_ID = Regex(""""DELEGATED_SESSION_ID"\s*:\s*"([^"]+)"""")
    private val CONFIG_SESSION_INDEX = Regex(""""SESSION_INDEX"\s*:\s*"?(\d+)""")
    private val CONFIG_VISITOR_DATA = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""")
    private val CONFIG_CLIENT_VERSION = Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"""")

    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true }

    private val client = musicClient(retryConnections = true)
    private val coverUploadClient by lazy { musicClient(retryConnections = false) }

    private fun musicClient(retryConnections: Boolean) = HttpClient(OkHttp) {
        // Same OkHttp instance ExoPlayer streams through — see Http.
        engine {
            preconfigured = com.music.bitchord.data.Http.client
            config { retryOnConnectionFailure(retryConnections) }
        }
        // A redirect must not replay a mutation or carry upload credentials elsewhere.
        followRedirects = retryConnections
        install(ContentNegotiation) { json(json) }
        // Without this the only bound is OkHttp's own read timeout, and the
        // failure it raises reads as "Socket timeout has expired […]
        // socket_timeout=unknown" — Ktor reporting a limit it was never told.
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
        expectSuccess = true
    }

    /**
     * Runs [block], giving transport failures another go before letting them
     * reach the caller.
     *
     * A connection reset on mobile data is weather, not information: the
     * request was fine and asking again generally answers. That matters most
     * on a shared connection pool, where a socket torn down under one request
     * — an abandoned search, a network handover — surfaces as
     * "Software caused connection abort" on whichever request picked that
     * connection up next, which had nothing to do with it.
     *
     * Only transport failures. An HTTP error status is an answer, and
     * repeating the question won't change it. Cancellation isn't caught at
     * all: [delay] throws when the coroutine is cancelled, so a search the
     * user has typed past stops here instead of retrying on behalf of a query
     * nobody is waiting for.
     */
    private suspend fun <T> withRetry(attempts: Int = 3, block: suspend () -> T): T {
        var backoff = 500L
        repeat(attempts - 1) {
            try {
                return block()
            } catch (e: HttpRequestTimeoutException) {
                // Not weather, and not worth repeating. A timeout is this app's
                // own decision that the request had long enough — so trying it
                // again cannot learn anything the first attempt didn't, and the
                // cost is multiplied rather than shared: [HttpRequestTimeoutException]
                // is an [IOException], so before this branch existed every timed-out
                // `player` call was quietly attempted three times. That turned a
                // six-second ceiling into a nineteen-second one on a walk of
                // seven clients, which is worse than the unbounded call the
                // ceiling was added to prevent. Give up on this client and let
                // the caller move to the next one.
                Log.d(TAG, "not retrying, request timed out: ${e.message}")
                throw e
            } catch (e: IOException) {
                Log.d(TAG, "retrying: ${e.message}")
            }
            delay(backoff)
            backoff *= 2
        }
        return block()
    }

    // ---- Public API ---------------------------------------------------------

    suspend fun browse(browseId: String, params: String? = null): JsonObject =
        postMusic("browse") {
            put("browseId", browseId)
            params?.let { put("params", it) }
        }

    /**
     * The next page of a paged browse response — playlists and library feeds
     * come back roughly 100 rows at a time. YouTube Music takes the token as
     * query parameters rather than in the body, and answers with a bare
     * continuation envelope carrying the same row renderers.
     */
    suspend fun browseContinuation(token: String): JsonObject = postMusic(
        endpoint = "browse",
        // The web client passes the token in the body and the older query-string
        // form is still honoured; both are sent so either is enough.
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
    ) {
        put("continuation", token)
    }

    /**
     * [browse] that rides out YouTube's momentary refusals: a 429 (asked too fast) or a 5xx
     * (a backend having a moment) is asked again, after the `Retry-After` the server named or a
     * doubling pause, up to [attempts] times. Only for reads: a browse changes nothing, so asking
     * twice cannot apply anything twice. A 4xx other than 429 is an answer and is thrown at once.
     *
     * Collaborative playlists read many playlists in a burst (discovery, the roster, every page
     * of a long playlist), which is exactly when YouTube starts saying 429; without this, one
     * refusal quietly dropped that playlist from the list until the next refresh.
     */
    suspend fun browseResilient(browseId: String, params: String? = null, attempts: Int = 3): JsonObject =
        readWithBackoff(attempts) { browse(browseId, params) }

    /** [browseContinuation] with [browseResilient]'s patience. */
    suspend fun browseContinuationResilient(token: String, attempts: Int = 3): JsonObject =
        readWithBackoff(attempts) { browseContinuation(token) }

    private suspend fun readWithBackoff(attempts: Int, read: suspend () -> JsonObject): JsonObject {
        var pause = 700L
        repeat(attempts - 1) {
            try {
                return read()
            } catch (e: ResponseException) {
                val status = e.response.status.value
                if (status != 429 && status !in 500..504) throw e
                val retryAfterMs = e.response.headers["Retry-After"]?.trim()?.toLongOrNull()?.times(1_000)
                val wait = (retryAfterMs ?: pause).coerceIn(300L, MAX_READ_BACKOFF_MS)
                Log.d(TAG, "browse refused ($status), asking again in ${wait}ms")
                delay(wait)
                pause *= 2
            }
        }
        return read()
    }

    /** Signed-in profile: display name, email/handle and avatar. */
    suspend fun accountMenu(): JsonObject = postMusic("account/account_menu") {}

    /**
     * Every channel this session can act as, as Innertube's account switcher
     * lists them: the account's own channel first, then its brand channels.
     *
     * Each entry carries the two things a request needs to be made *as* that
     * channel — a `pageIdToken` and a `datasyncIdToken` — which is the whole
     * reason to ask rather than to reason about it. See [selectChannel].
     */
    suspend fun accountsList(): JsonObject = postMusic("account/accounts_list") {}

    /**
     * The same list from youtube.com's own switcher, as a second route.
     *
     * Worth having both. `accounts_list` is the tidier call but it is not
     * uniformly answered for every client identity, and a listener whose music
     * is on a brand channel is stuck with the wrong library until *something*
     * enumerates their channels. This endpoint is what the youtube.com avatar
     * menu itself calls, and it answers a plain signed GET.
     *
     * The body is JSON behind Google's XSSI guard — a `)]}'` line that exists
     * to make the response invalid JavaScript — so it is trimmed before parsing
     * rather than being handed to the JSON reader as-is.
     */
    suspend fun accountSwitcher(): JsonObject {
        requireSession()
        ensureSessionScope()
        val session = scope
        val text = withRetry {
            client.get("$YOUTUBE_ORIGIN/getAccountSwitcherEndpoint") {
                header("User-Agent", WEB_USER_AGENT)
                header("Accept-Language", acceptLanguageHeader)
                header("X-Origin", YOUTUBE_ORIGIN)
                header("Referer", "$YOUTUBE_ORIGIN/")
                cookie?.let { c ->
                    header("Cookie", c)
                    header("X-Goog-AuthUser", authUserFor(session))
                    sapisidFrom(c)?.let {
                        header("Authorization", sapisidHash(it, YOUTUBE_ORIGIN))
                    }
                }
            }.bodyAsText()
        }
        val body = text.substringAfter(")]}'", text).trim()
        return json.parseToJsonElement(body).jsonObject
    }

    /**
     * The watch queue that YouTube Music would play after [videoId] — the
     * "RDAMVM" radio mix. Used to keep AutoPlay going past the last track.
     */
    suspend fun next(videoId: String): JsonObject = postMusic("next") {
        put("videoId", videoId)
        put("playlistId", "RDAMVM$videoId")
        put("isAudioOnly", true)
    }

    /**
     * A watch queue started from a playlist rather than a track: a mix such as
     * My Supermix, a playlist radio (`RDAMPL…`), or an artist's Mix / Shuffle
     * button, which also carry their own seed [videoId] and [params].
     */
    suspend fun nextPlaylist(playlistId: String, videoId: String? = null, params: String? = null): JsonObject =
        postMusic("next") {
            videoId?.let { put("videoId", it) }
            put("playlistId", playlistId)
            params?.let { put("params", it) }
            put("isAudioOnly", true)
        }

    /** Timed caption transcript used as a last-resort lyrics source. */
    suspend fun transcript(videoId: String): JsonObject = postMusic("get_transcript") {
        // get_transcript expects a tiny protobuf: field 1, length, video id.
        val bytes = byteArrayOf(10, videoId.toByteArray().size.toByte()) + videoId.toByteArray()
        put("params", Base64.getEncoder().encodeToString(bytes))
    }

    suspend fun search(query: String, params: String? = null, anonymous: Boolean = true): JsonObject =
        if (anonymous) {
            postMusicAnonymous("search", region = searchRegion) {
                put("query", query)
                params?.let { put("params", it) }
            }
        } else {
            postMusic("search", region = searchRegion) {
                put("query", query)
                params?.let { put("params", it) }
            }
        }

    /** The next page of a filtered search result. */
    suspend fun searchContinuation(token: String, anonymous: Boolean = true): JsonObject =
        if (anonymous) {
            postMusicAnonymous(
                endpoint = "search",
                query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
                region = searchRegion,
            ) { put("continuation", token) }
        } else {
            postMusic(
                endpoint = "search",
                query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
                region = searchRegion,
            ) { put("continuation", token) }
        }

    /**
     * The typeahead list YouTube Music's own search box shows for a
     * half-typed query — query strings, not results.
     *
     * A different endpoint from [search] rather than a cheap mode of it, and
     * far cheaper than one: the response is a few hundred bytes of text with
     * no shelves, thumbnails or playback endpoints in it, which is what makes
     * it affordable per keystroke where a search is not.
     */
    suspend fun searchSuggestions(input: String): JsonObject =
        postMusic("music/get_search_suggestions", region = searchRegion) {
            put("input", input)
        }

    /**
     * Live media results for the typeahead phase — same shape as [search] but
     * deliberately unauthenticated so YouTube Music does not log each debounced
     * keystroke to the account's server-side search history.
     *
     * The regular [search] endpoint records every call against the signed-in
     * account, which turns a slow typist's intermediate queries ("P", "Pe",
     * "Perf…") into polluting history entries.  By omitting the session cookie
     * here we still get full search results (tracks, artists, albums) but they
     * land as anonymous lookups that don't touch the user's account history.
     */
    suspend fun searchTypeahead(query: String): JsonObject =
        postMusicAnonymous("search", region = searchRegion) {
            put("query", query)
        }

    /** The stats endpoints a player response nominates for one playback. */
    data class PlaybackTracking(
        val playbackUrl: String,
        val watchtimeUrl: String?,
        /** The ad-tracking ping real clients fire a few seconds in. */
        val atrUrl: String?,
        /** How far in [atrUrl] is due, per the response's own schedule. */
        val atrAfterSeconds: Long,
    )

    /**
     * Player response fetched *with* the session cookie, purely to read back
     * `playbackTracking` — [player] deliberately skips auth so its device
     * clients are answered at all, so it never sees this block. Null for
     * guests: there's no account history to update.
     *
     * [signatureTimestamp] is not optional in practice, and that is the bug
     * this whole file was reported for.
     *
     * WEB_REMIX is a browser identity, and a browser proves it is running
     * YouTube's current player by quoting that player's timestamp. Without one
     * — or with a stale one — Google does not refuse the request in any way a
     * caller would notice: it answers HTTP 200, `playabilityStatus` `UNPLAYABLE`,
     * reason "Video unavailable", subreason "The page needs to be reloaded",
     * and simply omits `playbackTracking` entirely. So every play registration
     * this app made returned null here, logged one line, and stopped. No ping
     * was ever sent; no history was ever written. Nothing failed loudly enough
     * to notice, which is why it read as working.
     *
     * It is the *only* gate. Verified against the live endpoint: with a current
     * timestamp and nothing else — no visitor id, no referer, no
     * `html5Preference` — the block comes back. With every one of those and a
     * timestamp one revision old, it does not.
     */
    suspend fun playbackTracking(videoId: String, signatureTimestamp: Int?): PlaybackTracking? {
        if (cookie == null) return null
        ensureSessionScope()
        val response = postMusic("player") {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            // Real clients always describe where playback is happening; the
            // response's tracking block is scoped to it.
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    put("referer", "$MUSIC_ORIGIN/watch?v=$videoId")
                    signatureTimestamp?.let { put("signatureTimestamp", it) }
                }
            }
        }
        val tracking = response["playbackTracking"]?.jsonObject
        if (tracking == null) {
            val playability = response["playabilityStatus"]?.jsonObject
            Log.w(
                TAG,
                "player response has no playbackTracking for $videoId " +
                    "(status=${playability?.get("status")?.jsonPrimitive?.content}, " +
                    "reason=${playability?.get("reason")?.jsonPrimitive?.content}, " +
                    "sts=${signatureTimestamp ?: "none"})",
            )
            return null
        }
        val playbackUrl = tracking.trackingUrl("videostatsPlaybackUrl") ?: return null
        return PlaybackTracking(
            playbackUrl = playbackUrl,
            watchtimeUrl = tracking.trackingUrl("videostatsWatchtimeUrl"),
            atrUrl = tracking.trackingUrl("atrUrl"),
            atrAfterSeconds = tracking["atrUrl"]?.jsonObject
                ?.get("elapsedMediaTimeSeconds")?.jsonPrimitive?.contentOrNull
                ?.toLongOrNull() ?: DEFAULT_ATR_SECONDS,
        )
    }

    /** What YouTube Music itself schedules `atr` for, when it doesn't say. */
    private const val DEFAULT_ATR_SECONDS = 5L

    private fun JsonObject.trackingUrl(key: String): String? =
        this[key]?.jsonObject?.get("baseUrl")?.jsonPrimitive?.content

    /**
     * The "playback started" ping real YouTube Music clients send once a track
     * becomes audible. This is what creates the history entry the home feed
     * feeds off. [cpn] is the client-playback-nonce identifying this one play:
     * it must be the same value used for every [pingWatchtime] that follows.
     *
     * No `el` here. The base URL already carries `el=detailpage` — Google puts
     * it there — and a repeated query parameter is not a stronger statement of
     * the same thing, it is an ambiguous request whose resolution is Google's
     * to decide.
     */
    suspend fun pingPlayback(baseUrl: String, cpn: String) = pingStats(baseUrl, cpn) {}

    /**
     * The follow-up ping reporting how much of the track was actually heard.
     * A history entry with no watchtime behind it reads as a skip, so it
     * carries little weight in recommendations — [seconds] is what makes the
     * play count. `st`/`et` are the watched segment's bounds, in seconds.
     *
     * @param final whether this is the last report for the play, which is what
     *   lets Google close the play out rather than leave it looking abandoned.
     */
    suspend fun pingWatchtime(baseUrl: String, cpn: String, seconds: Long, final: Boolean = false) =
        pingStats(baseUrl, cpn) {
            parameter("st", "0")
            parameter("et", seconds.toString())
            // Where the playhead is, as distinct from how much was watched.
            // The web client sends both and they are not redundant: `et` bounds
            // a segment, `cmt` is a position.
            parameter("cmt", seconds.toString())
            parameter("state", if (final) "paused" else "playing")
            if (final) parameter("final", "1")
        }

    /**
     * The `atr` ping, fired a few seconds into a play.
     *
     * Not analytics garnish. It is the third leg of the sequence a real client
     * performs — playback, atr, watchtime — and the one that distinguishes a
     * play that started from a play that happened. Its base URL already carries
     * `ver`, `c` and `cver`, so unlike the others it is sent as-is.
     */
    suspend fun pingAtr(baseUrl: String, cpn: String): Int {
        // Playback can outlive the Activity that restored the session. Ensure
        // the selected profile's headers exist before this first history ping.
        if (cookie != null) ensureSessionScope()
        val session = scope
        return client.get(baseUrl) {
            parameter("cpn", cpn)
            statsHeaders(session)
        }.status.value
    }

    /** Shared shape of the s.youtube.com stats pings, including session auth. */
    private suspend fun pingStats(
        baseUrl: String,
        cpn: String,
        extras: HttpRequestBuilder.() -> Unit,
    ): Int {
        if (cookie != null) ensureSessionScope()
        val session = scope
        return client.get(baseUrl) {
            parameter("ver", "2")
            parameter("c", "WEB_REMIX")
            parameter("cver", webRemixVersion)
            parameter("cpn", cpn)
            // What the web client says about itself. Cheap, and the pings are
            // weighted by how much they look like a real session.
            parameter("cplayer", "UNIPLAYER")
            parameter("cbr", "Chrome")
            parameter("cbrver", "141.0.0.0")
            parameter("cos", "Windows")
            parameter("cosver", "10.0")
            parameter("hl", currentLanguage.replace('-', '_'))
            parameter("cr", currentRegion)
            extras()
            statsHeaders(session)
        }.status.value
    }

    /**
     * The three headers the tracking block asks for by name — `USER_AUTH`,
     * `VISITOR_ID` and `PLUS_PAGE_ID`. Google lists them per ping URL in the
     * player response; sending fewer is what makes a ping land somewhere other
     * than the listener's own history.
     */
    private fun HttpRequestBuilder.statsHeaders(session: SessionScope?) {
        header("X-Origin", MUSIC_ORIGIN)
        header("Origin", MUSIC_ORIGIN)
        header("Referer", "$MUSIC_ORIGIN/")
        header("User-Agent", WEB_USER_AGENT)
        visitorData?.let { header("X-Goog-Visitor-Id", it) }
        cookie?.let { c ->
            header("Cookie", c)
            header("X-Goog-AuthUser", authUserFor(session))
            pageIdFor(session)?.let { header("X-Goog-PageId", it) }
            sapisidFrom(c)?.let { header("Authorization", sapisidHash(it)) }
        }
    }

    // ---- Writes -------------------------------------------------------------
    //
    // Everything below changes something on the account, so all of it needs
    // the session cookie [postMusic] already signs with. None of it needs a
    // new credential or a different client — the same WEB_REMIX identity that
    // reads the library is the one allowed to edit it.

    /** A write attempted without a session; the caller has a sign-in prompt to show. */
    class NotSignedInException : IllegalStateException("Sign in to YouTube Music to do that")

    private fun requireSession() {
        if (cookie == null) throw NotSignedInException()
    }

    /**
     * Thumbs up / down / neither, for [videoId].
     *
     * The response is inspected rather than discarded. Innertube answers a
     * refused write with HTTP 200 and an `error` object in the body, so the
     * status line alone will happily report a rating that never happened.
     */
    suspend fun rate(videoId: String, status: LikeStatus) {
        requireSession()
        val endpoint = when (status) {
            LikeStatus.LIKE -> "like/like"
            LikeStatus.DISLIKE -> "like/dislike"
            LikeStatus.INDIFFERENT -> "like/removelike"
        }
        val response = postMusic(endpoint) {
            putJsonObject("target") { put("videoId", videoId) }
        }
        response["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            error("YouTube Music refused the rating: ${message ?: error}")
        }
        // YouTube states what it did in the toast it would have shown. Worth
        // keeping: a rating it declines to act on still answers 200, and this
        // one line is the difference between "the call was made" and "the
        // call did something".
        Log.d(TAG, "$endpoint $videoId -> ${findString(response, "text") ?: "no confirmation"}")
    }

    /**
     * Saves an album or playlist to the library, or takes it back out.
     *
     * The same endpoints [rate] uses, aimed at a playlist instead of a video:
     * YouTube has no separate "save" verb for a release — a saved album *is* a
     * liked one, which is why the Library tab's Albums and Playlists shelves and
     * the account's likes are the same list. [playlistId] is the id the page
     * itself named, not its browse id; see
     * [com.music.bitchord.data.model.LibraryState].
     *
     * No dislike half, unlike [rate]: nothing in YouTube Music reads a disliked
     * release, so the only two states worth expressing are saved and not.
     */
    suspend fun ratePlaylist(playlistId: String, saved: Boolean) {
        requireSession()
        val endpoint = if (saved) "like/like" else "like/removelike"
        val response = postMusic(endpoint) {
            putJsonObject("target") { put("playlistId", playlistId) }
        }
        // As in [rate]: a refusal arrives as HTTP 200 with an error in the body.
        response["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            error("YouTube Music refused the change: ${message ?: error}")
        }
        Log.d(TAG, "$endpoint $playlistId -> ${findString(response, "text") ?: "no confirmation"}")
    }

    /**
     * Subscribes to an artist's channel, or unsubscribes from it.
     *
     * Not one of the `like/…` endpoints: a subscription is a YouTube-wide
     * relationship rather than a Music one, and it is addressed by channel id —
     * the `UC…` the artist page is served under. See
     * [com.music.bitchord.data.model.SubscriptionState].
     *
     * As in [rate], the body is read rather than the status line: Innertube
     * answers a refused write with HTTP 200 and an `error` object.
     */
    suspend fun setSubscribed(channelId: String, subscribed: Boolean) {
        requireSession()
        val endpoint = if (subscribed) "subscription/subscribe" else "subscription/unsubscribe"
        val response = postMusic(endpoint) {
            putJsonArray("channelIds") { add(channelId) }
        }
        response["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            error("YouTube Music refused the change: ${message ?: error}")
        }
        Log.d(TAG, "$endpoint $channelId -> ${findString(response, "text") ?: "no confirmation"}")
    }

    /**
     * Adds or removes a track from the library, using a token minted by
     * YouTube for exactly that transition — see [com.music.bitchord.data.model.SongMenu].
     * There is no video-id form of this call; the token *is* the request.
     */
    suspend fun sendFeedback(token: String) {
        requireSession()
        postMusic("feedback") {
            putJsonArray("feedbackTokens") { add(token) }
        }
    }

    /**
     * Creates a playlist and returns its id.
     *
     * [videoIds] seeds it in the same request, which is what "add to a new
     * playlist" is: one round trip rather than a create followed by an edit
     * that could half-succeed.
     */
    suspend fun createPlaylist(
        title: String,
        privacy: PlaylistPrivacy,
        description: String? = null,
        videoIds: List<String> = emptyList(),
    ): String {
        requireSession()
        val cleanTitle = sanitizePlaylistTitle(title)
        val cleanDesc = sanitizePlaylistDescription(description)
        val validVideos = videoIds.map(String::trim).filter { YT_VIDEO_ID.matches(it) }

        suspend fun sendCreate(
            targetPrivacy: PlaylistPrivacy,
            includeOnBehalf: Boolean = true,
        ): JsonObject = postMusic("playlist/create", includeOnBehalfOfUser = includeOnBehalf) {
            put("title", cleanTitle)
            if (!cleanDesc.isNullOrEmpty()) {
                put("description", cleanDesc)
            }
            put("privacyStatus", targetPrivacy.apiValue)
            if (validVideos.isNotEmpty()) {
                putJsonArray("videoIds") { validVideos.forEach { add(it) } }
            }
        }

        // 1. Primary attempt with requested privacy and omitted blank description.
        var lastResponse = sendCreate(privacy)
        extractCreatedPlaylistId(lastResponse)?.let { return it }

        // 2. If YouTube returned a channel-creation token (bare Google account without a channel),
        //    provision the personal channel automatically when a token is provided, then retry.
        val channelToken = findString(lastResponse, "channelCreationToken")
            ?: runCatching {
                if (hasAnyKey(lastResponse, CHANNEL_CREATION_KEYS)) {
                    val acctResp = postMusic("account/accounts_list") {
                        put("triggerChannelCreation", true)
                    }
                    findString(acctResp, "channelCreationToken")
                } else null
            }.getOrNull()
        if (!channelToken.isNullOrBlank()) {
            runCatching {
                postMusic("channel/create_channel") {
                    put("channelCreationToken", channelToken)
                }
                refreshSessionScope()
            }
            lastResponse = sendCreate(privacy)
            extractCreatedPlaylistId(lastResponse)?.let { return it }
        }

        // 3. Refresh session scope & visitorData once, and if UNLISTED/PUBLIC was gated
        //    (e.g. PAfeature_enablement on accounts where PRIVATE succeeds), fall back to PRIVATE
        //    and promote privacy afterward.
        runCatching {
            refreshSessionScope()
            ensureVisitorData()
        }
        if (privacy != PlaylistPrivacy.PRIVATE) {
            val privateResp = runCatching { sendCreate(PlaylistPrivacy.PRIVATE) }.getOrNull()
            if (privateResp != null) {
                lastResponse = privateResp
                extractCreatedPlaylistId(privateResp)?.let { createdId ->
                    runCatching { setPlaylistPrivacy(createdId, privacy) }
                    return createdId
                }
            }
        } else {
            val retryResp = runCatching { sendCreate(PlaylistPrivacy.PRIVATE) }.getOrNull()
            if (retryResp != null) {
                lastResponse = retryResp
                extractCreatedPlaylistId(retryResp)?.let { return it }
            }
        }

        // 4. If an active onBehalfOfUser (raw Gaia DATASYNC_ID on a channel-less personal account)
        //    prevented playlist/create from returning an ID, try once with Cookie+SAPISIDHASH alone.
        if (channelOverride != null || scope?.dataSyncId != null) {
            val bareResp = runCatching {
                sendCreate(PlaylistPrivacy.PRIVATE, includeOnBehalf = false)
            }.getOrNull()
            if (bareResp != null) {
                lastResponse = bareResp
                extractCreatedPlaylistId(bareResp)?.let { createdId ->
                    if (privacy != PlaylistPrivacy.PRIVATE) {
                        runCatching { setPlaylistPrivacy(createdId, privacy) }
                    }
                    return createdId
                }
            }
        }

        // 5. Read-after-write verification: if YouTube returned HTTP 200 without an error/gate dialog,
        //    check the user's library playlists once in case the server created it without echoing the ID.
        if (lastResponse["error"] == null && !hasAnyKey(lastResponse, GATED_OR_POPUP_KEYS)) {
            val fromLibrary = runCatching {
                val lib = browse("FEmusic_liked_playlists")
                InnertubeParser.parseLibraryItems(lib)
                    .firstOrNull { it.title.equals(cleanTitle, ignoreCase = false) && !it.browseId.isNullOrBlank() }
                    ?.browseId
                    ?.removePrefix("VL")
                    ?.takeIf { it.isNotBlank() && it != "LM" && it != "SE" }
            }.getOrNull()
            if (!fromLibrary.isNullOrBlank()) {
                if (privacy != PlaylistPrivacy.PRIVATE) {
                    runCatching { setPlaylistPrivacy(fromLibrary, privacy) }
                }
                return fromLibrary
            }
        }

        throw IllegalStateException(diagnoseCreatePlaylistFailure(lastResponse))
    }

    suspend fun deletePlaylist(playlistId: String) {
        requireSession()
        postMusic("playlist/delete") { put("playlistId", playlistId.removePrefix("VL")) }
    }

    /**
     * One or more edits to a playlist, applied together.
     *
     * The endpoint answers `STATUS_SUCCEEDED` rather than an HTTP error when
     * it refuses — a playlist the account merely saved rather than owns is
     * the usual reason — so the body is checked as well as the status line.
     */
    private suspend fun editPlaylist(
        playlistId: String,
        includeOnBehalfOfUser: Boolean = true,
        actions: JsonArrayBuilder.() -> Unit,
    ): JsonObject {
        requireSession()
        val response = postMusic("browse/edit_playlist", includeOnBehalfOfUser = includeOnBehalfOfUser) {
            // The edit endpoint takes the raw id; `VL` is the browse prefix.
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions", actions)
        }
        val status = response["status"]?.jsonPrimitive?.contentOrNull
        if (status != null && !status.contains("SUCCEEDED")) {
            error("YouTube Music refused the edit ($status)")
        }
        return response
    }

    /**
     * Adds tracks to a playlist, and reports the per-entry id each one landed
     * under — video id to set-video-id, for the tracks the response named.
     *
     * Worth reading rather than discarding, because it is the only chance to
     * learn it without re-fetching the whole playlist: a set-video-id is minted
     * by this call, and it is what a later removal has to be expressed in (see
     * [removeFromPlaylist]). A row added to a playlist already on screen is
     * otherwise one the user can see but not take back out until the page is
     * reopened.
     *
     * Absences are normal and not an error — the add still happened; only the
     * id for undoing it is unknown.
     */
    suspend fun addToPlaylist(
        playlistId: String,
        videoIds: List<String>,
        allowDuplicates: Boolean = false,
        retryCollaboratorAcl: Boolean = false,
    ): Map<String, String> {
        val cleanIds = videoIds.map(String::trim).filter(String::isNotEmpty)
        if (cleanIds.isEmpty()) return emptyMap()

        suspend fun sendAdd(includeOnBehalf: Boolean): JsonObject =
            editPlaylist(playlistId, includeOnBehalfOfUser = includeOnBehalf) {
                cleanIds.forEach { videoId ->
                    addJsonObject {
                        put("action", "ACTION_ADD_VIDEO")
                        put("addedVideoId", videoId)
                        if (allowDuplicates) {
                            put("dedupeOption", "DEDUPE_OPTION_SKIP")
                        }
                    }
                }
            }

        var lastErr: Throwable? = null
        val maxAttempts = if (retryCollaboratorAcl) 4 else 1
        var response: JsonObject? = null
        for (attempt in 0 until maxAttempts) {
            if (attempt > 0 && settleRetryDelayMs > 0L) {
                delay(settleRetryDelayMs)
            }
            val res = runCatching { sendAdd(includeOnBehalf = true) }
            if (res.isSuccess) {
                response = res.getOrThrow()
                break
            }
            lastErr = res.exceptionOrNull()
            val msg = lastErr?.message.orEmpty()
            if (retryCollaboratorAcl && (msg.contains("403") || msg.contains("400")) &&
                (channelOverride != null || scope?.dataSyncId != null)
            ) {
                val bare = runCatching { sendAdd(includeOnBehalf = false) }
                if (bare.isSuccess) {
                    response = bare.getOrThrow()
                    break
                }
            }
            val retryable = retryCollaboratorAcl && (msg.contains("403") || msg.contains("409") || msg.contains("503"))
            if (!retryable) break
        }
        val finalResp = response ?: throw (lastErr ?: IllegalStateException("Could not add track to playlist"))
        return (finalResp["playlistEditResults"] as? JsonArray)
            .orEmpty()
            .mapNotNull { result ->
                val added = (result as? JsonObject)
                    ?.get("playlistEditVideoAddedResultData") as? JsonObject
                    ?: return@mapNotNull null
                val videoId = (added["videoId"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                val setVideoId = (added["setVideoId"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                videoId to setVideoId
            }
            .toMap()
    }

    /**
     * Removes entries from a playlist. Keyed by set-video-id as well as video
     * id: the same track added twice is two entries, and only the pair says
     * which of them to drop.
     */
    suspend fun removeFromPlaylist(playlistId: String, entries: List<Pair<String, String>>) {
        editPlaylist(playlistId) {
            entries.forEach { (setVideoId, videoId) ->
                addJsonObject {
                    put("action", "ACTION_REMOVE_VIDEO")
                    put("setVideoId", setVideoId)
                    put("removedVideoId", videoId)
                }
            }
        }
    }

    /**
     * Rearranges a playlist, one [PlaylistMove] after another in the order
     * given — see [com.music.bitchord.data.model.playlistMoves] for building
     * them. A move with no successor goes to the end.
     */
    suspend fun movePlaylistItems(playlistId: String, moves: List<PlaylistMove>) {
        if (moves.isEmpty()) return
        editPlaylist(playlistId) {
            moves.forEach { move ->
                addJsonObject {
                    put("action", "ACTION_MOVE_VIDEO_BEFORE")
                    put("setVideoId", move.setVideoId)
                    move.before?.let { put("movedSetVideoIdSuccessor", it) }
                }
            }
        }
    }

    suspend fun renamePlaylist(playlistId: String, title: String) {
        editPlaylist(playlistId) {
            addJsonObject {
                put("action", "ACTION_SET_PLAYLIST_NAME")
                put("playlistName", title)
            }
        }
    }

    val hasSession: Boolean get() = !cookie.isNullOrBlank()

    suspend fun setPlaylistDescription(playlistId: String, description: String) {
        editPlaylist(playlistId) {
            addJsonObject {
                put("action", "ACTION_SET_PLAYLIST_DESCRIPTION")
                put("playlistDescription", description)
            }
        }
    }

    suspend fun setPlaylistPrivacy(playlistId: String, privacy: PlaylistPrivacy) {
        editPlaylist(playlistId) {
            addJsonObject {
                put("action", "ACTION_SET_PLAYLIST_PRIVACY")
                put("playlistPrivacy", privacy.apiValue)
            }
        }
    }

    /**
     * Enables native YouTube Music collaboration on [playlistId] and returns the shareable
     * `https://music.youtube.com/playlist?list=...&jct=...` invite URL.
     * Collaborators cannot edit PRIVATE playlists on YouTube Music, so [ensureUnlisted] promotes
     * PRIVATE playlists to UNLISTED first.
     */
    suspend fun enablePlaylistCollaboration(
        playlistId: String,
        ensureUnlisted: Boolean = true,
        /** How many times to ask while a new playlist settles; 1 is a single quick try. */
        maxAttempts: Int = 14,
    ): String {
        val rawId = playlistId.removePrefix("VL")
        // Promote PRIVATE -> UNLISTED ONCE up front (never inside the loop: YouTube takes up to
        // ~18s to propagate UNLISTED before ACTION_CREATE_COLLABORATION_INVITE_LINK succeeds).
        if (ensureUnlisted) {
            runCatching {
                editPlaylist(rawId) {
                    addJsonObject {
                        put("action", "ACTION_SET_PLAYLIST_PRIVACY")
                        put("playlistPrivacy", PlaylistPrivacy.UNLISTED.apiValue)
                    }
                }
            }
        }
        var lastError: Throwable? = null
        for (attempt in 0 until maxAttempts) {
            if (attempt > 0 && settleRetryDelayMs > 0L) {
                delay(settleRetryDelayMs)
            }
            val attemptResult = runCatching {
                editPlaylist(rawId) {
                    addJsonObject {
                        put("action", "ACTION_CREATE_COLLABORATION_INVITE_LINK")
                    }
                }
            }
            val response = attemptResult.getOrNull()
            if (response != null) {
                extractCollaborationInviteUrl(response, rawId)
                    ?.takeIf { it.contains("jct=") }
                    ?.let { return it }
                val panelInvite = runCatching {
                    val browse = browse("VL$rawId")
                    val params = InnertubeParser.parseYtmCollabHeader(browse, rawId)?.panelParams
                    val panel = getCollaboratorsPanel(params, rawId)
                    extractCollaborationInviteUrl(panel, rawId)?.takeIf { it.contains("jct=") }
                }.getOrNull()
                if (!panelInvite.isNullOrBlank()) return panelInvite
            } else {
                lastError = attemptResult.exceptionOrNull()
                val msg = lastError?.message.orEmpty()
                // If playlist was still PRIVATE when ensureUnlisted=false, promote it once and keep polling
                if (attempt == 0 && !ensureUnlisted && (msg.contains("400") || msg.contains("PRECONDITION", ignoreCase = true))) {
                    runCatching { setPlaylistPrivacy(rawId, PlaylistPrivacy.UNLISTED) }
                }
                val retryable = msg.contains("409") ||
                    msg.contains("400") ||
                    msg.contains("404") ||
                    msg.contains("500") ||
                    msg.contains("503") ||
                    msg.contains("PRECONDITION", ignoreCase = true) ||
                    msg.contains("STATUS_FAILED", ignoreCase = true)
                if (!retryable) break
            }
        }
        throw IllegalStateException(
            lastError?.message
                ?: "YouTube Music is still preparing collaboration on this playlist. Wait a few seconds and tap Create Invite Link again.",
        )
    }

    /**
     * The ids of every playlist YouTube Music will let the signed-in account add a track to —
     * the ones it owns and the collaborative ones it has joined — read from the same
     * `playlist/get_add_to_playlist` call the web client's "Save to playlist" dialog makes.
     *
     * The web client asks with the track being saved (`videoIds`), so that is asked first;
     * the `playlistId` form (saving a whole album) is the fallback. A read-only viewer of a
     * collaborative playlist never appears here, which is what makes this the authority on
     * whether a join took.
     */
    suspend fun getEditablePlaylistIdsForCurrentUser(probeVideoId: String? = null): Set<String> {
        if (!hasSession) return emptySet()
        val ids = HashSet<String>()
        val probe = probeVideoId?.trim()?.takeIf { YT_VIDEO_ID.matches(it) } ?: ADD_TO_PLAYLIST_PROBE_VIDEO_ID
        runCatching {
            postMusic("playlist/get_add_to_playlist") {
                putJsonArray("videoIds") { add(probe) }
                put("excludeWatchLater", true)
            }
        }.onSuccess { ids += InnertubeParser.parseAddToPlaylistIds(it) }
        if (ids.isEmpty()) {
            runCatching {
                postMusic("playlist/get_add_to_playlist") {
                    put("playlistId", "LM")
                    put("excludeWatchLater", true)
                }
            }.onSuccess { ids += InnertubeParser.parseAddToPlaylistIds(it) }
        }
        return ids
    }

    /**
     * Whether the signed-in account can write to [playlistId]: it owns it, YouTube Music hands
     * it edit commands for it, or lists it among the playlists it may add to. Never confuses a
     * read-only viewer of a collaborative playlist with an accepted collaborator.
     */
    suspend fun isEditablePlaylistForCurrentUser(playlistId: String): Boolean {
        if (!hasSession) return false
        val rawId = playlistId.removePrefix("VL")
        val page = runCatching { browse("VL$rawId") }.getOrNull()
        if (page != null) {
            if (InnertubeParser.parseYtmCollabHeader(page, rawId)?.owned == true) return true
            if (InnertubeParser.hasPlaylistEditAffordance(page, rawId)) return true
        }
        val probe = page?.let { InnertubeParser.parsePlaylistShelf(it)?.songs?.firstOrNull()?.videoId }
        return rawId in getEditablePlaylistIdsForCurrentUser(probe)
    }

    /** How [joinCollaborativePlaylist] ended. */
    enum class CollabJoinOutcome {
        /** This call made the account a collaborator. */
        JOINED,

        /** The account already owned or collaborated on the playlist before this call. */
        ALREADY_COLLABORATOR,
    }

    /**
     * A join YouTube Music refused, worded for a dialog. The raw response body stays in
     * [cause] for the log; [message] never carries it.
     */
    class CollabJoinException(
        message: String,
        val httpStatus: Int?,
        cause: Throwable?,
    ) : IllegalStateException(message, cause)

    /**
     * Joins a YouTube Music collaborative playlist via its `jct` invite token.
     *
     * The call itself is `ACTION_JOIN_COLLABORATION` on `browse/edit_playlist`, and its
     * failures are ambiguous: YouTube Music answers **401 "You are not authorized to edit this
     * playlist"** both when the account is *already* a collaborator and while a freshly minted
     * token has not reached the replica answering (up to ~20s). So a refusal is never taken at
     * its word — write access is checked after it, and the join is retried across the
     * propagation window, through the invite page the web client itself loads
     * ([joinViaInvitePage]) and through the `WEB` client, before it is reported as failed.
     */
    suspend fun joinCollaborativePlaylist(playlistId: String, joinCollaborationToken: String): CollabJoinOutcome {
        requireSession()
        val rawId = playlistId.removePrefix("VL")
        val cleanJct = joinCollaborationToken.trim()
        require(cleanJct.isNotEmpty()) { "Invite link is missing a YouTube Music collaboration token (jct)." }

        suspend fun finish(outcome: CollabJoinOutcome): CollabJoinOutcome {
            runCatching { ratePlaylist(rawId, saved = true) }
            return outcome
        }

        var lastErr: Throwable? = null
        var channelChecked = false
        var needsChannel = false
        for (attempt in 0 until JOIN_ATTEMPTS) {
            if (attempt > 0 && settleRetryDelayMs > 0L) {
                delay(settleRetryDelayMs)
            }
            val primary = runCatching {
                editPlaylist(rawId) { joinCollaborationAction(cleanJct) }
            }
            if (primary.isSuccess) return finish(CollabJoinOutcome.JOINED)
            lastErr = primary.exceptionOrNull()

            // 401 is what an account that is already in gets, so look before believing it.
            if (isEditablePlaylistForCurrentUser(rawId)) {
                return finish(
                    if (attempt == 0) CollabJoinOutcome.ALREADY_COLLABORATOR else CollabJoinOutcome.JOINED,
                )
            }

            // An account that has never used YouTube has no channel, and YouTube Music will not
            // make a channel-less account a collaborator. Give it one (the same step the
            // create-playlist path takes) and go round again.
            if (!channelChecked) {
                channelChecked = true
                when (ensureYouTubeChannel()) {
                    ChannelState.CREATED -> continue
                    ChannelState.NEEDS_MANUAL_SETUP -> needsChannel = true
                    ChannelState.PRESENT_OR_UNKNOWN -> Unit
                }
            }

            // The other doors, tried on alternate rounds so a token that is merely still
            // propagating is not hammered through four paths every couple of seconds.
            if (attempt % 2 == 0) {
                if (attempt == 0) {
                    runCatching { refreshSessionScope() }
                    if (channelOverride != null || scope?.dataSyncId != null) {
                        val bare = runCatching {
                            editPlaylist(rawId, includeOnBehalfOfUser = false) { joinCollaborationAction(cleanJct) }
                        }
                        if (bare.isSuccess) return finish(CollabJoinOutcome.JOINED)
                    }
                }
                if (runCatching { joinViaInvitePage(rawId, cleanJct) }.getOrDefault(false)) {
                    return finish(CollabJoinOutcome.JOINED)
                }
                if (runCatching { joinViaYouTubeWeb(rawId, cleanJct) }.getOrDefault(false)) {
                    return finish(CollabJoinOutcome.JOINED)
                }
                if (isEditablePlaylistForCurrentUser(rawId)) return finish(CollabJoinOutcome.JOINED)
            }

            if (!isRetryableJoinFailure(lastErr)) break
        }

        Log.d(TAG, "collab join refused for $rawId: ${lastErr?.message}")
        if (needsChannel) {
            throw CollabJoinException(
                "CHANNEL_REQUIRED: This Google account doesn't have a YouTube channel yet, and YouTube Music only " +
                    "lets accounts with one collaborate. Tap 'Set Up YouTube Channel', finish the one-time setup, " +
                    "then join again.",
                httpStatusOf(lastErr),
                lastErr,
            )
        }
        throw CollabJoinException(describeJoinFailure(lastErr), httpStatusOf(lastErr), lastErr)
    }

    enum class ChannelState { PRESENT_OR_UNKNOWN, CREATED, NEEDS_MANUAL_SETUP }

    /**
     * Makes sure the signed-in Google account has a YouTube channel, creating the default one
     * when YouTube offers a creation token for it. A Google account that has never opened
     * YouTube has none, and without one it can neither own nor collaborate on a playlist.
     */
    suspend fun ensureYouTubeChannel(): ChannelState {
        if (!hasSession) return ChannelState.PRESENT_OR_UNKNOWN
        val accounts = runCatching {
            postMusic("account/accounts_list") { put("triggerChannelCreation", true) }
        }.getOrNull() ?: return ChannelState.PRESENT_OR_UNKNOWN
        val token = findString(accounts, "channelCreationToken")
        if (token.isNullOrBlank()) {
            return if (hasAnyKey(accounts, CHANNEL_CREATION_KEYS)) ChannelState.NEEDS_MANUAL_SETUP else ChannelState.PRESENT_OR_UNKNOWN
        }
        val created = runCatching {
            postMusic("channel/create_channel") { put("channelCreationToken", token) }
        }.isSuccess
        if (!created) return ChannelState.NEEDS_MANUAL_SETUP
        runCatching { refreshSessionScope() }
        return ChannelState.CREATED
    }

    private fun JsonArrayBuilder.joinCollaborationAction(jct: String) {
        addJsonObject {
            put("action", "ACTION_JOIN_COLLABORATION")
            put("joinCollaborationToken", jct)
        }
    }

    /**
     * The join the way music.youtube.com does it when an invite link is opened: the server
     * folds the link's `jct` into the playlist page's `browse` params
     * ([collaborationInviteBrowseParams]) and answers with an invite dialog whose accept
     * button carries the join command. That command is run exactly as served.
     */
    private suspend fun joinViaInvitePage(rawId: String, jct: String): Boolean {
        val page = postMusic("browse") {
            put("browseId", "VL$rawId")
            put("params", collaborationInviteBrowseParams(jct))
        }
        for (command in InnertubeParser.parseCollaborationJoinCommands(page, rawId)) {
            val accepted = runCatching {
                val response = postMusic(command.endpoint) {
                    command.body.forEach { (key, value) -> put(key, value) }
                }
                val status = response["status"]?.jsonPrimitive?.contentOrNull
                status == null || status.contains("SUCCEEDED")
            }.getOrDefault(false)
            if (accepted) return true
        }
        return false
    }

    /** The same join through `www.youtube.com`'s `WEB` client, which shares the playlist backend. */
    private suspend fun joinViaYouTubeWeb(rawId: String, jct: String): Boolean {
        val response = postYouTubeWeb("browse/edit_playlist") {
            put("playlistId", rawId)
            putJsonArray("actions") { joinCollaborationAction(jct) }
        }
        val status = response["status"]?.jsonPrimitive?.contentOrNull
        return status == null || status.contains("SUCCEEDED")
    }

    /** The HTTP status a failed call was answered with, or null if it never got an answer. */
    fun httpStatusOf(error: Throwable?): Int? =
        (error as? ResponseException)?.response?.status?.value

    private fun isRetryableJoinFailure(error: Throwable?): Boolean {
        val status = httpStatusOf(error)
        if (status != null) return status in RETRYABLE_JOIN_STATUSES
        val msg = error?.message.orEmpty()
        return msg.contains("PRECONDITION", ignoreCase = true) || msg.contains("STATUS_FAILED", ignoreCase = true)
    }

    private fun describeJoinFailure(error: Throwable?): String = when (httpStatusOf(error)) {
        400, 401, 403 ->
            "YouTube Music didn't accept this invite for the signed-in account. The link may have been " +
                "replaced by a newer one, or sharing was turned off — ask the owner to create a fresh " +
                "invite link and send it again. If it keeps happening, check that you're signed in with " +
                "the Google account you want to join with and that it has a YouTube channel."
        404 -> "This playlist can't be found on YouTube Music. It may have been deleted or made private."
        null -> if (error is IOException) {
            "Couldn't reach YouTube Music. Check your connection and try again."
        } else {
            "Could not join this collaborative playlist on YouTube Music. Try again in a moment."
        }
        else -> "YouTube Music couldn't complete the join right now (HTTP ${httpStatusOf(error)}). Try again in a moment."
    }

    /** Leaves a YouTube Music collaborative playlist and removes it from Library. */
    suspend fun leaveCollaborativePlaylist(playlistId: String) {
        val rawId = playlistId.removePrefix("VL")
        runCatching {
            editPlaylist(rawId) {
                addJsonObject {
                    put("action", "ACTION_LEAVE_COLLABORATIVE_PLAYLIST")
                }
            }
        }
        runCatching { ratePlaylist(rawId, saved = false) }
    }

    /** Closes a YouTube Music collaborative playlist to further collaborator contributions. */
    suspend fun closePlaylistCollaboration(playlistId: String, makePrivate: Boolean = true) {
        val rawId = playlistId.removePrefix("VL")
        runCatching {
            editPlaylist(rawId) {
                addJsonObject {
                    put("action", "ACTION_SET_CLOSED_TO_CONTRIBUTIONS")
                    put("closedToContributions", true)
                }
            }
        }
        if (makePrivate) {
            runCatching { setPlaylistPrivacy(rawId, PlaylistPrivacy.PRIVATE) }
        }
    }

    /**
     * Fetches the `PAplaylist_collaborate` engagement panel listing active collaborators.
     *
     * Asked of `WEB_REMIX` first, with the params the playlist header carried and then the ones
     * built from the playlist id ([collaboratorsPanelParams]) when the header had none; then of
     * `WEB` (`www.youtube.com`), whose playlist page can carry its own panel params or the
     * roster inline. The first answer that actually lists collaborators wins.
     */
    suspend fun getCollaboratorsPanel(params: String?, playlistId: String? = null): JsonObject {
        requireSession()
        val rawId = playlistId?.removePrefix("VL")?.takeIf { it.isNotBlank() }
        val candidates = listOfNotNull(
            params?.takeIf { it.isNotBlank() },
            rawId?.let(::collaboratorsPanelParams),
        ).distinct()

        var firstAnswer: JsonObject? = null
        suspend fun rosterFrom(request: suspend () -> JsonObject): JsonObject? {
            val panel = runCatching { request() }.getOrNull() ?: return null
            if (firstAnswer == null) firstAnswer = panel
            return panel.takeIf { InnertubeParser.parseCollaboratorsPanel(it).isNotEmpty() }
        }

        for (candidate in candidates.ifEmpty { listOf(null) }) {
            rosterFrom {
                postMusic("get_panel") {
                    put("panelId", COLLABORATE_PANEL_ID)
                    candidate?.let { put("params", it) }
                    rawId?.let { put("playlistId", it) }
                }
            }?.let { return it }
        }

        rosterFrom {
            postYouTubeWeb("get_panel") {
                put("panelId", COLLABORATE_PANEL_ID)
                candidates.firstOrNull()?.let { put("params", it) }
            }
        }?.let { return it }

        if (rawId != null) {
            val webPage = runCatching { postYouTubeWeb("browse") { put("browseId", "VL$rawId") } }.getOrNull()
            if (webPage != null) {
                if (InnertubeParser.parseCollaboratorsPanel(webPage, strict = true).isNotEmpty()) return webPage
                val webParams = InnertubeParser.parseCollaboratorsPanelParams(webPage)
                if (!webParams.isNullOrBlank() && webParams !in candidates) {
                    rosterFrom {
                        postYouTubeWeb("get_panel") {
                            put("panelId", COLLABORATE_PANEL_ID)
                            put("params", webParams)
                        }
                    }?.let { return it }
                }
            }
        }
        return firstAnswer ?: buildJsonObject {}
    }

    private const val COLLABORATE_PANEL_ID = "PAplaylist_collaborate"

    /** Rounds of [joinCollaborativePlaylist]; with [settleRetryDelayMs] this spans the ~20s token propagation. */
    private const val JOIN_ATTEMPTS = 7

    private val RETRYABLE_JOIN_STATUSES = setOf(400, 401, 403, 404, 409, 429, 500, 502, 503)

    /** Any catalogue track; `get_add_to_playlist` only needs something to "save" to list the targets. */
    private const val ADD_TO_PLAYLIST_PROBE_VIDEO_ID = "dQw4w9WgXcQ"

    /**
     * `browse` params carrying an invite link's `jct`, byte for byte what music.youtube.com's
     * server builds for `/playlist?list=…&jct=…`: field 39 holds the token, field 88 a fixed
     * `{1: 600}`. URL-safe base64 with the padding percent-encoded, as the page embeds it.
     */
    internal fun collaborationInviteBrowseParams(jct: String): String {
        val token = jct.toByteArray(Charsets.UTF_8)
        val bytes = byteArrayOf(0xBA.toByte(), 0x02) + protoVarint(token.size) + token +
            byteArrayOf(0xC2.toByte(), 0x05, 0x03, 0x08, 0xD8.toByte(), 0x04)
        return innertubeParams(bytes)
    }

    /**
     * `get_panel` params for a playlist's `PAplaylist_collaborate` panel — field 138 wrapping
     * the playlist id, which is all the header's own `showEngagementPanelEndpoint` params hold.
     */
    internal fun collaboratorsPanelParams(rawPlaylistId: String): String {
        val id = rawPlaylistId.removePrefix("VL").toByteArray(Charsets.UTF_8)
        val inner = byteArrayOf(0x0A) + protoVarint(id.size) + id
        return innertubeParams(byteArrayOf(0xD2.toByte(), 0x08) + protoVarint(inner.size) + inner)
    }

    private fun innertubeParams(bytes: ByteArray): String =
        Base64.getUrlEncoder().encodeToString(bytes).replace("=", "%3D")

    private fun protoVarint(value: Int): ByteArray {
        val out = ArrayList<Byte>(2)
        var rest = value
        while (rest >= 0x80) {
            out.add(((rest and 0x7F) or 0x80).toByte())
            rest = rest ushr 7
        }
        out.add(rest.toByte())
        return out.toByteArray()
    }

    private suspend fun postYouTubeWeb(
        endpoint: String,
        bodyExtras: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        if (cookie != null) ensureSessionScope()
        val session = scope
        val origin = youtubeWebOriginUrl
        return withRetry {
            client.post("$youtubeWebBaseUrl/$endpoint") {
                contentType(ContentType.Application.Json)
                parameter("prettyPrint", "false")
                parameter("hl", currentLanguage)
                header("Accept-Language", acceptLanguageHeader)
                header("X-Origin", origin)
                header("Origin", origin)
                header("Referer", "$origin/")
                header("X-YouTube-Client-Name", "1")
                header("X-YouTube-Client-Version", "2.20251001.01.00")
                visitorData?.let { header("X-Goog-Visitor-Id", it) }
                cookie?.let { c ->
                    header("Cookie", c)
                    header("X-Goog-AuthUser", authUserFor(session))
                    pageIdFor(session)?.let { header("X-Goog-PageId", it) }
                    sapisidFrom(c)?.let { header("Authorization", sapisidHash(it, origin = origin)) }
                }
                setBody(
                    buildJsonObject {
                        putJsonObject("context") {
                            putJsonObject("client") {
                                put("clientName", "WEB")
                                put("clientVersion", "2.20251001.01.00")
                                put("hl", currentLanguage)
                                currentRegion.let { put("gl", it) }
                                visitorData?.let { put("visitorData", it) }
                            }
                            putJsonObject("user") {
                                put("lockedSafetyMode", false)
                                dataSyncIdFor(session)?.let { put("onBehalfOfUser", it) }
                            }
                            putJsonObject("request") { put("useSsl", true) }
                        }
                        bodyExtras()
                    },
                )
            }.body<JsonObject>()
        }
    }

    /**
     * Resumable upload followed by a separate playlist mutation; neither is retried.
     * Protocol: Metrolist d84670c, InnerTube.kt:648-700 / EditPlaylistBody.kt:49-65.
     */
    suspend fun setPlaylistCover(playlistId: String, image: ByteArray, isCurrent: () -> Boolean = { true }) {
        check(isCurrent()) { "Account changed before cover upload" }
        requireSession()
        require(image.isNotEmpty() && image.size <= 10 * 1024 * 1024)
        val id = playlistId.removePrefix("VL")
        require(id.isNotBlank() && id.all { it.isLetterOrDigit() || it == '_' || it == '-' })
        ensureSessionScope()
        val login = checkNotNull(cookie)
        val selected = channelOverride
        val session = scope
        val signingCookie = checkNotNull(sapisidFrom(login))
        fun checkIdentity() {
            check(isCurrent() && cookie == login && channelOverride === selected) {
                "Account changed during cover upload"
            }
        }
        // Check the target under this same session before sending any image bytes.
        val playlist = browse("VL$id")
        checkIdentity()
        check(InnertubeParser.parsePlaylistCoverEditable(playlist)) { "Playlist is not editable" }
        fun HttpRequestBuilder.uploadHeaders() {
            checkIdentity()
            contentType(ContentType.Application.Json)
            header("X-Goog-Api-Format-Version", "1")
            header("User-Agent", WEB_USER_AGENT)
            header("Origin", MUSIC_ORIGIN)
            header("X-Origin", MUSIC_ORIGIN)
            header("Referer", "$MUSIC_ORIGIN/")
            header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_ID)
            header("X-YouTube-Client-Version", webRemixVersion)
            header("Cookie", login)
            header("Authorization", sapisidHash(signingCookie))
            header("X-Goog-AuthUser", authUserFor(session))
            pageIdFor(session)?.let { header("X-Goog-PageId", it) }
            visitorData?.let { header("X-Goog-Visitor-Id", it) }
        }
        val endpoint = "$MUSIC_ORIGIN/playlist_image_upload/playlist_custom_thumbnail"
        val start = coverUploadClient.post(endpoint) {
            uploadHeaders()
            header("X-Goog-Upload-Command", "start")
            header("X-Goog-Upload-Protocol", "resumable")
            header("X-Goog-Upload-Header-Content-Length", image.size.toString())
        }
        val uploadId = start.headers["X-GUploader-UploadID"]?.takeIf { it.isNotBlank() }
        start.bodyAsText()
        checkNotNull(uploadId) { "YouTube Music did not start the image upload" }
        val uploaded = coverUploadClient.post(endpoint) {
            uploadHeaders()
            parameter("upload_id", uploadId)
            parameter("upload_protocol", "resumable")
            header("X-Goog-Upload-Command", "upload, finalize")
            header("X-Goog-Upload-Offset", "0")
            setBody(image)
        }.bodyAsText()
        // The upload server need not label its JSON as application/json.
        val uploadResponse = json.parseToJsonElement(uploaded) as? JsonObject
        val blob = (uploadResponse?.get("encryptedBlobId") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        checkNotNull(blob) { "YouTube Music did not finalize the image upload" }
        val edited = postMusic("browse/edit_playlist", retry = false, checkIdentity = ::checkIdentity) {
            put("playlistId", id)
            putJsonArray("actions") {
                addJsonObject {
                    put("action", "ACTION_SET_CUSTOM_THUMBNAIL")
                    putJsonObject("addedCustomThumbnail") {
                        putJsonObject("imageKey") {
                            put("name", "studio_square_thumbnail")
                            put("type", "PLAYLIST_IMAGE_TYPE_CUSTOM_THUMBNAIL")
                        }
                        put("playlistScottyEncryptedBlobId", blob)
                    }
                }
            }
        }
        val status = (edited["status"] as? JsonPrimitive)?.contentOrNull
        // Metrolist's EditPlaylistResponse also supports a returned newHeader.
        val updatedHeader = edited["newHeader"]?.let { InnertubeParser.parseBrowseHeader(it) }
        check(status == "STATUS_SUCCEEDED" ||
            (status == null && !updatedHeader?.thumbnailUrl.isNullOrBlank())) {
            "YouTube Music did not confirm the playlist cover edit"
        }
    }

    /** A fresh client-playback-nonce, identifying one play of one track. */
    fun newCpn(): String = (1..16).map { CPN_ALPHABET.random() }.joinToString("")

    private const val CPN_ALPHABET =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

    // ---- Request plumbing ---------------------------------------------------

    private suspend fun postMusic(
        endpoint: String,
        query: Map<String, String> = emptyMap(),
        /** The context's `gl`; null sends none. */
        region: String? = currentRegion,
        retry: Boolean = true,
        includeOnBehalfOfUser: Boolean = true,
        checkIdentity: () -> Unit = {},
        bodyExtras: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        // This is the common authenticated request path: Home, Library,
        // likes, playlists, account menus and all their continuations pass
        // through it. Waiting here makes restoration process-wide rather than
        // a special case implemented by whichever screen happened to open.
        if (cookie != null) ensureSessionScope()
        val session = scope
        val clientVersion = webRemixVersion
        val request: suspend () -> JsonObject = {
            checkIdentity()
            val transport = if (retry) client else coverUploadClient
            transport.post("$musicBaseUrl/$endpoint") {
                contentType(ContentType.Application.Json)
                if (!retry) header("X-Goog-Api-Format-Version", "1")
                parameter("prettyPrint", "false")
                parameter("hl", currentLanguage)
                header("Accept-Language", acceptLanguageHeader)
                query.forEach { (key, value) -> parameter(key, value) }
                header("X-Origin", MUSIC_ORIGIN)
                header("Origin", MUSIC_ORIGIN)
                header("Referer", "$MUSIC_ORIGIN/")
                // Stats pings are only honoured for a session Google recognises
                // as a real client, so identify as one here too — the visitor
                // id is minted on the first call and reused for the session.
                header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_ID)
                header("X-YouTube-Client-Version", clientVersion)
                visitorData?.let { header("X-Goog-Visitor-Id", it) }
                cookie?.let { c ->
                    header("Cookie", c)
                    // Which account in the jar, and which brand channel of it.
                    // Both were fixed at "the first one" before — see
                    // [SessionScope].
                    header("X-Goog-AuthUser", authUserFor(session))
                    pageIdFor(session)?.let { header("X-Goog-PageId", it) }
                    sapisidFrom(c)?.let { header("Authorization", sapisidHash(it)) }
                }
                setBody(
                    buildJsonObject {
                        putJsonObject("context") {
                            putJsonObject("client") {
                                put("clientName", "WEB_REMIX")
                                put("clientVersion", clientVersion)
                                put("hl", currentLanguage)
                                region?.let { put("gl", it) }
                                visitorData?.let { put("visitorData", it) }
                            }
                            putJsonObject("user") {
                                put("lockedSafetyMode", false)
                                // Only ever a value read back from a shell that
                                // said it was signed in: Google answers an
                                // `onBehalfOfUser` it cannot tie to the cookie
                                // with 401, so a guess here would take the
                                // whole app down rather than just history.
                                if (includeOnBehalfOfUser) {
                                    dataSyncIdFor(session)?.let { put("onBehalfOfUser", it) }
                                }
                            }
                            putJsonObject("request") { put("useSsl", true) }
                        }
                        bodyExtras()
                    },
                )
            }.body<JsonObject>()
        }

        val response = if (retry) withRetry(block = request) else request()
        if (visitorData == null) {
            visitorData = response["responseContext"]?.jsonObject
                ?.get("visitorData")?.jsonPrimitive?.content
        }
        return response
    }

    /**
     * Like [postMusic] but deliberately strips the session cookie so YouTube
     * Music does not record the call against any account.
     *
     * Used for typeahead lookups where intermediate keystrokes must remain
     * anonymous — see [searchTypeahead].
     */
    private suspend fun postMusicAnonymous(
        endpoint: String,
        query: Map<String, String> = emptyMap(),
        /** The context's `gl`; null sends none. */
        region: String? = currentRegion,
        bodyExtras: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        val clientVersion = webRemixVersion
        return withRetry {
            client.post("$musicBaseUrl/$endpoint") {
                contentType(ContentType.Application.Json)
                parameter("prettyPrint", "false")
                header("X-Origin", MUSIC_ORIGIN)
                header("Origin", MUSIC_ORIGIN)
                header("Referer", "$MUSIC_ORIGIN/")
                header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_ID)
                header("X-YouTube-Client-Version", clientVersion)
                header("Accept-Language", acceptLanguageHeader)
                parameter("hl", currentLanguage)
                query.forEach { (key, value) -> parameter(key, value) }
                visitorData?.let { header("X-Goog-Visitor-Id", it) }
                // No Cookie / Authorization headers — anonymous request.
                setBody(
                    buildJsonObject {
                        putJsonObject("context") {
                            putJsonObject("client") {
                                put("clientName", "WEB_REMIX")
                                put("clientVersion", clientVersion)
                                put("hl", currentLanguage)
                                region?.let { put("gl", it) }
                                visitorData?.let { put("visitorData", it) }
                            }
                            putJsonObject("user") {
                                put("lockedSafetyMode", false)
                                // No onBehalfOfUser — no account context.
                            }
                            putJsonObject("request") { put("useSsl", true) }
                        }
                        bodyExtras()
                    },
                )
            }.body<JsonObject>()
        }
    }

    /** First string value under [key] anywhere in [element], depth-first. */
    private fun findString(element: JsonElement, key: String): String? = when (element) {
        is JsonObject -> (element[key] as? JsonPrimitive)?.contentOrNull
            ?: element.values.firstNotNullOfOrNull { findString(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findString(it, key) }
        else -> null
    }

    private val YT_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val HTML_TAG = Regex("<[^>]+>")
    private val PLAYLIST_URL_ID = Regex("[?&]list=(PL[A-Za-z0-9_-]+)")

    private val CHANNEL_CREATION_KEYS = setOf(
        "channelCreationDialogRenderer",
        "createCoreIdentityModalRenderer",
        "channelCreationFormEndpoint",
        "channelCreationServiceEndpoint",
        "createCoreIdentityEndpoint",
    )

    private val GATED_OR_POPUP_KEYS = CHANNEL_CREATION_KEYS + setOf(
        "showEngagementPanelEndpoint",
        "modalWithTitleAndButtonRenderer",
        "openPopupAction",
    )

    internal fun sanitizePlaylistTitle(raw: String): String =
        raw.trim()
            .replace('<', '‹')
            .replace('>', '›')
            .ifBlank { "Collaborative Playlist" }

    internal fun sanitizePlaylistDescription(raw: String?): String? =
        raw?.replace(HTML_TAG, "")
            ?.replace('<', '‹')
            ?.replace('>', '›')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Extracts a newly created playlist's raw ID (without `VL`) across all YouTube Music
     * `playlist/create` response shapes:
     * 1. Top-level `playlistId`
     * 2. Toast / command / action `browseEndpoint.playlistId` or `browseEndpoint.browseId` (`VLPL…` / `PL…`)
     * 3. Deep `playlistId`, playlist `browseId`, or `?list=PL…` URL anywhere outside `responseContext`.
     */
    internal fun extractCreatedPlaylistId(response: JsonObject): String? {
        fun normalizePlaylistId(candidate: String?): String? {
            val cleaned = candidate?.trim()?.removePrefix("VL")?.trim().orEmpty()
            if (cleaned.length < 4) return null
            if (cleaned == "LM" || cleaned == "SE") return null
            if (cleaned.startsWith("FEmusic", ignoreCase = true) ||
                cleaned.startsWith("FE", ignoreCase = false) ||
                cleaned.startsWith("UC") ||
                cleaned.startsWith("MP") ||
                cleaned.startsWith("SP")
            ) {
                return null
            }
            if (!cleaned.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null
            return cleaned
        }

        normalizePlaylistId((response["playlistId"] as? JsonPrimitive)?.contentOrNull)?.let { return it }

        fun searchElement(el: JsonElement, parentKey: String? = null): String? = when (el) {
            is JsonObject -> {
                normalizePlaylistId((el["playlistId"] as? JsonPrimitive)?.contentOrNull)?.let { return it }
                val bid = (el["browseId"] as? JsonPrimitive)?.contentOrNull?.trim()
                if (!bid.isNullOrEmpty() && (bid.startsWith("VL") || bid.startsWith("PL") || bid.startsWith("OL"))) {
                    normalizePlaylistId(bid)?.let { return it }
                }
                for ((k, v) in el) {
                    if (k == "responseContext") continue
                    searchElement(v, k)?.let { return it }
                }
                null
            }
            is JsonArray -> el.firstNotNullOfOrNull { searchElement(it, parentKey) }
            is JsonPrimitive -> {
                val s = el.contentOrNull
                if (s != null && s.contains("list=PL")) {
                    PLAYLIST_URL_ID.find(s)?.groupValues?.getOrNull(1)?.let(::normalizePlaylistId)
                } else null
            }
        }

        return searchElement(response)
    }

    internal fun extractCollaborationInviteUrl(element: JsonElement, rawPlaylistId: String): String? {
        val direct = (element as? JsonObject)?.get("collaborationInviteLink")?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: findString(element, "collaborationInviteLink")
        if (!direct.isNullOrBlank()) {
            return if (direct.startsWith("http")) {
                direct.replace("https://www.youtube.com/", "https://music.youtube.com/")
            } else {
                "https://music.youtube.com/playlist?list=$rawPlaylistId&jct=$direct"
            }
        }
        val jct = findString(element, "joinCollaborationToken")
        if (!jct.isNullOrBlank()) {
            return "https://music.youtube.com/playlist?list=$rawPlaylistId&jct=$jct"
        }
        fun findJctUrl(el: JsonElement): String? = when (el) {
            is JsonObject -> el.values.firstNotNullOfOrNull(::findJctUrl)
            is JsonArray -> el.firstNotNullOfOrNull(::findJctUrl)
            is JsonPrimitive -> el.contentOrNull?.takeIf { it.startsWith("http") && it.contains("jct=") }
        }
        return findJctUrl(element)?.replace("https://www.youtube.com/", "https://music.youtube.com/")
    }

    private fun hasAnyKey(element: JsonElement, keys: Set<String>): Boolean = when (element) {
        is JsonObject -> element.keys.any { it in keys } || element.values.any { hasAnyKey(it, keys) }
        is JsonArray -> element.any { hasAnyKey(it, keys) }
        else -> false
    }

    internal fun diagnoseCreatePlaylistFailure(response: JsonObject): String {
        response["error"]?.let { err ->
            val msg = (err as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
            val status = (err as? JsonObject)?.get("status")?.let { (it as? JsonPrimitive)?.contentOrNull }
            if (!msg.isNullOrBlank()) {
                return "YouTube Music refused playlist creation: $msg${status?.let { " ($it)" }.orEmpty()}"
            }
        }
        if (hasAnyKey(response, CHANNEL_CREATION_KEYS) ||
            response.toString().contains("youtubeSignupRequired", ignoreCase = true)
        ) {
            return "CHANNEL_REQUIRED: Your Google account doesn't have a YouTube channel yet. Tap 'Set Up YouTube Channel' below once to finish setting up your profile on YouTube Music."
        }
        val panelTag = findString(response, "tag") ?: findString(response, "panelIdentifier")
        if (panelTag == "PAfeature_enablement") {
            return "YouTube Music temporarily gated playlist creation on this account (PAfeature_enablement). Please wait a moment or verify your YouTube channel in 'Set Up YouTube Channel'."
        }
        if (hasAnyKey(response, setOf("modalWithTitleAndButtonRenderer"))) {
            val modalText = findString(response, "text")
            return "SESSION_EXPIRED: ${modalText ?: "Your YouTube Music session needs to be refreshed. Tap 'Sign in with Google' to reconnect."}"
        }
        return "YouTube Music did not return a playlist ID. Check that your Google account has an active YouTube Music profile and try again."
    }

    /**
     * The API-signing secret out of a cookie header.
     *
     * Three names for one value, and all three have to be looked for. `SAPISID`
     * is the one everybody documents, and it is also the one a cookie jar can
     * be missing: on a third-party-cookie-partitioned or `__Host`-prefixed
     * login, Google sets only the `__Secure-` forms. Any of them signs a
     * request; the digest does not care which it came from.
     *
     * The cost of not looking was invisible and total. `AuthStore.isSignedIn`
     * tests the cookie for the *substring* `SAPISID`, which `__Secure-3PAPISID`
     * satisfies — so the app knew it was signed in, sent the cookie, and sent
     * no `Authorization` header, which Google reads as a request from nobody.
     * Every write and every history ping was silently anonymous for those
     * users, while the UI showed them signed in.
     *
     * Order matters: the plain form first because it is what Google's own
     * origin-scoped hash is documented against, then the third-party form, then
     * the first-party one.
     */
    private fun sapisidFrom(cookieHeader: String): String? {
        val jar = cookieHeader.split(';')
            .mapNotNull { entry ->
                val name = entry.substringBefore('=').trim()
                val value = entry.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
            .toMap()
        return SAPISID_NAMES.firstNotNullOfOrNull { jar[it] }
    }

    private val SAPISID_NAMES =
        listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

    private fun sapisidHash(sapisid: String, origin: String = MUSIC_ORIGIN): String {
        val timestamp = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$timestamp $sapisid $origin".toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        return "SAPISIDHASH ${timestamp}_$digest"
    }
}
