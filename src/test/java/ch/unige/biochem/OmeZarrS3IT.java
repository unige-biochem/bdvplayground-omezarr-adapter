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
package ch.unige.biochem;

import ch.unige.biochem.bdv.img.omezarr.OmeZarrOpener;
import ch.unige.biochem.bdv.img.omezarr.S3Options;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import org.junit.Assume;
import org.junit.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Integration tests for opening an OME-Zarr through an {@code s3://} URI on a
 * non-AWS endpoint — here IDR's EBI Embassy store, which serves the very same
 * containers the other ITs read over plain {@code https://}.
 * <p>
 * The point of {@link S3Options} is that an {@code s3://bucket/key} URI carries
 * no endpoint, so without one the AWS SDK looks for the bucket on Amazon and
 * finds nothing. These tests pin both halves of that: the failure without
 * settings, and byte-identical metadata with them.
 * <p>
 * <b>Opt-in</b>, exactly like {@code OmeZarrOpenerIT}: run with
 * {@code -Domezarr.integration=true} (plus {@code -Djna.library.path=<dir with
 * blosc>}); otherwise every test self-skips.
 */
public class OmeZarrS3IT {

	/** The EBI Embassy S3 endpoint that hosts IDR's {@code idr} bucket. */
	private static final String IDR_ENDPOINT = "https://livingobjects.ebi.ac.uk";

	/** Dataset A of {@code OmeZarrOpenerIT}, addressed over https … */
	private static final String A_HTTPS =
			"https://livingobjects.ebi.ac.uk/idr/zarr/v0.4/idr0062A/6001240.zarr";

	/** … and the same container addressed as an S3 object. */
	private static final String A_S3 = "s3://idr/zarr/v0.4/idr0062A/6001240.zarr";

	private static final double EPS = 1e-6;

	@Test
	public void s3AndHttpsYieldTheSameDataset() {
		final AbstractSpimData<?> viaS3 = openOrSkip(A_S3, S3Options.anonymous(IDR_ENDPOINT));
		final AbstractSpimData<?> viaHttps = openOrSkip(A_HTTPS, null);

		final List<? extends BasicViewSetup> s3Setups = setups(viaS3);
		final List<? extends BasicViewSetup> httpsSetups = setups(viaHttps);
		assertEquals("setup count", httpsSetups.size(), s3Setups.size());
		assertTrue("expected the reference dataset's 2 channels", s3Setups.size() == 2);

		for (int i = 0; i < s3Setups.size(); i++) {
			final BasicViewSetup a = s3Setups.get(i);
			final BasicViewSetup b = httpsSetups.get(i);
			assertEquals("name[" + i + "]", b.getName(), a.getName());
			assertArrayEquals("size[" + i + "]",
					b.getSize().dimensionsAsLongArray(), a.getSize().dimensionsAsLongArray());

			final VoxelDimensions va = a.getVoxelSize();
			final VoxelDimensions vb = b.getVoxelSize();
			assertNotNull("voxel size[" + i + "]", va);
			assertEquals("unit[" + i + "]", vb.unit(), va.unit());
			for (int d = 0; d < 3; d++) {
				assertEquals("voxel[" + i + "][" + d + "]",
						vb.dimension(d), va.dimension(d), EPS);
			}
		}

		assertEquals("timepoints",
				viaHttps.getSequenceDescription().getTimePoints().getTimePointsOrdered().size(),
				viaS3.getSequenceDescription().getTimePoints().getTimePointsOrdered().size());
	}

	@Test
	public void s3WithoutAnEndpointFailsAndSaysWhy() {
		Assume.assumeTrue("opt-in: run with -Domezarr.integration=true", INTEGRATION);
		Assume.assumeTrue("IDR host unreachable", isReachable());

		try {
			OmeZarrOpener.open(A_S3);
			fail("expected the bare s3:// URI to fail: it resolves against AWS, "
					+ "where the idr bucket does not exist");
		} catch (final NoClassDefFoundError | UnsatisfiedLinkError e) {
			Assume.assumeNoException("native blosc unavailable (set -Djna.library.path)", e);
		} catch (final Exception e) {
			// The diagnosis matters as much as the failure: this is the message the
			// user actually sees, and the plain "unsupported layout" reading of it
			// sends them looking in the wrong place.
			final String msg = String.valueOf(e.getMessage());
			assertTrue("message should point at the missing endpoint, was: " + msg,
					msg.contains("s3:// URI carries no endpoint"));
		}
	}

	// ===========================================================================
	// Gating + helpers (mirrors OmeZarrOpenerIT)
	// ===========================================================================

	private static final boolean INTEGRATION = Boolean.getBoolean("omezarr.integration");
	private static Boolean reachable;

	private static AbstractSpimData<?> openOrSkip(final String url, final S3Options s3) {
		Assume.assumeTrue("opt-in: run with -Domezarr.integration=true", INTEGRATION);
		Assume.assumeTrue("IDR host unreachable", isReachable());
		try {
			return OmeZarrOpener.open(url, s3);
		} catch (final NoClassDefFoundError | UnsatisfiedLinkError e) {
			Assume.assumeNoException("native blosc unavailable (set -Djna.library.path)", e);
			throw e; // unreachable — assumeNoException aborts the test
		}
	}

	private static synchronized boolean isReachable() {
		if (reachable == null) {
			reachable = probe(IDR_ENDPOINT + "/");
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
	private static List<? extends BasicViewSetup> setups(final AbstractSpimData<?> sd) {
		return (List<? extends BasicViewSetup>) sd.getSequenceDescription().getViewSetupsOrdered();
	}
}
