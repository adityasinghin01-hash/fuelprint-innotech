# FuelPrint · KIET InnoTech26

An AI-assisted industrial pollution investigation system. Three web apps (Operations, Field, Cases) on one Java server, running a synthetic estate on the real Kundli map.

**Status (16 Oct 2026):** plan and agreement done; building starts 17 Oct. Department round 22 to 24 Oct, finale 30 Oct.

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

The Markdown sources of the docs are next to the PDFs in `docs/plan/`. Rebuild the PDFs with `tools/plan/build_pdfs.py`.

## Folders

```
contracts/        agreement + reference numbers (Aditya Singh only)
server/           Spring Boot app: core, api, science, ops, cases, field packages
apps/             operations, cases, field, shared (React + TypeScript + Vite)
data/context/     Kundli map crop and historical wind (real data, read only)
docs/plan/        plan documents
tools/            scripts
```

Map data: Overture Maps, Google Open Buildings, Microsoft Building Footprints, © OpenStreetMap contributors. Weather: Open-Meteo. See `data/context/ATTRIBUTION.md`.
