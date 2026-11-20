package com.music.bitchord.data.listentogether

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Party sync against a simulated device: the party's playhead runs on real time; the device's
 * advances at its rate, and after a seek goes silent for its re-buffer latency while the party
 * moves on — the thing that used to leave phones about a second behind for good.
 */
class PartyDriftCorrectorTest {

    private class Sim(
        startDriftMs: Long,
        val seekLatencyMs: Long,
        val honoursRate: Boolean = true,
        canNudge: Boolean = true,
        val jitterMs: Int = 0,
    ) {
        val c = PartyDriftCorrector(canNudge)
        var t = 0L
        var pos = startDriftMs.toDouble()
        var rate = 1.0
        var bufferingUntil = 0L
        var seeks = 0
        val rnd = Random(7)

        fun drift(): Long = (pos - t).toLong()

        /** Runs [seconds], ticking the corrector every 700 ms as PartySync does. */
        fun run(seconds: Int) {
            val end = t + seconds * 1000L
            while (t < end) {
                t += 50
                if (t >= bufferingUntil) pos += 50 * (if (honoursRate) rate else 1.0)
                if (t % 700 == 0L) {
                    val settled = t >= bufferingUntil
                    val reading = drift() + if (jitterMs > 0) rnd.nextInt(-jitterMs, jitterMs + 1) else 0
                    when (val a = c.decide(reading, t, t, settled)) {
                        is PartyDriftCorrector.Action.Seek -> {
                            seeks++
                            pos = a.toMs.toDouble()
                            bufferingUntil = t + seekLatencyMs
                            rate = 1.0
                        }
                        is PartyDriftCorrector.Action.Rate -> rate = a.rate
                        PartyDriftCorrector.Action.None -> Unit
                    }
                }
            }
        }
    }

    @Test
    fun aPhoneThatStartedASecondLateOnMobileDataCatchesUp() {
        // Started 900 ms behind, and every seek costs it 900 ms of silence.
        val s = Sim(startDriftMs = -900, seekLatencyMs = 900)
        s.run(30)
        assertTrue(abs(s.drift()) <= 40, "still ${s.drift()} ms out")
        assertTrue(s.seeks <= 3, "${s.seeks} seeks")
        assertTrue(s.c.seekLeadMs in 500L..1300L, "learned lead ${s.c.seekLeadMs}")
    }

    @Test
    fun aSmallGapIsClosedByTempoWithoutAnySeek() {
        val s = Sim(startDriftMs = -300, seekLatencyMs = 900)
        s.run(25)
        assertEquals(0, s.seeks)
        assertTrue(abs(s.drift()) <= 35, "still ${s.drift()} ms out")
        s.run(5)
        assertEquals(1.0, s.rate, "tempo back to normal once in step")
    }

    @Test
    fun runningAheadIsCorrectedToo() {
        val s = Sim(startDriftMs = 1_000, seekLatencyMs = 80)
        s.run(30)
        assertTrue(abs(s.drift()) <= 40, "still ${s.drift()} ms out")
    }

    @Test
    fun anOutputThatIgnoresTempoFallsBackToSeeking() {
        // Bit-perfect USB output: speed changes are accepted and ignored.
        val s = Sim(startDriftMs = -350, seekLatencyMs = 200, honoursRate = false)
        s.run(40)
        assertTrue(abs(s.drift()) <= 60, "still ${s.drift()} ms out")
        assertTrue(s.seeks in 1..4, "${s.seeks} seeks")
    }

    @Test
    fun seekOnlyClientsConvergeWithoutLooping() {
        // The desktop: no tempo nudging, a 700 ms re-buffer on every seek.
        val s = Sim(startDriftMs = -1_000, seekLatencyMs = 700, canNudge = false)
        s.run(40)
        assertTrue(abs(s.drift()) <= PartyDriftCorrector.SEEK_ONLY_THRESHOLD_MS, "still ${s.drift()} ms out")
        assertTrue(s.seeks <= 4, "${s.seeks} seeks")
    }

    @Test
    fun ordinaryJitterInSyncCausesNoSeeksAndNoWobble() {
        val s = Sim(startDriftMs = 0, seekLatencyMs = 900, jitterMs = 20)
        s.run(60)
        assertEquals(0, s.seeks)
        assertEquals(1.0, s.rate)
    }

    @Test
    fun aStallMidSongIsRecovered() {
        val s = Sim(startDriftMs = 0, seekLatencyMs = 600)
        s.run(10)
        s.bufferingUntil = s.t + 1_500 // a 1.5 s network stall
        s.run(30)
        assertTrue(abs(s.drift()) <= 40, "still ${s.drift()} ms out")
        assertTrue(s.seeks <= 3, "${s.seeks} seeks")
    }

    @Test
    fun seeksAreRateLimitedSoAFlakyDeviceCannotLoop() {
        // A device whose seek latency swings wildly: whatever happens, never more than the cap.
        val s = Sim(startDriftMs = -2_000, seekLatencyMs = 2_500)
        s.run(60)
        assertTrue(s.seeks <= PartyDriftCorrector.MAX_SEEKS_PER_MINUTE + 1, "${s.seeks} seeks in a minute")
    }
}
