import { createContext, useContext } from 'react';
import { Explain, useCommand } from '@fp/shared';
import type { SessionState, SnapshotState, LiveState, Role } from '@fp/shared';

export interface OperationsState {
  snapshot: SnapshotState;
  session: SessionState;
  live: LiveState;
  /** Advances on finding/run events, including events from other runs. */
  reportRevision?: number;
}
export const OperationsContext = createContext<OperationsState | null>(null);
export function useOperations() {
  const value = useContext(OperationsContext);
  if (!value) throw new Error('Operations pages need the Operations frame.');
  return value;
}
export function useOperationsCommand(role: Role, runControl = false) {
  const { snapshot, session, live } = useOperations();
  const command = useCommand();
  const run = snapshot.snapshot?.run;
  const allowed = session.status === 'ready' && session.session?.signed_in === true && session.can(role);
  const disabled = !allowed || command.pending || live.status !== 'live' || snapshot.status !== 'ready'
    || (!runControl && (!run || run.state_reason === 'INTERRUPTED' || run.state === 'COMPLETED' || run.state === 'ABORTED'));
  async function send(type: string, payload: object, revision: number | null = null) {
    if (disabled) return;
    const receipt = await command.send(type, payload, revision, { runId: run?.id ?? null });
    if (receipt.status === 'APPLIED' || receipt.reason_code === 'STALE_REVISION') snapshot.reload();
  }
  return { ...command, send, allowed, disabled, role };
}
export type OperationsAction = ReturnType<typeof useOperationsCommand>;
export function CommandFeedback({ action }: { action: OperationsAction }) {
  return <div aria-live="polite">
    {!action.allowed ? <p>Not allowed: sign in with the {action.role} role to use these controls.</p> : null}
    {action.pending ? <p role="status">Command pending. Waiting for the server receipt.</p> : null}
    {action.receipt ? <p role={action.problem ? 'alert' : 'status'}>
      {action.receipt.status}: {action.problem ?? 'The server saved this command.'}
    </p> : null}
    <Explain what="Controls send a command to the server. They are read-only while stale, offline or interrupted." />
  </div>;
}
export function PageState() {
  const { snapshot } = useOperations();
  if (snapshot.status === 'loading') return <p role="status">Loading records from the server…</p>;
  if (snapshot.status === 'error') return <p role="alert">Could not load records: {snapshot.error}</p>;
  return null;
}
