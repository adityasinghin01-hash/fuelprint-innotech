// Types for every record and message of contracts/AGREEMENT.md (fp/1, version 2). Field names are the wire names.
// A value the server does not know is null, never 0: screens must show "unknown" for null.

export type Uuid = string;
/** ISO 8601 wall time like 2026-10-22T10:15:04.120Z. */
export type Utc = string;

// ---------------------------------------------------------------- primitives

export interface Enu { east: number; north: number; up: number }
export interface Pose extends Enu { yaw_deg: number }
export interface Velocity { east: number; north: number; up: number }
export interface Wind { from_deg: number; speed_m_s: number }
export interface RecordRef { type: string; id: Uuid; revision: number }

export type Role =
  | 'presenter' | 'analyst' | 'supervisor' | 'field_operator' | 'qc_reviewer' | 'case_reviewer'
  | 'inspector_demo' | 'lab_demo' | 'exporter' | 'board' | 'visitor' | 'guest' | 'bench_adapter' | 'automation';

export type Origin = 'SYNTHETIC_TEACHING' | 'PHYSICAL_BENCH';
export type Pilot = 'NONE' | 'VISITOR' | 'OPERATOR' | 'AUTOPILOT';

/** Fields every record has (AGREEMENT section 6). created_by is "automation" for automatic steps. */
export interface RecordBase {
  id: Uuid;
  run_id: Uuid | null;
  revision: number;
  created_utc: Utc;
  created_by: string;
}

// ---------------------------------------------------------------- records

export type RunState = 'CREATED' | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'ABORTED';
export interface RunRecord extends RecordBase {
  preset_id: string;
  mode: 'BLIND' | 'TEACHING';
  state: RunState;
  parent_run_id: Uuid | null;
  /** Core extras: seed, where the clock stood, and why the state changed (INTERRUPTED, RESET, REPLAY_LOOP...). */
  seed?: number;
  sim_time_s?: number;
  state_reason?: string | null;
}

export interface SensorRecord extends RecordBase {
  label: string;
  inlet: Enu;
  channels: string[];
  status: string;
  calibration_state: string;
}

export interface ReadingRecord extends RecordBase {
  sensor_or_vehicle_id: Uuid;
  channel: string;
  value: number | null;
  raw_value: number | null;
  unit: string;
  sim_time_s: number;
  quality: string[];
  origin: Origin;
}

export interface WindowRecord extends RecordBase {
  sensor_or_vehicle_id: Uuid | null;
  channel: string;
  start_s: number;
  end_s: number;
  valid_seconds: number;
  mean_enhancement: number | null;
  std_uncertainty: number | null;
  background_window_id: Uuid | null;
}

export interface TriggerRecord extends RecordBase {
  channel: string;
  sensor: Uuid;
  intervals: [number, number][];
  background_window_id: Uuid | null;
  rule: 'TEACH-DETECT-1';
}

export interface AnomalyRecord extends RecordBase {
  sensor: Uuid;
  channel: string;
  start_s: number;
  end_s: number;
  observed_mean: number;
  expected_low: number;
  expected_high: number;
  model_version_id: Uuid;
  state: 'OPEN' | 'CLOSED';
  suggestion: 'SCHEDULE_SURVEY' | null;
}

export type InvestigationState = 'OPEN' | 'ANALYSED' | 'MISSION_PROPOSED' | 'MISSION_APPROVED' | 'EVIDENCE_BACK' | 'CLOSED';
export interface InvestigationRecord extends RecordBase {
  trigger_id: Uuid | null;
  survey_id: Uuid | null;
  state: InvestigationState;
  analysis_ids: Uuid[];
  warned_area_ids: string[];
}

export type AnalysisOutcome =
  | 'INVALID' | 'NO_SIGNAL' | 'UNKNOWN_OR_MISMATCH' | 'AMBIGUOUS_OR_OPTIONAL_MIXTURE' | 'PROVISIONAL_SINGLE';
export interface Candidate {
  explanation_id: string;
  sources: string[];
  /** A fit number (lower fits better), never a probability. null for UNKNOWN. */
  fit: number | null;
  status: 'SURVIVES' | 'REJECTED';
  reason: string | null;
}
export interface LearnedRank {
  explanation_id: string;
  rank: number;
  /** An ordering number only. Never show it; show the rank. */
  order_score: number;
}
export type LearnedStatus = 'OK' | 'MODEL_MISSING' | 'NOT_APPLICABLE' | 'FAILED';
export interface AnalysisRecord extends RecordBase {
  input_window_ids: Uuid[];
  outcome: AnalysisOutcome;
  qualification: 'MODEL_NOT_QUALIFIED' | 'SYNTHETIC_SCOPE_ONLY';
  candidates: Candidate[];
  exclusions: { reading_window: Uuid; reason: string }[];
  limitations: string[];
  explanation_text: string;
  algorithm_version: string;
  learned_order: LearnedRank[];
  learned_status: LearnedStatus | null;
  learned_model_version_id: Uuid | null;
  history_counts: Record<string, number> | null;
}

