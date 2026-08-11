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
 * NGFF names its axes rather than fixing their order: the canonical layout is
 * {@code (t,c,z,y,x)}, which n5/imglib2 reverses into {@code (x,y,z,c,t)}, but the
 * spec only <i>recommends</i> {@code zyx} for the spatial axes and a container may
 * legitimately store e.g. {@code (c,x,y,z)}. Nor is the reversal itself a given —
 * n5-zarr only reverses row-major arrays. Which imglib2 dimension is which is
 * therefore image-dependent, and is described per view by a {@link HyperSlice}
 * precomputed by {@link OmeZarrOpener}: it fixes the {@code c}/{@code t} axes at
 * that view's indices, appends a singleton {@code z} for a 2D image so the result
 * is the single-slice volume BigDataViewer expects, and reorders what is left into
 * the {@code (x,y,z)} BigDataViewer assumes.
 */
public class OmeZarrImageLoader extends N5ImageLoader {

	/**
	 * How to reduce one stored {@code nD} array to the 3D {@code (x,y,z)} view of a
	 * single {@code (setup, timepoint)}: hyperslice the non-spatial axes at fixed
	 * indices, append a singleton {@code z} if the image has no {@code z} axis, then
	 * reorder the three that remain into {@code (x,y,z)}.
	 */
	public static final class HyperSlice {

		/** imglib2 dimensions to slice away (the c and/or t axes), ascending. */
		final int[] dims;

		/** the index each of {@link #dims} is fixed at. */
		final long[] indices;

		/** whether to append a singleton z, i.e. whether the image is 2D. */
		final boolean appendZ;

		/**
		 * Where x, y and z sit once {@link #dims} have been sliced away and the
		 * singleton z (if any) appended: {@code order[k]} is the dimension of that
		 * view which must end up at {@code k}. {@code {0,1,2}} — the identity — for
		 * the canonical NGFF layout.
		 */
		final int[] order;

		/** A reduction that leaves the surviving dimensions in storage order. */
		public HyperSlice(final int[] dims, final long[] indices, final boolean appendZ) {
			this(dims, indices, appendZ, new int[] { 0, 1, 2 });
		}

		public HyperSlice(final int[] dims, final long[] indices, final boolean appendZ,
				final int[] order) {
			this.dims = dims;
			this.indices = indices;
			this.appendZ = appendZ;
			this.order = order;
		}
	}

	private final N5Properties properties;
	private final Map<ViewId, HyperSlice> hyperSlices;
	private final S3Options s3Options;
	private final HcsOptions hcsOptions;
	private final boolean labels;

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
		this(reader, uri, seq, props, hyperSlices, s3Options, hcsOptions, false);
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
	 * @param labels      whether the images' {@code labels} groups were opened too.
	 */
	public OmeZarrImageLoader(final N5Reader reader, final URI uri,
			final AbstractSequenceDescription<?, ?, ?> seq,
			final N5Properties props, final Map<ViewId, HyperSlice> hyperSlices,
			final S3Options s3Options, final HcsOptions hcsOptions, final boolean labels) {
		super(reader, uri, seq);
		this.properties = props;
		this.hyperSlices = hyperSlices;
		this.s3Options = s3Options;
		this.hcsOptions = hcsOptions;
		this.labels = labels;
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

	/**
	 * Whether the images' {@code labels} groups were opened alongside them. Like
	 * {@link #getHcsOptions()} this is part of the dataset's identity rather than
	 * state the loader consults: label images contribute their own setups, so a
	 * saved BDV XML has to be re-discovered with the same setting or the setup ids
	 * would no longer line up.
	 */
	public boolean isLabelsOpened() {
		return labels;
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
	 * remaining dimension indices stay valid — appends a singleton {@code z} for a 2D
	 * image, yielding a {@code z} extent of exactly {@code [0,0]}, and finally
	 * reorders the surviving dimensions into {@code (x,y,z)}.
	 * <p>
	 * The reordering is what makes a container that stores its spatial axes in a
	 * non-canonical order — {@code (c,x,y,z)}, say — line up with the dimensions and
	 * mipmap resolutions {@link OmeZarrN5Properties} declares, which are always
	 * {@code (x,y,z)}. Without it the two disagree and BigDataViewer places each
	 * resolution level somewhere different.
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
		if (hs.appendZ) {
			out = Views.addDimension(out, 0, 0);
		}
		return reorder(out, hs.order);
	}

	/**
	 * Permutes {@code volume} so that {@code order[k]} becomes dimension {@code k},
	 * as a sequence of transpositions. A no-op for the identity order, which is what
	 * every canonical container yields.
	 */
	private static <T> RandomAccessibleInterval<T> reorder(RandomAccessibleInterval<T> volume,
			final int[] order) {
		// at[d] is the dimension of the original volume currently sitting at d.
		final int[] at = new int[volume.numDimensions()];
		for (int d = 0; d < at.length; d++) {
			at[d] = d;
		}
		for (int d = 0; d < order.length; d++) {
			int from = d;
			while (from < at.length && at[from] != order[d]) {
				from++;
			}
			if (from != d && from < at.length) {
				volume = Views.permute(volume, d, from);
				at[from] = at[d];
				at[d] = order[d];
			}
		}
		return volume;
	}
}
