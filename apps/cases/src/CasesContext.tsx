import { createContext, useContext } from 'react';
import type { ReactNode } from 'react';
import { useLive, useSession, useSnapshot } from '@fp/shared';
import type { SessionState, SnapshotState } from '@fp/shared';
import { useSearchParams } from 'react-router-dom';

interface CasesContextValue { data: SnapshotState; session: SessionState; readOnly: boolean; readOnlyReason: string | null }
const CasesContext = createContext<CasesContextValue | null>(null);

export function CasesProvider({ children }: { children: ReactNode }) {
  const [params] = useSearchParams();
  const data = useSnapshot(params.get('run'));
  const session = useSession();
  const live = useLive();
  const readOnlyReason = live.status !== 'live' || data.status !== 'ready'
    ? 'The connection is not fresh. Records are read-only until live data returns.'
    : live.mode !== 'live' || data.snapshot?.mode !== 'live'
      ? 'Replay is read-only. These recorded scenarios cannot be changed.'
      : !data.snapshot.run || data.snapshot.run.state_reason === 'INTERRUPTED'
        ? 'There is no active, uninterrupted run. These records are read-only.'
        : live.tick && (live.tick.server_epoch !== data.snapshot.server_epoch || live.tick.run_id !== data.snapshot.run_id)
          ? 'This case belongs to a different run. Its records remain read-only.' : null;
  return <CasesContext.Provider value={{ data, session, readOnly: Boolean(readOnlyReason), readOnlyReason }}>
    {children}
  </CasesContext.Provider>;
}

export function useCases() {
  const value = useContext(CasesContext);
  if (!value) throw new Error('CasesProvider is required.');
  return value;
}
