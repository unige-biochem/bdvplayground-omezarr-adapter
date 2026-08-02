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

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void saveReloadReopens() throws Exception {
		final SpimData original = (SpimData) openOrSkip(V04_6001240);

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
		Assume.assumeTrue("opt-in: run with -Domezarr.integration=true",
				Boolean.getBoolean("omezarr.integration"));
		Assume.assumeTrue("IDR host unreachable", probe("https://livingobjects.ebi.ac.uk/"));
		try {
			return (SpimData) ch.unige.biochem.bdv.img.omezarr.OmeZarrOpener.open(url);
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
