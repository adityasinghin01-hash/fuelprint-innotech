# Repository Guidelines

## Scope & Project Structure

Aditya owns the Cases frontend in `apps/cases/**`: app frame, case list, timeline, and CA01–CA06. Antara owns its backend. Read root `AGENTS.md`, `contracts/AGREEMENT.md`, `docs/plan/03-cases-guide.md`, and local `PROJECT_CONTEXT.md` before implementation. Keep changes inside this app; request shared interfaces rather than editing another owner's folders.

`src/` contains React screens and colocated unit tests; `e2e/` contains integration tests. `apps/shared/` supplies read-only shared components, types, theme, and fixtures. Java lives in `server/`; scripts live in `tools/`.

## Build, Test & Development Commands

Use Node 22+ and Java 17+. From repository root on Windows:

- `./tools/start-fake.ps1 -Keep`: start replay on port 8080 while retaining replay data.
- `./tools/dev-app.ps1 cases`: install dependencies and serve `/cases/` at `http://localhost:5173/cases/`.
- `./tools/test-all.ps1`: run Java, frontend, and replay integration checks.

From `apps/`, run `npm ci` to install locked dependencies, then:

- `npm run typecheck --workspace cases`: check TypeScript.
- `npm test --workspace cases`: run Vitest.
- `npm run build --workspace cases`: typecheck and build production assets.

Use matching `.sh` scripts in Bash. Integration tests require the replay server; consult `tools/test-e2e.ps1`.

## Coding Style & Interface Rules

Follow existing two-space indentation, single quotes, semicolons, PascalCase components, and camelCase functions. Use strict TypeScript and `@fp/shared`, including `sendCommand`, `StaleBanner`, `Explain`, and `AutomationLog`. No formatter/linter is currently configured.

Use shared green/ivory theme tokens, keyboard-accessible controls, 44 px touch targets, and text alongside status colours. Handle loading, empty, pending, stale/read-only, forbidden, rejected, and interrupted states. Show server results; never calculate lab QC, rankings, or outcomes locally. Preserve nulls, revisions, and command IDs on retries.

## Testing Guidelines

Use Vitest and Testing Library; name tests `*.test.ts` or `*.test.tsx`. Cover timeline/corrections, stale controls, role restrictions, receipts, and supported/rejected/inconclusive results. No numerical coverage threshold is configured. Check keyboard operation and 200% zoom.

## Commit & Pull Request Guidelines

Use `cases/aditya`, or a separate `codex/` topic branch. Fetch and merge `origin/main` into your branch. Never push to `main`, force-push, or merge your own PR. Follow scoped history, e.g. `cases: add intake screen`. PRs target `main` and describe scope, validation, screenshots, and related issues. Keep secrets out of commits.

## Evidence Language

Label synthetic data. Keep screening records separate from specimens and corrections append-only. Never claim fuel detection, guilt, or probability. Legal suitability stays “Not assessed”; vault verification shows seven separate axes.
