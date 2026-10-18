# Rules for every AI coding agent in this repo

Kiro, Codex and Claude all read this file before working. Follow it exactly.

## What this project is

FuelPrint is a simulated, AI-driven industrial pollution investigation system for the KIET InnoTech26 exhibition (Category 2, AI Solutions for Automation). Three React apps (Operations, Field, Cases) plus a phone alerts page talk to one Java Spring Boot server with SQLite. Everything in the demo is synthetic teaching data on a fictional estate placed on the real Kundli map. Some AI pieces are trained or checked on real public data (see AGREEMENT section 14).

## The contract

`contracts/AGREEMENT.md` (fp/1, version 2) is the single source of truth for messages, commands, records, roles, state machines, scenarios, the AI pieces, automation and SMS alerts. Read it before writing code. Never edit anything in `contracts/` or this file. If the task seems to need a contract change, stop and write the needed change in your final message instead.

## Ownership: only edit your own folders

| Owner | Folders you may edit |
|---|---|
| core (Aditya Singh, built by Kiro) | `server/src/main/java/fuelprint/core/**`, `server/src/main/java/fuelprint/api/**`, migrations `V001`–`V099`, `server/pom.xml`, `tools/**` except `tools/train/**` |
| science (Aditya Singh, built by Kiro) | `server/src/main/java/fuelprint/science/**`, migrations `V100`–`V199`, its tests |
| ops (Aditya Singhal, Abhivansh) | `server/src/main/java/fuelprint/ops/**`, migrations `V200`–`V299`, `apps/operations/**`, their tests |
| cases backend (Antara) | `server/src/main/java/fuelprint/cases/**`, migrations `V300`–`V399`, its tests |
| cases screens (Aditya) | `apps/cases/**` |
| field (Aditya Singh, Kiro code, Claude 3D look) | `server/src/main/java/fuelprint/field/**`, migrations `V400`–`V499`, `apps/field/**` |
| ai (Aditya Singh, built by Kiro) | `server/src/main/java/fuelprint/ai/**`, migrations `V500`–`V599`, `tools/train/**`, `server/src/main/resources/models/**` |
| alerts (Aditya Singh, built by Kiro) | `server/src/main/java/fuelprint/alerts/**`, migrations `V600`–`V699`, `apps/alerts/**` |
| shared screens (Aditya Singh, built by Kiro) | `apps/shared/**` |

Tests for a package go in the matching folder under `server/src/test/java/fuelprint/<package>/`. If you need something from another owner's folder, use its public interface or ask; do not edit it.

## Backend rules

- Java source level 17. Spring Boot 3.5. Maven wrapper (`./mvnw`). Plain SQL with `JdbcTemplate`. Flyway migrations in your number range.
- The default profile is `stub`; real beans use `@Profile("!stub")`; tests that need real beans use `@ActiveProfiles("real")`.
- Tests that send commands log in with a real session cookie from `CookieSessionService` (see `CommandControllerTest`).
- Every state change goes through a command handler. The core `CommandBus` checks the role from the session, then idempotency by `command_id`, then `expected_revision` (of the handler's subject record), runs the handler, saves the new record revision and the event in ONE transaction, then returns the receipt. Use the core `CommandBus` and `RecordStore`; do not write your own.
- Automatic steps: an event listener never saves records directly. It calls `AutomationBus.submit(...)`, which sends a normal command as the `automation` actor. Register `automation` in the allowed roles of a command only where AGREEMENT section 5 lists it.
- The five human-only commands (`approve_mission`, `reject_mission`, `review_capture`, `revise_finding`, `record_board_decision`) must refuse the `automation` actor, with a test.
- Records are never updated in place. Write a new revision row.
- Never import `fuelprint.science.truth` outside `fuelprint.science`. A test enforces this. Training labels live only in `data/training/`; the server never reads that folder.
- The learned ranker only orders surviving explanations. It never changes an outcome, a candidate status or the UNKNOWN row.
- The chat model (AI wording) only gets public record text, never truth and never phone numbers, and its output must pass the checks in AGREEMENT section 11 or the template is used.
- Phone numbers stay in the alerts contact table only. Never log them, never put them in a record, event, tick, API response, export or prompt.
- `server/config/sms.json` and `server/config/llm.json` are never committed. Default SMS mode is LOG_ONLY.
- No value is silently turned into zero. Unknown is `null` plus a reason.
- Every rule from AGREEMENT.md that you implement gets a unit test, including the failure cases (wrong role, stale revision, repeated command, expired item, automation refused).

## Screen rules

- React 18 + TypeScript + Vite. Import shared pieces from `@fp/shared` (live connection, `sendCommand`, types, buttons, badges, `Explain`, `AutomationLog`).
- The screen never calculates readings, scores, AI outputs or outcomes. It shows what the server sent.
- Every number, dot, badge and control on screen gets a short plain "what it is and what it's for" line, using the `Explain` component. A judge who knows nothing must understand it.
- Every screen handles: loading, empty, ready, command pending, stale (read-only), not allowed, rejected with reason, and interrupted.
- Scores and model outputs are never shown as percentages or chances. The learned ranker shows a rank (1, 2, 3), never its score.
- Anything done by `automation` is marked "done automatically" with the time, so a judge can see what the system did by itself.
- AI wording is labelled "Plain words by AI (it cannot change any number)". If it fell back to the template, say so.
- No real factory names. Sources are S01 to S06.
- Keyboard works for everything. Buttons at least 44 px tall on phones.

## Before you finish

1. Run `tools/test-all.sh` (or the tests for your package) and make them pass.
2. List the files you changed.
3. Say what you could not finish or what you assumed.
