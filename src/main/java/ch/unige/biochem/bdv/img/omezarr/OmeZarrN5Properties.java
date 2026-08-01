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

import bdv.img.n5.N5Properties;
import mpicbg.spim.data.sequence.ViewId;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.N5Reader;

import java.util.Arrays;
import java.util.Map;

/**
 * {@link N5Properties} implementation for OME-Zarr, adapting {@code N5ImageLoader}
 * to the OME-NGFF multiscale layout.
 * <p>
 * Everything is precomputed by {@link OmeZarrOpener}: the per-level dataset paths
 * and the mipmap resolutions. Dimensions and data type are read lazily from the
 * (attribute-caching) {@link N5Reader}. Per-image entries are shared across all
 * of that image's channels and timepoints.
 */
public class OmeZarrN5Properties implements N5Properties {

	/** Metadata shared by every channel/timepoint of one multiscale image. */
	static final class ImageEntry {
		final String[] levelPaths;          // full dataset path per resolution level
		final double[][] mipmapResolutions; // [level][x,y,z], relative to level 0

		ImageEntry(final String[] levelPaths, final double[][] mipmapResolutions) {
			this.levelPaths = levelPaths;
			this.mipmapResolutions = mipmapResolutions;
		}
	}

	private final Map<ViewId, ImageEntry> byView;
	private final Map<Integer, ImageEntry> bySetup;

	public OmeZarrN5Properties(final Map<ViewId, ImageEntry> byView,
			final Map<Integer, ImageEntry> bySetup) {
		this.byView = byView;
		this.bySetup = bySetup;
	}

	@Override
	public String getDatasetPath(final N5Reader n5, final int setupId, final int timepointId, final int level) {
		return byView.get(new ViewId(timepointId, setupId)).levelPaths[level];
	}

	@Override
	public DataType getDataType(final N5Reader n5, final int setupId) {
		return n5.getDatasetAttributes(bySetup.get(setupId).levelPaths[0]).getDataType();
	}

	@Override
	public double[][] getMipmapResolutions(final N5Reader n5, final int setupId) {
		return bySetup.get(setupId).mipmapResolutions;
	}

	@Override
	public long[] getDimensions(final N5Reader n5, final int setupId, final int timepointId, final int level) {
		final String path = byView.get(new ViewId(timepointId, setupId)).levelPaths[level];
		// The array carries c/t as extra dimensions; the source is the first 3 (x,y,z).
		return Arrays.copyOf(n5.getDatasetAttributes(path).getDimensions(), 3);
	}
}
