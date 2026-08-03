/*-
 * #%L
 * Opens OME-Zarr (OME-NGFF v0.4 / v0.5) as SpimData for BigDataViewer and BigDataViewer-Playground.
 * %%
 * Copyright (C) 2026 Department of Biochemistry, University of Geneva
 * %%
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 * #L%
 */
package ch.unige.biochem.bdv.img.omezarr;

import ch.unige.biochem.bdv.img.omezarr.OmeZarrImageLoader.HyperSlice;
import ch.unige.biochem.bdv.img.omezarr.OmeZarrN5Properties.ImageEntry;
import com.google.gson.GsonBuilder;
import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.MissingViews;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.realtransform.AffineTransform3D;
import spimdata.SpimDataHelper;
import spimdata.util.Displaysettings;
import spimdata.util.Field;
import spimdata.util.Plate;
import spimdata.util.Well;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.janelia.saalfeldlab.n5.universe.metadata.axes.Axis;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.OmeNgffMetadata;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.OmeNgffMultiScaleMetadata;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.OmeNgffMultiScaleMetadata.OmeNgffDataset;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.coordinateTransformations.CoordinateTransformation;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.coordinateTransformations.CoordinateTransformationAdapter;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.coordinateTransformations.ScaleCoordinateTransformation;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.coordinateTransformations.TranslationCoordinateTransformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds a {@link SpimData} from an OME-Zarr (OME-NGFF v0.4 / v0.5) container,
 * with spatial calibration + unit, timepoints and channels-as-{@link ViewSetup}s.
 * <p>
 * By default the calibration keeps the unit the NGFF axes declare; pass a
 * {@link WorldUnit} to convert it into a common world unit instead (or to
 * normalise it for BigStitcher).
 * <p>
 * Pixels are served by {@link OmeZarrImageLoader} (an {@code N5ImageLoader}
 * specialisation) using {@link OmeZarrN5Properties}; here we synthesise the
 * {@code SequenceDescription}, {@code ViewRegistrations} and the per-view
 * dataset-path / hyperslice metadata those need.
 * <p>
 * Channel names and colors/contrast are read from the transitional
 * {@code omero} block and attached as a {@link Displaysettings} entity per
 * {@link ViewSetup}, so BigDataViewer-Playground can apply them without this
 * library depending on it.
 * <p>
 * Supports a single image at the container root, a {@code bioformats2raw}
 * container whose images are integer-named child groups ({@code 0, 1, …})
 * discovered by probing, and an <b>HCS plate</b>, whose field images are
 * enumerated from the {@code plate} / {@code well} metadata and tagged with
 * {@link spimdata.util.Plate} / {@link spimdata.util.Well} /
 * {@link spimdata.util.Field} entities (see {@link HcsOptions} for how much of a
 * plate is opened, and how carefully). Each image needs axes {@code (y,x)},
 * optionally {@code z}, plus optional {@code c} and {@code t}; a 2D image (no
 * {@code z}) is presented as a single-slice volume, since BigDataViewer sources
 * are 3D. {@code labels} groups are out of scope for now (see {@code PLAN.md}).
 */
public class OmeZarrOpener {

	private static final Logger log = LoggerFactory.getLogger(OmeZarrOpener.class);

	/** Multiscale group path for a single image located at the container root. */
	private static final String ROOT_GROUP = "";

	/** Safety cap when probing bioformats2raw series "0", "1", … over HTTP. */
	private static final int MAX_SERIES = 100_000;

	private OmeZarrOpener() {}

	/**
	 * Opens an OME-Zarr container as a {@link SpimData}.
	 *
	 * @param uriString file path or URL (S3/https/…) of the {@code .ome.zarr}
	 *                  container whose root holds a multiscale image.
	 * @return a {@link SpimData} backed by {@link OmeZarrImageLoader}.
	 */
	public static AbstractSpimData<?> open(final String uriString) {
		return open(uriString, null);
	}

	/**
	 * Opens an OME-Zarr container as a {@link SpimData}, with explicit S3 settings.
	 * <p>
	 * An {@code s3://bucket/key} URI carries no endpoint, so a container on an
	 * object store that is not AWS needs {@code s3} to say where to connect (and,
	 * for a private bucket, with which credentials).
	 *
	 * @param uriString file path or URL (S3/https/…) of the {@code .ome.zarr}
	 *                  container whose root holds a multiscale image.
	 * @param s3        S3 connection settings, or {@code null} for the defaults
	 *                  n5-universe infers from the URI (which is all a plain
	 *                  {@code https://} or local container needs).
	 * @return a {@link SpimData} backed by {@link OmeZarrImageLoader}.
	 */
	public static AbstractSpimData<?> open(final String uriString, final S3Options s3) {
		return open(uriString, s3, null);
	}

	/**
	 * Opens an OME-Zarr container as a {@link SpimData}, with explicit S3 and HCS
	 * settings.
	 *
	 * @param uriString file path or URL (S3/https/…) of the {@code .ome.zarr}
	 *                  container whose root holds a multiscale image or a
	 *                  {@code plate}.
	 * @param s3        S3 connection settings, or {@code null} for the defaults.
	 * @param hcs       how much of an HCS plate to open and how carefully, or
	 *                  {@code null} for {@link HcsOptions#DEFAULT}. Inert for a
	 *                  container that is not a plate.
	 * @return a {@link SpimData} backed by {@link OmeZarrImageLoader}.
	 */
	public static AbstractSpimData<?> open(final String uriString, final S3Options s3,
			final HcsOptions hcs) {
		return open(uriString, s3, hcs, null);
	}

