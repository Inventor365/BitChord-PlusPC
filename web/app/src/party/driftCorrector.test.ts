import { describe, expect, it } from 'vitest';
import { DriftCorrector, MAX_SEEKS_PER_MINUTE, SEEK_THRESHOLD_MS } from './driftCorrector';

/** A player whose seeks go silent for their re-buffer time while the party moves on. */
function simulate(startDriftMs: number, seekLatencyMs: number, seconds: number) {
  const c = new DriftCorrector();
  let t = 0;
  let pos = startDriftMs;
  let bufferingUntil = 0;
  let seeks = 0;
  while (t < seconds * 1000) {
    t += 50;
    if (t >= bufferingUntil) pos += 50;
    if (t % 700 === 0) {
      const to = c.decide(pos - t, t, t, t >= bufferingUntil);
      if (to !== null) {
        seeks++;
        pos = to;
        bufferingUntil = t + seekLatencyMs;
      }
    }
  }
  return { drift: pos - t, seeks, lead: c.seekLeadMs };
}

describe('party drift correction', () => {
  it('catches up a player that started a second late on a slow stream', () => {
    const r = simulate(-1_000, 800, 40);
    expect(Math.abs(r.drift)).toBeLessThanOrEqual(SEEK_THRESHOLD_MS);
    expect(r.seeks).toBeLessThanOrEqual(4);
  });

  it('leaves a player in step alone', () => {
    expect(simulate(40, 800, 60).seeks).toBe(0);
  });

  it('never seeks more than the cap in a minute', () => {
    expect(simulate(-3_000, 2_800, 60).seeks).toBeLessThanOrEqual(MAX_SEEKS_PER_MINUTE + 1);
  });
});
