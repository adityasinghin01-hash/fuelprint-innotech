# Rules for every AI coding agent in this repo

Codex, Claude, Cortex and Kiro all read this file before working. Follow it exactly.

## What this project is

FuelPrint is a simulated industrial pollution investigation system for the KIET InnoTech26 exhibition. Three React apps (Operations, Field, Cases) talk to one Java Spring Boot server with SQLite. Everything is synthetic teaching data on a fictional estate placed on the real Kundli map.

## The contract

`contracts/AGREEMENT.md` is the single source of truth for messages, commands, records, roles, state machines and scenarios. Read it before writing code. Never edit anything in `contracts/`. If the task seems to need a contract change, stop and write the needed change in your final message instead.

## Ownership: only edit your own folders

| Owner | Folders you may edit |
|---|---|
| core (Aditya Singh) | `server/src/main/java/fuelprint/core/**`, `server/src/main/java/fuelprint/api/**`, `server/src/main/resources/db/migration/V0*`, `server/pom.xml`, `tools/**` |
| science (Cortex) | `server/src/main/java/fuelprint/science/**`, migrations `V1xx`, its tests |
| ops (Aditya Singhal, Abhivansh) | `server/src/main/java/fuelprint/ops/**`, migrations `V2xx`, `apps/operations/**`, their tests |
| cases backend (Antara) | `server/src/main/java/fuelprint/cases/**`, migrations `V3xx`, its tests |
| cases screens (Aditya) | `apps/cases/**` |
| field (Aditya Singh) | `server/src/main/java/fuelprint/field/**`, migrations `V4xx`, `apps/field/**` |
| shared screens (Claude) | `apps/shared/**` |

Tests for a package go in the matching folder under `server/src/test/java/fuelprint/<package>/`. If you need something from another owner's folder, use its public interface or ask; do not edit it.

## Backend rules

- Java source level 17. Spring Boot 3.5. Maven wrapper (`./mvnw`). Plain SQL with `JdbcTemplate`. Flyway migrations in your number range.
- Every state change goes through a command handler that: checks the role from the session, checks `expected_revision`, checks idempotency by `command_id`, saves the new record revision and the event in ONE transaction, then returns the receipt. Use the core `CommandBus` and `RecordStore`; do not write your own.
- Records are never updated in place. Write a new revision row.
- Never import `fuelprint.science.truth` outside `fuelprint.science`. A test enforces this.
- No value is silently turned into zero. Unknown is `null` plus a reason.
- Every rule from AGREEMENT.md that you implement gets a unit test, including the failure cases (wrong role, stale revision, repeated command, expired item).

## Screen rules

- React 18 + TypeScript + Vite. Import shared pieces from `@fp/shared` (live connection, `sendCommand`, types, buttons, badges, `Explain`).
- The screen never calculates readings, scores or outcomes. It shows what the server sent.
- Every number, dot, badge and control on screen gets a short plain "what it is and what it's for" line, using the `Explain` component. A judge who knows nothing must understand it.
- Every screen handles: loading, empty, ready, command pending, stale (read-only), not allowed, rejected with reason, and interrupted.
- Scores are never shown as percentages or chances.
- No real factory names. Sources are S01 to S06.
- Keyboard works for everything. Buttons at least 44 px tall on phones.

## Before you finish

1. Run `tools/test-all.sh` (or the tests for your package) and make them pass.
2. List the files you changed.
3. Say what you could not finish or what you assumed.