	/**
	 * Opens an OME-Zarr container as a {@link SpimData}, with explicit S3, HCS and
	 * world-unit settings.
	 *
	 * @param uriString file path or URL (S3/https/…) of the {@code .ome.zarr}
	 *                  container whose root holds a multiscale image or a
	 *                  {@code plate}.
	 * @param s3        S3 connection settings, or {@code null} for the defaults.
	 * @param hcs       how much of an HCS plate to open, or {@code null} for
	 *                  {@link HcsOptions#DEFAULT}.
	 * @param unit      the unit to express voxel sizes and registrations in, or
	 *                  {@code null} for {@link WorldUnit#AS_STORED}, which keeps the
	 *                  unit the NGFF axes declare.
	 * @return a {@link SpimData} backed by {@link OmeZarrImageLoader}.
	 */
	public static AbstractSpimData<?> open(final String uriString, final S3Options s3,
			final HcsOptions hcs, final WorldUnit unit) {
		final URI uri = toUri(uriString);
		final HcsOptions opts = hcs != null ? hcs : HcsOptions.DEFAULT;
		final WorldUnit world = unit != null ? unit : WorldUnit.AS_STORED;
		final Parsed p = parse(uri, s3, opts, world);
		final SequenceDescription seq =
				new SequenceDescription(new TimePoints(p.timePoints), p.setups, null, p.missingViews);
		final OmeZarrImageLoader loader =
				new OmeZarrImageLoader(p.n5, uri, seq, p.props, p.hyperSlices, s3, opts);
		seq.setImgLoader(loader);
		final SpimData spimData = new SpimData(new File("."), seq, new ViewRegistrations(p.registrations));

		if (world == WorldUnit.BIGSTITCHER_COMPATIBLE) {
			// The calibration is already normalised (see applyWorldUnit); what is left
			// is to drop Displaysettings. BigStitcher refuses to fuse tiles whose
			// entities differ, even for an entity that has nothing to do with the
			// grouping — and Displaysettings differs per setup by construction, since
			// it carries that channel's own color and contrast. Plate/Well/Field are
			// kept, as the Bio-Formats importer keeps them.
			SpimDataHelper.removeEntities(spimData, Displaysettings.class);
		}
		return spimData;
	}

	/**
	 * Rebuilds only the {@link OmeZarrImageLoader} for an already-restored
	 * {@link AbstractSequenceDescription}. Used by the XML deserializer
	 * ({@link XmlIoOmeZarrImageLoader}): the BDV XML restores the sequence
	 * description (setups, registrations, timepoints), and this re-runs OME-NGFF
	 * discovery to reconstruct the pixel loader. Discovery is deterministic, so the
	 * per-view path / hyperslice metadata lines up with the setup and timepoint ids
	 * in {@code seq} as long as the container is unchanged.
	 *
	 * @param uriString the container URI stored in the XML.
	 * @param seq       the sequence description restored from the XML.
	 */
	public static OmeZarrImageLoader openLoader(final String uriString,
			final AbstractSequenceDescription<?, ?, ?> seq) {
		return openLoader(uriString, null, seq);
	}

	/**
	 * As {@link #openLoader(String, AbstractSequenceDescription)}, with explicit S3
	 * settings for a container on a non-AWS or private endpoint.
	 *
	 * @param uriString the container URI stored in the XML.
	 * @param s3        S3 connection settings, or {@code null} for the defaults.
	 * @param seq       the sequence description restored from the XML.
	 */
	public static OmeZarrImageLoader openLoader(final String uriString, final S3Options s3,
			final AbstractSequenceDescription<?, ?, ?> seq) {
		return openLoader(uriString, s3, null, seq);
	}

	/**
	 * As {@link #openLoader(String, S3Options, AbstractSequenceDescription)}, with
	 * explicit HCS settings.
	 * <p>
	 * These have to be the settings the dataset was originally opened with: capping
	 * discovery changes which field images exist and therefore which setup ids they
	 * carry, so a saved plate only lines up with its XML when it is re-discovered
	 * the same way. {@link XmlIoOmeZarrImageLoader} persists them for exactly that
	 * reason.
	 *
	 * @param uriString the container URI stored in the XML.
	 * @param s3        S3 connection settings, or {@code null} for the defaults.
	 * @param hcs       the HCS settings stored in the XML, or {@code null} for
	 *                  {@link HcsOptions#DEFAULT}.
	 * @param seq       the sequence description restored from the XML.
	 */
	public static OmeZarrImageLoader openLoader(final String uriString, final S3Options s3,
			final HcsOptions hcs, final AbstractSequenceDescription<?, ?, ?> seq) {
		final URI uri = toUri(uriString);
		final HcsOptions opts = hcs != null ? hcs : HcsOptions.DEFAULT;
		// The world unit only shapes the setups and registrations, which the XML has
		// already restored, so the loader can be rebuilt without knowing about it.
		final Parsed p = parse(uri, s3, opts, WorldUnit.AS_STORED);
		return new OmeZarrImageLoader(p.n5, uri, seq, p.props, p.hyperSlices, s3, opts);
	}

	/** Everything discovery/parsing yields, shared by {@link #open} and {@link #openLoader}. */
	private static final class Parsed {
		N5Reader n5;
		List<ViewSetup> setups;
		List<ViewRegistration> registrations;
		List<TimePoint> timePoints;
		MissingViews missingViews;
		OmeZarrN5Properties props;
		Map<ViewId, HyperSlice> hyperSlices;
	}

