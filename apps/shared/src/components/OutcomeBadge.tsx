import { ANALYSIS_OUTCOME_WORDS, FINDING_OUTCOME_WORDS } from '../plainWords';
import type { AnalysisOutcome, FindingOutcome } from '../types';
import { Explain } from './Explain';

/**
 * An analysis or finding outcome with its plain meaning. All outcomes get the same visual weight: rejected and
 * inconclusive are proper results, not errors. Never shows a chance or a percentage.
 */
export function OutcomeBadge({ outcome, explain = true }: { outcome: AnalysisOutcome | FindingOutcome | null; explain?: boolean }) {
  if (!outcome) {
    return <span className="fp-badge" data-outcome="none">No outcome yet</span>;
  }
  const words = (ANALYSIS_OUTCOME_WORDS as Record<string, { label: string; meaning: string }>)[outcome]
    ?? (FINDING_OUTCOME_WORDS as Record<string, { label: string; meaning: string }>)[outcome]
    ?? { label: outcome, meaning: 'An outcome this screen does not know yet.' };
  return (
    <span className="fp-outcome">
      <span className="fp-badge" data-outcome={outcome}>{words.label}</span>
      {explain ? <Explain what={words.meaning} /> : null}
    </span>
  );
}
