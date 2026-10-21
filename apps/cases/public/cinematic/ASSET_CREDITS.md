# FuelPrint cinematic asset credits

This is an illustrative environment, not a real estate, measured telemetry, or Field's simulation.

## Current stylized opening

`stylized/estate.glb` is an original Blender-authored FuelPrint environment: continuous terrain, curved
roads, six fictional factories in three styles, HQ, inactive roof sensors and planted landscaping.
Its reproducible source is `apps/cases/art/build_stylized_estate.py`; the editable `.blend` stays local.
`stylized/drone.glb` and `stylized/sensor.glb` are original matching instrument models authored by
`apps/cases/art/build_stylized_instruments.py`. They include enamel shells, optics, inlet, skids,
rotor guards, separate propeller pivots and indicator materials. They are illustrative designs,
not a commercial aircraft or validated monitoring instrument. No external asset licence is required.
`stylized/estate.json` contains authored smoke-emitter positions, not readings or a surveyed map.

Clouds use an original world-space volume shader; smoke uses original soft instanced particles.
Neither represents a scientific dispersion model. Static opening posters are Blender renders of the
same original estate. No found model, reference image or teammate-video asset is used in this opening.
The flight curve, atmosphere and inlet airflow ribbons/particles are original Cases-owned
Three.js artwork. Airflow is illustrative; it encodes no chemical identity, quantity or source result.
The monitoring-network paths and travelling sensor-to-HQ pulse are original authored graphics, not
observed network traffic. The return arc reuses the same aircraft, launch terrace and landscape.
Refinement V1 grades the original named material slots and painted meadow colors without replacing
the geometry. `src/cinematic/artDirection.json` is shared by the runtime and Blender poster renderer.
Final polish retains these assets, grades materials by role and removes the extra closing cloud
volume; no additional model, texture, lighting pass or external licence is introduced.
The Draco decoder licence below still applies. The following older model credits are retained for
earlier delivery files, which the current opening does not load.

## Original instrument models

`monitoring-drone.glb` and `monitoring-station.glb` are original FuelPrint models authored in Blender by `apps/cases/art/build_models.py`. They include bevelled bodywork, fasteners, service components, instrument details, material slots and separate rotor pivots. They do not reproduce a commercial drone or validated monitoring instrument.

## CC0 environment sources

Poly Haven publishes the following assets under [CC0](https://polyhaven.com/license), allowing adaptation and redistribution. These are credited source assets, not scans made by the FuelPrint team.

| Delivered asset | Source and creator | Adaptations |
| --- | --- | --- |
| `factory.glb` | [Modular Factory Facade](https://polyhaven.com/a/modular_factory_facade), James Ray Cock | Window/facade meshes assembled into a fictional hall; original roof monitors, standing seams, vents, downpipes, canopy and annex |
| `pipe-kit.glb` | [Modular Industrial Pipes 01](https://polyhaven.com/a/modular_industrial_pipes_01), Jorge Camacho | Web delivery compression |
| `tree.glb`, `tree-mobile.glb` | [Tree Small 02](https://polyhaven.com/a/tree_small_02), Rico Cilliers | Mesh simplification and WebP texture compression; repeated at different scales/rotations |
| `daylight.hdr`, `daylight-mobile.hdr` | [Kloofendal 48d Partly Cloudy Pure Sky](https://polyhaven.com/a/kloofendal_48d_partly_cloudy_puresky), Greg Zaal; sky edits Jarod Guest | 1K/512 px HDR lighting; the background uses a matching atmospheric colour |
| Asphalt maps | [Asphalt 02](https://polyhaven.com/a/asphalt_02), Rob Tuytel | 1K colour, 512 px normal/roughness, optimized JPEGs |
| Concrete maps | [Concrete Floor 02](https://polyhaven.com/a/concrete_floor_02), Rob Tuytel | Optimized 1K/512 px maps; courtyard albedo colour-graded for cast concrete |
| Grass maps | [Grass Ground](https://polyhaven.com/a/grass_ground), Charlotte Baglioni | Optimized 1K/512 px maps; runtime green tint |

Fallback posters are offline Blender renders of this same scene and these assets. They are not photographs. Source downloads are checksum-checked during asset preparation. Editable `.blend` files and source caches are local, ignored working files; delivered GLBs and maps are self-hosted.

## Decoder

`decoders/` contains Google's Draco glTF decoder distributed with Three.js, licensed under [Apache License 2.0](https://github.com/google/draco/blob/main/LICENSE). See `decoders/LICENSE`.