	/**
	 * Detects the storage format, discovers the image group(s) and parses each
	 * image into the {@link ViewSetup}s / {@link ViewRegistration}s and the
	 * per-view path / hyperslice maps the loader consumes.
	 */
	private static Parsed parse(final URI uri, final S3Options s3, final HcsOptions hcs,
			final WorldUnit unit) {

		// --- 1. Detect the storage format ------------------------------------
		// OME-NGFF v0.5 is Zarr v3 (StorageFormat.ZARR); v0.4 is Zarr v2 (ZARR2).
		// A URL may point at a single image, at a bioformats2raw container whose
		// images are child groups "0", "1", ... (no multiscales at root), or at an
		// HCS plate, which has no multiscales at the root either but does carry a
		// "plate" attribute naming its wells.
		StorageFormat format = null;
		Exception lastFailure = null;
		for (final StorageFormat candidate : new StorageFormat[] { StorageFormat.ZARR, StorageFormat.ZARR2 }) {
			try {
				final N5Reader probe = openReader(candidate, uri, s3);
				if (readMultiscale(probe, ROOT_GROUP, candidate) != null
						|| readMultiscale(probe, "/0", candidate) != null
						|| readPlate(probe, candidate) != null) {
					format = candidate;
					break;
				}
			} catch (final Exception e) {
				log.debug("Not readable as {}: {}", candidate, e.getMessage());
				lastFailure = e;
			}
		}
		if (format == null) {
			// Every probe came up empty. That is usually a layout we don't support,
			// but it is also what a connection problem looks like from here — an
			// s3:// URI without an endpoint, say — so carry the last failure as the
			// cause rather than reporting only the layout diagnosis.
			throw new IllegalArgumentException(
					"No OME-NGFF multiscale metadata found at " + uri + ", its child \"0\", " +
					"and no HCS \"plate\" attribute either. If this is an unusual layout it is " +
					"not yet supported; otherwise point at the image group directly." +
					(uri.getScheme() != null && uri.getScheme().equalsIgnoreCase("s3") && s3 == null
							? " Note that an s3:// URI carries no endpoint: for a store that is not"
							+ " AWS, open it with S3 settings (see the [OME-Zarr on S3] command)."
							: ""),
					lastFailure);
		}

		// --- 2. Discover the image group(s) inside the container -------------
		final N5Reader n5 = openReader(format, uri, s3);
		final PlateMeta plate = readPlate(n5, format);
		final List<ImageRef> refs;
		if (plate != null) {
			refs = discoverPlateImages(n5, format, plate, hcs);
			log.info("Opened {} as {}: HCS plate \"{}\", {} field image(s), {}",
					uri, format, plate.name, refs.size(), hcs);
		} else {
			refs = new ArrayList<>();
			for (final String path : discoverImagePaths(n5, format)) {
				refs.add(new ImageRef(path, null));
			}
			log.info("Opened {} as {}: {} image(s)", uri, format, refs.size());
		}
		final boolean multiImage = refs.size() > 1;

		// --- 3. Parse each image's metadata (calibration, channels, omero) ---
		// Every field of a plate comes from the same acquisition, so unless asked
		// to be strict we read one field and reuse its layout for all the others —
		// the difference between three HTTP round-trips and two per field.
		final List<ImageInfo> images = new ArrayList<>();
		int globalMaxT = 1;
		if (plate != null && !hcs.isStrictPerField()) {
			final ImageInfo template = parseTemplateImage(n5, refs, format);
			for (final ImageRef ref : refs) {
				images.add(template.copyFor(ref.path));
			}
			globalMaxT = template.sizeT;
		} else {
			for (final ImageRef ref : refs) {
				final ImageInfo info = parseImage(n5, ref.path, format);
				images.add(info);
				globalMaxT = Math.max(globalMaxT, info.sizeT);
			}
		}

		// --- 3b. Express the calibration in the requested world unit ---------
		applyWorldUnit(images, unit);

		// --- 4. Build ViewSetups / registrations / metadata maps -------------
		final List<ViewSetup> setups = new ArrayList<>();
		final List<ViewRegistration> registrations = new ArrayList<>();
		final List<ViewId> missing = new ArrayList<>();
		final Map<ViewId, ImageEntry> byView = new HashMap<>();      // dataset paths + mipmaps
		final Map<Integer, ImageEntry> bySetup = new HashMap<>();    // same, per setup
		final Map<ViewId, HyperSlice> hyperSlices = new HashMap<>(); // nD array → 3D view

		// One shared entity instance per plate / per well, so the SpimData entity
		// lists (and the XML) hold each of them exactly once.
		final Plate plateEntity = plate != null ? new Plate(0, plateName(plate, uri)) : null;
		final Map<Integer, Well> wellEntities = new HashMap<>();

		int setupId = 0;
		for (int s = 0; s < images.size(); s++) {
			final ImageInfo img = images.get(s);
			final HcsCoords coords = refs.get(s).hcs;
			final ImageEntry entry = new ImageEntry(
					img.levelPaths, img.mipmapResolutions, img.dimX, img.dimY, img.dimZ);
			for (int c = 0; c < img.sizeC; c++) {
				final OmeroChannel oc = (img.omero != null && img.omero.channels != null
						&& c < img.omero.channels.length) ? img.omero.channels[c] : null;
				final String channelLabel = (oc != null && oc.label != null && !oc.label.isEmpty())
						? oc.label : "channel " + c;
				final String setupName;
				if (coords != null) {
					setupName = coords.wellName + " - f" + coords.fieldId + " - " + channelLabel;
				} else if (multiImage) {
					setupName = "s" + s + " - " + channelLabel;
				} else {
					setupName = channelLabel;
				}

				final ViewSetup vs = new ViewSetup(
						setupId,
						setupName,
						img.size,
						img.voxel,
						new Tile(s), // one tile per image, so series/fields stay grouped
						new Channel(c, channelLabel),
						new Angle(0),
						new Illumination(0));

				// Plate / well / field entities (spimdata-extras), so downstream tools
				// can group and filter the sources by their position on the plate.
				if (coords != null) {
					vs.setAttribute(plateEntity);
					vs.setAttribute(wellEntities.computeIfAbsent(coords.wellId,
							id -> new Well(id, coords.wellName, coords.row, coords.column)));
					vs.setAttribute(new Field(coords.fieldId));
				}

				// Display settings entity (color + contrast), read by BDV-Playground.
				final Displaysettings ds = new Displaysettings(setupId, setupName);
				if (oc != null) {
					final int[] rgba = parseHexColor(oc.color);
					if (rgba != null) {
						ds.color = rgba;
						ds.isSet = true;
					}
					if (oc.window != null) {
						if (oc.window.start != null) ds.min = oc.window.start;
						if (oc.window.end != null) ds.max = oc.window.end;
						ds.isSet = true;
					}
				}
				vs.setAttribute(ds);
				setups.add(vs);
				bySetup.put(setupId, entry);

				for (int t = 0; t < globalMaxT; t++) {
					final ViewId viewId = new ViewId(t, setupId);
					if (t < img.sizeT) {
						byView.put(viewId, entry);
						hyperSlices.put(viewId, hyperSlice(img, c, t));
						registrations.add(new ViewRegistration(t, setupId, img.calibration));
					} else {
						missing.add(viewId); // this image has fewer timepoints than the union
					}
				}
				setupId++;
			}
		}

		// --- 5. Collect timepoints + missing views ---------------------------
		final List<TimePoint> timePointList = new ArrayList<>();
		for (int t = 0; t < globalMaxT; t++) {
			timePointList.add(new TimePoint(t));
		}

		final Parsed parsed = new Parsed();
		parsed.n5 = n5;
		parsed.setups = setups;
		parsed.registrations = registrations;
		parsed.timePoints = timePointList;
		parsed.missingViews = missing.isEmpty() ? null : new MissingViews(missing);
		parsed.props = new OmeZarrN5Properties(byView, bySetup);
		parsed.hyperSlices = hyperSlices;
		return parsed;
	}

