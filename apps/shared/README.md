# @fp/shared

The shared screen kit (owner: Aditya Singh, built by Kiro). Import everything from `@fp/shared`:

```tsx
import { LiveProvider, useLive, useSnapshot, sendCommand, StaleBanner, Explain, OutcomeBadge,
         AutomationLog, DoneAutomatically, StationLogin, Button } from '@fp/shared';
import '@fp/shared/theme.css';
```

- `LiveProvider` + `useLive()`: the `/ws/live` socket with reconnect (`after=` the last cursor), status
  `connecting | live | stale | offline` (stale after 3 s without a message: disable buttons).
- `useSnapshot()`: `GET /api/snapshot` for the current run, kept up to date with live events.
- `sendCommand(type, payload, expectedRevision)`: UUID command id, the same id on every retry; returns the receipt.
- Types for every record and message of AGREEMENT version 2 (`types.ts`).
- Theme tokens (IDEAVERSE palette, dark variant for Field) in `theme.ts` and `theme.css`.

There is deliberately no percentage helper: scores and model outputs are never shown as percentages.
`fixtures/` holds one example of every record (generated; do not edit).
