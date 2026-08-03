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

import ch.unige.biochem.bdv.img.omezarr.HcsOptions;
import ch.unige.biochem.bdv.img.omezarr.OmeZarrImageLoader;
import ch.unige.biochem.bdv.img.omezarr.OmeZarrOpener;
import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.XmlIoSpimData;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.RandomAccessibleInterval;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import spimdata.util.Displaysettings;
import spimdata.util.Field;
import spimdata.util.Plate;
import spimdata.util.Well;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for HCS (high-content screening) plate support in
 * {@link OmeZarrOpener}, against pinned, immutable public plates from the IDR
 * NGFF sample catalog
 * (<a href="https://idr.github.io/ome-ngff-samples/">idr.github.io/ome-ngff-samples</a>).
 * <p>
 * Same <b>opt-in</b> gating as {@link OmeZarrOpenerIT}: nothing runs unless
 * {@code -Domezarr.integration=true} is set, and each test self-skips (via JUnit
 * {@link Assume}) when the IDR host is unreachable or native {@code blosc} is
 * missing ({@code -Djna.library.path=<dir with blosc.dll/.so>}, which Fiji ships).
 * <p>
 * The expected ("golden") values were captured once from a trusted run and are
 * hard-coded as regression anchors.
 * <p>
 * Note how little of the network these tests use for how much structure they
 * assert: the v0.5 plate below is 1568 field images and 7840 view setups, but
 * opening it costs about fifty HTTP round-trips, because the plate and well
 * metadata list every path and one field's layout stands in for all of them (see
 * {@link HcsOptions}). {@link #v04_strictPerField_matchesUniform()} is the test
 * that keeps that shortcut honest.
 */
public class OmeZarrHcsIT {

	// --- Pinned plates ---------------------------------------------------------

	/**
	 * v0.5 / Zarr-v3 plate: 49 wells x 32 fields x 5 channels, 3D (z = 31).
	 * Rows B–F, columns 2–11, so the first well is not A1 — which is exactly what
	 * makes it a good test of the rowIndex/columnIndex mapping.
	 */
	private static final String V05_PLATE =
			"https://livingobjects.ebi.ac.uk/idr/zarr/v0.5/idr0090/190129.zarr";

	/**
	 * v0.4 / Zarr-v2 plate: 96 wells x 6 fields x 2 channels, 3D (z = 16). Its
	 * {@code plate} block sits at the container root rather than under {@code ome},
	 * and its wells list one image per acquisition.
	 */
	private static final String V04_PLATE =
			"https://livingobjects.ebi.ac.uk/idr/zarr/v0.4/idr0001A/2551.zarr";

	private static final double EPS = 1e-6;

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	// ---------------------------------------------------------------------------
	// A — v0.5 plate, opened whole
	// ---------------------------------------------------------------------------

	@Test
	public void v05_plate_wholeStructure() {
		final SpimData sd = openOrSkip(V05_PLATE, null);
		final List<ViewSetup> setups = viewSetups(sd);

		assertEquals("timepoints", 1, timepointCount(sd));
		assertEquals("49 wells x 32 fields x 5 channels", 7840, setups.size());
		assertEquals("one Tile per field image", 1568, distinctTiles(setups).size());
		assertEquals("one Well entity per well", 49, distinctWells(setups).size());

		// --- the plate entity is shared by every setup ---
		final Plate plate = setups.get(0).getAttribute(Plate.class);
		assertNotNull("plate entity", plate);
		assertEquals("190129", plate.getName());
		for (final ViewSetup vs : setups) {
			assertEquals("every setup carries the same plate",
					plate.getName(), vs.getAttribute(Plate.class).getName());
		}

		// --- first setup: well B2 (the first in row/column order), field 0 ---
		final ViewSetup first = setups.get(0);
		assertEquals("B2 - f0 - BF", first.getName());
		final Well b2 = first.getAttribute(Well.class);
		assertEquals("B2", b2.getName());
		assertEquals("row index of B", 1, b2.getRow());
		assertEquals("column index of 2", 1, b2.getColumn());
		assertEquals("first well gets id 0", 0, b2.getId());
		assertEquals(0, first.getAttribute(Field.class).getId());
		assertEquals(0, first.getAttribute(Channel.class).getId());
		assertEquals(0, first.getAttribute(Tile.class).getId());

		// --- geometry, shared by every field of the plate ---
		assertArrayEquals(new long[] { 2048, 2044, 31 }, sizeXYZ(first));
		assertEquals(0.065, first.getVoxelSize().dimension(0), EPS);
		assertEquals(0.065, first.getVoxelSize().dimension(1), EPS);
		assertEquals(0.2, first.getVoxelSize().dimension(2), EPS);
		assertEquals("micrometer", first.getVoxelSize().unit());
		assertArrayEquals(new int[] { 255, 255, 255, 255 }, // BF is white
				first.getAttribute(Displaysettings.class).color);

		// --- channels of a field are consecutive setups sharing well/field/tile ---
		final ViewSetup dna = setups.get(1);
		assertEquals("B2 - f0 - DNA", dna.getName());
		assertEquals(1, dna.getAttribute(Channel.class).getId());
		assertEquals("same field image, so same tile", 0, dna.getAttribute(Tile.class).getId());
		assertEquals(0, dna.getAttribute(Field.class).getId());
		assertArrayEquals(new int[] { 0, 0, 255, 255 }, // DNA is blue
				dna.getAttribute(Displaysettings.class).color);

		// --- the next field of the same well: new field id and tile, same well ---
		final ViewSetup field1 = setups.get(5);
		assertEquals("B2 - f1 - BF", field1.getName());
		assertEquals(1, field1.getAttribute(Field.class).getId());
		assertEquals(1, field1.getAttribute(Tile.class).getId());
		assertEquals("still well B2", 0, field1.getAttribute(Well.class).getId());

		// --- the next well starts after this well's 32 fields x 5 channels ---
		final ViewSetup well1 = setups.get(32 * 5);
		assertEquals("B3 - f0 - BF", well1.getName());
		assertEquals(1, well1.getAttribute(Well.class).getId());
		assertEquals("field ids restart per well", 0, well1.getAttribute(Field.class).getId());
		assertEquals("tile ids do not restart", 32, well1.getAttribute(Tile.class).getId());

		// --- the last well of the plate is F11 ---
		final ViewSetup last = setups.get(setups.size() - 1);
		final Well f11 = last.getAttribute(Well.class);
		assertEquals("F11", f11.getName());
		assertEquals(5, f11.getRow());
		assertEquals(10, f11.getColumn());
		assertEquals(48, f11.getId());
		assertEquals("F11 - f31 - Mitochondria", last.getName());
	}

	// ---------------------------------------------------------------------------
	// B — v0.4 plate (root-level "plate" attribute), opened with caps
	// ---------------------------------------------------------------------------

	@Test
	public void v04_plate_capped() {
		final SpimData sd = openOrSkip(V04_PLATE, HcsOptions.wells(3).fields(2));
		final List<ViewSetup> setups = viewSetups(sd);

		assertEquals("timepoints", 1, timepointCount(sd));
		assertEquals("3 wells x 2 fields x 2 channels", 12, setups.size());
		assertEquals("one Tile per field image", 6, distinctTiles(setups).size());
		assertEquals("one Well entity per well", 3, distinctWells(setups).size());

		assertEquals("JL_120731_S6A", setups.get(0).getAttribute(Plate.class).getName());

		// This plate does start at A1, and its rows/columns labels are A.. and 1..
		final ViewSetup first = setups.get(0);
		assertEquals("A1 - f0 - GFP", first.getName());
		final Well a1 = first.getAttribute(Well.class);
		assertEquals("A1", a1.getName());
		assertEquals(0, a1.getRow());
		assertEquals(0, a1.getColumn());
		assertEquals(0, first.getAttribute(Field.class).getId());
		assertArrayEquals(new long[] { 1376, 1040, 16 }, sizeXYZ(first));
		assertEquals(0.1077, first.getVoxelSize().dimension(0), EPS);
		assertEquals(0.1077, first.getVoxelSize().dimension(1), EPS);
		assertEquals("no scale along z in this plate", 1.0, first.getVoxelSize().dimension(2), EPS);
		assertEquals("micrometer", first.getVoxelSize().unit());
		assertArrayEquals(new int[] { 0, 255, 0, 255 }, // GFP is green
				first.getAttribute(Displaysettings.class).color);

		assertEquals("A1 - f0 - Cascade blue", setups.get(1).getName());
		assertEquals("A1 - f1 - GFP", setups.get(2).getName());

		// Second well: the cap applies per well, so field ids restart.
		final ViewSetup well1 = setups.get(4);
		assertEquals("A2 - f0 - GFP", well1.getName());
		assertEquals(1, well1.getAttribute(Well.class).getId());
		assertEquals(1, well1.getAttribute(Well.class).getColumn());
		assertEquals(0, well1.getAttribute(Field.class).getId());
		assertEquals(2, well1.getAttribute(Tile.class).getId());

		assertEquals("A3 - f1 - Cascade blue", setups.get(11).getName());
		assertEquals(2, setups.get(11).getAttribute(Well.class).getId());
	}

	// ---------------------------------------------------------------------------
	// C — the uniform-field shortcut must agree with reading every field
	// ---------------------------------------------------------------------------

	/**
	 * The default fast path parses one field and reuses its layout for the whole
	 * plate. This checks that assumption against the plate itself, by opening the
	 * same wells both ways and comparing everything the shortcut could have got
	 * wrong: names, sizes, voxel sizes, resolution levels and dataset paths.
	 */
	@Test
	public void v04_strictPerField_matchesUniform() {
		final HcsOptions capped = HcsOptions.wells(2).fields(3);
		final SpimData uniform = openOrSkip(V04_PLATE, capped);
		final SpimData strict = openOrSkip(V04_PLATE, capped.strict());

		final List<ViewSetup> a = viewSetups(uniform);
		final List<ViewSetup> b = viewSetups(strict);
		assertEquals("setup count", a.size(), b.size());
		assertEquals("2 wells x 3 fields x 2 channels", 12, a.size());

		for (int i = 0; i < a.size(); i++) {
			assertEquals("name[" + i + "]", a.get(i).getName(), b.get(i).getName());
			assertArrayEquals("size[" + i + "]", sizeXYZ(a.get(i)), sizeXYZ(b.get(i)));
			for (int d = 0; d < 3; d++) {
				assertEquals("voxel[" + i + "][" + d + "]",
						a.get(i).getVoxelSize().dimension(d),
						b.get(i).getVoxelSize().dimension(d), EPS);
			}
			assertEquals("well[" + i + "]",
					a.get(i).getAttribute(Well.class).getName(),
					b.get(i).getAttribute(Well.class).getName());
			assertEquals("field[" + i + "]",
					a.get(i).getAttribute(Field.class).getId(),
					b.get(i).getAttribute(Field.class).getId());
		}

		// The per-level dataset paths are what the shortcut rebuilds, so compare the
		// mipmap pyramid each loader reports for a field other than the template.
		final int setupId = a.get(6).getId();
		final MultiResolutionSetupImgLoader<?> la = setupLoader(uniform, setupId);
		final MultiResolutionSetupImgLoader<?> lb = setupLoader(strict, setupId);
		assertEquals("mipmap levels", 5, la.numMipmapLevels());
		assertEquals("mipmap levels", lb.numMipmapLevels(), la.numMipmapLevels());
		for (int l = 0; l < la.numMipmapLevels(); l++) {
			assertArrayEquals("mipmap resolution[" + l + "]",
					lb.getMipmapResolutions()[l], la.getMipmapResolutions()[l], EPS);
		}
	}

	// ---------------------------------------------------------------------------
	// D — XML round trip: the plate/well/field entities and the caps must survive
	// ---------------------------------------------------------------------------

	@Test
	public void xmlRoundTrip_keepsPlateWellField() throws Exception {
		final SpimData original = openOrSkip(V04_PLATE, HcsOptions.wells(3).fields(2));

		final File xml = new File(tmp.getRoot(), "omezarr-plate.xml");
		new XmlIoSpimData().save(original, xml.getAbsolutePath());
		assertTrue("XML was written", xml.isFile());

		final SpimData reloaded = new XmlIoSpimData().load(xml.getAbsolutePath());
		assertTrue("reloaded loader is an OmeZarrImageLoader",
				reloaded.getSequenceDescription().getImgLoader() instanceof OmeZarrImageLoader);

		// The caps ride along in the XML: without them discovery would come back with
		// the whole 96-well plate and the setup ids would no longer line up.
		final List<ViewSetup> before = viewSetups(original);
		final List<ViewSetup> after = viewSetups(reloaded);
		assertEquals("capped discovery is reproduced on load", before.size(), after.size());
		assertEquals(HcsOptions.wells(3).fields(2).getMaxWells(),
				((OmeZarrImageLoader) reloaded.getSequenceDescription().getImgLoader())
						.getHcsOptions().getMaxWells());

		for (int i = 0; i < before.size(); i++) {
			final ViewSetup a = before.get(i);
			final ViewSetup b = after.get(i);
			assertEquals("name[" + i + "]", a.getName(), b.getName());

			final Plate plate = b.getAttribute(Plate.class);
			assertNotNull("plate entity survived[" + i + "]", plate);
			assertEquals("plate name[" + i + "]", a.getAttribute(Plate.class).getName(), plate.getName());

			final Well well = b.getAttribute(Well.class);
			assertNotNull("well entity survived[" + i + "]", well);
			assertEquals("well name[" + i + "]", a.getAttribute(Well.class).getName(), well.getName());
			assertEquals("well id[" + i + "]", a.getAttribute(Well.class).getId(), well.getId());
			assertEquals("well row[" + i + "]", a.getAttribute(Well.class).getRow(), well.getRow());
			assertEquals("well column[" + i + "]",
					a.getAttribute(Well.class).getColumn(), well.getColumn());

			final Field field = b.getAttribute(Field.class);
			assertNotNull("field entity survived[" + i + "]", field);
			assertEquals("field id[" + i + "]", a.getAttribute(Field.class).getId(), field.getId());
		}
	}

	// ---------------------------------------------------------------------------
	// E — pixels: a field image loads through the re-pointed dataset paths
	// ---------------------------------------------------------------------------

	/**
	 * The uniform-field shortcut rewrites the dataset paths of every field from one
	 * parsed field, so the only real proof it points at the right arrays is to read
	 * pixels from a field that was <em>not</em> the template.
	 */
	@Test
	public void pixelLoad_ofANonTemplateField() {
		final SpimData sd = openOrSkip(V04_PLATE, HcsOptions.wells(2).fields(2));
		final List<ViewSetup> setups = viewSetups(sd);

		// 2 fields x 2 channels per well, so this is the second well's second field —
		// as far from the template (well 1, field 0) as this cap allows.
		final ViewSetup vs = setups.get(6);
		assertEquals("A2 - f1 - GFP", vs.getName());

		final MultiResolutionSetupImgLoader<?> sil = setupLoader(sd, vs.getId());
		final int tp = sd.getSequenceDescription().getTimePoints()
				.getTimePointsOrdered().get(0).getId();
		final RandomAccessibleInterval<?> img;
		try {
			img = sil.getImage(tp, sil.numMipmapLevels() - 1);
		} catch (final NoClassDefFoundError | UnsatisfiedLinkError e) {
			Assume.assumeNoException("native blosc unavailable for pixel decode", e);
			return;
		}
		assertEquals("a field view is 3D", 3, img.numDimensions());

		final net.imglib2.RandomAccess<?> ra = img.randomAccess();
		final long[] c = new long[3];
		for (int d = 0; d < 3; d++) c[d] = (img.min(d) + img.max(d)) / 2;
		ra.setPosition(c);
		assertNotNull(ra.get());
	}

	// ===========================================================================
	// Gating + helpers (mirrors OmeZarrOpenerIT)
	// ===========================================================================

	private static final boolean INTEGRATION = Boolean.getBoolean("omezarr.integration");
	private static Boolean reachable; // cached reachability probe

	private static SpimData openOrSkip(final String url, final HcsOptions hcs) {
		Assume.assumeTrue("opt-in: run with -Domezarr.integration=true", INTEGRATION);
		Assume.assumeTrue("IDR host unreachable", isReachable());
		try {
			return (SpimData) OmeZarrOpener.open(url, null, hcs);
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

	@SuppressWarnings("unchecked")
	private static List<ViewSetup> viewSetups(final SpimData sd) {
		return (List<ViewSetup>) (List<?>) sd.getSequenceDescription().getViewSetupsOrdered();
	}

	private static MultiResolutionSetupImgLoader<?> setupLoader(final SpimData sd, final int setupId) {
		return (MultiResolutionSetupImgLoader<?>)
				sd.getSequenceDescription().getImgLoader().getSetupImgLoader(setupId);
	}

	private static int timepointCount(final SpimData sd) {
		return sd.getSequenceDescription().getTimePoints().getTimePointsOrdered().size();
	}

	private static long[] sizeXYZ(final ViewSetup vs) {
		return new long[] { vs.getSize().dimension(0), vs.getSize().dimension(1),
				vs.getSize().dimension(2) };
	}

	private static Set<Integer> distinctTiles(final List<ViewSetup> setups) {
		final Set<Integer> ids = new LinkedHashSet<>();
		for (final ViewSetup vs : setups) ids.add(vs.getAttribute(Tile.class).getId());
		return ids;
	}

	private static Set<String> distinctWells(final List<ViewSetup> setups) {
		final Set<String> names = new LinkedHashSet<>();
		final List<Integer> ids = new ArrayList<>();
		for (final ViewSetup vs : setups) {
			final Well w = vs.getAttribute(Well.class);
			names.add(w.getName());
			if (!ids.contains(w.getId())) ids.add(w.getId());
		}
		assertEquals("well names and ids agree", names.size(), ids.size());
		return names;
	}
}