	/**
	 * Opens an OME-Zarr reader for the given storage format, with the OME-NGFF
	 * coordinate-transformation Gson adapter registered so that {@code scale} /
	 * {@code translation} deserialize. {@link StorageFormat#ZARR} = Zarr v3
	 * (NGFF 0.5), {@link StorageFormat#ZARR2} = Zarr v2 (NGFF 0.4).
	 * <p>
	 * {@code s3} (when given) configures the S3 client for {@code s3://} URIs; it
	 * is inert for local and {@code https://} containers, which n5 reads without
	 * an S3 client at all.
	 */
	private static N5Reader openReader(final StorageFormat format, final URI uri, final S3Options s3) {
		final GsonBuilder gson = new GsonBuilder().registerTypeAdapter(
				CoordinateTransformation.class, new CoordinateTransformationAdapter());
		final N5Factory factory = new N5Factory().gsonBuilder(gson);
		final Consumer<S3ClientBuilder> s3Config = s3 == null ? null : s3.asBuilderConfig();
		if (s3Config != null) {
			factory.s3Configuration(s3Config);
		}
		return factory.openReader(format, uri);
	}

	/**
	 * Lists the multiscale image group(s) inside the container. A single image
	 * lives at the root; a {@code bioformats2raw} container has no multiscales at
	 * the root and holds its images as integer-named child groups {@code 0, 1, …}
	 * — enumerated here by probing until one is missing (works over plain HTTP,
	 * which cannot list directories).
	 */
	private static List<String> discoverImagePaths(final N5Reader n5, final StorageFormat format) {
		if (readMultiscale(n5, ROOT_GROUP, format) != null) {
			return java.util.Collections.singletonList(ROOT_GROUP);
		}
		final List<String> paths = new ArrayList<>();
		for (int s = 0; s < MAX_SERIES; s++) {
			final String path = "/" + s;
			OmeNgffMultiScaleMetadata ms = null;
			try {
				ms = readMultiscale(n5, path, format);
			} catch (final Exception e) {
				break;
			}
			if (ms == null) break;
			paths.add(path);
		}
		if (paths.isEmpty()) {
			throw new IllegalArgumentException(
					"Container has no multiscale image at the root, no series \"0\" and no " +
					"HCS \"plate\" attribute. Other layouts are not yet supported.");
		}
		return paths;
	}

