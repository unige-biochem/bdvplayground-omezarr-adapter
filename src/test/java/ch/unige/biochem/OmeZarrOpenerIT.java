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
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * #L%
 */
package ch.unige.biochem;

import ch.unige.biochem.bdv.img.omezarr.OmeZarrOpener;
import ch.unige.biochem.bdv.img.omezarr.WorldUnit;
import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.RandomAccessibleInterval;
import org.junit.Assume;
import org.junit.Test;
import spimdata.util.Displaysettings;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Integration tests for {@link OmeZarrOpener} against pinned, immutable public
 * OME-Zarr datasets from the IDR NGFF sample catalog
 * (<a href="https://idr.github.io/ome-ngff-samples/">idr.github.io/ome-ngff-samples</a>).
 * <p>
 * These are <b>opt-in</b>: they only run when {@code -Domezarr.integration=true}
 * is set, and each self-skips (via JUnit {@link Assume}) if the IDR host is
 * unreachable or if the native {@code blosc} codec is unavailable (needed to even
 * read the v0.4 / Zarr-v2 compressor metadata — pass
 * {@code -Djna.library.path=<dir with blosc.dll/.so>}, which Fiji ships).
 * <p>
 * The expected ("golden") values were captured once from a trusted run and are
 * hard-coded here as regression anchors: if a refactor silently swaps an axis or
 * breaks scale/translation parsing, these numbers catch it.
 */
public class OmeZarrOpenerIT {

	// --- Pinned datasets (IDR NGFF sample catalog, livingobjects.ebi.ac.uk) -----

	/** A: v0.4 / Zarr-v2, 2 channels, 3D, with an omero display block. */
	private static final String A_V04_6001240 =
			"https://livingobjects.ebi.ac.uk/idr/zarr/v0.4/idr0062A/6001240.zarr";
	/** B: the same physical image as A, re-encoded as v0.5 / Zarr-v3. */
	private static final String B_V05_6001240 =
			"https://livingobjects.ebi.ac.uk/idr/zarr/v0.5/idr0062A/6001240_labels.zarr";
	/** C: v0.4 multichannel timelapse (exercises the {@code t} axis). */
	private static final String C_V04_TIMELAPSE =
			"https://livingobjects.ebi.ac.uk/idr/zarr/v0.4/idr0101A/13457227.zarr";
	/** D: v0.4 with a {@code translation} coordinateTransformation on the dataset. */
	private static final String D_V04_TRANSLATE =
			"https://livingobjects.ebi.ac.uk/idr/zarr/v0.4/idr0101A/13457537.zarr";
	/** E: v0.5 bioformats2raw container, single 79-timepoint image. */
	private static final String E_V05_B2RAW =
			"https://livingobjects.ebi.ac.uk/idr/zarr/v0.5/idr0051/" +
			"180712_H2B_22ss_Courtney1_20180712-163837_p00_c00_preview.zarr";
	/** F: v0.5 2D-only (axes {@code y,x}) — the minimal singleton-z case. */
	private static final String F_V05_2D =
			"https://livingobjects.ebi.ac.uk/idr/zarr/v0.5/idr0066/ExpD_chicken_embryo_MIP.ome.zarr";
	/** G: v0.4 2D multichannel (axes {@code c,y,x}) — c hyperslice without a z axis. */
	private static final String G_V04_2D_MULTICHANNEL =
			"https://livingobjects.ebi.ac.uk/idr/zarr/v0.4/idr0076A/10501752.zarr";
	/** H: v0.5 bioformats2raw container of 2D multichannel images. */
	private static final String H_V05_2D_B2RAW =
			"https://livingobjects.ebi.ac.uk/idr/zarr/v0.5/idr0033A/BR00109990_C2.zarr";

	private static final double EPS = 1e-6;

	// Golden voxel sizes (micrometer) shared by A and B.
	private static final double VX_6001240 = 0.3603981534640209;
	private static final double VZ_6001240 = 0.5002025531914894;

