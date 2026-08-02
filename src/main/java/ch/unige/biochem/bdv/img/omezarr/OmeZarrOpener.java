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
import spimdata.util.Displaysettings;
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

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link SpimData} from an OME-Zarr (OME-NGFF v0.4 / v0.5) container,
 * with spatial calibration + unit, timepoints and channels-as-{@link ViewSetup}s.
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
 * Supports a single image at the container root, or a {@code bioformats2raw}
 * container whose images are integer-named child groups ({@code 0, 1, …}),
 * discovered by probing. Each image needs axes {@code (z,y,x)} plus optional
 * {@code c} and {@code t}. HCS plates, labels and 2D-only data are out of scope
 * for now (see {@code PLAN.md}).
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
		final URI uri = toUri(uriString);
		final Parsed p = parse(uri);
		final SequenceDescription seq =
				new SequenceDescription(new TimePoints(p.timePoints), p.setups, null, p.missingViews);
		final OmeZarrImageLoader loader = new OmeZarrImageLoader(p.n5, uri, seq, p.props, p.higher);
		seq.setImgLoader(loader);
		return new SpimData(new File("."), seq, new ViewRegistrations(p.registrations));
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
		final URI uri = toUri(uriString);
		final Parsed p = parse(uri);
		return new OmeZarrImageLoader(p.n5, uri, seq, p.props, p.higher);
	}

	/** Everything discovery/parsing yields, shared by {@link #open} and {@link #openLoader}. */
	private static final class Parsed {
		N5Reader n5;
		List<ViewSetup> setups;
		List<ViewRegistration> registrations;
		List<TimePoint> timePoints;
		MissingViews missingViews;
		OmeZarrN5Properties props;
		Map<ViewId, int[]> higher;
	}

	/**
	 * Detects the storage format, discovers the image group(s) and parses each
	 * image into the {@link ViewSetup}s / {@link ViewRegistration}s and the
	 * per-view path / hyperslice maps the loader consumes.
	 */
	private static Parsed parse(final URI uri) {

		// --- 1. Detect the storage format ------------------------------------
		// OME-NGFF v0.5 is Zarr v3 (StorageFormat.ZARR); v0.4 is Zarr v2 (ZARR2).
		// A URL may point at a single image, OR at a bioformats2raw container
		// whose images are child groups "0", "1", ... (no multiscales at root).
		StorageFormat format = null;
		for (final StorageFormat candidate : new StorageFormat[] { StorageFormat.ZARR, StorageFormat.ZARR2 }) {
			try {
				final N5Reader probe = openReader(candidate, uri);
				if (readMultiscale(probe, ROOT_GROUP, candidate) != null
						|| readMultiscale(probe, "/0", candidate) != null) {
					format = candidate;
					break;
				}
			} catch (final Exception e) {
				log.debug("Not readable as {}: {}", candidate, e.getMessage());
			}
		}
		if (format == null) {
			throw new IllegalArgumentException(
					"No OME-NGFF multiscale metadata found at " + uri + " or its child \"0\". " +
					"If this is an HCS plate or an unusual layout it is not yet supported; " +
					"otherwise point at the image group directly.");
		}

		// --- 2. Discover the image group(s) inside the container -------------
		final N5Reader n5 = openReader(format, uri);
		final List<String> imagePaths = discoverImagePaths(n5, format);
		final boolean multiImage = imagePaths.size() > 1;
		log.info("Opened {} as {}: {} image(s) at {}", uri, format, imagePaths.size(), imagePaths);

		// --- 3. Parse each image's metadata (calibration, channels, omero) ---
		final List<ImageInfo> images = new ArrayList<>();
		int globalMaxT = 1;
		for (final String path : imagePaths) {
			final ImageInfo info = parseImage(n5, path, format);
			images.add(info);
			globalMaxT = Math.max(globalMaxT, info.sizeT);
		}

		// --- 4. Build ViewSetups / registrations / metadata maps -------------
		final List<ViewSetup> setups = new ArrayList<>();
		final List<ViewRegistration> registrations = new ArrayList<>();
		final List<ViewId> missing = new ArrayList<>();
		final Map<ViewId, ImageEntry> byView = new HashMap<>();   // dataset paths + mipmaps
		final Map<Integer, ImageEntry> bySetup = new HashMap<>(); // same, per setup
		final Map<ViewId, int[]> higher = new HashMap<>();        // c/t hyperslice indices

		int setupId = 0;
		for (int s = 0; s < images.size(); s++) {
			final ImageInfo img = images.get(s);
			final ImageEntry entry = new ImageEntry(img.levelPaths, img.mipmapResolutions);
			for (int c = 0; c < img.sizeC; c++) {
				final OmeroChannel oc = (img.omero != null && img.omero.channels != null
						&& c < img.omero.channels.length) ? img.omero.channels[c] : null;
				final String channelLabel = (oc != null && oc.label != null && !oc.label.isEmpty())
						? oc.label : "channel " + c;
				final String setupName = multiImage ? ("s" + s + " - " + channelLabel) : channelLabel;

				final ViewSetup vs = new ViewSetup(
						setupId,
						setupName,
						img.size,
						img.voxel,
						new Tile(s), // one tile per image, so series stay grouped
						new Channel(c, channelLabel),
						new Angle(0),
						new Illumination(0));

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
						higher.put(viewId, higherDimensionIndices(img.dimC, img.dimT, c, t));
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
		parsed.higher = higher;
		return parsed;
	}

	/**
	 * Opens an OME-Zarr reader for the given storage format, with the OME-NGFF
	 * coordinate-transformation Gson adapter registered so that {@code scale} /
	 * {@code translation} deserialize. {@link StorageFormat#ZARR} = Zarr v3
	 * (NGFF 0.5), {@link StorageFormat#ZARR2} = Zarr v2 (NGFF 0.4).
	 */
	private static N5Reader openReader(final StorageFormat format, final URI uri) {
		final GsonBuilder gson = new GsonBuilder().registerTypeAdapter(
				CoordinateTransformation.class, new CoordinateTransformationAdapter());
		return new N5Factory().gsonBuilder(gson).openReader(format, uri);
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
					"Container has no multiscale image at the root and no series \"0\". " +
					"HCS plates and other layouts are not yet supported.");
		}
		return paths;
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
		if (dimX < 0 || dimY < 0 || dimZ < 0) {
			throw new IllegalArgumentException(
					"Image at " + path + " lacks 3 spatial axes (z, y, x); 2D-only OME-Zarr is not yet supported.");
		}

		final String level0Path = path + "/" + ms.datasets[0].path;
		final long[] dims = n5.getDatasetAttributes(level0Path).getDimensions();

		final ImageInfo info = new ImageInfo();
		info.path = path;
		info.dimC = dimC;
		info.dimT = dimT;
		info.sizeC = dimC >= 0 ? (int) dims[dimC] : 1;
		info.sizeT = dimT >= 0 ? (int) dims[dimT] : 1;
		info.size = new FinalDimensions(dims[dimX], dims[dimY], dims[dimZ]);

		// scale/translation come back in imglib2 dim order (see note in class doc).
		final double[] scale = level0Transform(ms.datasets[0], true);
		final double[] trans = level0Transform(ms.datasets[0], false);
		final double sx = scale != null ? scale[dimX] : 1.0;
		final double sy = scale != null ? scale[dimY] : 1.0;
		final double sz = scale != null ? scale[dimZ] : 1.0;
		final double tx = trans != null ? trans[dimX] : 0.0;
		final double ty = trans != null ? trans[dimY] : 0.0;
		final double tz = trans != null ? trans[dimZ] : 0.0;

		final String unit = axes[posX].getUnit() != null ? axes[posX].getUnit() : "pixel";
		info.voxel = new FinalVoxelDimensions(unit, sx, sy, sz);
		info.calibration = new AffineTransform3D();
		info.calibration.set(
				sx, 0, 0, tx,
				0, sy, 0, ty,
				0, 0, sz, tz);

		// Per-level dataset paths and mipmap resolutions (relative to level 0).
		final int nLevels = ms.datasets.length;
		info.levelPaths = new String[nLevels];
		info.mipmapResolutions = new double[nLevels][3];
		final int[] spatialDims = { dimX, dimY, dimZ };
		for (int l = 0; l < nLevels; l++) {
			info.levelPaths[l] = path + "/" + ms.datasets[l].path;
			final double[] scaleL = level0Transform(ms.datasets[l], true);
			for (int k = 0; k < 3; k++) {
				final int d = spatialDims[k];
				final double r = (scaleL != null && scale != null && scale[d] != 0.0)
						? scaleL[d] / scale[d] : 1.0;
				info.mipmapResolutions[l][k] = Math.round(r * 10000) / 10000d;
			}
		}

		info.omero = readOmero(n5, path);

		log.info("  image {}: {} channel(s), {} timepoint(s), {} level(s), voxel [{}, {}, {}] {}",
				path, info.sizeC, info.sizeT, nLevels, sx, sy, sz, unit);
		return info;
	}

	/** Per-image parsed metadata used to assemble the SpimData. */
	private static class ImageInfo {
		String path;
		int dimC, dimT;
		int sizeC, sizeT;
		FinalDimensions size;
		VoxelDimensions voxel;
		AffineTransform3D calibration;
		String[] levelPaths;
		double[][] mipmapResolutions;
		Omero omero;
	}

	/**
	 * @param dimC imglib2 dim of the channel axis, or -1
	 * @param dimT imglib2 dim of the time axis, or -1
	 * @return the c/t hyperslice indices for {@link OmeZarrImageLoader}, in
	 *         ascending imglib2-dim order: {@code [c]}, {@code [t]}, {@code [c,t]}
	 *         or {@code null} for a pure 3D volume.
	 */
	private static int[] higherDimensionIndices(final int dimC, final int dimT, final int c, final int t) {
		if (dimC >= 0 && dimT >= 0) return new int[] { c, t }; // dims 3 (c) and 4 (t)
		if (dimC >= 0) return new int[] { c };                 // dim 3 = c
		if (dimT >= 0) return new int[] { t };                 // dim 3 = t
		return null;                                            // pure 3D
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
