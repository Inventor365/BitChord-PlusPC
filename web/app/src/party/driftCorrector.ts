/**
 * Seek-only party drift correction — the web's port of the apps' `PartyDriftCorrector`
 * (shared/.../listentogether/PartyDriftCorrector.kt), which explains the design.
 *
 * In short: the old 1.2 s dead zone let a player that started a cold stream sit about a second
 * behind for a whole song, because `play()` on time is followed by a silent re-buffer. Here a
 * settled player more than [SEEK_THRESHOLD_MS] out is seeked, aimed ahead by a seek latency it
 * learns from where each seek lands, with strikes, a cooldown and a per-minute cap so a slow
 * stream cannot loop. The YouTube player has no fine tempo control, so there is no nudging.
 */
export const SEEK_THRESHOLD_MS = 180;
export const SETTLE_MS = 500;
export const STRIKES = 2;
export const SEEK_COOLDOWN_MS = 3_000;
export const MAX_SEEKS_PER_MINUTE = 4;
export const INITIAL_SEEK_LEAD_MS = 200;
export const MAX_SEEK_LEAD_MS = 3_000;
const LEAD_LEARNING = 0.8;

export class DriftCorrector {
  seekLeadMs = INITIAL_SEEK_LEAD_MS;
  private settledSince: number | null = null;
  private strikes = 0;
  private cooldownUntil = 0;
  private awaitingSeekResult = false;
  private recentSeeks: number[] = [];

  /** One reading: returns where to seek, or null to leave the player alone. */
  decide(driftMs: number, partyMs: number, nowMs: number, settled: boolean): number | null {
    if (!settled) {
      this.settledSince = null;
      this.strikes = 0;
      return null;
    }
    if (this.settledSince === null) this.settledSince = nowMs;
    if (nowMs - this.settledSince < SETTLE_MS) return null;
    if (this.awaitingSeekResult) {
      this.awaitingSeekResult = false;
      this.seekLeadMs = Math.min(MAX_SEEK_LEAD_MS, Math.max(0, this.seekLeadMs - driftMs * LEAD_LEARNING));
    }
    if (Math.abs(driftMs) <= SEEK_THRESHOLD_MS) {
      this.strikes = 0;
      return null;
    }
    if (nowMs < this.cooldownUntil) return null;
    this.recentSeeks = this.recentSeeks.filter((t) => nowMs - t <= 60_000);
    if (this.recentSeeks.length >= MAX_SEEKS_PER_MINUTE) return null;
    if (++this.strikes < STRIKES) return null;
    this.strikes = 0;
    this.cooldownUntil = nowMs + SEEK_COOLDOWN_MS;
    return this.seekTarget(partyMs, nowMs, false);
  }

  /** A seek made by the caller (a start, a new control): aimed with the lead unless already buffered. */
  seekTarget(partyMs: number, nowMs: number, buffered: boolean): number {
    this.recentSeeks.push(nowMs);
    this.awaitingSeekResult = true;
    this.settledSince = null;
    return buffered ? partyMs : partyMs + this.seekLeadMs;
  }

  reset(): void {
    this.settledSince = null;
    this.strikes = 0;
    this.cooldownUntil = 0;
    this.awaitingSeekResult = false;
  }
}
