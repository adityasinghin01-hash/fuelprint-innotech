import type { ReactNode } from 'react';

export interface ExplainProps {
  /** What it is, in one short plain line. */
  what: ReactNode;
  /** What it is for (optional second half of the line). */
  why?: ReactNode;
  id?: string;
}

/**
 * The small "what it is and what it's for" line every number, dot, badge and control gets (AGENTS.md screen
 * rules). Give the control aria-describedby={id} so screen readers read it too.
 */
export function Explain({ what, why, id }: ExplainProps) {
  return (
    <small className="fp-explain" id={id}>
      {what}
      {why ? <> {why}</> : null}
    </small>
  );
}