	// ---------------------------------------------------------------------------
	// A — v0.4 multichannel 3D + omero colors (the reference dataset)
	// ---------------------------------------------------------------------------

	@Test
	public void v04_6001240_metadata() {
		final AbstractSpimData<?> sd = openOrSkip(A_V04_6001240);

		assertEquals("timepoints", 1, timepointCount(sd));
		final List<? extends BasicViewSetup> setups = setups(sd);
		assertEquals("view setups (= channels)", 2, setups.size());

		final ViewSetup lamin = (ViewSetup) setups.get(0);
		assertEquals("LaminB1", lamin.getName());
		assertEquals(0, lamin.getAttribute(Channel.class).getId());
		assertEquals(0, lamin.getAttribute(Tile.class).getId());
		assertArrayEquals(new long[] { 271, 275, 236 }, sizeXYZ(lamin));
		assertEquals(VX_6001240, lamin.getVoxelSize().dimension(0), EPS);
		assertEquals(VX_6001240, lamin.getVoxelSize().dimension(1), EPS);
		assertEquals(VZ_6001240, lamin.getVoxelSize().dimension(2), EPS);
		assertEquals("micrometer", lamin.getVoxelSize().unit());

		final Displaysettings dsLamin = lamin.getAttribute(Displaysettings.class);
		assertNotNull(dsLamin);
		assertArrayEquals(new int[] { 0, 0, 255, 255 }, dsLamin.color); // blue
		assertEquals(0.0, dsLamin.min, EPS);
		assertEquals(1500.0, dsLamin.max, EPS);

		final ViewSetup dapi = (ViewSetup) setups.get(1);
		assertEquals("Dapi", dapi.getName());
		assertArrayEquals(new int[] { 255, 255, 0, 255 }, // yellow
				dapi.getAttribute(Displaysettings.class).color);

		// Pure scale, no translation.
		assertArrayEquals(new double[] { 0, 0, 0 }, translation(sd, 0, 0), EPS);
	}

	// ---------------------------------------------------------------------------
	// B — same image as A, but v0.5 / Zarr-v3: metadata must be identical
	// ---------------------------------------------------------------------------

	@Test
	public void v05_matchesV04() {
		final AbstractSpimData<?> a = openOrSkip(A_V04_6001240);
		final AbstractSpimData<?> b = openOrSkip(B_V05_6001240);

		final List<? extends BasicViewSetup> sa = setups(a);
		final List<? extends BasicViewSetup> sb = setups(b);
		assertEquals("timepoints match across versions", timepointCount(a), timepointCount(b));
		assertEquals("channel count matches across versions", sa.size(), sb.size());

		for (int i = 0; i < sa.size(); i++) {
			final ViewSetup va = (ViewSetup) sa.get(i);
			final ViewSetup vb = (ViewSetup) sb.get(i);
			assertEquals("name[" + i + "]", va.getName(), vb.getName());
			assertArrayEquals("size[" + i + "]", sizeXYZ(va), sizeXYZ(vb));
			for (int d = 0; d < 3; d++) {
				assertEquals("voxel[" + i + "][" + d + "]",
						va.getVoxelSize().dimension(d), vb.getVoxelSize().dimension(d), EPS);
			}
			assertEquals("unit[" + i + "]", va.getVoxelSize().unit(), vb.getVoxelSize().unit());
			assertArrayEquals("color[" + i + "]",
					va.getAttribute(Displaysettings.class).color,
					vb.getAttribute(Displaysettings.class).color);
		}
	}

	// ---------------------------------------------------------------------------
	// C — v0.4 multichannel timelapse (the t axis → TimePoints)
	// ---------------------------------------------------------------------------

