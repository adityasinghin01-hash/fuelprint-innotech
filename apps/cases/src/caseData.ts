import type { AnyRecord, CaseRecord, CustodyEventRecord, RecordBase, Snapshot } from '@fp/shared';

export const stages = [
  { key: 'intake', title: 'Air screening', detail: 'Keep every channel and its limits.', state: 'INTAKE' },
  { key: 'specimens/new', title: 'Inspector specimen', detail: 'A separate item. Its own identity.', state: 'SPECIMEN' },
  { key: 'custody', title: 'Chain of custody', detail: 'Every handover, kept in sequence.', state: 'SPECIMEN' },
  { key: 'lab', title: 'Laboratory', detail: 'Check the result and its quality.', state: 'LAB' },
  { key: 'finding', title: 'Finding', detail: 'Say what the evidence supports.', state: 'FINDING' },
  { key: 'vault', title: 'Evidence vault', detail: 'Seal the bytes. Check each limit.', state: 'SEALED' },
] as const;

export const caseStages = [...stages,
  { key: 'aerosol', title: 'Aerosol filter' },
  { key: 'replay', title: 'Case replay' },
];

export const axisDescriptions: Record<string, { title: string; meaning: string }> = {
  integrity: { title: 'Byte integrity', meaning: 'Do the stored bytes match the manifest?' },
  key_trust: { title: 'Key trust', meaning: 'Is the signing key independently trusted?' },
  lineage: { title: 'Record lineage', meaning: 'Are the cited records included in the pack?' },
  trusted_time: { title: 'Trusted time', meaning: 'Is the timestamp independently verified?' },
  custody_documentation: { title: 'Custody record', meaning: 'Are handovers documented without exceptions?' },
  measurement_qualification: { title: 'Measurement scope', meaning: 'What kind of measurement is this qualified for?' },
  legal_suitability: { title: 'Legal suitability', meaning: 'Has suitability for legal use been assessed?' },
};

export function words(value: string | null | undefined) {
  if (!value) return 'Unknown';
  const known: Record<string, string> = {
    SUPPORTED_WITHIN_SCOPE: 'Supported within scope', REJECTED_FOR_USE: 'Rejected for use',
    INCONCLUSIVE: 'Inconclusive', NOT_ASSESSED: 'Not assessed', NOT_SIGNED: 'Not signed',
    SYNTHETIC_SCOPE_ONLY: 'Synthetic scope only', PROVISIONAL_SINGLE: 'One explanation within this model',
    AMBIGUOUS_OR_OPTIONAL_MIXTURE: 'More than one explanation fits', FLOW_FAULT: 'Flow fault',
    BELOW_LOQ: 'Below the reporting limit', UNKNOWN_OR_MISMATCH: 'Source unresolved',
  };
  return known[value] ?? value.toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());
}

export const shortId = (id: string) => id.slice(0, 8).toUpperCase();
export const displayNumber = (value: number | null | undefined, unit = '', digits = 1) =>
  value == null ? 'Unknown' : `${value.toLocaleString('en-IN', { minimumFractionDigits: digits, maximumFractionDigits: digits })}${unit ? ` ${unit}` : ''}`;
export const timeLabel = (value: string) => {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'Time unavailable' : date.toLocaleString('en-IN', {
    timeZone: 'Asia/Kolkata', day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit',
  });
};

export function caseHref(record: Pick<CaseRecord, 'id' | 'run_id'>, stage = 'intake') {
  return `/${record.id}/${stage}${record.run_id ? `?run=${encodeURIComponent(record.run_id)}` : ''}`;
}

export interface TimelineEntry { id: string; revision: number; at: string; title: string; actor: string; automatic: boolean; detail: string; correctionOf?: string }

/** Public record metadata only. Corrections stay beside the original; no history is overwritten. */
export function buildTimeline(snapshot: Snapshot | null, caseRecord: CaseRecord, history: { type: string; record: RecordBase }[] = []): TimelineEntry[] {
  if (!snapshot) return [];
  const records = snapshot.records;
  const specimens = records.specimen?.filter((s) => s.case_id === caseRecord.id) ?? [];
  const specimenIds = new Set(specimens.map((s) => s.id));
  const media = records.media?.filter(item => item.case_id === caseRecord.id) ?? [];
  const mediaIds = new Set(media.map(item => item.id));
  const exports = records.export?.filter((e) => e.case_id === caseRecord.id) ?? [];
  const exportIds = new Set(exports.map((e) => e.id));
  const selected: [string, RecordBase][] = [
    ['Case record', caseRecord],
    ...(records.screening_record?.filter((s) => caseRecord.screening_record_ids.includes(s.id)).map((s) => ['Air screening', s] as [string, RecordBase]) ?? []),
    ...specimens.map((s) => ['Specimen registered', s] as [string, RecordBase]),
    ...media.map(item => ['Aerosol filter recorded', item] as [string, RecordBase]),
    ...(records.media_custody_event?.filter(item => mediaIds.has(item.media_id)).map(item => ['Aerosol custody', item] as [string, RecordBase]) ?? []),
    ...(records.media_lab_result?.filter(item => mediaIds.has(item.media_id)).map(item => ['Aerosol lab result', item] as [string, RecordBase]) ?? []),
    ...(records.custody_event?.filter((e) => specimenIds.has(e.specimen_id)).map((e) => ['Custody handover', e] as [string, RecordBase]) ?? []),
    ...(records.lab_result?.filter((r) => specimenIds.has(r.specimen_id)).map((r) => ['Lab result recorded', r] as [string, RecordBase]) ?? []),
    ...(records.finding?.filter((f) => f.case_id === caseRecord.id).map((f) => ['Finding recorded', f] as [string, RecordBase]) ?? []),
    ...exports.map((e) => ['Evidence sealed', e] as [string, RecordBase]),
    ...(records.verification_report?.filter((r) => exportIds.has(r.export_id)).map((r) => ['Evidence checked', r] as [string, RecordBase]) ?? []),
  ];
  for (const item of history) selected.push([item.type === 'case' ? `Case ${words((item.record as CaseRecord).state).toLowerCase()}` : `${words(item.type)} revision`, item.record]);
  const unique = [...new Map(selected.map((item) => [`${item[1].id}/${item[1].revision}`, item])).values()];
  const items = unique.map(([title, record]) => {
    const custody = record as CustodyEventRecord & { corrects_event_id?: string; previous_event_id?: string };
    return { id: record.id, revision: record.revision, at: custody.event_time_utc ?? record.created_utc, title,
      actor: record.created_by, automatic: record.created_by === 'automation',
      detail: custody.to_holder ? `${custody.from_holder ?? 'Collected'} → ${custody.to_holder} · ${words(custody.seal_state)}` : `Revision ${record.revision}`,
      correctionOf: custody.corrects_event_id ?? (custody.exceptions?.includes('CORRECTION') ? custody.previous_event_id : undefined),
    };
  }).sort((a, b) => Date.parse(a.at) - Date.parse(b.at) || a.id.localeCompare(b.id));
  const corrections = items.filter((item) => item.correctionOf && items.some((parent) => parent.id === item.correctionOf));
  const seen = new Set<string>();
  const append = (item: TimelineEntry): TimelineEntry[] => {
    const key = `${item.id}/${item.revision}`;
    if (seen.has(key)) return [];
    seen.add(key);
    return [item, ...corrections.filter(c => c.correctionOf === item.id).flatMap(append)];
  };
  return [...items.filter(item => !corrections.includes(item)).flatMap(append), ...items.flatMap(append)];
}

export function reference(record: AnyRecord, type: string) {
  return { type, id: record.id, revision: record.revision };
}
