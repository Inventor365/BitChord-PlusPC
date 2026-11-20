package com.music.bitchord.data.listentogether

import kotlin.math.abs

/**
 * Keeps one device's playhead on the party's, closely enough that nobody hears an echo.
 *
 * ## Why parties sat a second apart
 *
 * The old rule seeked only past 1.2 s of drift, on the idea that smaller gaps were decoder jitter.
 * They weren't: the steady offset people heard came from *starting*. A device that is told to play
 * a track it has not buffered yet (a track change, a resume on mobile data) calls `play()` on time
 * and is silent while the stream loads — often most of a second — then plays from where it was
 * told, that much behind everyone. Under 1.2 s, that was never corrected. Its own corrective seeks
 * did the same thing: every seek re-buffers, so it landed behind by the re-buffer.
 *
 * ## What this does instead
 *
 * - **Measures only a settled player.** A reading counts once the player has been playing (not
 *   buffering) for [SETTLE_MS]; before that its position is where it will be, not where it is.
 * - **Small gaps are closed by tempo, not seeks.** Where the player can stretch time ([canNudge]),
 *   a gap up to [seekThresholdMs] is closed by playing up to [MAX_NUDGE] faster or slower — pitch
 *   kept, inaudible — easing off as the gap closes, and stopping inside [DEADBAND_MS].
 * - **Big gaps are seeked, ahead of the party by this device's own seek latency.** A seek lands
 *   at `target + seekLead` so the device arrives on time after its re-buffer. The lead is learned:
 *   the first settled reading after each seek says how far off it landed, and the lead moves by
 *   most of that. A slow device converges in a seek or two instead of looping.
 * - **Safety rails.** A seek needs [STRIKES] readings in a row, waits out a cooldown, and at most
 *   [MAX_SEEKS_PER_MINUTE] happen in a minute. Tempo that is not closing the gap (an output that
 *   ignores speed, like bit-perfect USB) escalates to a seek after [NUDGE_PATIENCE_MS].
 *
 * Pure: the caller feeds it readings and applies what it returns, so the rules are tested without
 * a player or a network (see PartyDriftCorrectorTest).
 */