	@Test
	public void v04_timelapse() {
		final AbstractSpimData<?> sd = openOrSkip(C_V04_TIMELAPSE);

		assertEquals("timepoints", 18, timepointCount(sd));
		final List<? extends BasicViewSetup> setups = setups(sd);
		assertEquals("channels", 4, setups.size());

		final ViewSetup cy1 = (ViewSetup) setups.get(0);
		assertEquals("cy 1", cy1.getName());
		assertArrayEquals(new long[] { 2048, 2048, 35 }, sizeXYZ(cy1));
		assertEquals(0.108335, cy1.getVoxelSize().dimension(0), EPS);
		assertEquals(0.4, cy1.getVoxelSize().dimension(2), EPS);
		assertEquals("micrometer", cy1.getVoxelSize().unit());
		assertArrayEquals(new int[] { 255, 0, 0, 255 }, // red
				cy1.getAttribute(Displaysettings.class).color);

		// Every timepoint of every setup must have a registration (no MissingViews).
		final SpimData spim = (SpimData) sd;
		for (final BasicViewSetup vs : setups) {
			for (int t = 0; t < 18; t++) {
				assertNotNull("registration t" + t + " s" + vs.getId(),
						spim.getViewRegistrations().getViewRegistration(new ViewId(t, vs.getId())));
			}
		}
	}

	// ---------------------------------------------------------------------------
	// D — v0.4 with a translation coordinateTransformation (the tx/ty/tz branch)
	// ---------------------------------------------------------------------------

	@Test
	public void v04_translation_registration() {
		final AbstractSpimData<?> sd = openOrSkip(D_V04_TRANSLATE);

		assertEquals("timepoints", 18, timepointCount(sd));
		final List<? extends BasicViewSetup> setups = setups(sd);
		assertEquals("channels", 6, setups.size());
		assertEquals("DAPI", ((ViewSetup) setups.get(4)).getName());
		assertEquals("Hyb probe", ((ViewSetup) setups.get(5)).getName());

		// The dataset carries a real spatial offset: it must land in the
		// ViewRegistration's translation column (indices 3, 7, 11 of the model).
		assertArrayEquals("translation from NGFF must reach the registration",
				new double[] { 60.88427, 52.109135, 1.2 }, translation(sd, 0, 0), EPS);
	}

	// ---------------------------------------------------------------------------
	// E — v0.5 bioformats2raw container: one series discovered, 79 timepoints
	// ---------------------------------------------------------------------------

	@Test
	public void v05_bioformats2raw_container() {
		final AbstractSpimData<?> sd = openOrSkip(E_V05_B2RAW);

		assertEquals("timepoints", 79, timepointCount(sd));
		final List<? extends BasicViewSetup> setups = setups(sd);
		assertEquals("one image / one channel discovered in the container", 1, setups.size());

		final ViewSetup vs = (ViewSetup) setups.get(0);
		assertEquals("Channel 0", vs.getName());
		assertEquals(0, vs.getAttribute(Tile.class).getId());
		assertArrayEquals(new long[] { 333, 333, 201 }, sizeXYZ(vs));
		// No spatial calibration in this dataset → identity voxel, "pixel" unit.
		assertEquals(1.0, vs.getVoxelSize().dimension(0), EPS);
		assertEquals(1.0, vs.getVoxelSize().dimension(2), EPS);
		assertEquals("pixel", vs.getVoxelSize().unit());
	}

	// ---------------------------------------------------------------------------
	// F — 2D-only (no z axis): opened as a single-slice volume
	// ---------------------------------------------------------------------------

