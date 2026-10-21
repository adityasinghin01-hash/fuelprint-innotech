import { useCases as useCasesState } from './CasesContext';
import { CommandFeedback, useCaseCommand } from './CaseScreens';

// Keep the integrated owner's aerosol forms on the same session, snapshot and retry path as Cases.
export function useCases() {
  const { data, session } = useCasesState();
  return { snapshot: data, session };
}
export function useCasesCommand(role: string) {
  const command = useCaseCommand(role);
  return { ...command, disabled: command.blocked };
}
export function Feedback({ action }: { action: ReturnType<typeof useCasesCommand> }) {
  return <>{action.reason && <p className="permission-note">{action.reason}</p>}<CommandFeedback receipt={action.receipt}/></>;
}
export const lines = (value: string) => value.split('\n').map(line => line.trim()).filter(Boolean);
