# Cases app

Owners: Antara (backend) and Aditya (screens CA01 to CA06). See `docs/plan/03-cases-guide.md`.

The workbench covers case intake, separate fuel specimens and aerosol filters, custody, raw lab inputs, human findings and evidence verification. It displays saved server calculations and QC decisions; it does not calculate measurements or decide findings in the browser. Missing lab values remain unknown, and decimal input that would change during JSON number serialization is refused explicitly.

The versioned signature/trust display, aerosol commands and ZIP link follow Lane B's approved protocol. Their real backend integration remains pending Lane B's reviewed READY delivery. Component tests use protocol fixtures and are separate from end-to-end verification. A single session state feeds both the login and the workbench. Stale, replay and interrupted views keep commands disabled.

Run `npm run test --workspace=@fp/cases` and `npm run typecheck --workspace=@fp/cases` from `apps`. For local development, use `tools/dev-app.sh cases` with a running backend and open http://localhost:5173/cases/. Fake-server checks are separate; its older fixture set does not establish support for the new evidence or aerosol interfaces.