	@Test
	public void v05_2dOnly_isSingleSliceVolume() {
		final AbstractSpimData<?> sd = openOrSkip(F_V05_2D);

		assertEquals("timepoints", 1, timepointCount(sd));
		final List<? extends BasicViewSetup> setups = setups(sd);
		// No channel axis either — the lone omero channel still names the setup.
		assertEquals("view setups", 1, setups.size());

		final ViewSetup vs = (ViewSetup) setups.get(0);
		assertEquals("Cy3", vs.getName());
		assertArrayEquals("2D image gets a singleton z", new long[] { 6510, 8978, 1 }, sizeXYZ(vs));
		assertEquals(1.6, vs.getVoxelSize().dimension(0), EPS);
		assertEquals(1.6, vs.getVoxelSize().dimension(1), EPS);
		assertEquals("no z axis → identity along z", 1.0, vs.getVoxelSize().dimension(2), EPS);
		assertEquals("micrometer", vs.getVoxelSize().unit());
		assertArrayEquals(new int[] { 255, 255, 255, 255 }, // white
				vs.getAttribute(Displaysettings.class).color);

		// Only x/y are downsampled; z stays at 1 to match the singleton dimension.
		final MultiResolutionSetupImgLoader<?> sil = (MultiResolutionSetupImgLoader<?>)
				sd.getSequenceDescription().getImgLoader().getSetupImgLoader(vs.getId());
		assertEquals("mipmap levels", 8, sil.numMipmapLevels());
		for (final double[] res : sil.getMipmapResolutions()) {
			assertEquals("z is never downsampled", 1.0, res[2], EPS);
		}
	}

	// ---------------------------------------------------------------------------
	// G — 2D multichannel: c is hypersliced although there is no z axis
	// ---------------------------------------------------------------------------

	@Test
	public void v04_2dMultichannel() {
		final AbstractSpimData<?> sd = openOrSkip(G_V04_2D_MULTICHANNEL);

		assertEquals("timepoints", 1, timepointCount(sd));
		final List<? extends BasicViewSetup> setups = setups(sd);
		assertEquals("view setups (= channels)", 50, setups.size());

		final ViewSetup first = (ViewSetup) setups.get(0);
		assertEquals("Total HH3-113In", first.getName());
		assertEquals(0, first.getAttribute(Channel.class).getId());
		assertArrayEquals(new long[] { 464, 494, 1 }, sizeXYZ(first));
		// No spatial calibration in this dataset → identity voxel, "pixel" unit.
		assertEquals(1.0, first.getVoxelSize().dimension(0), EPS);
		assertEquals(1.0, first.getVoxelSize().dimension(2), EPS);
		assertEquals("pixel", first.getVoxelSize().unit());
		assertArrayEquals(new int[] { 0, 255, 0, 255 }, // green
				first.getAttribute(Displaysettings.class).color);

		// Every channel is the same 2D image, so all setups share the singleton z.
		for (final BasicViewSetup vs : setups) {
			assertArrayEquals("size of setup " + vs.getId(), new long[] { 464, 494, 1 }, sizeXYZ(vs));
		}
	}

	// ---------------------------------------------------------------------------
	// H — 2D inside a bioformats2raw container (discovery + singleton z together)
	// ---------------------------------------------------------------------------

	@Test
	public void v05_2dBioformats2rawContainer() {
		final AbstractSpimData<?> sd = openOrSkip(H_V05_2D_B2RAW);

		assertEquals("timepoints", 1, timepointCount(sd));
		final List<? extends BasicViewSetup> setups = setups(sd);
		assertEquals("9 series x 5 channels", 45, setups.size());

		final ViewSetup first = (ViewSetup) setups.get(0);
		assertEquals("s0 - Nuclei", first.getName());
		assertEquals(0, first.getAttribute(Tile.class).getId());
		assertArrayEquals(new long[] { 2080, 1552, 1 }, sizeXYZ(first));
		assertEquals(0.3, first.getVoxelSize().dimension(0), EPS);
		assertEquals(1.0, first.getVoxelSize().dimension(2), EPS);
		assertEquals("micrometer", first.getVoxelSize().unit());

		// Series are grouped by Tile; the 6th setup starts series 1.
		final ViewSetup secondSeries = (ViewSetup) setups.get(5);
		assertEquals("s1 - Nuclei", secondSeries.getName());
		assertEquals(1, secondSeries.getAttribute(Tile.class).getId());
		assertEquals("channel restarts per series",
				0, secondSeries.getAttribute(Channel.class).getId());
		assertArrayEquals(new long[] { 2080, 1552, 1 }, sizeXYZ(secondSeries));
	}

	// ---------------------------------------------------------------------------
	// World coordinate units: the importers' unit choice, on a calibrated dataset
	// ---------------------------------------------------------------------------

