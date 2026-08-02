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
package ch.unige.biochem.bdv.img.omezarr;

import ch.unige.biochem.bdv.img.omezarr.OmeZarrImageLoader.HyperSlice;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Offline unit tests for {@link OmeZarrImageLoader#extract3D}, the reduction of a
 * stored OME-NGFF array to the 3D view of one {@code (setup, timepoint)}.
 * <p>
 * NGFF's axis order is {@code (t,c,z,y,x)} and imglib2 reverses it, so which
 * dimension carries {@code c} / {@code t} depends on whether the image has a
 * {@code z} axis at all: a 3D image is {@code (x,y,z,c,t)}, a 2D one
 * {@code (x,y,c,t)}. These cover every {@code z?/c?/t?} permutation the opener can
 * produce, checking both the resulting interval (a 2D image must come back as a
 * single-slice volume with z in {@code [0,0]}) and that the <i>correct</i> c/t
 * plane was selected — every stored voxel is stamped with its own {@code (c,t)}.
 */
public class ExtractTo3DTest {

	private static final long SX = 4, SY = 3, SZ = 2;
	private static final int NC = 5, NT = 7;

	// --- 3D images: (x,y,z[,c][,t]) --------------------------------------------

	@Test
	public void volume3D_noChannelNoTime_isUnchanged() {
		final RandomAccessibleInterval<UnsignedShortType> stored = stored(new long[] { SX, SY, SZ }, -1, -1);
		final RandomAccessibleInterval<UnsignedShortType> out =
				OmeZarrImageLoader.extract3D(stored, new HyperSlice(new int[0], new long[0], false));

		assertSize(out, SX, SY, SZ);
		assertPlane(out, 0, 0);
	}

	@Test
	public void volume3D_channel_slicesDim3() {
		final RandomAccessibleInterval<UnsignedShortType> stored =
				stored(new long[] { SX, SY, SZ, NC }, 3, -1);
		final RandomAccessibleInterval<UnsignedShortType> out = OmeZarrImageLoader.extract3D(
				stored, new HyperSlice(new int[] { 3 }, new long[] { 2 }, false));

		assertSize(out, SX, SY, SZ);
		assertPlane(out, 2, 0);
	}

	@Test
	public void volume3D_channelAndTime_slicesDims3And4() {
		final RandomAccessibleInterval<UnsignedShortType> stored =
				stored(new long[] { SX, SY, SZ, NC, NT }, 3, 4);
		final RandomAccessibleInterval<UnsignedShortType> out = OmeZarrImageLoader.extract3D(
				stored, new HyperSlice(new int[] { 3, 4 }, new long[] { 2, 5 }, false));

		assertSize(out, SX, SY, SZ);
		assertPlane(out, 2, 5);
	}

	// --- 2D images: (x,y[,c][,t]) → a single-slice volume ----------------------

	@Test
	public void plane2D_noChannelNoTime_getsSingletonZ() {
		final RandomAccessibleInterval<UnsignedShortType> stored = stored(new long[] { SX, SY }, -1, -1);
		final RandomAccessibleInterval<UnsignedShortType> out =
				OmeZarrImageLoader.extract3D(stored, new HyperSlice(new int[0], new long[0], true));

		assertSize(out, SX, SY, 1);
		assertPlane(out, 0, 0);
	}

	@Test
	public void plane2D_channel_slicesDim2ThenPadsZ() {
		final RandomAccessibleInterval<UnsignedShortType> stored = stored(new long[] { SX, SY, NC }, 2, -1);
		final RandomAccessibleInterval<UnsignedShortType> out = OmeZarrImageLoader.extract3D(
				stored, new HyperSlice(new int[] { 2 }, new long[] { 3 }, true));

		assertSize(out, SX, SY, 1);
		assertPlane(out, 3, 0);
	}

	@Test
	public void plane2D_time_slicesDim2ThenPadsZ() {
		final RandomAccessibleInterval<UnsignedShortType> stored = stored(new long[] { SX, SY, NT }, -1, 2);
		final RandomAccessibleInterval<UnsignedShortType> out = OmeZarrImageLoader.extract3D(
				stored, new HyperSlice(new int[] { 2 }, new long[] { 6 }, true));

		assertSize(out, SX, SY, 1);
		assertPlane(out, 0, 6);
	}

	@Test
	public void plane2D_channelAndTime_slicesDims2And3ThenPadsZ() {
		final RandomAccessibleInterval<UnsignedShortType> stored =
				stored(new long[] { SX, SY, NC, NT }, 2, 3);
		final RandomAccessibleInterval<UnsignedShortType> out = OmeZarrImageLoader.extract3D(
				stored, new HyperSlice(new int[] { 2, 3 }, new long[] { 4, 1 }, true));

		assertSize(out, SX, SY, 1);
		assertPlane(out, 4, 1);
	}

	// --- the defensive fallback for a view with no descriptor ------------------

	@Test
	public void noHyperSlice_dropsSingletonHigherDimensions() {
		final RandomAccessibleInterval<UnsignedShortType> stored =
				stored(new long[] { SX, SY, SZ, 1, 1 }, 3, 4);
		final RandomAccessibleInterval<UnsignedShortType> out =
				OmeZarrImageLoader.extract3D(stored, null);

		assertSize(out, SX, SY, SZ);
		assertPlane(out, 0, 0);
	}

	// --- helpers ---------------------------------------------------------------

	/**
	 * An array of the given shape in which every voxel is stamped with the
	 * {@link #code(int, int)} of the {@code (c,t)} plane it belongs to, so a
	 * hyperslice can be checked to have picked the plane it was asked for.
	 */
	private static RandomAccessibleInterval<UnsignedShortType> stored(final long[] dims,
			final int dimC, final int dimT) {
		final ArrayImg<UnsignedShortType, ?> img = ArrayImgs.unsignedShorts(dims);
		final Cursor<UnsignedShortType> cursor = img.localizingCursor();
		while (cursor.hasNext()) {
			cursor.fwd();
			cursor.get().set(code(
					dimC >= 0 ? cursor.getIntPosition(dimC) : 0,
					dimT >= 0 ? cursor.getIntPosition(dimT) : 0));
		}
		return img;
	}

	private static int code(final int c, final int t) {
		return 100 * c + t + 1;
	}

	private static void assertSize(final RandomAccessibleInterval<?> out,
			final long x, final long y, final long z) {
		assertEquals("must be reduced to x,y,z", 3, out.numDimensions());
		assertArrayEquals("min", new long[] { 0, 0, 0 }, out.minAsLongArray());
		assertArrayEquals("max", new long[] { x - 1, y - 1, z - 1 }, out.maxAsLongArray());
	}

	/** Asserts the whole view comes from the {@code (c,t)} plane it was asked for. */
	private static void assertPlane(final RandomAccessibleInterval<UnsignedShortType> out,
			final int c, final int t) {
		final RandomAccess<UnsignedShortType> ra = out.randomAccess();
		for (long z = out.min(2); z <= out.max(2); z++) {
			for (long y = out.min(1); y <= out.max(1); y++) {
				for (long x = out.min(0); x <= out.max(0); x++) {
					ra.setPosition(new long[] { x, y, z });
					assertEquals("voxel (" + x + "," + y + "," + z + ")",
							code(c, t), ra.get().get());
				}
			}
		}
	}
}
