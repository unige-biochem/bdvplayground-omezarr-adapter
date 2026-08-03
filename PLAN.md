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
    `prepareCachedImage()` to reduce the stored nD array to 3D (`extract3D`),
    driven by a per-view `HyperSlice` (which `c`/`t` dims to pin, and whether to
    append a singleton `z` for a 2D image).
  - `OmeZarrN5Properties implements bdv.img.n5.N5Properties` — resolves per-level
    dataset paths, dimensions (via the recorded imglib2 dim of each spatial axis,
    with `z = 1` when the image has none), data type and mipmap resolutions
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
  **Which dim is which is image-dependent**: an image without a `z` axis is
  `(x,y,c,t)`, so `c`/`t` shift down. `parseImage` records the imglib2 dim of every
  axis (`-1` when absent) and everything downstream is driven by that mapping
  rather than by fixed dim indices.

## v0.1 scope (agreed)

**In:** spatial calibration + unit (from NGFF `axes` + level-0 `scale`),
timepoints (NGFF `t` axis → `TimePoints`, per-timepoint views), channels split
into one `ViewSetup` each with a `Channel` entity. Single multiscale image per
container (image at the container root).

**Out (later milestones):** `omero` channel names/colors/contrast; HCS
plate/well; `labels`; RFC-5 rich transforms; multi-image / `bioformats2raw`
series discovery; writing/export.

## Components

| File | Role |
|---|---|
| `bdv/img/omezarr/OmeZarrOpener.java` | Discovery + `SpimData` builder (the metadata layer). `open(String uri, ...)`. |
| `bdv/img/omezarr/command/OpenOmeZarrCommand.java` | SciJava `Command`: URL field → outputs `AbstractSpimData`. |
| `bdv/img/omezarr/command/OpenOmeZarrS3Command.java` | SciJava `Command`: `s3://` URI + endpoint + optional credentials. |
| `bdv/img/omezarr/S3Options.java` | S3 endpoint / region / addressing style / credentials, applied via `N5Factory.s3Configuration`. |
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
- [x] **2D-only OME-Zarr** (no `z` axis) — opened as a **single-slice volume**
  (`z` size 1), since BDV sources are inherently 3D. The whole axis layout is now
  mapping-driven instead of assuming `x,y,z,c,t`: `parseImage` records the imglib2
  dim of each axis, `OmeZarrN5Properties.getDimensions` reads `x`/`y`/`z` through it
  (reporting `z = 1` when absent), and the loader's per-view `HyperSlice` pins the
  `c`/`t` dims wherever they actually are, then appends a singleton `z`. Voxel depth
  and the `z` calibration stay at identity, and `z` is never downsampled, so the
  mipmap resolutions agree with the singleton dimension. This also unblocks the
  majority of IDR HCS plates, which are 2D.

- [x] **Regression tests** (`OmeZarrOpenerIT`) against eight pinned, immutable IDR
  datasets from the [NGFF sample catalog](https://idr.github.io/ome-ngff-samples/):
  golden assertions on format detection, channel/timepoint counts, voxel
  size + unit, omero colors/contrast, bioformats2raw series discovery, and the
  `translation` → `ViewRegistration` path. Includes a v0.4-vs-v0.5 parity test on
  the same image and a pixel-load test proving c/t hyperslicing to 3D. Three of the
  datasets are 2D (`XY`, `XYC`, and a 2D bioformats2raw container of 9 series),
  covering the singleton-z path end to end. `ExtractTo3DTest` additionally unit-tests
  the reduction offline over every `z?/c?/t?` permutation — including `2D + t` and
  `2D + c + t`, for which the catalog has no non-HCS dataset — checking both the
  resulting interval and that the correct `(c,t)` plane was selected.
  - **Opt-in** (network + native): `mvn test -Dtest=OmeZarrOpenerIT
    -Domezarr.integration=true -Djna.library.path=<dir with blosc>`. Each test
    self-skips (never fails) when the flag is off, the IDR host is unreachable, or
    native blosc is missing, so the default build stays green offline.

- [x] **XML round-trip (`XmlIo`)** — `XmlIoOmeZarrImageLoader`
  (`@ImgLoaderIo(format = "ch.unige.bdv.omezarr")`) saves/reloads an OME-Zarr-backed
  SpimData as a BDV XML (BigStitcher/BigWarp interop). It persists only the
  container URI and re-runs discovery on load via `OmeZarrOpener.openLoader`
  (like `XmlIoN5ImageLoader` re-opens its store), rather than persisting the whole
  view→entry map. Covered by `XmlIoOmeZarrRoundTripIT` (open → save → reload →
  pixel load). The URI is stored verbatim (not relativized), so remote/S3
  containers round-trip; local containers are stored as absolute URIs. For an
  `s3://` container the endpoint / region / addressing style ride along (the URI
  alone does not identify the store); credentials never do.

- [x] **S3 endpoints + credentials** (`S3Options`, `OpenOmeZarrS3Command`). An
  `s3://bucket/key` URI carries no endpoint, so the AWS SDK resolves it against
  Amazon and a container on any other S3-speaking store (EBI Embassy, Ceph,
  MinIO) is simply not found — which surfaced as the generic "no multiscale
  metadata" error. `S3Options` supplies endpoint / region / addressing style /
  optional key pair through `N5Factory.s3Configuration(Consumer<S3ClientBuilder>)`,
  which n5 applies *after* its own defaults, so each field wins. With no key pair
  it sets no credentials provider at all, preserving n5's anonymous-then-AWS-
  default-chain fallback (`AmazonS3Utils.createS3` compares the provider by
  identity to decide). The XML persists the connection but **never the secret**;
  `parse` now also chains the underlying failure as the cause and, for a bare
  `s3://`, names the missing endpoint. Covered by `S3OptionsTest` (offline, drives
  a real `S3ClientBuilder`) and `OmeZarrS3IT` (opens IDR over `s3://` and asserts
  metadata parity with the `https://` open of the same container).

## Next

- [ ] **Interactive** BDV display check (`OpenOmeZarrDemo.main` with a UI; note
  `BdvFunctions.show` won't apply `Displaysettings` — BDV-Playground does).
- [ ] Register sources into BDV-Playground's `SourceService`/`SourceTree` (so the
  attached `Displaysettings` actually colors the sources) — needs a
  bdv-playground (test-scope) dependency.
- [ ] HCS plate/well support (plate → wells → fields discovery on top of the
  existing image parsing; most IDR plates are 2D, which is now handled).
- [ ] Cache: reuse Playground's global cache (the loader's
  `VolatileGlobalCellCache` is the injection point).