	/**
	 * Lists the field images of an HCS plate, in row/column order of the wells and
	 * in the order each well lists its images, capped by {@code hcs}.
	 * <p>
	 * Nothing is probed: the {@code plate} attribute names every well group, and
	 * each well group's {@code well} attribute names every field image inside it,
	 * so this costs one attribute read per well. A well that is listed but cannot
	 * be read is logged and skipped rather than failing the whole plate — a partial
	 * upload is a real thing on a public store.
	 */
	private static List<ImageRef> discoverPlateImages(final N5Reader n5, final StorageFormat format,
			final PlateMeta plate, final HcsOptions hcs) {
		if (plate.wells == null || plate.wells.length == 0) {
			throw new IllegalArgumentException("HCS plate \"" + plate.name + "\" lists no wells.");
		}

		// Sort by (row, column) so the plate opens in reading order. Discovery has
		// to be deterministic in any case: XmlIoOmeZarrImageLoader re-runs it on
		// load and relies on the setup ids coming out the same.
		final List<PlateWell> wells = new ArrayList<>();
		for (final PlateWell w : plate.wells) {
			if (w != null && w.path != null) wells.add(w);
		}
		wells.sort(Comparator.comparingInt((PlateWell w) -> w.rowIndex == null ? 0 : w.rowIndex)
				.thenComparingInt(w -> w.columnIndex == null ? 0 : w.columnIndex)
				.thenComparing(w -> w.path));

		final int nWells = hcs.limitWells(wells.size());
		final List<ImageRef> refs = new ArrayList<>();
		for (int w = 0; w < nWells; w++) {
			final PlateWell well = wells.get(w);
			final String wellPath = "/" + trimSlashes(well.path);
			final WellMeta meta;
			try {
				meta = readWell(n5, wellPath, format);
			} catch (final Exception e) {
				log.warn("Skipping well {}: {}", well.path, e.getMessage());
				continue;
			}
			if (meta == null || meta.images == null || meta.images.length == 0) {
				log.warn("Skipping well {}: no \"well\" metadata / no images listed.", well.path);
				continue;
			}
			final int row = well.rowIndex != null ? well.rowIndex : 0;
			final int column = well.columnIndex != null ? well.columnIndex : 0;
			final HcsCoords base = new HcsCoords(w, wellName(plate, well), row, column, 0);
			final int nFields = hcs.limitFields(meta.images.length);
			for (int f = 0; f < nFields; f++) {
				final WellImage image = meta.images[f];
				if (image == null || image.path == null) continue;
				refs.add(new ImageRef(wellPath + "/" + trimSlashes(image.path), base.withField(f)));
			}
		}
		if (refs.isEmpty()) {
			throw new IllegalArgumentException(
					"HCS plate \"" + plate.name + "\" yielded no readable field image.");
		}
		return refs;
	}

	/**
	 * Parses the first field image that can be read, to stand in for every field of
	 * the plate (see {@link HcsOptions}). The first field is normally the one used;
	 * later ones are tried only if it is unreadable.
	 */
	private static ImageInfo parseTemplateImage(final N5Reader n5, final List<ImageRef> refs,
			final StorageFormat format) {
		Exception lastFailure = null;
		for (final ImageRef ref : refs) {
			try {
				return parseImage(n5, ref.path, format);
			} catch (final Exception e) {
				log.warn("Field {} is not readable, trying the next one: {}", ref.path, e.getMessage());
				lastFailure = e;
			}
		}
		throw new IllegalArgumentException(
				"No readable field image among the " + refs.size() + " listed by the plate metadata.",
				lastFailure);
	}

	/** A plate's display name, falling back to the last path segment of the container. */
	private static String plateName(final PlateMeta plate, final URI uri) {
		if (plate.name != null && !plate.name.isEmpty()) return plate.name;
		final String path = trimSlashes(uri.getPath() == null ? "" : uri.getPath());
		final int slash = path.lastIndexOf('/');
		final String last = slash < 0 ? path : path.substring(slash + 1);
		return last.isEmpty() ? "plate" : last;
	}

	/**
	 * A well's name, as row label + column label ({@code "C3"}). The plate's own
	 * {@code rows}/{@code columns} labels are authoritative — they need not be
	 * letters and digits — with the well's group path ({@code "C/3"}) as fallback.
	 */
	private static String wellName(final PlateMeta plate, final PlateWell well) {
		final String row = label(plate.rows, well.rowIndex);
		final String column = label(plate.columns, well.columnIndex);
		if (row != null && column != null) return row + column;
		return trimSlashes(well.path).replace("/", "");
	}

	private static String label(final PlateNamed[] entries, final Integer index) {
		if (entries == null || index == null || index < 0 || index >= entries.length) return null;
		final PlateNamed e = entries[index];
		return (e == null || e.name == null || e.name.isEmpty()) ? null : e.name;
	}

	private static String trimSlashes(final String s) {
		int from = 0;
		int to = s.length();
		while (from < to && s.charAt(from) == '/') from++;
		while (to > from && s.charAt(to - 1) == '/') to--;
		return s.substring(from, to);
	}

	/** One image to open: its group path, plus where it sits on a plate (or {@code null}). */
	private static final class ImageRef {
		final String path;
		final HcsCoords hcs;

		ImageRef(final String path, final HcsCoords hcs) {
			this.path = path;
			this.hcs = hcs;
		}
	}

	/** Where a field image sits on its plate. */
	private static final class HcsCoords {
		final int wellId;
		final String wellName;
		final int row, column;
		final int fieldId;

		HcsCoords(final int wellId, final String wellName, final int row, final int column,
				final int fieldId) {
			this.wellId = wellId;
			this.wellName = wellName;
			this.row = row;
			this.column = column;
			this.fieldId = fieldId;
		}

		HcsCoords withField(final int field) {
			return new HcsCoords(wellId, wellName, row, column, field);
		}
	}

