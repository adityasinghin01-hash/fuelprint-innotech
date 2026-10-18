import { clockTime } from '../plainWords';

/** The mark for anything the system did by itself: "done automatically at 10:15:04". */
export function DoneAutomatically({ at }: { at: string | null | undefined }) {
  return (
    <span className="fp-auto" data-testid="done-automatically">
      <span aria-hidden="true">⚙</span> done automatically at {clockTime(at)}
    </span>
  );
}