class PartyDriftCorrector(
    /** The output can play slightly faster or slower without changing pitch. */
    private val canNudge: Boolean,
) {
    /** What to do with the player now. */
    sealed interface Action {
        data object None : Action

        /** Play at this multiple of normal speed (1.0 to stop nudging). */
        data class Rate(val rate: Double) : Action

        /** Seek to this position (the party's playhead plus this device's learned seek lead). */
        data class Seek(val toMs: Long) : Action
    }

    /** How far ahead of the party a seek is aimed, to land on time after re-buffering. */
    var seekLeadMs: Long = INITIAL_SEEK_LEAD_MS
        private set

    /** Gaps above this are seeked rather than closed by tempo. */
    val seekThresholdMs: Long = if (canNudge) NUDGE_SEEK_THRESHOLD_MS else SEEK_ONLY_THRESHOLD_MS

    private var settledSinceMs: Long? = null
    private var strikes = 0
    private var cooldownUntilMs = 0L
    private var awaitingSeekResult = false
    private val recentSeeks = ArrayDeque<Long>()
    private var rate = 1.0
    private var nudgeStartedMs: Long? = null
    private var nudgeStartDrift = 0L

    /** The rate last asked for; 1.0 when not nudging. */
    val currentRate: Double get() = rate

    /**
     * One reading. [driftMs] is this device's playhead minus the party's (positive: ahead);
     * [partyMs] the party's playhead now; [settled] whether the player is ready and playing.
     */
    fun decide(driftMs: Long, partyMs: Long, nowMs: Long, settled: Boolean): Action {
        if (!settled) {
            settledSinceMs = null
            strikes = 0
            return stopNudging()
        }
        val since = settledSinceMs ?: nowMs.also { settledSinceMs = it }
        if (nowMs - since < SETTLE_MS) return Action.None

        if (awaitingSeekResult) {
            awaitingSeekResult = false
            // Landed `driftMs` off: aim that much further (or less far) next time.
            seekLeadMs = (seekLeadMs - (driftMs * LEAD_LEARNING).toLong()).coerceIn(0L, MAX_SEEK_LEAD_MS)
        }

        val gap = abs(driftMs)
        if (gap > seekThresholdMs || nudgeStalled(driftMs, nowMs)) {
            if (nowMs < cooldownUntilMs) return Action.None
            while (recentSeeks.isNotEmpty() && nowMs - recentSeeks.first() > 60_000L) recentSeeks.removeFirst()
            if (recentSeeks.size >= MAX_SEEKS_PER_MINUTE) return nudgeTowards(driftMs, nowMs)
            if (++strikes < STRIKES) return Action.None
            return seek(partyMs, nowMs)
        }
        strikes = 0
        if (gap <= DEADBAND_MS) return stopNudging()
        return nudgeTowards(driftMs, nowMs)
    }

    /**
     * A seek the caller makes on its own (a fresh start, a new control): aimed with the lead, and
     * its landing measured like any other.
     */
    fun seekTarget(partyMs: Long, nowMs: Long, buffered: Boolean): Long {
        noteSeek(nowMs)
        // A seek inside what is already buffered lands at once; only a cold one needs the lead.
        return if (buffered) partyMs else partyMs + seekLeadMs
    }

    /** Leaving the party, or the player handed over: start clean, but keep what was learned. */
    fun reset() {
        settledSinceMs = null
        strikes = 0
        cooldownUntilMs = 0L
        awaitingSeekResult = false
        rate = 1.0
        nudgeStartedMs = null
    }

    private fun seek(partyMs: Long, nowMs: Long): Action {
        strikes = 0
        val to = partyMs + seekLeadMs
        noteSeek(nowMs)
        cooldownUntilMs = nowMs + SEEK_COOLDOWN_MS
        return Action.Seek(to)
    }

    private fun noteSeek(nowMs: Long) {
        recentSeeks.addLast(nowMs)
        awaitingSeekResult = true
        settledSinceMs = null
        rate = 1.0
        nudgeStartedMs = null
    }

    private fun nudgeTowards(driftMs: Long, nowMs: Long): Action {
        if (!canNudge) return Action.None
        val wanted = 1.0 - (driftMs.toDouble() / NUDGE_HORIZON_MS).coerceIn(-MAX_NUDGE, MAX_NUDGE)
        if (nudgeStartedMs == null) {
            nudgeStartedMs = nowMs
            nudgeStartDrift = driftMs
        }
        // Only changed in visible steps: every rate change costs the stretcher a little.
        if (abs(wanted - rate) < RATE_STEP) return Action.None
        rate = wanted
        return Action.Rate(rate)
    }

    private fun stopNudging(): Action {
        nudgeStartedMs = null
        if (rate == 1.0) return Action.None
        rate = 1.0
        return Action.Rate(1.0)
    }

    /** Tempo has been asked to close this gap for a while and it has not shrunk: the output ignores it. */
    private fun nudgeStalled(driftMs: Long, nowMs: Long): Boolean {
        val started = nudgeStartedMs ?: return false
        if (nowMs - started < NUDGE_PATIENCE_MS) return false
        val closed = abs(nudgeStartDrift) - abs(driftMs)
        return closed < abs(nudgeStartDrift) * 0.25 && abs(driftMs) > DEADBAND_MS * 3
    }

    companion object {
        /** Inside this, two devices sound as one: nothing is done. */
        const val DEADBAND_MS = 30L

        /** A player has to be playing this long before its position is believed. */
        const val SETTLE_MS = 500L

        /** With tempo available, gaps up to this are closed by it. */
        const val NUDGE_SEEK_THRESHOLD_MS = 450L

        /** Without tempo, gaps above this are seeked. */
        const val SEEK_ONLY_THRESHOLD_MS = 180L

        /** The largest tempo change used: 3.5%, pitch-preserved, not something anyone hears. */
        const val MAX_NUDGE = 0.035

        /** A gap is closed over roughly this long (until [MAX_NUDGE] caps it). */
        const val NUDGE_HORIZON_MS = 6_000.0

        const val RATE_STEP = 0.002
        const val NUDGE_PATIENCE_MS = 8_000L
        const val STRIKES = 2
        const val SEEK_COOLDOWN_MS = 3_000L
        const val MAX_SEEKS_PER_MINUTE = 4
        const val INITIAL_SEEK_LEAD_MS = 200L
        const val MAX_SEEK_LEAD_MS = 3_000L
        const val LEAD_LEARNING = 0.8
    }
}
