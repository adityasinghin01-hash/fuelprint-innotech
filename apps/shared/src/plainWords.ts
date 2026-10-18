// Plain words for screens. A judge who knows nothing must understand them. No percentages, no chances.
import type { AnalysisOutcome, FindingOutcome } from './types';

/** What each command did, said as a finished step ("Opened an investigation"). */
export const COMMAND_WORDS: Record<string, string> = {
  create_run: 'Started a new run',
  pause_run: 'Paused the run',
  resume_run: 'Resumed the run',
  reset_run: 'Reset to a new run',
  change_automation: 'Changed an automation switch',
  open_investigation: 'Opened an investigation after the alarm',
  schedule_survey: 'Scheduled a survey',
  run_analysis: 'Ranked the possible sources and planned the next measurement',
  propose_mission: 'Proposed a drone mission',
  approve_mission: 'Approved the mission',
  reject_mission: 'Rejected the mission',
  expire_mission: 'Withdrew an approval because the wind or the analysis changed',
  create_pairing: 'Made a pairing code for a phone',
  confirm_pairing: 'Confirmed the phone pairing',
  acquire_lease: 'Took control of the drone',
  release_lease: 'Let go of the drone',
  take_over: 'Took over the drone',
  start_autopilot: 'Started the autopilot',
  stop_autopilot: 'Stopped the autopilot',
  return_to_depot: 'Sent the drone back to the depot',
  start_capture: 'Started sampling the air',
  review_capture: 'Reviewed the sample channel by channel',
  create_case: 'Opened a case',
  create_specimen: 'Recorded a sealed specimen',
  append_custody: 'Recorded a hand-over of the specimen',
  request_lab: 'Checked the lab result',
  revise_finding: 'Wrote the finding',
  record_board_decision: 'Recorded the board decision',
  seal_export: 'Sealed the evidence',
  verify_export: 'Checked the sealed evidence',
  tamper_copy: 'Changed one byte in a copy (tamper demo)',
  record_anomaly: 'Flagged unusual air for this hour and wind',
  write_wording: 'Wrote plain words for a record',
  retrain_model: 'Retrained the learned ranker',
  subscribe_alerts: 'Signed a phone up for alerts',
  unsubscribe_alerts: 'Removed an alerts sign-up',
  send_alert: 'Sent an SMS alert',
  purge_subscriptions: 'Deleted the phone numbers',
};

/** Plain words for a command type (falls back to the type with spaces). */
export function commandWords(type: string): string {
  return COMMAND_WORDS[type] ?? type.replace(/_/g, ' ');
}

/** AGREEMENT section 6: what each analysis outcome means on screen. */
export const ANALYSIS_OUTCOME_WORDS: Record<AnalysisOutcome, { label: string; meaning: string }> = {
  INVALID: { label: 'Not enough data', meaning: 'Not enough usable data to analyse.' },
  NO_SIGNAL: {
    label: 'No signal',
    meaning: 'Usable data, but nothing above the floor. This does NOT mean the factories are clean.',
  },
  UNKNOWN_OR_MISMATCH: {
    label: 'Unknown source',
    meaning: 'None of the registered factories explains the data. Maybe an unregistered source.',
  },
  AMBIGUOUS_OR_OPTIONAL_MIXTURE: { label: 'More than one fits', meaning: 'More than one explanation still fits.' },
  PROVISIONAL_SINGLE: {
    label: 'One source fits best',
    meaning: 'One registered factory explains the data best, inside this synthetic model only.',
  },
};

/** The three finding outcomes: all three are normal results, not errors. */
export const FINDING_OUTCOME_WORDS: Record<FindingOutcome, { label: string; meaning: string }> = {
  SUPPORTED_WITHIN_SCOPE: {
    label: 'Supported (within scope)',
    meaning: 'The cited records support it, for this synthetic observation only. Legal suitability not assessed.',
  },
  REJECTED_FOR_USE: {
    label: 'Rejected for use',
    meaning: 'A measurement failed a rule, so it cannot be used. That is a proper result, not a failure of the system.',
  },
  INCONCLUSIVE: {
    label: 'Inconclusive',
    meaning: 'The evidence fits more than one explanation. No source is named.',
  },
};

/** "10:15:04" in the screen's local time, from an ISO time. */
export function clockTime(utc: string | null | undefined): string {
  if (!utc) return 'unknown time';
  const date = new Date(utc);
  if (Number.isNaN(date.getTime())) return 'unknown time';
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
}

/** A number with its unit, or "unknown" for null (never 0). */
export function valueOrUnknown(value: number | null | undefined, unit = '', digits = 1): string {
  if (value === null || value === undefined || Number.isNaN(value)) return 'unknown';
  return `${value.toFixed(digits)}${unit ? ' ' + unit : ''}`;
}
