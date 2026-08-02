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

Both v0.4 (Zarr v2) and v0.5 (Zarr v3) are auto-detected. Remote URLs
(`https://…`, S3) and local paths are accepted.

## BigDataViewer XML round-trip

The resulting `SpimData` saves to and reloads from a BigDataViewer XML
(`XmlIoSpimData`), so it interoperates with BigStitcher / BigWarp. Only the
container URI is written to the XML; the OME-NGFF layout is re-discovered on load.
The URI is stored verbatim (not relativized), so remote/S3 containers round-trip;
local containers are stored as absolute URIs. The pixel data itself is **not**
exported — the XML always points back at the original OME-Zarr.

### Not yet supported

- HCS plates / wells
- `labels` (segmentation) groups
- 2D-only OME-Zarr (an image must have `z`, `y`, `x` spatial axes)
- Writing / export of pixel data (OME-Zarr is read-only; only the BDV XML is written)
- Coordinate transformations beyond `scale` + `translation`

See [`PLAN.md`](PLAN.md) for the roadmap.

## Fiji command

| Command | Menu |
| --- | --- |
| Open an OME-Zarr dataset | `Plugins > BigDataViewer-Playground > Import > Dataset - Create [OME-Zarr]` |

The command takes a single location field (path or URL) and outputs an
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

Note: plain `BdvFunctions.show` does not apply the `Displaysettings` entity
(channel colors/contrast) — BigDataViewer-Playground does.

## License

MIT. See [LICENSE.txt](LICENSE.txt).
