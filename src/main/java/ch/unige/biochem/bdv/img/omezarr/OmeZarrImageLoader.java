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

import bdv.img.n5.N5ImageLoader;
import bdv.img.n5.N5Properties;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.type.NativeType;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.n5.N5Reader;

import java.net.URI;
import java.util.Map;

/**
 * {@code N5ImageLoader} specialised for OME-Zarr: it inherits the multiresolution
 * + volatile-cache machinery of {@link N5ImageLoader}, uses an
 * {@link OmeZarrN5Properties} for the OME-NGFF layout, and hyperslices each
 * {@code (setup, timepoint)} out of the channel/time axes.
 * <p>
 * NGFF stores {@code (t,c,z,y,x)}; n5/imglib2 reverses to {@code (x,y,z,c,t)}, so
 * the first three dimensions are spatial and any extra dimensions ({@code c} at 3,
 * {@code t} at 4) are sliced away. This requires 3 spatial axes; 2D-only OME-Zarr
 * is not yet supported (see {@code PLAN.md}).
 */
public class OmeZarrImageLoader extends N5ImageLoader {

	private final N5Properties properties;
	private final Map<ViewId, int[]> higherDimensionIndices;

	/**
	 * @param reader   an already-opened reader on the container.
	 * @param uri      the container URI.
	 * @param seq      the sequence description.
	 * @param props    the OME-Zarr metadata/path resolver.
	 * @param higher   per-{@code (timepoint,setup)} indices of the c/t hyperslice
	 *                 to extract, in ascending imglib2-dimension order (e.g.
	 *                 {@code [c]}, {@code [t]} or {@code [c,t]}); {@code null} for
	 *                 a pure 3D volume.
	 */
	public OmeZarrImageLoader(final N5Reader reader, final URI uri,
			final AbstractSequenceDescription<?, ?, ?> seq,
			final N5Properties props, final Map<ViewId, int[]> higher) {
		super(reader, uri, seq);
		this.properties = props;
		this.higherDimensionIndices = higher;
	}

	@Override
	protected N5Properties createN5PropertiesInstance() {
		return properties;
	}

	@Override
	protected <T extends NativeType<T>> RandomAccessibleInterval<T> prepareCachedImage(
			final String datasetPath, final int setupId, final int timepointId,
			final int level, final CacheHints cacheHints, final T type) {
		final RandomAccessibleInterval<T> full =
				super.prepareCachedImage(datasetPath, setupId, timepointId, level, cacheHints, type);
		return extract3D(full, higherDimensionIndices.get(new ViewId(timepointId, setupId)));
	}

	/**
	 * Reduces an {@code nD} volume ({@code n > 3}) to its 3D {@code (x,y,z)} core
	 * by hyperslicing the higher dimensions at the given indices. Slices from the
	 * highest dimension down so indices stay valid.
	 */
	static <T> RandomAccessibleInterval<T> extract3D(final RandomAccessibleInterval<T> volume,
			final int[] higher) {
		if (volume.numDimensions() <= 3) {
			return volume;
		}
		RandomAccessibleInterval<T> out = volume;
		if (higher == null || higher.length == 0) {
			// No indices given: only valid when the higher dimensions are singletons.
			for (int d = out.numDimensions() - 1; d >= 3; d--) {
				out = Views.hyperSlice(out, d, out.min(d));
			}
			return out;
		}
		for (int d = 3 + higher.length - 1; d >= 3; d--) {
			out = Views.hyperSlice(out, d, higher[d - 3]);
		}
		return out;
	}
}
