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

import bdv.util.BdvFunctions;
import ch.unige.biochem.bdv.img.omezarr.OmeZarrOpener;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imagej.ImageJ;

import javax.swing.SwingUtilities;

/**
 * Interactive smoke test for {@link OmeZarrOpener}. Opens a public OME-NGFF v0.4
 * dataset (IDR), prints the discovered metadata and shows it in BDV.
 * <p>
 * Run headless (discovery only, no UI) via {@link #summarize(String)}.
 */
public class OpenOmeZarrDemo {

	// A classic public OME-NGFF v0.4 (Zarr v2) dataset from IDR: czyx.
	static final String IDR_V04 =
			"https://uk1s3.embassy.ebi.ac.uk/idr/zarr/v0.4/idr0062A/6001240.zarr";

	// The same image converted to OME-NGFF v0.5 (Zarr v3), from the 2024 challenge.
	static final String IDR_V05 =
			"https://uk1s3.embassy.ebi.ac.uk/idr/share/ome2024-ngff-challenge/0.0.5/6001240.zarr";

	/** Override with -Domezarr.url=... ; defaults to the v0.4 dataset. */
	static String url() {
		return System.getProperty("omezarr.url", IDR_V04);
	}

	public static void main(String... args) throws Exception {
		if (Boolean.getBoolean("omezarr.headless")) { // discovery-only validation
			summarize(url());
			return;
		}
		final ImageJ ij = new ImageJ();
		SwingUtilities.invokeAndWait(() -> ij.ui().showUI());

		final AbstractSpimData<?> spimData = OmeZarrOpener.open(url());
		printSummary(spimData);
		BdvFunctions.show(spimData);
	}

	/** Headless: open and print metadata without launching a UI. */
	public static void summarize(final String url) {
		printSummary(OmeZarrOpener.open(url));
	}

	private static void printSummary(final AbstractSpimData<?> spimData) {
		System.out.println("=== OME-Zarr opened ===");
		System.out.println("timepoints: " + spimData.getSequenceDescription()
				.getTimePoints().getTimePointsOrdered().size());
		for (final Object o : spimData.getSequenceDescription().getViewSetupsOrdered()) {
			final ViewSetup vs = (ViewSetup) o;
			System.out.println("  setup " + vs.getId()
					+ " '" + vs.getName() + "'"
					+ " size=" + net.imglib2.util.Util.printInterval(new net.imglib2.FinalInterval(
							vs.getSize().dimension(0), vs.getSize().dimension(1), vs.getSize().dimension(2)))
					+ " voxel=" + vs.getVoxelSize().dimension(0) + "x"
					+ vs.getVoxelSize().dimension(1) + "x" + vs.getVoxelSize().dimension(2)
					+ " " + vs.getVoxelSize().unit()
					+ " channel=" + (vs.getAttribute(mpicbg.spim.data.sequence.Channel.class) != null
							? vs.getAttribute(mpicbg.spim.data.sequence.Channel.class).getId() : "-"));
			final spimdata.util.Displaysettings ds =
					vs.getAttribute(spimdata.util.Displaysettings.class);
			if (ds != null) {
				System.out.println("      display: name='" + ds.getName() + "'"
						+ " color=" + java.util.Arrays.toString(ds.color)
						+ " range=[" + ds.min + ", " + ds.max + "]"
						+ " isSet=" + ds.isSet);
			}
		}
		pixelCheck(spimData);
	}

	/** Forces a real block load to exercise the loader (hyperslice + cache + codec). */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static void pixelCheck(final AbstractSpimData<?> sd) {
		try {
			final mpicbg.spim.data.generic.sequence.AbstractSequenceDescription<?, ?, ?> seq =
					sd.getSequenceDescription();
			final int setupId = ((ViewSetup) seq.getViewSetupsOrdered().get(0)).getId();
			final int tp = seq.getTimePoints().getTimePointsOrdered().get(0).getId();
			final mpicbg.spim.data.generic.sequence.BasicSetupImgLoader<?> sil =
					seq.getImgLoader().getSetupImgLoader(setupId);
			net.imglib2.RandomAccessibleInterval<?> img;
			int level = 0;
			if (sil instanceof mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader) {
				final mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader<?> m =
						(mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader<?>) sil;
				level = m.numMipmapLevels() - 1;
				img = m.getImage(tp, level);
			} else {
				img = sil.getImage(tp);
			}
			final net.imglib2.RandomAccess<?> ra = img.randomAccess();
			final long[] c = new long[img.numDimensions()];
			for (int d = 0; d < c.length; d++) c[d] = (img.min(d) + img.max(d)) / 2;
			ra.setPosition(c);
			System.out.println("  pixel check: setup " + setupId + " level " + level
					+ " numDim=" + img.numDimensions()
					+ " dims=" + java.util.Arrays.toString(img.dimensionsAsLongArray())
					+ " centerVoxel=" + ra.get());
		} catch (final Exception e) {
			System.out.println("  pixel check FAILED: " + e);
			e.printStackTrace();
		}
	}
}
