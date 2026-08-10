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

import ch.unige.biochem.bdv.img.omezarr.OmeZarrImageLoader;
import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.XmlIoSpimData;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.RandomAccessibleInterval;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import spimdata.util.Displaysettings;
import spimdata.util.ImageName;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Round-trips an OME-Zarr-backed {@link SpimData} through a BigDataViewer XML:
 * open &rarr; save &rarr; reload, then confirm the {@link OmeZarrImageLoader} is
 * re-discovered (via {@link XmlIoOmeZarrImageLoader}) and still serves pixels.
 * <p>
 * Opt-in, same gating as {@link OmeZarrOpenerIT}: run with
 * {@code -Domezarr.integration=true} and (for the v0.4 dataset) a native blosc on
 * {@code -Djna.library.path}. Self-skips otherwise.
 */
public class XmlIoOmeZarrRoundTripIT {

	/** v0.4 / Zarr-v2, 2 channels, 3D, with omero display settings. */
	private static final String V04_6001240 =
			"https://livingobjects.ebi.ac.uk/idr/zarr/v0.4/idr0062A/6001240.zarr";

	/** v0.4 / Zarr-v2, 50 channels, 2D (axes {@code c,y,x}). */
	private static final String V04_2D_MULTICHANNEL =
			"https://livingobjects.ebi.ac.uk/idr/zarr/v0.4/idr0076A/10501752.zarr";

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void saveReloadReopens() throws Exception {
		saveReloadReopens(V04_6001240, 236);
	}

	/**
	 * A 2D image must survive the round trip as well: the reloaded loader re-runs
	 * discovery, so it has to rebuild the singleton z from the NGFF axes and still
	 * serve a single-slice 3D view.
	 */
	@Test
	public void saveReloadReopens_2d() throws Exception {
		saveReloadReopens(V04_2D_MULTICHANNEL, 1);
	}

	/**
	 * A dataset opened with its label images must reload as the same dataset. The
	 * setting is part of its identity: replayed without it, discovery would find two
	 * setups where the XML describes three, and every id from the label on would
	 * refer to something else.
	 */
	@Test
	public void saveReloadReopens_withLabels() throws Exception {
		final SpimData original = (SpimData) openOrSkip(V04_6001240, true);
		assertEquals("2 channels + 1 label image", 3, viewSetups(original).size());

		final File xml = new File(tmp.getRoot(), "omezarr-labels-roundtrip.xml");
		new XmlIoSpimData().save(original, xml.getAbsolutePath());
		final SpimData reloaded = new XmlIoSpimData().load(xml.getAbsolutePath());

		final OmeZarrImageLoader loader =
				(OmeZarrImageLoader) reloaded.getSequenceDescription().getImgLoader();
		assertTrue("the label setting is replayed on load", loader.isLabelsOpened());

		final List<ViewSetup> after = viewSetups(reloaded);
		assertEquals("view setups", 3, after.size());

		final ViewSetup label = after.get(2);
		assertEquals("6001240 - labels/0", label.getName());
		final Displaysettings ds = label.getAttribute(Displaysettings.class);
		assertTrue("label flag survives the XML", ds.isLabelImage);
		assertEquals("glasbey_on_dark", ds.lutName);
		assertArrayEquals(new int[] { 255, 255, 255, 255 }, ds.color);
		// The label is still grouped with the image it annotates.
		assertEquals(after.get(0).getAttribute(ImageName.class), label.getAttribute(ImageName.class));

		// The re-discovered loader must serve the label's pixels, not just describe it.
		final int tp = reloaded.getSequenceDescription().getTimePoints()
				.getTimePointsOrdered().get(0).getId();
		final MultiResolutionSetupImgLoader<?> sil = (MultiResolutionSetupImgLoader<?>)
				loader.getSetupImgLoader(label.getId());
		final RandomAccessibleInterval<?> img;
		try {
			img = sil.getImage(tp, sil.numMipmapLevels() - 1);
		} catch (final NoClassDefFoundError | UnsatisfiedLinkError e) {
			Assume.assumeNoException("native blosc unavailable for pixel decode", e);
			return;
		}
		assertEquals("reloaded label view is 3D", 3, img.numDimensions());
		assertNotNull(img.randomAccess().get());
	}

