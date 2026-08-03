# OME-Zarr adapter for BigDataViewer-Playground

Opens **OME-Zarr / OME-NGFF** datasets (v0.4 and v0.5) as a
[SpimData](https://github.com/mpicbg-csbd/mpicbg-spim/tree/master/src/main/java/mpicbg/spim/data)
object for [BigDataViewer](https://imagej.net/plugins/bdv/) and
[BigDataViewer-Playground](https://github.com/bigdataviewer/bigdataviewer-playground),
with the acquisition metadata that NGFF carries (spatial calibration, timepoints,
channels and display settings) mapped onto the SpimData model.

Pixels are served by a small `N5ImageLoader` specialisation
(`OmeZarrImageLoader` + `OmeZarrN5Properties`) built directly on
[bigdataviewer-core](https://github.com/bigdataviewer/bigdataviewer-core) and
[n5-universe](https://github.com/saalfeldlab/n5-universe). `OmeZarrOpener`
synthesises the `SequenceDescription`, `ViewRegistrations` and the per-view
dataset-path / hyperslice metadata the loader consumes.

## Requirements

- **Java 11+** and a Fiji/ImageJ with BigDataViewer on the classpath. Depends only
  on permissive (BSD/MIT) libraries.

## What it maps

| OME-NGFF | SpimData |
| --- | --- |
| `axes` + level-0 `scale` / `translation` | `ViewSetup` voxel size + unit, `ViewRegistration` (pixel → physical) |
| `channel` axis | one `ViewSetup` per channel, each with a `Channel` entity |
| `time` axis | `TimePoints` (per-timepoint views) |
| `omero` channels (`label`, `color`, `window`) | a `Displaysettings` entity (spimdata-extras: name + color + contrast) per `ViewSetup` |
| `bioformats2raw` series (`0`, `1`, …) | one `Tile` per series, all in one dataset |
| no `z` axis (2D image) | a single-slice volume (`z` size 1) — BDV sources are 3D |

Reading `omero` display settings needs no dependency on BigDataViewer-Playground:
the information is stored as a serializable SpimData entity, and Playground reads
it downstream to color the sources.

## Supported layouts

- A single multiscale image at the container root (e.g. `.../image.ome.zarr`).
- A **`bioformats2raw` container** whose images are integer-named child groups
  (`.../container.zarr` with `0`, `1`, … inside). These are discovered by
  probing, so you can pass the container URL directly — you do **not** have to
  append `/0`. Passing an explicit series path (`.../container.zarr/0`) also
  works.
- **2D images** (axes `y`, `x` with no `z`, optionally `c` and `t`). Since
  BigDataViewer sources are inherently 3D, these are opened as **single-slice
  volumes**: `z` has size 1, voxel depth 1 and no calibration along `z`, and only
  `x`/`y` are downsampled across resolution levels.

Both v0.4 (Zarr v2) and v0.5 (Zarr v3) are auto-detected. Remote URLs
(`https://…`, S3) and local paths are accepted.

### S3 endpoints

An `s3://bucket/key` URI carries a bucket and a key but **no endpoint**, so the
AWS SDK resolves it against Amazon's own hosts. A container on any other
S3-speaking store — EBI Embassy, Ceph/RadosGW, MinIO, an institutional store —
therefore has to be told where to connect. For example, IDR's data lives in the
`idr` bucket at `https://uk1s3.embassy.ebi.ac.uk`, so a bare
`s3://idr/zarr/v0.4/…` looks for a non-existent `idr` bucket on AWS and fails
with "No OME-NGFF multiscale metadata found".

Use the **[OME-Zarr on S3]** command (or `S3Options` when scripting) to supply
the endpoint, and optionally credentials for a private bucket. Leave the
credentials empty for a public bucket, or to pick up the ambient AWS
credentials (`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`, `~/.aws/credentials`,
instance profile, …).

Most non-Amazon stores serve **path-style** addressing (`endpoint/bucket/key`),
which is the default; turn it off for virtual-host style (`bucket.endpoint/key`).

## BigDataViewer XML round-trip

The resulting `SpimData` saves to and reloads from a BigDataViewer XML
(`XmlIoSpimData`), so it interoperates with BigStitcher / BigWarp. Only the
container URI is written to the XML; the OME-NGFF layout is re-discovered on load.
The URI is stored verbatim (not relativized), so remote/S3 containers round-trip;
local containers are stored as absolute URIs. The pixel data itself is **not**
exported — the XML always points back at the original OME-Zarr.

For an `s3://` container the endpoint, region and addressing style are saved
alongside the URI, since the URI alone does not say which store to talk to.
**Credentials are never written** — a BDV XML is a shareable plain-text document.
A private bucket therefore reloads through the ambient AWS credentials, which
have to be in place on whichever machine opens the XML.

### Not yet supported

- HCS plates / wells
- `labels` (segmentation) groups
- Writing / export of pixel data (OME-Zarr is read-only; only the BDV XML is written)
- Coordinate transformations beyond `scale` + `translation`

See [`PLAN.md`](PLAN.md) for the roadmap.

## Fiji command

| Command | Menu |
| --- | --- |
| Open an OME-Zarr dataset | `Plugins > BigDataViewer-Playground > Import > Dataset - Create [OME-Zarr]` |
| Open an OME-Zarr on an S3 store | `Plugins > BigDataViewer-Playground > Import > Dataset - Create [OME-Zarr on S3]` |

The first command takes a single location field (path or URL); the second adds an
S3 endpoint, region, addressing style and optional credentials. Both output an
`AbstractSpimData`, which BigDataViewer-Playground displays and registers.

## Scripting

```java
import ch.unige.biochem.bdv.img.omezarr.OmeZarrOpener;
import mpicbg.spim.data.generic.AbstractSpimData;

AbstractSpimData<?> spimData = OmeZarrOpener.open(
        "https://uk1s3.embassy.ebi.ac.uk/idr/zarr/v0.4/idr0062A/6001240.zarr");

// e.g. show it directly in BigDataViewer:
bdv.util.BdvFunctions.show(spimData);
```

The same container over S3, which needs the endpoint spelled out:

```java
import ch.unige.biochem.bdv.img.omezarr.S3Options;

// public bucket on a non-AWS store
AbstractSpimData<?> spimData = OmeZarrOpener.open(
        "s3://idr/zarr/v0.4/idr0062A/6001240.zarr",
        S3Options.anonymous("https://uk1s3.embassy.ebi.ac.uk"));

// private bucket: endpoint, region, path-style addressing, key pair
AbstractSpimData<?> priv = OmeZarrOpener.open(
        "s3://my-bucket/my-image.ome.zarr",
        new S3Options("https://s3.example.org", "us-east-1", true, accessKey, secretKey));
```

Note: plain `BdvFunctions.show` does not apply the `Displaysettings` entity
(channel colors/contrast) — BigDataViewer-Playground does.

## License

MIT. See [LICENSE.txt](LICENSE.txt).
