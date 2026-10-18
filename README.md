# FuelPrint · KIET InnoTech26

An AI system that automates industrial pollution investigations, from the first sulphur rise to a sealed evidence pack; people sign only the five decisions with legal weight. Three web apps (Operations, Field, Cases) and an SMS alerts phone page on one Java server, running a synthetic estate on the real Kundli map.

**Status (18 Oct 2026):** agreement version 2 (AI and automation) and the team docs version 2 are done. Kiro builds the core from Mon 19 Oct. Department round 22 to 24 Oct, finale 30 Oct.

## Read first

| Doc | For |
|---|---|
| [docs/plan/pdf/01-team-plan.pdf](docs/plan/pdf/01-team-plan.pdf) | Everyone: who does what, dates, git rules, the demo |
| [docs/plan/pdf/02-operations-guide.pdf](docs/plan/pdf/02-operations-guide.pdf) | Aditya Singhal, Abhivansh |
| [docs/plan/pdf/03-cases-guide.pdf](docs/plan/pdf/03-cases-guide.pdf) | Antara, Aditya |
| [docs/plan/pdf/04-field-plan.pdf](docs/plan/pdf/04-field-plan.pdf) | Field, shared for everyone |
| [docs/plan/pdf/05-study-pack.pdf](docs/plan/pdf/05-study-pack.pdf) | Everyone; mostly Abhivansh |
| [contracts/AGREEMENT.md](contracts/AGREEMENT.md) | The contract every part builds against |
| [AGENTS.md](AGENTS.md) | Rules every AI coding agent follows, and who owns which folder |
| [contracts/reference/alert-areas.json](contracts/reference/alert-areas.json) | The four fictional areas for SMS alerts |

The Markdown sources of the docs are next to the PDFs in `docs/plan/`. Rebuild the PDFs with `tools/plan/build_pdfs.py`.

## Folders

```
contracts/        agreement + reference numbers (Aditya Singh only)
server/           Spring Boot app: core, api, science, ai, alerts, ops, cases, field packages
apps/             operations, cases, field, alerts, shared (React + TypeScript + Vite)
data/context/     Kundli map crop and historical wind (real data, read only)
docs/plan/        plan documents
tools/            scripts; tools/train = model training (Python)
```

Map data: Overture Maps, Google Open Buildings, Microsoft Building Footprints, © OpenStreetMap contributors. Weather: Open-Meteo. See `data/context/ATTRIBUTION.md`.