	private void saveReloadReopens(final String url, final long expectedSizeZ) throws Exception {
		final SpimData original = (SpimData) openOrSkip(url);

		// --- save to XML ---
		final File xml = new File(tmp.getRoot(), "omezarr-roundtrip.xml");
		new XmlIoSpimData().save(original, xml.getAbsolutePath());
		assertTrue("XML was written", xml.isFile());

		// --- reload from XML ---
		final SpimData reloaded = new XmlIoSpimData().load(xml.getAbsolutePath());

		// The loader must have been re-discovered as our own type.
		assertTrue("reloaded loader is an OmeZarrImageLoader",
				reloaded.getSequenceDescription().getImgLoader() instanceof OmeZarrImageLoader);

		// --- view model must survive the round trip ---
		final List<ViewSetup> before = viewSetups(original);
		final List<ViewSetup> after = viewSetups(reloaded);
		assertEquals("timepoints",
				original.getSequenceDescription().getTimePoints().getTimePointsOrdered().size(),
				reloaded.getSequenceDescription().getTimePoints().getTimePointsOrdered().size());
		assertEquals("view setups", before.size(), after.size());
		for (int i = 0; i < before.size(); i++) {
			final ViewSetup a = before.get(i);
			final ViewSetup b = after.get(i);
			assertEquals("name[" + i + "]", a.getName(), b.getName());
			assertEquals("sizeZ[" + i + "]", expectedSizeZ, b.getSize().dimension(2));
			for (int d = 0; d < 3; d++) {
				assertEquals("size[" + i + "][" + d + "]", a.getSize().dimension(d), b.getSize().dimension(d));
				assertEquals("voxel[" + i + "][" + d + "]",
						a.getVoxelSize().dimension(d), b.getVoxelSize().dimension(d), 1e-6);
			}
			assertEquals("unit[" + i + "]", a.getVoxelSize().unit(), b.getVoxelSize().unit());
			// Display-settings entity round-trips too (serialized by spimdata-extras).
			assertArrayEquals("color[" + i + "]",
					a.getAttribute(Displaysettings.class).color,
					b.getAttribute(Displaysettings.class).color);
			// So does the image name, which is what ties an image's channels together.
			assertEquals("image name[" + i + "]",
					a.getAttribute(ImageName.class).getName(),
					b.getAttribute(ImageName.class).getName());
		}

		// --- the re-discovered loader must actually serve pixels ---
		final int setupId = after.get(0).getId();
		final int tp = reloaded.getSequenceDescription().getTimePoints().getTimePointsOrdered().get(0).getId();
		final MultiResolutionSetupImgLoader<?> sil = (MultiResolutionSetupImgLoader<?>)
				reloaded.getSequenceDescription().getImgLoader().getSetupImgLoader(setupId);
		final RandomAccessibleInterval<?> img;
		try {
			img = sil.getImage(tp, sil.numMipmapLevels() - 1);
		} catch (final NoClassDefFoundError | UnsatisfiedLinkError e) {
			Assume.assumeNoException("native blosc unavailable for pixel decode", e);
			return;
		}
		assertEquals("reloaded view is 3D", 3, img.numDimensions());
		if (expectedSizeZ == 1) {
			// A 2D image keeps its singleton z at every level (z is never downsampled).
			assertEquals("reloaded 2D view keeps its singleton z", 0, img.max(2));
		}
		final net.imglib2.RandomAccess<?> ra = img.randomAccess();
		final long[] c = new long[3];
		for (int d = 0; d < 3; d++) c[d] = (img.min(d) + img.max(d)) / 2;
		ra.setPosition(c);
		assertNotNull(ra.get());
	}

	// --- gating (mirrors OmeZarrOpenerIT) --------------------------------------

	@SuppressWarnings("unchecked")
	private static List<ViewSetup> viewSetups(final SpimData sd) {
		return (List<ViewSetup>) (List<?>) sd.getSequenceDescription().getViewSetupsOrdered();
	}

	private static SpimData openOrSkip(final String url) {
		return openOrSkip(url, false);
	}

	private static SpimData openOrSkip(final String url, final boolean labels) {
		Assume.assumeTrue("opt-in: run with -Domezarr.integration=true",
				Boolean.getBoolean("omezarr.integration"));
		Assume.assumeTrue("IDR host unreachable", probe("https://livingobjects.ebi.ac.uk/"));
		try {
			return (SpimData) ch.unige.biochem.bdv.img.omezarr.OmeZarrOpener.open(
					url, null, null, null, labels);
		} catch (final NoClassDefFoundError | UnsatisfiedLinkError e) {
			Assume.assumeNoException("native blosc unavailable (set -Djna.library.path)", e);
			throw e; // unreachable
		}
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
}
