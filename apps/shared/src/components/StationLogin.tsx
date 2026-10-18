import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';

import { getJson, HttpError } from '../http';
import type { SessionInfo, StationInfo } from '../types';
import { Button } from './Button';
import { Explain } from './Explain';

export interface StationLoginProps {
  /** Called after a good PIN. useSession().login is a good choice. */
  login: (stationId: string, pin: string) => Promise<SessionInfo>;
  onLoggedIn?: (session: SessionInfo) => void;
}

/** The laptop login form: pick the station, type its PIN (POST /api/session/station). */
export function StationLogin({ login, onLoggedIn }: StationLoginProps) {
  const [stations, setStations] = useState<StationInfo[]>([]);
  const [stationId, setStationId] = useState('');
  const [pin, setPin] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getJson<StationInfo[]>('/api/session/stations')
      .then((list) => {
        setStations(list);
        if (list.length > 0) setStationId((current) => current || list[0].station_id);
      })
      .catch(() => setError('The station list could not be loaded. Is the server running?'));
  }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      const session = await login(stationId, pin);
      setPin('');
      onLoggedIn?.(session);
    } catch (e) {
      setError(e instanceof HttpError ? e.reasonText : 'No connection to the server.');
    } finally {
      setPending(false);
    }
  }

  return (
    <form className="fp-form" onSubmit={submit} aria-label="Station login">
      <label htmlFor="fp-station">Station</label>
      <select id="fp-station" className="fp-input" value={stationId} onChange={(e) => setStationId(e.target.value)}
        aria-describedby="fp-station-explain">
        {stations.map((s) => (
          <option key={s.station_id} value={s.station_id}>{s.name} ({s.roles.join(', ')})</option>
        ))}
      </select>
      <Explain id="fp-station-explain" what="Which laptop this is." why="Each laptop has its own roles, set by the presenter." />
      <label htmlFor="fp-pin">PIN</label>
      <input id="fp-pin" className="fp-input" type="password" inputMode="numeric" autoComplete="off" value={pin}
        onChange={(e) => setPin(e.target.value)} aria-describedby="fp-pin-explain" required />
      <Explain id="fp-pin-explain" what="The station PIN from the team." why="It keeps visitors from acting as staff." />
      {error ? <p className="fp-error" role="alert">{error}</p> : null}
      <Button type="submit" pending={pending} disabled={!stationId || !pin}>Sign in</Button>
    </form>
  );
}