	/**
	 * Rewrites every image's voxel size and pixel&rarr;physical transform into the
	 * requested world unit, in place. Called once for the whole container, so a
	 * multi-image container or an HCS plate is converted by one common factor and
	 * its images stay in the same world space.
	 * <p>
	 * {@link WorldUnit#AS_STORED} does nothing. {@link WorldUnit#PIXEL} drops the
	 * calibration entirely. A metric unit scales by the ratio of the two units,
	 * unless the image declares no length unit at all — an uncalibrated image
	 * cannot be placed in a metric world, and inventing a factor would be worse
	 * than leaving it alone, so it is kept as stored and a warning is logged.
	 * {@link WorldUnit#BIGSTITCHER_COMPATIBLE} divides by the first image's
	 * {@code x} voxel size, so one pixel along {@code x} measures 1 while the
	 * {@code y/x} and {@code z/x} anisotropy is preserved.
	 */
	private static void applyWorldUnit(final List<ImageInfo> images, final WorldUnit unit) {
		if (unit == WorldUnit.AS_STORED || images.isEmpty()) return;

		if (unit == WorldUnit.PIXEL) {
			for (final ImageInfo img : images) {
				img.setCalibration(1, 1, 1, 0, 0, 0, unit.unitName());
			}
			log.info("  world unit: pixel — physical calibration dropped");
			return;
		}

		final double factor;
		if (unit == WorldUnit.BIGSTITCHER_COMPATIBLE) {
			final double sx = images.get(0).voxel.dimension(0);
			if (!(sx > 0) || !Double.isFinite(sx)) {
				log.warn("Cannot normalise for BigStitcher: the first image has an x voxel size "
						+ "of {}. Keeping the calibration as stored.", sx);
				return;
			}
			factor = 1.0 / sx;
		} else {
			final String stored = images.get(0).voxel.unit();
			factor = unit.factorFrom(stored);
			if (Double.isNaN(factor)) {
				log.warn("Cannot express \"{}\" in {}: the image declares no length unit. "
						+ "Keeping the calibration as stored.", stored, unit.unitName());
				return;
			}
		}

		for (final ImageInfo img : images) {
			final double[] m = img.calibration.getRowPackedCopy();
			img.setCalibration(
					img.voxel.dimension(0) * factor,
					img.voxel.dimension(1) * factor,
					img.voxel.dimension(2) * factor,
					m[3] * factor, m[7] * factor, m[11] * factor,
					unit.unitName());
		}
		log.info("  world unit: {} (calibration scaled by {})", unit.unitName(), factor);
	}

	/** Parses one multiscale image group into calibration + channel metadata. */
	private static ImageInfo parseImage(final N5Reader n5, final String path, final StorageFormat format) {
		final OmeNgffMultiScaleMetadata ms = readMultiscale(n5, path, format);
		if (ms == null) {
			throw new IllegalArgumentException("No multiscale metadata at " + path);
		}

		// Map NGFF axes (file order t,c,z,y,x) to imglib2 dims (reversed).
		final Axis[] axes = ms.axes;
		final int nAxes = axes.length;
		int posX = -1;
		int dimX = -1, dimY = -1, dimZ = -1, dimC = -1, dimT = -1;
		for (int p = 0; p < nAxes; p++) {
			final int dim = nAxes - 1 - p;
			final String type = axes[p].getType();
			final String name = axes[p].getName();
			if (Axis.CHANNEL.equalsIgnoreCase(type)) {
				dimC = dim;
			} else if (Axis.TIME.equalsIgnoreCase(type)) {
				dimT = dim;
			} else if (Axis.SPACE.equalsIgnoreCase(type) || type == null) {
				if ("x".equalsIgnoreCase(name)) { dimX = dim; posX = p; }
				else if ("y".equalsIgnoreCase(name)) { dimY = dim; }
				else if ("z".equalsIgnoreCase(name)) { dimZ = dim; }
			}
		}
		if (dimX < 0 || dimY < 0) {
			throw new IllegalArgumentException(
					"Image at " + path + " lacks the x and y spatial axes.");
		}

		final String level0Path = path + "/" + ms.datasets[0].path;
		final long[] dims = n5.getDatasetAttributes(level0Path).getDimensions();

		final ImageInfo info = new ImageInfo();
		info.path = path;
		info.dimX = dimX;
		info.dimY = dimY;
		info.dimZ = dimZ;
		info.dimC = dimC;
		info.dimT = dimT;
		info.sizeC = dimC >= 0 ? (int) dims[dimC] : 1;
		info.sizeT = dimT >= 0 ? (int) dims[dimT] : 1;
		// BDV sources are 3D, so a 2D image (no z axis) becomes a single z slice.
		info.size = new FinalDimensions(dims[dimX], dims[dimY], dimZ >= 0 ? dims[dimZ] : 1);

		// scale/translation come back in imglib2 dim order (see note in class doc).
		// Without a z axis there is nothing to calibrate along z: keep it identity.
		final double[] scale = level0Transform(ms.datasets[0], true);
		final double[] trans = level0Transform(ms.datasets[0], false);
		final double sx = scale != null ? scale[dimX] : 1.0;
		final double sy = scale != null ? scale[dimY] : 1.0;
		final double sz = (scale != null && dimZ >= 0) ? scale[dimZ] : 1.0;
		final double tx = trans != null ? trans[dimX] : 0.0;
		final double ty = trans != null ? trans[dimY] : 0.0;
		final double tz = (trans != null && dimZ >= 0) ? trans[dimZ] : 0.0;

		final String unit = axes[posX].getUnit() != null ? axes[posX].getUnit() : "pixel";
		info.voxel = new FinalVoxelDimensions(unit, sx, sy, sz);
		info.calibration = new AffineTransform3D();
		info.calibration.set(
				sx, 0, 0, tx,
				0, sy, 0, ty,
				0, 0, sz, tz);

		// Per-level dataset paths and mipmap resolutions (relative to level 0).
		final int nLevels = ms.datasets.length;
		info.levelNames = new String[nLevels];
		info.levelPaths = new String[nLevels];
		info.mipmapResolutions = new double[nLevels][3];
		final int[] spatialDims = { dimX, dimY, dimZ };
		for (int l = 0; l < nLevels; l++) {
			info.levelNames[l] = ms.datasets[l].path;
			info.levelPaths[l] = path + "/" + info.levelNames[l];
			final double[] scaleL = level0Transform(ms.datasets[l], true);
			for (int k = 0; k < 3; k++) {
				// A 2D image (d < 0 for z) only downsamples in x/y: keep z at 1.
				final int d = spatialDims[k];
				final double r = (d >= 0 && scaleL != null && scale != null && scale[d] != 0.0)
						? scaleL[d] / scale[d] : 1.0;
				info.mipmapResolutions[l][k] = Math.round(r * 10000) / 10000d;
			}
		}

		info.omero = readOmero(n5, path);

		log.info("  image {}: {}, {} channel(s), {} timepoint(s), {} level(s), voxel [{}, {}, {}] {}",
				path, dimZ >= 0 ? "3D" : "2D (single z slice)",
				info.sizeC, info.sizeT, nLevels, sx, sy, sz, unit);
		return info;
	}