	/**
	 * The reference dataset stores micrometers; every metric world unit is that
	 * calibration rescaled, in both the voxel size and the registration.
	 */
	@Test
	public void worldUnit_convertsCalibration() {
		// Millimeter, the default of both Fiji commands.
		final AbstractSpimData<?> mm = openOrSkip(A_V04_6001240, WorldUnit.MILLIMETER);
		final ViewSetup vsMm = (ViewSetup) setups(mm).get(0);
		assertEquals("millimeter", vsMm.getVoxelSize().unit());
		assertEquals(VX_6001240 * 1e-3, vsMm.getVoxelSize().dimension(0), EPS * 1e-3);
		assertEquals(VZ_6001240 * 1e-3, vsMm.getVoxelSize().dimension(2), EPS * 1e-3);
		assertArrayEquals("registration is scaled with it",
				new double[] { VX_6001240 * 1e-3, VX_6001240 * 1e-3, VZ_6001240 * 1e-3 },
				scale(mm, 0, 0), EPS * 1e-3);

		// Micrometer is what the file already uses, so nothing must move.
		final AbstractSpimData<?> um = openOrSkip(A_V04_6001240, WorldUnit.MICROMETER);
		final ViewSetup vsUm = (ViewSetup) setups(um).get(0);
		assertEquals("micrometer", vsUm.getVoxelSize().unit());
		assertEquals(VX_6001240, vsUm.getVoxelSize().dimension(0), EPS);
		assertEquals(VZ_6001240, vsUm.getVoxelSize().dimension(2), EPS);

		// Nanometer goes the other way.
		final ViewSetup vsNm = (ViewSetup) setups(openOrSkip(A_V04_6001240, WorldUnit.NANOMETER)).get(0);
		assertEquals("nanometer", vsNm.getVoxelSize().unit());
		assertEquals(VX_6001240 * 1e3, vsNm.getVoxelSize().dimension(0), EPS * 1e3);

		// The image size is a pixel count and must never follow the unit.
		assertArrayEquals(new long[] { 271, 275, 236 }, sizeXYZ(vsMm));
		assertArrayEquals(new long[] { 271, 275, 236 }, sizeXYZ(vsNm));
	}

	/** PIXEL drops the calibration entirely — voxel 1,1,1 and an identity model. */
	@Test
	public void worldUnit_pixelDropsCalibration() {
		final AbstractSpimData<?> sd = openOrSkip(A_V04_6001240, WorldUnit.PIXEL);
		final ViewSetup vs = (ViewSetup) setups(sd).get(0);

		assertEquals("pixel", vs.getVoxelSize().unit());
		for (int d = 0; d < 3; d++) {
			assertEquals("voxel[" + d + "]", 1.0, vs.getVoxelSize().dimension(d), EPS);
		}
		assertArrayEquals(new double[] { 1, 1, 1 }, scale(sd, 0, 0), EPS);
		assertArrayEquals(new double[] { 0, 0, 0 }, translation(sd, 0, 0), EPS);
	}

	/**
	 * BIGSTITCHER COMPATIBLE normalises so one pixel along x measures 1, keeping the
	 * z/x anisotropy in the model — and drops the Displaysettings entities, because
	 * BigStitcher will not fuse tiles whose entities differ, even for an entity
	 * irrelevant to the grouping, and Displaysettings differs per setup by
	 * construction (it carries that channel's own color and contrast).
	 */
	@Test
	public void worldUnit_bigStitcherNormalisesAndStripsDisplaysettings() {
		final AbstractSpimData<?> sd = openOrSkip(A_V04_6001240, WorldUnit.BIGSTITCHER_COMPATIBLE);
		final List<? extends BasicViewSetup> setups = setups(sd);
		final ViewSetup vs = (ViewSetup) setups.get(0);

		final double anisotropy = VZ_6001240 / VX_6001240;
		assertEquals("pixel", vs.getVoxelSize().unit());
		assertEquals("one pixel in x measures 1", 1.0, vs.getVoxelSize().dimension(0), EPS);
		assertEquals("y is isotropic with x here", 1.0, vs.getVoxelSize().dimension(1), EPS);
		assertEquals("z keeps its anisotropy", anisotropy, vs.getVoxelSize().dimension(2), EPS);
		assertArrayEquals(new double[] { 1.0, 1.0, anisotropy }, scale(sd, 0, 0), EPS);

		for (final BasicViewSetup s : setups) {
			assertNull("Displaysettings must be stripped: differing entities block fusion",
					((ViewSetup) s).getAttribute(Displaysettings.class));
		}
		// The channel entities are structural and stay.
		assertNotNull(vs.getAttribute(Channel.class));
	}

