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
 * {@link OmeZarrN5Properties} for the OME-NGFF layout, and reduces the stored
 * {@code nD} array to the 3D view of a single {@code (setup, timepoint)}.
 * <p>
 * NGFF stores its axes in the order {@code (t,c,z,y,x)} and n5/imglib2 reverses
 * them, so a 3D image maps to {@code (x,y,z,c,t)} and a 2D one — which has no
 * {@code z} axis at all — to {@code (x,y,c,t)}. Which imglib2 dimension is which
 * is therefore image-dependent, and is described per view by a {@link HyperSlice}
 * precomputed by {@link OmeZarrOpener}: it fixes the {@code c}/{@code t} axes at
 * that view's indices and, for a 2D image, appends a singleton {@code z} so the
 * result is the single-slice volume BigDataViewer expects.
 */
public class OmeZarrImageLoader extends N5ImageLoader {

	/**
	 * How to reduce one stored {@code nD} array to the 3D {@code (x,y,z)} view of a
	 * single {@code (setup, timepoint)}: hyperslice the non-spatial axes at fixed
	 * indices, then append a singleton {@code z} if the image has no {@code z} axis.
	 */
	public static final class HyperSlice {

		/** imglib2 dimensions to slice away (the c and/or t axes), ascending. */
		final int[] dims;

		/** the index each of {@link #dims} is fixed at. */
		final long[] indices;

		/** whether to append a singleton z, i.e. whether the image is 2D. */
		final boolean appendZ;

		public HyperSlice(final int[] dims, final long[] indices, final boolean appendZ) {
			this.dims = dims;
			this.indices = indices;
			this.appendZ = appendZ;
		}
	}

	private final N5Properties properties;
	private final Map<ViewId, HyperSlice> hyperSlices;
	private final S3Options s3Options;
	private final HcsOptions hcsOptions;

	/**
	 * @param reader      an already-opened reader on the container.
	 * @param uri         the container URI.
	 * @param seq         the sequence description.
	 * @param props       the OME-Zarr metadata/path resolver.
	 * @param hyperSlices per-{@code (timepoint,setup)} description of the reduction
	 *                    from the stored array down to a 3D view.
	 */
	public OmeZarrImageLoader(final N5Reader reader, final URI uri,
			final AbstractSequenceDescription<?, ?, ?> seq,
			final N5Properties props, final Map<ViewId, HyperSlice> hyperSlices) {
		this(reader, uri, seq, props, hyperSlices, null, null);
	}

	/**
	 * @param reader      an already-opened reader on the container.
	 * @param uri         the container URI.
	 * @param seq         the sequence description.
	 * @param props       the OME-Zarr metadata/path resolver.
	 * @param hyperSlices per-{@code (timepoint,setup)} description of the reduction
	 *                    from the stored array down to a 3D view.
	 * @param s3Options   the S3 settings {@code reader} was opened with, kept so the
	 *                    connection can be described in a saved BDV XML; {@code null}
	 *                    for a local, {@code https://} or plain-AWS container.
	 */
	public OmeZarrImageLoader(final N5Reader reader, final URI uri,
			final AbstractSequenceDescription<?, ?, ?> seq,
			final N5Properties props, final Map<ViewId, HyperSlice> hyperSlices,
			final S3Options s3Options) {
		this(reader, uri, seq, props, hyperSlices, s3Options, null);
	}

	/**
	 * @param reader      an already-opened reader on the container.
	 * @param uri         the container URI.
	 * @param seq         the sequence description.
	 * @param props       the OME-Zarr metadata/path resolver.
	 * @param hyperSlices per-{@code (timepoint,setup)} description of the reduction
	 *                    from the stored array down to a 3D view.
	 * @param s3Options   the S3 settings {@code reader} was opened with, or
	 *                    {@code null} for a local, {@code https://} or plain-AWS
	 *                    container.
	 * @param hcsOptions  the HCS settings the plate was discovered with, or
	 *                    {@code null} / {@link HcsOptions#DEFAULT} for a container
	 *                    that is not a plate, or a plate opened whole.
	 */
	public OmeZarrImageLoader(final N5Reader reader, final URI uri,
			final AbstractSequenceDescription<?, ?, ?> seq,
			final N5Properties props, final Map<ViewId, HyperSlice> hyperSlices,
			final S3Options s3Options, final HcsOptions hcsOptions) {
		super(reader, uri, seq);
		this.properties = props;
		this.hyperSlices = hyperSlices;
		this.s3Options = s3Options;
		this.hcsOptions = hcsOptions;
	}

	/**
	 * The S3 settings this loader's container was opened with, or {@code null} if
	 * none were needed. Pixels are served through the reader handed in at
	 * construction, so this is metadata about the connection rather than state the
	 * loader itself consults.
	 */
	public S3Options getS3Options() {
		return s3Options;
	}

	/**
	 * The HCS settings this loader's plate was discovered with, or {@code null}.
	 * Like {@link #getS3Options()} this is metadata about how the dataset was
	 * built, kept so that a saved BDV XML can be re-discovered identically —
	 * capping the wells or fields changes which setup ids exist.
	 */
	public HcsOptions getHcsOptions() {
		return hcsOptions;
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
		return extract3D(full, hyperSlices.get(new ViewId(timepointId, setupId)));
	}

	/**
	 * Reduces a stored {@code nD} volume to the 3D {@code (x,y,z)} view described by
	 * {@code hs}: hyperslices the c/t dimensions — from the highest down, so the
	 * remaining dimension indices stay valid — and appends a singleton {@code z} for
	 * a 2D image, yielding a {@code z} extent of exactly {@code [0,0]}.
	 *
	 * @param hs the reduction to apply, or {@code null} for an unmapped view, in
	 *           which case any dimension beyond the third is dropped at its minimum
	 *           (only correct when those are singletons).
	 */
	static <T> RandomAccessibleInterval<T> extract3D(final RandomAccessibleInterval<T> volume,
			final HyperSlice hs) {
		RandomAccessibleInterval<T> out = volume;
		if (hs == null) {
			for (int d = out.numDimensions() - 1; d >= 3; d--) {
				out = Views.hyperSlice(out, d, out.min(d));
			}
			return out;
		}
		for (int i = hs.dims.length - 1; i >= 0; i--) {
			out = Views.hyperSlice(out, hs.dims[i], hs.indices[i]);
		}
		return hs.appendZ ? Views.addDimension(out, 0, 0) : out;
	}
}