/** Existing public survey record saved by schedule_survey. */
export interface SurveyRecord extends RecordBase {
  investigation_id: Uuid;
  action_id: string;
  reason: string;
  anomaly_id: Uuid | null;
  vehicle_id: Uuid;
  method_id: string;
  vehicle_kind: string;
  target: Enu;
  channel_ids: string[];
}
export interface ProposalAction {
  action_id: string;
  kind: string;
  vehicle: 'DRONE' | 'VAN';
  target: Enu | null;
  score: number | null;
  complete_minutes: number | null;
  feasible: boolean;
  rejection_reason: string | null;
}
export interface ProposalRecord extends RecordBase {
  analysis_id: Uuid;
  actions: ProposalAction[];
  result: 'BEST_ACTION' | 'NO_USEFUL_MEASUREMENT' | 'PENDING';
  best_action_id: string | null;
  cheaper_action_id: string | null;
}

export type MissionState =
  | 'PROPOSED' | 'APPROVED' | 'LEASED' | 'OUTBOUND' | 'AT_TARGET' | 'COLLECTING' | 'RETURNING' | 'COMPLETE'
  | 'INTERRUPTED' | 'EXPIRED' | 'REJECTED';
export interface MissionRecord extends RecordBase {
  proposal_id: Uuid | null;
  survey_id: Uuid | null;
  action_id: string;
  vehicle_id: Uuid;
  target: Enu;
  route: Enu[];
  method_id: string;
  channel_ids: string[];
  dependency_hash: string;
  state: MissionState;
  expires_s: number;
  pilot: Pilot;
}

export interface PairingRecord extends RecordBase {
  mission_id: Uuid;
  vehicle_id: Uuid;
  code: string;
  phrase: string;
  expires_utc: Utc;
  state: string;
}

export interface LeaseRecord extends RecordBase {
  vehicle_id: Uuid;
  holder_session: string;
  state: 'ACTIVE' | 'RELEASED' | 'REVOKED' | 'EXPIRED';
  expires_utc: Utc;
  last_sequence: number;
}

export type ChannelDecision = 'VALID' | 'LIMITED' | 'REJECTED';
export interface AttemptRecord extends RecordBase {
  mission_id: Uuid;
  method_id: string;
  stages: { stage: string; start_s: number; end_s: number | null }[];
  channels: { channel: string; valid_seconds: number | null; volume_L: number | null; status: ChannelDecision; reasons: string[] }[];
  state: 'COLLECTING' | 'COMPLETE' | 'FAILED' | 'INTERRUPTED';
}

export interface ScreeningRecord extends RecordBase {
  attempt_id: Uuid;
  channel: string;
  named_use: string;
  decision: ChannelDecision;
  reasons: string[];
  summary: Record<string, number | null>;
}

export type CaseState = 'DRAFT' | 'INTAKE' | 'SPECIMEN' | 'LAB' | 'FINDING' | 'SEALED';
export interface CaseRecord extends RecordBase {
  investigation_id: Uuid;
  screening_record_ids: Uuid[];
  purpose: string;
  state: CaseState;
}

export interface SpecimenRecord extends RecordBase {
  case_id: Uuid;
  site_id: string;
  material: string;
  method_id: string;
  container: string;
  seal_id: string;
  mass_mg: number;
  authority_basis: 'INSPECTOR_DEMO';
  current_holder: string;
}

export interface CustodyEventRecord extends RecordBase {
  specimen_id: Uuid;
  from_holder: string | null;
  to_holder: string;
  seal_state: 'INTACT' | 'BROKEN' | 'MISSING';
  event_time_utc: Utc;
  recorded_utc: Utc;
  exceptions: string[];
}

export interface LabResultRecord extends RecordBase {
  specimen_id: Uuid;
  method_id: string;
  raw: {
    gross_ug: number | null; blank_ug: number | null; mass_mg: number | null; dup_a_pct: number | null;
    dup_b_pct: number | null; spike_added_ug: number | null; spike_recovered_ug: number | null;
  };
  computed: { net_ug: number | null; percent_w_w: number | null; rpd_pct: number | null; recovery_pct: number | null };
  qc_status: string;
  qc_reasons: string[];
  reporting: 'REPORTED' | 'BELOW_LOQ';
}

