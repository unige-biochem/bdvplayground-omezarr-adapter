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

import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Offline end-to-end tests over hand-written containers, covering the three ways an
 * OME-NGFF image can lay its axes out on disk.
 * <p>
 * NGFF identifies axes by name, and only <i>recommends</i> the canonical
 * {@code zyx} spatial order, so {@code (c,x,y,z)} — what webKnossos writes — is
 * legal. On top of that, n5-zarr keeps its fastest-varying axis at dimension 0: it
 * reverses a row-major ({@code order: "C"}) array's shape but not a column-major
 * ({@code "F"}) one. The three containers here therefore reach the opener through
 * three different mappings and must all come out the same: an {@code (x,y,z)} volume
 * of the declared size, in which every voxel holds the {@link #code} of its own
 * position. A transposed source shows up as a size mismatch, a scrambled one as a
 * wrong code.
 */
public class AxisOrderTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static final int NX = 4, NY = 6, NZ = 5;
	private static final double SX = 1.0, SY = 2.0, SZ = 3.0;

	/** The value stored at {@code (x,y,z)}: unique, non-zero, and fits in a byte. */
	private static int code(final int x, final int y, final int z) {
		return 1 + x + NX * (y + NY * z);
	}

	// --- the three layouts -----------------------------------------------------

	/** Canonical NGFF {@code (c,z,y,x)}, Zarr v2 row-major: n5-zarr reverses it. */
	@Test
	public void canonicalRowMajorV2() throws IOException {
		final File root = tmp.newFolder("canonical-c");
		writeV2(root, new String[] { "c", "z", "y", "x" }, false);
		assertOpensAsXyzVolume(root);
	}

	/**
	 * Non-canonical {@code (c,x,y,z)}, Zarr v2 column-major — the webKnossos layout.
	 * n5-zarr leaves the shape alone here, so assuming the reversal puts every axis
	 * at the wrong dimension.
	 */
	@Test
	public void nonCanonicalColumnMajorV2() throws IOException {
		final File root = tmp.newFolder("wk-f");
		writeV2(root, new String[] { "c", "x", "y", "z" }, true);
		assertOpensAsXyzVolume(root);
	}

	/**
	 * Non-canonical {@code (c,x,y,z)}, Zarr v3: the shape is reversed and so are the
	 * {@code dimension_names}, which is what the opener reads the mapping off.
	 */
	@Test
	public void nonCanonicalV3() throws IOException {
		final File root = tmp.newFolder("wk-v3");
		writeV3(root, new String[] { "c", "x", "y", "z" });
		assertOpensAsXyzVolume(root);
	}

	// --- the shared expectation ------------------------------------------------

	/** Every layout must yield the same {@code (x,y,z)} volume, voxel for voxel. */
	private static void assertOpensAsXyzVolume(final File root) {
		final AbstractSpimData<?> sd = OmeZarrOpener.open(root.getAbsolutePath());

		assertEquals("one channel, so one setup",
				1, sd.getSequenceDescription().getViewSetups().size());
		final BasicViewSetup setup = (BasicViewSetup)
				sd.getSequenceDescription().getViewSetups().values().iterator().next();
		assertArrayEquals("declared size",
				new long[] { NX, NY, NZ }, Intervals.dimensionsAsLongArray(setup.getSize()));
		assertEquals("voxel x", SX, setup.getVoxelSize().dimension(0), 1e-9);
		assertEquals("voxel y", SY, setup.getVoxelSize().dimension(1), 1e-9);
		assertEquals("voxel z", SZ, setup.getVoxelSize().dimension(2), 1e-9);

		@SuppressWarnings("unchecked")
		final MultiResolutionSetupImgLoader<UnsignedByteType> loader =
				(MultiResolutionSetupImgLoader<UnsignedByteType>) sd.getSequenceDescription()
						.getImgLoader().getSetupImgLoader(setup.getId());

		assertArrayEquals("downsampling is (2,2,1) in x,y,z",
				new double[] { 2, 2, 1 }, loader.getMipmapResolutions()[1], 1e-9);

		// The declared dimensions and the pixels they describe must agree: this is
		// exactly what disagrees when the spatial axes are left in storage order.
		final RandomAccessibleInterval<UnsignedByteType> level0 = loader.getImage(0, 0);
		assertArrayEquals("level 0 pixels",
				new long[] { NX, NY, NZ }, Intervals.dimensionsAsLongArray(level0));
		assertArrayEquals("level 1 pixels",
				new long[] { NX / 2, NY / 2, NZ },
				Intervals.dimensionsAsLongArray(loader.getImage(0, 1)));

		final RandomAccess<UnsignedByteType> ra = level0.randomAccess();
		for (int z = 0; z < NZ; z++) {
			for (int y = 0; y < NY; y++) {
				for (int x = 0; x < NX; x++) {
					ra.setPosition(new long[] { x, y, z });
					assertEquals("voxel (" + x + "," + y + "," + z + ")",
							code(x, y, z), ra.get().get());
				}
			}
		}
	}

	// --- container writers -----------------------------------------------------

	/**
	 * A two-level Zarr v2 container whose axes are {@code names} — {@code c} plus the
	 * three spatial ones in whatever order — stored row-major, or column-major when
	 * {@code columnMajor}.
	 */
	private static void writeV2(final File root, final String[] names, final boolean columnMajor)
			throws IOException {
		write(new File(root, ".zgroup"), "{\"zarr_format\":2}");
		write(new File(root, ".zattrs"), "{\"multiscales\":[{\"version\":\"0.4\","
				+ "\"name\":\"test\",\"axes\":" + axesJson(names)
				+ ",\"datasets\":" + datasetsJson(names) + "}]}");

		for (int level = 0; level < 2; level++) {
			final File dir = new File(root, Integer.toString(level));
			if (!dir.mkdirs()) {
				throw new IOException("could not create " + dir);
			}
			final long[] shape = shape(names, level);
			write(new File(dir, ".zarray"), "{\"zarr_format\":2,\"dtype\":\"|u1\","
					+ "\"shape\":" + json(shape) + ",\"chunks\":" + json(shape)
					+ ",\"compressor\":null,\"filters\":null,\"fill_value\":0,"
					+ "\"dimension_separator\":\".\",\"order\":\"" + (columnMajor ? "F" : "C") + "\"}");
			// One chunk holds the whole array, so its key is all zeros.
			Files.write(new File(dir, "0.0.0.0").toPath(), chunk(names, level, columnMajor));
		}
	}

	/** The same image as {@link #writeV2}, as a Zarr v3 / NGFF 0.5 container. */
	private static void writeV3(final File root, final String[] names) throws IOException {
		write(new File(root, "zarr.json"), "{\"zarr_format\":3,\"node_type\":\"group\","
				+ "\"attributes\":{\"ome\":{\"version\":\"0.5\",\"multiscales\":[{"
				+ "\"name\":\"test\",\"axes\":" + axesJson(names)
				+ ",\"datasets\":" + datasetsJson(names) + "}]}}}");

		for (int level = 0; level < 2; level++) {
			final File dir = new File(root, Integer.toString(level));
			if (!dir.mkdirs()) {
				throw new IOException("could not create " + dir);
			}
			final long[] shape = shape(names, level);
			write(new File(dir, "zarr.json"), "{\"zarr_format\":3,\"node_type\":\"array\","
					+ "\"shape\":" + json(shape) + ",\"data_type\":\"uint8\","
					+ "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":"
					+ json(shape) + "}},"
					+ "\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":0,"
					+ "\"codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}],"
					+ "\"dimension_names\":" + quoted(names) + "}");
			final File chunk = new File(dir, "c/0/0/0/0");
			if (!chunk.getParentFile().mkdirs()) {
				throw new IOException("could not create " + chunk.getParentFile());
			}
			// Zarr v3 always serialises the (untransposed) chunk in C order.
			Files.write(chunk.toPath(), chunk(names, level, false));
		}
	}

	/** The array shape at {@code level}, following the axis order in {@code names}. */
	private static long[] shape(final String[] names, final int level) {
		final int f = 1 << level; // x and y halve per level, z does not
		final long[] shape = new long[names.length];
		for (int i = 0; i < names.length; i++) {
			switch (names[i]) {
				case "x": shape[i] = NX / f; break;
				case "y": shape[i] = NY / f; break;
				case "z": shape[i] = NZ; break;
				default:  shape[i] = 1; break; // c
			}
		}
		return shape;
	}

	/**
	 * The single chunk of {@code level}, with each voxel stamped with the
	 * {@link #code} of the full-resolution position it stands for. {@code columnMajor}
	 * selects which end of {@code names} varies fastest.
	 */
	private static byte[] chunk(final String[] names, final int level, final boolean columnMajor) {
		final long[] shape = shape(names, level);
		final int f = 1 << level;
		int n = 1;
		for (final long s : shape) {
			n *= s;
		}
		// Strides for the storage order in use, so a position can be addressed by axis.
		final long[] stride = new long[shape.length];
		long acc = 1;
		if (columnMajor) {
			for (int i = 0; i < shape.length; i++) { stride[i] = acc; acc *= shape[i]; }
		} else {
			for (int i = shape.length - 1; i >= 0; i--) { stride[i] = acc; acc *= shape[i]; }
		}

		final byte[] bytes = new byte[n];
		for (int z = 0; z < NZ; z++) {
			for (int y = 0; y < NY / f; y++) {
				for (int x = 0; x < NX / f; x++) {
					int offset = 0;
					for (int i = 0; i < names.length; i++) {
						switch (names[i]) {
							case "x": offset += stride[i] * x; break;
							case "y": offset += stride[i] * y; break;
							case "z": offset += stride[i] * z; break;
							default: break; // c, always 0
						}
					}
					bytes[offset] = (byte) code(x * f, y * f, z);
				}
			}
		}
		return bytes;
	}

	private static String axesJson(final String[] names) {
		final StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < names.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append("c".equals(names[i])
					? "{\"name\":\"c\",\"type\":\"channel\"}"
					: "{\"name\":\"" + names[i] + "\",\"type\":\"space\",\"unit\":\"micrometer\"}");
		}
		return sb.append(']').toString();
	}

	/** The two levels, each with the scale vector its axis order calls for. */
	private static String datasetsJson(final String[] names) {
		final StringBuilder sb = new StringBuilder("[");
		for (int level = 0; level < 2; level++) {
			final int f = 1 << level;
			final double[] scale = new double[names.length];
			for (int i = 0; i < names.length; i++) {
				switch (names[i]) {
					case "x": scale[i] = SX * f; break;
					case "y": scale[i] = SY * f; break;
					case "z": scale[i] = SZ; break;
					default:  scale[i] = 1.0; break; // c
				}
			}
			if (level > 0) {
				sb.append(',');
			}
			sb.append("{\"path\":\"").append(level).append("\",\"coordinateTransformations\":[")
					.append("{\"type\":\"scale\",\"scale\":").append(json(scale)).append("}]}");
		}
		return sb.append(']').toString();
	}

	private static String json(final long[] values) {
		final StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < values.length; i++) {
			sb.append(i > 0 ? "," : "").append(values[i]);
		}
		return sb.append(']').toString();
	}

	private static String json(final double[] values) {
		final StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < values.length; i++) {
			sb.append(i > 0 ? "," : "").append(values[i]);
		}
		return sb.append(']').toString();
	}

	private static String quoted(final String[] values) {
		final StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < values.length; i++) {
			sb.append(i > 0 ? ",\"" : "\"").append(values[i]).append('"');
		}
		return sb.append(']').toString();
	}

	private static void write(final File file, final String content) throws IOException {
		Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
	}
}
