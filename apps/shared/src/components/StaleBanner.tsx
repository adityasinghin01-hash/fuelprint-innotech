import type { LiveStatus } from '../live';

/**
 * Shown across the page while the data is not fresh (AGREEMENT section 3: no message for 3 seconds). Screens also
 * disable every button that changes something (isReadOnly(status)).
 */
export function StaleBanner({ status }: { status: LiveStatus }) {
  if (status === 'live') return null;
  const text = status === 'connecting'
    ? 'Connecting to the server... Buttons are off until the first data arrives.'
    : status === 'stale'
      ? 'No fresh data for 3 seconds. You are seeing the last known state; buttons are off until data comes back.'
      : 'The connection to the server is lost. Trying again by itself; buttons are off until it is back.';
  return (
    <div className="fp-banner" role="alert" data-testid="stale-banner">
      {text}
    </div>
  );
}
