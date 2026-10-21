# Cases app

Owners: Antara (backend) and Aditya (screens CA01 to CA06). See `docs/plan/03-cases-guide.md`.

## Frontend

Current work is the approved **Final Cinematic & UI Polish**: material clarity, framing, a continuous
HQ-to-Cases handoff and restrained polish of existing functional screens. See `VERIFICATION.md` for
results and direct desktop/mobile captures. No dashboard rebuild or website expansion is included.
`src/cinematic/artDirection.json` supplies the runtime material/light grade and Blender poster colors.

The observatory opens with one continuous stylized Three.js/GSAP scene: aerial estate → first approach
→ rooftop monitoring → illustrative signal to HQ → proposed measurement → human approval → drone lift
→ outbound flight → purge/settle/collect → human channel review → return and landing → cloud transition
into the actual casebook, headed **Follow the evidence.** HQ stays visible through the final caption;
the previous full-screen white veil is removed.
Its oversized FUELPRINT title gives way to short narrative
captions. An original Blender GLB supplies the terrain, HQ, six factories, roads and trees. World-space
volume clouds and soft instanced chimney smoke create the atmosphere. No photographic assets are loaded.

All **nine scenes (twelve reading beats)** are implemented. A single canvas and authored drone path preserve
spatial continuity. Native scrolling reveals the existing `Workspace` component below the story;
search, filters and intake are functional, using the existing Cases providers. Nothing in the
illustration sends operational commands. Skip/Open casework bypass the story when needed.

The illustration is not Field's simulator or a source prediction. Scroll reverses the camera path;
pause freezes cloud/smoke/rotor/airflow time. Reduced motion, short windows and unavailable WebGL use
static storytelling. A chapter selector and previous/next arrows provide keyboard access to every beat.
Icons are Phosphor; Manrope is bundled. See `DESIGN_DIRECTION.md` and `SCENE_STORYBOARD.md`.

The working interface uses `@fp/shared` for live updates, snapshots, station login, command receipts,
stale/read-only handling, and the automation log. It never computes scientific results locally.

| Route below `/cases/` | Screen |
| --- | --- |
| `/` | Full cinematic story followed by the real casebook and live field context |
| `/workspace` | Searchable/filterable casebook |
| `/guide` | Evidence workflow and its limits |
| `/intake`, `/:caseId/intake` | CA01 screening and case creation |
| `/:caseId/specimens/new` | CA02 inspector specimen |
| `/:caseId/custody`, `/specimens/:specimenId/custody` | CA03 custody |
| `/:caseId/lab`, `/specimens/:specimenId/lab` | CA04 laboratory |
| `/:caseId/finding` | CA05 human-reviewed finding |
| `/:caseId/vault` | CA06 manifest, seven checks, tamper-copy flow |
| `/:caseId/aerosol` | Separate synthetic aerosol filter, custody and laboratory |
| `/:caseId/replay` | Read-only sealed-case record history |

Case links preserve `?run=` so replay rotation does not replace a case's evidence. The timeline fetches earlier
case and specimen revisions through the existing read API. Interrupted retries retain the command ID.

## Run and check

From repository root, start `tools/start-fake.ps1 -Keep`, then `tools/dev-app.ps1 cases` in another terminal.
Open http://localhost:5173/cases/. Bash users can use the matching `.sh` scripts.

From `apps/`: `npm ci`, `npm run typecheck --workspace cases`, `npm test --workspace cases`, and
`npm run build --workspace cases`. Root `tools/test-e2e.ps1` starts a separate disposable replay server for the
live integration tests. On Windows, use the documented repository tools or an installed Maven with Java 17+.

## Integration status

The integration base is main `13495d4`, with the real Cases backend and agreement v2.4. Forms use its
public commands; replay is read-only. Corrections refer to the immediately previous custody event,
as defined by the current backend. Signature validity and external key trust remain separate, and
exporter stations can download original or labelled tamper-copy ZIPs. No browser generates scientific
results or treats an included public key as a trust anchor.

The build and 50 frontend tests pass. Full real-server workflow acceptance remains open: the local
HTTP rehearsal stopped at `NEEDS_NEW_APPROVAL`, and inherited Windows backend checks exposed signing
test path/JSON and offline pack-read failures. See [PR review](PR_REVIEW.md) for the validation boundary,
remaining integration work and committed screenshots. Historical visual reports do not supersede it.

All data remains synthetic teaching material. Legal suitability stays "Not assessed". No fuel detection,
guilt, or probability claims are made. See `VERIFICATION.md` for checks and current limitations.

## Cinematic assets

Current delivery files live in `public/cinematic/stylized/`; [credits](public/cinematic/ASSET_CREDITS.md)
distinguish the original opening from retained earlier assets. There are no runtime CDN dependencies.
Phone uses fewer smoke particles, fewer cloud ray-march steps and 1024 px shadows. The estate GLB is
about 646 kB, shared by both compositions. Opening casework unmounts the lazy renderer and disposes GPU
resources. No new npm dependencies or workspace lockfile changes were needed for this revision.

Reproduce the current opening with Blender 4.5 LTS, from repository root:

```powershell
blender --background --python apps/cases/art/build_stylized_estate.py
blender --background --python apps/cases/art/build_stylized_instruments.py
blender --background --python apps/cases/art/render_stylized_posters.py
```

The following commands reproduce the **earlier photoreal asset set**, retained for provenance rather
than used by the current opening:

Reproduce assets with Blender 4.5 LTS and Python/Pillow, from repository root:

```powershell
python apps/cases/art/fetch_assets.py
blender --background --python apps/cases/art/build_models.py
blender --background --python apps/cases/art/optimize_models.py
python apps/cases/art/optimize_textures.py
blender --background --python apps/cases/art/render_previews.py
```

Source `.blend` files and verified download caches remain local and ignored. `build_models.py` authors
actual instrument meshes and assembles licensed facade modules; it is an offline asset pipeline, not
procedural placeholder geometry generated in the browser. Later fetches restore source textures before
optimization; preserve `.cache/texture-originals/` when adjusting JPEG quality.
