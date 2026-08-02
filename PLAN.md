# OME-Zarr adapter for BigDataViewer-Playground — plan

Goal: open an OME-Zarr (OME-NGFF v0.4 / v0.5) as a **SpimData** with correct
metadata, driven by a clean **SciJava command** (not BigStitcher's dialog stack),
so that BDV-Playground's existing entity/tree/export machinery keeps working.

## Strategy

- **Own the loader.** The pixel loader is two small classes of our own, built on
  `bigdataviewer-core`'s `N5ImageLoader` — no heavy image-loading dependency. The
  project is **MIT**, builds on **Java 11**, and depends only on permissive libs
  (bigdataviewer-core, n5-universe, n5/n5-zarr, imglib2, spim_data, spimdata-extras).
- **The owned loader = two small classes:**
  - `OmeZarrImageLoader extends bdv.img.n5.N5ImageLoader` — inherits
    multiresolution + volatile cache; overrides `createN5PropertiesInstance()` and
    `prepareCachedImage()` to hyperslice `c`/`t` down to 3D (`extract3D`).
  - `OmeZarrN5Properties implements bdv.img.n5.N5Properties` — resolves per-level
    dataset paths, dimensions (`copyOf(dims,3)`), data type and mipmap resolutions
    from precomputed OME-NGFF metadata.
- **The metadata layer (`OmeZarrOpener`)** builds the `SequenceDescription`
  (`ViewSetup`s with calibration + `Channel`, `TimePoints`), `ViewRegistrations`
  (pixel→physical) and the per-`(setup,timepoint)` maps the loader consumes, then
  opens the reader via `N5Factory` (with the coordinate-transform Gson adapter).

### Key facts (verified against the pinned versions)

Pinned by pom-scijava 45.1.0: bigdataviewer-core 10.6.11, n5-universe 3.0.2.

- Reader: `new N5Factory().gsonBuilder(…CoordinateTransformationAdapter).openReader(format, uri)`.
  **`StorageFormat.ZARR`** = Zarr v3 (NGFF 0.5), **`StorageFormat.ZARR2`** = Zarr v2 (NGFF 0.4).
- `N5Properties.getDatasetPath = imagePath + "/" + datasets[level].path`.
- Axis order: NGFF stores `(t,c,z,y,x)`; n5/imglib2 reverses to `(x,y,z,c,t)` →
  imglib2 dim `0,1,2 = x,y,z`, `3 = c`, `4 = t`. `scale`/`translation` come back in
  imglib2 order (indexed by dim); `axes[]` stays in NGFF order (unit by position).
  Requires **3 spatial axes (z,y,x)**; the hyperslice index is `[c]`, `[t]`, or
  `[c,t]` (ascending imglib2 dim), `null` for pure 3D.

## v0.1 scope (agreed)

**In:** spatial calibration + unit (from NGFF `axes` + level-0 `scale`),
timepoints (NGFF `t` axis → `TimePoints`, per-timepoint views), channels split
into one `ViewSetup` each with a `Channel` entity. Single multiscale image per
container (image at the container root).

**Out (later milestones):** `omero` channel names/colors/contrast; HCS
plate/well; `labels`; RFC-5 rich transforms; multi-image / `bioformats2raw`
series discovery; 2D-only (no z axis) OME-Zarr; writing/export.

## Components

| File | Role |
|---|---|
| `bdv/img/omezarr/OmeZarrOpener.java` | Discovery + `SpimData` builder (the metadata layer). `open(String uri, ...)`. |
| `bdv/img/omezarr/command/OpenOmeZarrCommand.java` | SciJava `Command`: URL field → outputs `AbstractSpimData`. |
| `SpimDataPostprocessor` (already present, test) | Auto-shows any `AbstractSpimData` command output in BDV. |
| `OpenOmeZarrDemo` (test) | Smoke test against a public OME-Zarr. |

## Status (done)

- [x] `OmeZarrOpener` — detect format (`N5Factory`), parse multiscale, build the
  `SpimData` + per-view maps.
- [x] Owned loader: `OmeZarrImageLoader` + `OmeZarrN5Properties`.
- [x] `OpenOmeZarrCommand` (single-URL SciJava import).
- [x] **Spatial calibration + unit** and **timepoints** from the NGFF axes/scale.
  Gotcha handled: n5-universe returns `axes` in NGFF (file) order but
  `scale`/`translation` in imglib2 order → index scale by imglib2 dim, unit by
  axis position.
- [x] **Channel names + colors/contrast** from the `omero` block → a
  `Displaysettings` entity (spimdata-extras) per `ViewSetup`. v0.5 reads the
  nested `ome/omero` attribute; v0.4 the root `omero`.
- [x] **bioformats2raw containers**: URLs like `…preview.zarr` (no `multiscales`
  at root) hold images as integer child groups `0,1,…`, auto-discovered by
  **probing** `/0,/1,…` (HTTP can't list dirs). Each series → its own `Tile` +
  channel `ViewSetup`s; heterogeneous timepoint counts handled via `MissingViews`.
- [x] Verified end-to-end on live IDR data (v0.4, v0.5, and a bioformats2raw
  container with a 79-timepoint timelapse): correct metadata plus a real block
  load. `mvn clean install` passes with the license check enabled.
  - Runtime needs the native `blosc` lib (`-Djna.library.path=...`); Fiji ships it.
- [x] **Regression tests** (`OmeZarrOpenerIT`) against five pinned, immutable IDR
  datasets from the [NGFF sample catalog](https://idr.github.io/ome-ngff-samples/):
  golden assertions on format detection, channel/timepoint counts, voxel
  size + unit, omero colors/contrast, bioformats2raw series discovery, and the
  `translation` → `ViewRegistration` path. Includes a v0.4-vs-v0.5 parity test on
  the same image and a pixel-load test proving c/t hyperslicing to 3D.
  - **Opt-in** (network + native): `mvn test -Dtest=OmeZarrOpenerIT
    -Domezarr.integration=true -Djna.library.path=<dir with blosc>`. Each test
    self-skips (never fails) when the flag is off, the IDR host is unreachable, or
    native blosc is missing, so the default build stays green offline.

- [x] **XML round-trip (`XmlIo`)** — `XmlIoOmeZarrImageLoader`
  (`@ImgLoaderIo(format = "bdv.omezarr")`) saves/reloads an OME-Zarr-backed
  SpimData as a BDV XML (BigStitcher/BigWarp interop). It persists only the
  container URI and re-runs discovery on load via `OmeZarrOpener.openLoader`
  (like `XmlIoN5ImageLoader` re-opens its store), rather than persisting the whole
  view→entry map. Covered by `XmlIoOmeZarrRoundTripIT` (open → save → reload →
  pixel load). The URI is stored verbatim (not relativized), so remote/S3
  containers round-trip; local containers are stored as absolute URIs.

## Next

- [ ] **Interactive** BDV display check (`OpenOmeZarrDemo.main` with a UI; note
  `BdvFunctions.show` won't apply `Displaysettings` — BDV-Playground does).
- [ ] Register sources into BDV-Playground's `SourceService`/`SourceTree` (so the
  attached `Displaysettings` actually colors the sources) — needs a
  bdv-playground (test-scope) dependency.
- [ ] 2D and HCS support (both require extending the owned loader — see the 2D
  assessment: singleton-z in `extract3D` + the properties' dimension handling).
- [ ] Cache: reuse Playground's global cache (the loader's
  `VolatileGlobalCellCache` is the injection point).