export type FindingOutcome = 'SUPPORTED_WITHIN_SCOPE' | 'REJECTED_FOR_USE' | 'INCONCLUSIVE';
export interface FindingRecord extends RecordBase {
  case_id: Uuid;
  outcome: FindingOutcome;
  cited_refs: RecordRef[];
  alternatives: string[];
  limitations: string[];
  legal_suitability: 'NOT_ASSESSED';
}

export type BoardDecision = 'MORE_INVESTIGATION' | 'NO_SUPPORTED_SOURCE' | 'CANDIDATE_SUPPORTED_SIMULATED' | 'SAMPLE_INVALID';
export interface BoardDecisionRecord extends RecordBase {
  case_id: Uuid;
  decision: BoardDecision;
  rationale: string;
  notice: { area_ids: string[]; area: string; interval: string; status_text: string };
}

export interface ExportRecord extends RecordBase {
  case_id: Uuid;
  case_revision: number;
  manifest_sha256: string;
  members: { path: string; length: number; sha256: string }[];
  signature: string | null;
  state: string;
}

export interface VerificationReportRecord extends RecordBase {
  export_id: Uuid;
  axes: {
    integrity: string; key_trust: string; lineage: string; trusted_time: string; custody_documentation: string;
    measurement_qualification: string; legal_suitability: string;
  };
  reasons: string[];
}

export interface ModelVersionRecord extends RecordBase {
  model_kind: 'LEARNED_RANKER' | 'NORMAL_AIR';
  version: string;
  parent_id: Uuid | null;
  state: 'ACTIVE' | 'RETIRED' | 'REJECTED_BY_GATE';
  trained_on: { synthetic_nights: number; case_examples: number; real_hours: number; note: string };
  trained_where: 'MAC_CPU' | 'DGX_A100';
  /** Counts only (like "first in N of M test nights"); never a percentage. */
  test_results: Record<string, number>;
  real_checks: { dataset: string; what: string; result_text: string }[];
  gate: { passed: boolean; reason: string };
  lessons: Record<string, unknown>[];
  file: { path: string; sha256: string };
}

export type WordingPurpose = 'EXPLANATION' | 'FINDING_SUMMARY' | 'SMS_SMOKE_WARNING' | 'SMS_BOARD_UPDATE';
export interface WordingRecord extends RecordBase {
  target: RecordRef;
  purpose: WordingPurpose;
  text: string;
  source: 'LLM' | 'TEMPLATE';
  model: string | null;
  prompt_version: string;
  checks: { check: string; passed: boolean }[];
  fallback_reason: string | null;
}

export interface AlertSubscriptionRecord extends RecordBase {
  /** Always masked, like +91 98xxxxxx78. A full number never reaches a screen. */
  phone_masked: string;
  area_ids: string[];
  consent_text_version: string;
  consent_utc: Utc;
  state: 'ACTIVE' | 'REMOVED' | 'PURGED';
  expires_utc: Utc;
}

export interface NotificationRecord extends RecordBase {
  kind: 'SMOKE_WARNING' | 'BOARD_UPDATE' | 'TEST';
  area_ids: string[];
  text: string;
  wording_id: Uuid | null;
  cause: RecordRef | null;
  recipients: number;
  sent: number;
  failed: number;
  state: 'QUEUED' | 'SENDING' | 'SENT' | 'PARTIAL' | 'FAILED' | 'SUPPRESSED';
  suppressed_reason: 'RATE_LIMIT' | 'DAILY_CAP' | 'NO_SUBSCRIBERS' | 'GATEWAY_OFF' | null;
  gateway_mode: 'REAL' | 'LOG_ONLY';
}

export interface AutomationSettingsRecord extends RecordBase {
  auto_propose: boolean;
  auto_autopilot: boolean;
  autopilot_wait_s: number;
  auto_capture: boolean;
  auto_seal: boolean;
  auto_alerts: boolean;
  auto_retrain: boolean;
}

