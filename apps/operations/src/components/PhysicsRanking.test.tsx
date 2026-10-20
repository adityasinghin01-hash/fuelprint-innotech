import { render, screen, within, cleanup } from '@testing-library/react';
import type { AnalysisRecord } from '@fp/shared';
import { afterEach, describe, expect, it } from 'vitest';
import { PhysicsRanking } from './PhysicsRanking';

const analysis: AnalysisRecord = {
  id: 'analysis-1', run_id: 'run-1', revision: 1, created_utc: '2026-10-20T10:00:00Z', created_by: 'automation',
  input_window_ids: ['window-1'], outcome: 'AMBIGUOUS_OR_OPTIONAL_MIXTURE', qualification: 'SYNTHETIC_SCOPE_ONLY',
  candidates: [
    { explanation_id: 'S02', sources: ['S02'], fit: 2, status: 'SURVIVES', reason: null },
    { explanation_id: 'S01', sources: ['S01'], fit: 1, status: 'SURVIVES', reason: null },
    { explanation_id: 'UNKNOWN', sources: [], fit: null, status: 'SURVIVES', reason: 'Unregistered source remains possible' },
    { explanation_id: 'S03', sources: ['S03'], fit: .01, status: 'REJECTED', reason: 'Rejected by physics' },
    { explanation_id: 'BACKGROUND', sources: [], fit: .1, status: 'SURVIVES', reason: 'Background remains possible' },
  ], exclusions: [{ reading_window: 'bad-window', reason: 'Invalid flow' }], limitations: ['Synthetic scope only'],
  explanation_text: 'Several explanations fit', algorithm_version: 'FP-COMPAT-3/4', learned_order: [],
  learned_status: 'MODEL_MISSING', learned_model_version_id: null, history_counts: null,
};
afterEach(cleanup);
describe('honest physics presentation', () => {
  it('labels the rules and synthetic scope and keeps model unavailable', () => {
    render(<PhysicsRanking analysis={analysis} />);
    expect(screen.getByRole('heading', { name: /Physics fit order/ })).toBeTruthy();
    expect(screen.getByText(/rules-based demo/)).toBeTruthy();
    expect(screen.getByText(/Synthetic teaching data/)).toBeTruthy();
    expect(screen.getByText(/MODEL_MISSING/)).toBeTruthy();
    expect(screen.getByText(/trained ranker unavailable/i)).toBeTruthy();
    expect(document.body.textContent).not.toMatch(/%|probability|chance|accuracy/i);
  });
  it('displays server sequence unchanged even if fit values would suggest a different order', () => {
    render(<PhysicsRanking analysis={analysis} />);
    const rows = within(screen.getByRole('list', { name: 'Registered survivors in server order' })).getAllByRole('listitem');
    expect(rows.map(r => r.getAttribute('data-explanation'))).toEqual(['S02', 'S01']);
    expect(rows[0].textContent).toContain('2');
  });
  it('keeps UNKNOWN, background and rejected rows visible with saved reasons', () => {
    render(<PhysicsRanking analysis={analysis} />);
    expect(screen.getByText('UNKNOWN')).toBeTruthy();
    expect(screen.getByText('Unregistered source remains possible')).toBeTruthy();
    const other = screen.getByRole('list', { name: 'Other saved explanations' });
    expect(other.textContent).toContain('S03'); expect(other.textContent).toContain('REJECTED');
    expect(other.textContent).toContain('Rejected by physics'); expect(other.textContent).toContain('BACKGROUND');
    expect(screen.getByText(/Invalid flow/)).toBeTruthy(); expect(screen.getByText('Synthetic scope only')).toBeTruthy();
  });
  it.each(['INVALID', 'NO_SIGNAL'] as const)('does not rank %s', outcome => {
    render(<PhysicsRanking analysis={{ ...analysis, outcome, learned_status: 'NOT_APPLICABLE', candidates: [analysis.candidates[2]] }} />);
    expect(screen.queryByRole('list', { name: 'Registered survivors in server order' })).toBeNull();
    expect(screen.getByText(/No registered survivors to order/)).toBeTruthy();
    expect(screen.getByText('UNKNOWN')).toBeTruthy(); expect(screen.getByText(new RegExp(outcome))).toBeTruthy();
  });
  it('shows missing fit as unknown rather than zero', () => {
    render(<PhysicsRanking analysis={{ ...analysis, candidates: [{ ...analysis.candidates[0], fit: null }, analysis.candidates[2]] }} />);
    expect(screen.getByRole('list', { name: 'Registered survivors in server order' }).textContent).toContain('unknown');
  });
  it('preserves a failed model status and saved explanation', () => {
    render(<PhysicsRanking analysis={{ ...analysis, learned_status: 'FAILED' }} />);
    expect(screen.getByText(/FAILED/)).toBeTruthy(); expect(screen.getByText('Several explanations fit')).toBeTruthy();
  });
});