	/** Per-image parsed metadata used to assemble the SpimData. */
	private static class ImageInfo {
		String path;
		/** imglib2 dim of each axis, or -1 when the image has no such axis. */
		int dimX, dimY, dimZ, dimC, dimT;
		int sizeC, sizeT;
		FinalDimensions size;
		VoxelDimensions voxel;
		AffineTransform3D calibration;
		/** dataset name of each resolution level, relative to {@link #path}. */
		String[] levelNames;
		String[] levelPaths;
		double[][] mipmapResolutions;
		Omero omero;

		/**
		 * Replaces the voxel size and the pixel&rarr;physical transform, e.g. after a
		 * change of world unit. Both are rebuilt rather than mutated, since the HCS
		 * fast path shares one {@link #voxel} instance across a whole plate.
		 */
		void setCalibration(final double sx, final double sy, final double sz,
				final double tx, final double ty, final double tz, final String unitName) {
			voxel = new FinalVoxelDimensions(unitName, sx, sy, sz);
			calibration = new AffineTransform3D();
			calibration.set(
					sx, 0, 0, tx,
					0, sy, 0, ty,
					0, 0, sz, tz);
		}

		/**
		 * This metadata, re-pointed at another image group. Used for the fields of an
		 * HCS plate, which share their layout with the field this was parsed from (see
		 * {@link HcsOptions}): only the group path differs, so only the per-level
		 * dataset paths are rebuilt.
		 */
		ImageInfo copyFor(final String otherPath) {
			final ImageInfo copy = new ImageInfo();
			copy.path = otherPath;
			copy.dimX = dimX;
			copy.dimY = dimY;
			copy.dimZ = dimZ;
			copy.dimC = dimC;
			copy.dimT = dimT;
			copy.sizeC = sizeC;
			copy.sizeT = sizeT;
			copy.size = size;
			copy.voxel = voxel;
			copy.calibration = calibration.copy(); // each view registration gets its own
			copy.levelNames = levelNames;
			copy.levelPaths = new String[levelNames.length];
			for (int l = 0; l < levelNames.length; l++) {
				copy.levelPaths[l] = otherPath + "/" + levelNames[l];
			}
			copy.mipmapResolutions = mipmapResolutions;
			copy.omero = omero;
			return copy;
		}
	}

	/**
	 * Describes, for {@link OmeZarrImageLoader}, how to reduce {@code img}'s stored
	 * array to the 3D view of channel {@code c} at timepoint {@code t}: the c/t axes
	 * are pinned at their imglib2 dimensions (which depend on the image's axes), and
	 * a 2D image additionally gets a singleton z appended.
	 */
	private static HyperSlice hyperSlice(final ImageInfo img, final int c, final int t) {
		final int n = (img.dimC >= 0 ? 1 : 0) + (img.dimT >= 0 ? 1 : 0);
		final int[] dims = new int[n];
		final long[] indices = new long[n];
		int i = 0;
		if (img.dimC >= 0) { dims[i] = img.dimC; indices[i] = c; i++; }
		if (img.dimT >= 0) { dims[i] = img.dimT; indices[i] = t; i++; }
		// NGFF's axis order puts t outside c, so c already comes first in imglib2
		// order; sort defensively, since the loader slices from the highest dim down.
		if (n == 2 && dims[0] > dims[1]) {
			final int d = dims[0]; dims[0] = dims[1]; dims[1] = d;
			final long x = indices[0]; indices[0] = indices[1]; indices[1] = x;
		}
		return new HyperSlice(dims, indices, img.dimZ < 0);
	}

	/** Extracts the level-0 scale (or translation) vector, in NGFF axes order. */
	private static double[] level0Transform(final OmeNgffDataset dataset, final boolean scale) {
		if (dataset.coordinateTransformations == null) return null;
		for (final CoordinateTransformation<?> ct : dataset.coordinateTransformations) {
			if (scale && ct instanceof ScaleCoordinateTransformation) {
				return ((ScaleCoordinateTransformation) ct).getScale();
			}
			if (!scale && ct instanceof TranslationCoordinateTransformation) {
				return ((TranslationCoordinateTransformation) ct).getTranslation();
			}
		}
		return null;
	}