/** Record type name to its TypeScript type. */
export interface RecordTypes {
  run: RunRecord;
  sensor: SensorRecord;
  reading: ReadingRecord;
  window: WindowRecord;
  trigger: TriggerRecord;
  anomaly: AnomalyRecord;
  investigation: InvestigationRecord;
  analysis: AnalysisRecord;
  proposal: ProposalRecord;
  survey: SurveyRecord;
  mission: MissionRecord;
  pairing: PairingRecord;
  lease: LeaseRecord;
  attempt: AttemptRecord;
  screening_record: ScreeningRecord;
  case: CaseRecord;
  specimen: SpecimenRecord;
  custody_event: CustodyEventRecord;
  lab_result: LabResultRecord;
  finding: FindingRecord;
  board_decision: BoardDecisionRecord;
  export: ExportRecord;
  verification_report: VerificationReportRecord;
  model_version: ModelVersionRecord;
  wording: WordingRecord;
  alert_subscription: AlertSubscriptionRecord;
  notification: NotificationRecord;
  automation_settings: AutomationSettingsRecord;
}
export type RecordType = keyof RecordTypes;
export type AnyRecord = RecordTypes[RecordType];

/** Record types not tied to a run (list them without run_id). */
export const RUN_INDEPENDENT_TYPES: readonly RecordType[] = [
  'model_version', 'alert_subscription', 'notification', 'automation_settings',
];

// ---------------------------------------------------------------- commands and receipts

export interface Command<P = Record<string, unknown>> {
  schema_version: 'fp/1';
  command_id: Uuid;
  command_type: string;
  run_id: Uuid | null;
  expected_revision: number | null;
  payload: P;
}

export type ReceiptStatus = 'APPLIED' | 'REJECTED' | 'QUEUED' | 'INTERRUPTED';
export interface Receipt {
  receipt_id: Uuid;
  command_id: Uuid;
  status: ReceiptStatus;
  /** STALE_REVISION, NOT_ALLOWED, INVALID, SERVER_ERROR, or a handler's own code. */
  reason_code: string | null;
  reason_text: string | null;
  record_refs: RecordRef[];
  actor: string;
  automatic: boolean;
}

// ---------------------------------------------------------------- live socket

export type MessageType = 'hello' | 'tick' | 'event';
export interface Envelope<P> {
  schema_version: 'fp/1';
  message_type: MessageType;
  run_id: Uuid | null;
  server_epoch: Uuid;
  cursor: number;
  sim_time_s: number | null;
  server_time_utc: Utc;
  payload: P;
}

export interface HelloPayload {
  mode: 'live' | 'replay';
  after: number | null;
  /** True when after= is older than the kept window: reload the snapshot. */
  reload_snapshot: boolean;
  kept_events: number;
}

export interface TickVehicle {
  vehicle_id: Uuid;
  label: string;
  kind: 'DRONE' | 'VAN';
  pose: Pose;
  velocity: Velocity;
  inlet: Enu;
  energy_wh: number | null;
  stage: string | null;
  lease_holder: string | null;
  pilot: Pilot;
}
export interface TickReading {
  sensor: string;
  channel: string;
  value: number | null;
  quality: string[];
  sim_time_s: number;
}
export interface TickPayload {
  wind: Wind | null;
  time_scale: number;
  vehicles: TickVehicle[];
  latest_readings: TickReading[];
}

export interface EventPayload<R = AnyRecord> {
  cursor: number;
  event_type: string;
  record_type: string;
  record_id: Uuid;
  revision: number;
  run_id: Uuid | null;
  sim_time_s: number | null;
  wall_utc: Utc;
  actor: string;
  automatic: boolean;
  record: R | null;
}

export type HelloMessage = Envelope<HelloPayload>;
export type TickMessage = Envelope<TickPayload>;
export type EventMessage = Envelope<EventPayload>;
export type LiveMessage = HelloMessage | TickMessage | EventMessage;

// ---------------------------------------------------------------- read API

export interface Snapshot {
  schema_version: 'fp/1';
  server_epoch: Uuid;
  mode: 'live' | 'replay';
  cursor: number;
  run_id: Uuid | null;
  run: RunRecord | null;
  records: { [T in RecordType]?: RecordTypes[T][] } & Record<string, AnyRecord[] | undefined>;
  latest_readings: ReadingRecord[];
}

export interface CurrentRun {
  server_epoch: Uuid;
  mode: 'live' | 'replay';
  cursor: number;
  run_id: Uuid | null;
  state: RunState | null;
  run: RunRecord | null;
  sim_time_s: number;
  time_scale: number;
  presets: { preset_id: string; description: string }[];
}

export interface SessionInfo {
  signed_in: boolean;
  actor_label: string;
  station_id: string | null;
  roles: Role[];
}

export interface StationInfo { station_id: string; name: string; roles: Role[] }

export interface AutomationLogEntry {
  command_id: Uuid;
  command_type: string;
  run_id: Uuid | null;
  received_utc: Utc;
  receipt: Receipt;
}
