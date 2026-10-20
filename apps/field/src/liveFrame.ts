import type { RunState, TickMessage } from '@fp/shared';

/** Validates public frame identity/time; never advances a pose or reading. */
export function selectFrame(tick: TickMessage | null, run: string | null, epoch: string | null,
  state: RunState | null, previous: TickMessage | null): TickMessage | null {
  if (!tick || !run || !epoch || tick.run_id !== run || tick.server_epoch !== epoch
    || (state !== 'RUNNING' && state !== 'PAUSED') || tick.sim_time_s === null || !Number.isFinite(tick.sim_time_s)) return null;
  const prior = previous?.run_id === run && previous.server_epoch === epoch ? previous : null;
  if (prior && prior.sim_time_s !== null && (tick.sim_time_s < prior.sim_time_s || (state === 'PAUSED' && tick.sim_time_s > prior.sim_time_s))) return null;
  return tick;
}