	// ---------------------------------------------------------------------------
	// Pixel path: force a real block load and confirm c/t hyperslicing to 3D
	// ---------------------------------------------------------------------------

	@Test
	public void pixelLoad_hyperslicesTo3D() {
		final AbstractSpimData<?> sd = openOrSkip(A_V04_6001240);
		final int setupId = setups(sd).get(0).getId();
		final RandomAccessibleInterval<?> img = coarsestImageOrSkip(sd, setupId);

		// A single (setup, timepoint) view is always 3D, even though the stored
		// array has an extra channel dimension that must be hypersliced away.
		assertEquals("view must be reduced to x,y,z", 3, img.numDimensions());
		assertCenterVoxelReadable(img);
	}

	/**
	 * The 2D pixel path: a channel is hypersliced away although the stored array
	 * has no z axis, and the result must still be a 3D view whose singleton z
	 * agrees with the dimensions the loader reports (BDV sizes its cell cache from
	 * those, so a mismatch would corrupt the grid).
	 */
	@Test
	public void pixelLoad_2d_isSingleSliceVolume() {
		final AbstractSpimData<?> sd = openOrSkip(G_V04_2D_MULTICHANNEL);
		final AbstractSequenceDescription<?, ?, ?> seq = sd.getSequenceDescription();
		// Not channel 0, so the hyperslice has to actually move along c.
		final int setupId = setups(sd).get(7).getId();
		final int tp = seq.getTimePoints().getTimePointsOrdered().get(0).getId();

		final MultiResolutionSetupImgLoader<?> sil =
				(MultiResolutionSetupImgLoader<?>) seq.getImgLoader().getSetupImgLoader(setupId);
		final RandomAccessibleInterval<?> img = coarsestImageOrSkip(sd, setupId);

		assertEquals("2D view must be padded to x,y,z", 3, img.numDimensions());
		assertArrayEquals("z must be the singleton [0,0]",
				new long[] { 0, 0, 0 }, img.minAsLongArray());
		assertEquals("z extent", 0, img.max(2));
		assertArrayEquals("interval must match the reported image size",
				new long[] { img.dimension(0), img.dimension(1), img.dimension(2) },
				sil.getImageSize(tp, sil.numMipmapLevels() - 1).dimensionsAsLongArray());
		assertCenterVoxelReadable(img);

		// The full-resolution level goes through the same path.
		final RandomAccessibleInterval<?> full = sil.getImage(tp, 0);
		assertEquals("full-resolution view is 3D", 3, full.numDimensions());
		assertArrayEquals(new long[] { 464, 494, 1 }, full.dimensionsAsLongArray());
	}

	/** Loads the coarsest level of a setup, skipping the test if blosc is missing. */
	private static RandomAccessibleInterval<?> coarsestImageOrSkip(final AbstractSpimData<?> sd,
			final int setupId) {
		final AbstractSequenceDescription<?, ?, ?> seq = sd.getSequenceDescription();
		final int tp = seq.getTimePoints().getTimePointsOrdered().get(0).getId();
		final MultiResolutionSetupImgLoader<?> sil =
				(MultiResolutionSetupImgLoader<?>) seq.getImgLoader().getSetupImgLoader(setupId);
		try {
			return sil.getImage(tp, sil.numMipmapLevels() - 1);
		} catch (final NoClassDefFoundError | UnsatisfiedLinkError e) {
			Assume.assumeNoException("native blosc unavailable for pixel decode", e);
			throw e; // unreachable — assumeNoException aborts the test
		}
	}

