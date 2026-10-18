// @fp/shared: everything a FuelPrint screen imports. See README.md.
export * from './types';
export { configureServer, apiUrl, wsUrl, getJson, sendJson, HttpError } from './http';
export { sendCommand, newCommandId, receiptProblem } from './commands';
export type { SendOptions } from './commands';
export { LiveConnection, STALE_AFTER_MS, isReadOnly } from './live';
export type { LiveOptions, LiveState, LiveStatus } from './live';
export {
  LiveProvider, useLive, useLiveStatus, useLiveEvents, useSnapshot, useSession, useAutomationLog, useCommand,
} from './hooks';
export type { LiveProviderProps, SnapshotState, SessionState, CommandState } from './hooks';
export {
  COMMAND_WORDS, commandWords, ANALYSIS_OUTCOME_WORDS, FINDING_OUTCOME_WORDS, clockTime, valueOrUnknown,
} from './plainWords';
export { palette, lightTheme, darkTheme, MIN_TOUCH_PX } from './theme';
export type { Theme } from './theme';
export { Explain } from './components/Explain';
export type { ExplainProps } from './components/Explain';
export { Button } from './components/Button';
export type { ButtonProps } from './components/Button';
export { LiveBadge } from './components/LiveBadge';
export { StaleBanner } from './components/StaleBanner';
export { OutcomeBadge } from './components/OutcomeBadge';
export { DoneAutomatically } from './components/DoneAutomatically';
export { AutomationLog, AutomationLogList } from './components/AutomationLog';
export { StationLogin } from './components/StationLogin';
export type { StationLoginProps } from './components/StationLogin';
