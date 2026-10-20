import { describe, it, expect } from 'vitest';
import type { TickMessage } from '@fp/shared';
import { selectFrame } from './liveFrame';
export const tick: TickMessage = { schema_version: 'fp/1', message_type: 'tick', run_id: 'run-1', server_epoch: 'epoch-1',
  cursor: 3, sim_time_s: 12, server_time_utc: '2026-10-20T10:00:00Z',
  payload: { wind: { from_deg: 270, speed_m_s: 3 }, time_scale: 1, vehicles: [], latest_readings: [] } };
describe('same-run observed frames', () => {
  it('accepts only a matching epoch/run frame', () => {
    expect(selectFrame(tick, 'run-1', 'epoch-1', 'RUNNING', null)).toBe(tick);
    expect(selectFrame(tick, 'run-2', 'epoch-1', 'RUNNING', null)).toBeNull();
    expect(selectFrame(tick, 'run-1', 'epoch-2', 'RUNNING', null)).toBeNull();
  });
  it('refuses time reversal and future motion during pause', () => {
    expect(selectFrame({ ...tick, sim_time_s: 11 }, 'run-1', 'epoch-1', 'RUNNING', tick)).toBeNull();
    expect(selectFrame({ ...tick, sim_time_s: 13 }, 'run-1', 'epoch-1', 'PAUSED', tick)).toBeNull();
    expect(selectFrame(tick, 'run-1', 'epoch-1', 'PAUSED', tick)).toBe(tick);
  });
  it('requires a running or paused run, finite time and valid observed coordinates', () => {
    for (const state of ['COMPLETED', 'ABORTED', 'CREATED'] as const)
      expect(selectFrame(tick, 'run-1', 'epoch-1', state, null)).toBeNull();
    expect(selectFrame({ ...tick, sim_time_s: NaN }, 'run-1', 'epoch-1', 'RUNNING', null)).toBeNull();
  });
});
