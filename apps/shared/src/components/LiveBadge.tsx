import type { LiveStatus } from '../live';
import { Explain } from './Explain';

const WORDS: Record<LiveStatus, { label: string; meaning: string }> = {
  connecting: { label: 'Connecting', meaning: 'Waiting for the first message from the server.' },
  live: { label: 'Live', meaning: 'Fresh data from the server in the last 3 seconds.' },
  stale: { label: 'Stale', meaning: 'No data for 3 seconds: showing the last known state, buttons are off.' },
  offline: { label: 'Offline', meaning: 'No connection to the server; trying again by itself.' },
};

/** The connection badge: live, stale, offline or connecting, with its meaning. */
export function LiveBadge({ status, explain = true }: { status: LiveStatus; explain?: boolean }) {
  const words = WORDS[status];
  return (
    <span className="fp-live-badge">
      <span className="fp-badge" data-status={status} role="status" aria-live="polite" data-testid="live-badge">
        <span aria-hidden="true">{status === 'live' ? '●' : '○'}</span> {words.label}
      </span>
      {explain ? <Explain what={words.meaning} /> : null}
    </span>
  );
}
