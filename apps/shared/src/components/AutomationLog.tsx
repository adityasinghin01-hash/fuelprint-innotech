import { useAutomationLog } from '../hooks';
import { clockTime, commandWords } from '../plainWords';
import type { AutomationLogEntry, Uuid } from '../types';
import { Explain } from './Explain';

export interface AutomationLogListProps {
  entries: AutomationLogEntry[];
  /** Show at most this many (newest first). */
  max?: number;
}

/** The list itself (no data loading), newest first, with plain words and time. */
export function AutomationLogList({ entries, max }: AutomationLogListProps) {
  const sorted = [...entries].sort((a, b) => b.received_utc.localeCompare(a.received_utc));
  const shown = max ? sorted.slice(0, max) : sorted;
  if (shown.length === 0) {
    return <p className="fp-explain">The system has not done anything by itself yet.</p>;
  }
  return (
    <ol className="fp-log" data-testid="automation-log">
      {shown.map((entry) => {
        const applied = entry.receipt.status === 'APPLIED';
        return (
          <li key={entry.command_id} className={applied ? undefined : 'fp-refused'}>
            <time dateTime={entry.received_utc}>{clockTime(entry.received_utc)}</time>{' '}
            {applied
              ? <>{commandWords(entry.command_type)}, done automatically.</>
              : <>Not done: {commandWords(entry.command_type).toLowerCase()}. Reason: {entry.receipt.reason_text ?? entry.receipt.reason_code}.</>}
          </li>
        );
      })}
    </ol>
  );
}

/** What the system did by itself (AGREEMENT section 12), newest first, live. Needs a LiveProvider. */
export function AutomationLog({ runId, max = 20, title = 'Done automatically' }: { runId?: Uuid | null; max?: number; title?: string }) {
  const { entries, status } = useAutomationLog(runId, Math.max(max, 20));
  return (
    <section aria-label={title} className="fp-automation-log">
      <h2>{title}</h2>
      <Explain what="Every step the system did by itself, as a normal command with a receipt." why="Switch steps off on the AI and automation page." />
      {status === 'loading' ? <p className="fp-explain">Loading...</p> : null}
      {status === 'error' ? <p className="fp-error">The automation log could not be loaded.</p> : null}
      {status === 'ready' ? <AutomationLogList entries={entries} max={max} /> : null}
    </section>
  );
}