	/**
	 * Reads the first multiscale metadata object at {@code groupPath}. v0.5 nests
	 * it under the {@code ome} key; v0.4 stores {@code multiscales} directly.
	 */
	private static OmeNgffMultiScaleMetadata readMultiscale(final N5Reader n5, final String groupPath,
			final StorageFormat format) {
		if (format == StorageFormat.ZARR) { // v0.5 / Zarr v3
			final OmeNgffMetadata ome = n5.getAttribute(groupPath, "ome", OmeNgffMetadata.class);
			if (ome != null && ome.multiscales != null && ome.multiscales.length > 0) {
				return ome.multiscales[0];
			}
			return null;
		}
		// v0.4 / Zarr v2
		final OmeNgffMultiScaleMetadata[] multiscales =
				n5.getAttribute(groupPath, "multiscales", OmeNgffMultiScaleMetadata[].class);
		if (multiscales != null && multiscales.length > 0) {
			return multiscales[0];
		}
		return null;
	}

	/**
	 * Reads the transitional {@code omero} display block. v0.4 stores it at the
	 * group root; v0.5 nests it under {@code ome}. Returns {@code null} if absent.
	 */
	private static Omero readOmero(final N5Reader n5, final String groupPath) {
		try {
			final Omero top = n5.getAttribute(groupPath, "omero", Omero.class);
			if (top != null && top.channels != null) return top;
		} catch (final Exception e) {
			log.debug("No root-level omero: {}", e.getMessage());
		}
		try {
			final Omero nested = n5.getAttribute(groupPath, "ome/omero", Omero.class);
			if (nested != null && nested.channels != null) return nested;
		} catch (final Exception e) {
			log.debug("No ome/omero: {}", e.getMessage());
		}
		return null;
	}

	/**
	 * Reads the HCS {@code plate} block at the container root, or {@code null} when
	 * the container is not a plate. v0.5 nests it under {@code ome}; v0.4 stores it
	 * directly — the same split as {@code multiscales}, and, like it, keyed on the
	 * storage format rather than tried both ways.
	 * <p>
	 * That matters during format detection: the Zarr-v3 reader will happily hand
	 * back the root-level attributes of a Zarr-v2 container, so a lenient read here
	 * would let a v0.4 plate be detected as v0.5 and every one of its field images
	 * would then be looked up under the wrong nesting.
	 * <p>
	 * A plate without wells is treated as not-a-plate, so that an unusual container
	 * still falls through to the ordinary multiscale probes rather than failing.
	 */
	private static PlateMeta readPlate(final N5Reader n5, final StorageFormat format) {
		final String key = format == StorageFormat.ZARR ? "ome/plate" : "plate";
		try {
			final PlateMeta plate = n5.getAttribute(ROOT_GROUP, key, PlateMeta.class);
			if (plate != null && plate.wells != null && plate.wells.length > 0) return plate;
		} catch (final Exception e) {
			log.debug("No {} attribute: {}", key, e.getMessage());
		}
		return null;
	}

	/** Reads a well group's {@code well} block (v0.5 under {@code ome}, v0.4 at the root). */
	private static WellMeta readWell(final N5Reader n5, final String wellPath,
			final StorageFormat format) {
		final String key = format == StorageFormat.ZARR ? "ome/well" : "well";
		final WellMeta well = n5.getAttribute(wellPath, key, WellMeta.class);
		return (well != null && well.images != null) ? well : null;
	}

	/** Minimal deserialization view of the NGFF {@code plate} block. */
	private static class PlateMeta {
		String name;
		PlateNamed[] rows;
		PlateNamed[] columns;
		PlateWell[] wells;
	}

	/** A {@code rows[]} / {@code columns[]} entry: only its label matters here. */
	private static class PlateNamed {
		String name;
	}

	/** A {@code wells[]} entry: the well group's path and its place on the plate. */
	private static class PlateWell {
		String path;      // e.g. "C/3"
		Integer rowIndex;
		Integer columnIndex;
	}

	/** Minimal deserialization view of a well group's {@code well} block. */
	private static class WellMeta {
		WellImage[] images;
	}

	/** An {@code images[]} entry of a well: the field image's path within the well. */
	private static class WellImage {
		String path;      // e.g. "0"
	}

	/**
	 * Parses an omero color, a 6-digit RGB hex string such as {@code "0000FF"},
	 * into an RGBA {@code int[]} (alpha forced to 255). Returns {@code null} on
	 * a missing or malformed value.
	 */
	private static int[] parseHexColor(final String hex) {
		if (hex == null) return null;
		final String h = hex.startsWith("#") ? hex.substring(1) : hex;
		if (h.length() < 6) return null;
		try {
			final int r = Integer.parseInt(h.substring(0, 2), 16);
			final int g = Integer.parseInt(h.substring(2, 4), 16);
			final int b = Integer.parseInt(h.substring(4, 6), 16);
			return new int[] { r, g, b, 255 };
		} catch (final NumberFormatException e) {
			return null;
		}
	}

	/** Minimal deserialization view of the NGFF {@code omero} block. */
	private static class Omero {
		OmeroChannel[] channels;
	}

	private static class OmeroChannel {
		String label;
		String color;
		OmeroWindow window;
		Boolean active;
	}

	private static class OmeroWindow {
		Double min;
		Double max;
		Double start;
		Double end;
	}

	private static URI toUri(final String s) {
		try {
			final URI u = new URI(s);
			if (u.getScheme() == null) return new File(s).toURI();
			return u;
		} catch (final URISyntaxException e) {
			return new File(s).toURI();
		}
	}
}