	/** Reads the center voxel, exercising the cache + codec end to end. */
	private static void assertCenterVoxelReadable(final RandomAccessibleInterval<?> img) {
		final net.imglib2.RandomAccess<?> ra = img.randomAccess();
		final long[] c = new long[img.numDimensions()];
		for (int d = 0; d < c.length; d++) c[d] = (img.min(d) + img.max(d)) / 2;
		ra.setPosition(c);
		assertNotNull(ra.get());
	}

	// ===========================================================================
	// Gating + helpers
	// ===========================================================================

	private static final boolean INTEGRATION = Boolean.getBoolean("omezarr.integration");
	private static Boolean reachable; // cached reachability probe

	/**
	 * Opens a dataset, or skips the test (never fails) when integration tests are
	 * off, the IDR host is unreachable, or native blosc is missing.
	 */
	private static AbstractSpimData<?> openOrSkip(final String url) {
		return openOrSkip(url, null);
	}

	/** As {@link #openOrSkip(String)}, in an explicit world coordinate unit. */
	private static AbstractSpimData<?> openOrSkip(final String url, final WorldUnit unit) {
		Assume.assumeTrue("opt-in: run with -Domezarr.integration=true", INTEGRATION);
		Assume.assumeTrue("IDR host unreachable", isReachable());
		try {
			return OmeZarrOpener.open(url, null, null, unit);
		} catch (final NoClassDefFoundError | UnsatisfiedLinkError e) {
			// v0.4/Zarr-v2 needs native blosc even to read compressor metadata.
			Assume.assumeNoException("native blosc unavailable (set -Djna.library.path)", e);
			throw e; // unreachable — assumeNoException aborts the test
		}
	}

	private static synchronized boolean isReachable() {
		if (reachable == null) {
			reachable = probe("https://livingobjects.ebi.ac.uk/");
		}
		return reachable;
	}

	private static boolean probe(final String url) {
		HttpURLConnection c = null;
		try {
			c = (HttpURLConnection) new URL(url).openConnection();
			c.setConnectTimeout(5000);
			c.setReadTimeout(5000);
			c.setRequestMethod("HEAD");
			final int code = c.getResponseCode();
			return code > 0 && code < 500;
		} catch (final Exception e) {
			return false;
		} finally {
			if (c != null) c.disconnect();
		}
	}

	private static int timepointCount(final AbstractSpimData<?> sd) {
		return sd.getSequenceDescription().getTimePoints().getTimePointsOrdered().size();
	}

	@SuppressWarnings("unchecked")
	private static List<? extends BasicViewSetup> setups(final AbstractSpimData<?> sd) {
		return (List<? extends BasicViewSetup>) sd.getSequenceDescription().getViewSetupsOrdered();
	}

	private static long[] sizeXYZ(final BasicViewSetup vs) {
		return new long[] { vs.getSize().dimension(0), vs.getSize().dimension(1), vs.getSize().dimension(2) };
	}

	/** The (tx, ty, tz) translation column of a view's registration model. */
	private static double[] translation(final AbstractSpimData<?> sd, final int t, final int setup) {
		final double[] m = model(sd, t, setup);
		return new double[] { m[3], m[7], m[11] };
	}

	/** The (sx, sy, sz) diagonal of a view's registration model. */
	private static double[] scale(final AbstractSpimData<?> sd, final int t, final int setup) {
		final double[] m = model(sd, t, setup);
		return new double[] { m[0], m[5], m[10] };
	}

	private static double[] model(final AbstractSpimData<?> sd, final int t, final int setup) {
		return ((SpimData) sd).getViewRegistrations()
				.getViewRegistration(new ViewId(t, setup)).getModel().getRowPackedCopy();
	}
}
