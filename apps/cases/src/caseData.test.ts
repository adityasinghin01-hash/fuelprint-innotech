import { describe, expect, it } from 'vitest';
import type { CaseRecord, Snapshot } from '@fp/shared';
import { buildTimeline, caseHref, displayNumber } from './caseData';

const record = { id: 'case-1', run_id: 'run-1', revision: 1, created_utc: '2026-10-20T10:00:00Z', created_by: 'case_reviewer', investigation_id: 'inv-1', screening_record_ids: [], purpose: 'Synthetic investigation', state: 'SPECIMEN' } as CaseRecord;
const base = { run_id: 'run-1', revision: 1, created_by: 'inspector_demo' };

describe('case record presentation', () => {
  it('keeps null unknown and preserves a real zero', () => {
    expect(displayNumber(null, 'L')).toBe('Unknown');
    expect(displayNumber(0, 'L', 3)).toBe('0.000 L');
  });
  it('pins links to the case run so replay rotation does not replace its evidence', () => {
    expect(caseHref(record, 'vault')).toBe('/case-1/vault?run=run-1');
  });
  it('orders custody by event time and retains a correction beside the original', () => {
    const specimen = { ...base, id: 'specimen-1', case_id: 'case-1', created_utc: '2026-10-20T10:01:00Z' };
    const first = { ...base, id: 'handover-1', specimen_id: 'specimen-1', created_utc: '2026-10-20T10:06:00Z', event_time_utc: '2026-10-20T10:02:00Z', from_holder: 'inspector', to_holder: 'courier', seal_state: 'INTACT', exceptions: [] };
    const later = { ...first, id: 'handover-2', created_utc: '2026-10-20T10:04:00Z', event_time_utc: '2026-10-20T10:04:00Z', from_holder: 'courier', to_holder: 'lab' };
    const correction = { ...first, id: 'correction-1', created_utc: '2026-10-20T10:10:00Z', event_time_utc: '2026-10-20T10:10:00Z', corrects_event_id: 'handover-1', exceptions: ['CORRECTION'] };
    const snapshot = { records: { specimen: [specimen], custody_event: [later, correction, first] } } as unknown as Snapshot;
    const entries = buildTimeline(snapshot, record);
    expect(entries.map((entry) => entry.id)).toEqual(['case-1', 'specimen-1', 'handover-1', 'correction-1', 'handover-2']);
    expect(snapshot.records.custody_event?.[2].to_holder).toBe('courier');
  });
  it('marks automatic steps and excludes records from another case', () => {
    const snapshot = { records: { specimen: [{ ...base, id: 'foreign', case_id: 'other', created_utc: record.created_utc }], finding: [{ ...base, id: 'finding-1', case_id: record.id, created_by: 'automation', created_utc: '2026-10-20T10:02:00Z' }] } } as unknown as Snapshot;
    const entries = buildTimeline(snapshot, record);
    expect(entries.map((entry) => entry.id)).not.toContain('foreign');
    expect(entries.find((entry) => entry.id === 'finding-1')?.automatic).toBe(true);
  });
});
