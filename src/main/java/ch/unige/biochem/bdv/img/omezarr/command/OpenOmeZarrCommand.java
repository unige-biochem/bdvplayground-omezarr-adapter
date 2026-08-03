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
package ch.unige.biochem.bdv.img.omezarr.command;

import ch.unige.biochem.bdv.img.omezarr.OmeZarrOpener;
import ch.unige.biochem.bdv.img.omezarr.WorldUnit;
import mpicbg.spim.data.generic.AbstractSpimData;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/**
 * Opens an OME-Zarr (OME-NGFF v0.4 / v0.5) container as a {@link AbstractSpimData}.
 * <p>
 * A single URL field, in place of BigStitcher's multi-step dialog. The resulting
 * dataset is emitted as a command output; a {@code PostprocessorPlugin} (or
 * BigDataViewer-Playground) is responsible for displaying and registering it.
 * <p>
 * An HCS plate opens whole. Scripts that want only part of one can pass
 * {@link ch.unige.biochem.bdv.img.omezarr.HcsOptions} to
 * {@link OmeZarrOpener#open(String, ch.unige.biochem.bdv.img.omezarr.S3Options,
 * ch.unige.biochem.bdv.img.omezarr.HcsOptions)} directly.
 */
@Plugin(type = Command.class,
		menuPath = "Plugins>BigDataViewer-Playground>Import>Dataset - Create [OME-Zarr]")
public class OpenOmeZarrCommand implements Command {

	@Parameter(label = "OME-Zarr location (path or URL)",
			description = "File path or URL (S3 / https) of an .ome.zarr container "
					+ "whose root holds a multiscale image or an HCS plate.")
	String url;

	@Parameter(required = false,
			label = "World coordinate units",
			description = "Unit for the coordinate system where images will be positioned.",
			choices = { "MILLIMETER", "MICROMETER", "NANOMETER", "PIXEL", "BIGSTITCHER COMPATIBLE" })
	public String unit = "MILLIMETER";

	@Parameter(type = ItemIO.OUTPUT)
	AbstractSpimData<?> spimData;

	@Override
	public void run() {
		spimData = OmeZarrOpener.open(url, null, null, WorldUnit.fromChoice(unit));
	}
}
