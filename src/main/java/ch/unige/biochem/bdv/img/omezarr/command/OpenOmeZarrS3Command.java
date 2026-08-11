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
package ch.unige.biochem.bdv.img.omezarr.command;

import ch.unige.biochem.bdv.img.omezarr.OmeZarrOpener;
import ch.unige.biochem.bdv.img.omezarr.S3Options;
import ch.unige.biochem.bdv.img.omezarr.WorldUnit;
import mpicbg.spim.data.generic.AbstractSpimData;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.TextWidget;

/**
 * Opens an OME-Zarr container held on an S3 object store, with an explicit
 * endpoint and optional credentials.
 * <p>
 * The plain {@link OpenOmeZarrCommand} is enough for local paths, {@code https://}
 * URLs and buckets on Amazon itself. This one exists because an
 * {@code s3://bucket/key} URI carries <em>no endpoint</em>: the AWS SDK resolves
 * it against Amazon's hosts, so a container on any other S3-speaking store — EBI
 * Embassy, Ceph/RadosGW, MinIO, an institutional store — fails to be found unless
 * the endpoint is supplied here. Credentials are optional; leave them empty for a
 * public bucket.
 * <p>
 * The credentials are used for this session only and are never written to a BDV
 * XML — see {@link ch.unige.biochem.bdv.img.omezarr.XmlIoOmeZarrImageLoader}.
 */
@Plugin(type = Command.class,
		menuPath = "Plugins>BigDataViewer-Playground>Import>Dataset - Create [OME-Zarr on S3]")
public class OpenOmeZarrS3Command implements Command {

	@Parameter(label = "OME-Zarr location (s3://bucket/path)",
			description = "S3 URI of an .ome.zarr container, e.g. "
					+ "s3://idr/zarr/v0.4/idr0062A/6001240.zarr")
	String url;

	@Parameter(label = "S3 endpoint",
			description = "URL of the object store, e.g. https://uk1s3.embassy.ebi.ac.uk . "
					+ "Leave empty only for buckets on Amazon itself.",
			required = false)
	String endpoint = "";

	@Parameter(label = "Region",
			description = "Ignored by most non-Amazon stores, but the AWS SDK requires "
					+ "a value; us-east-1 is used when this is left empty.",
			required = false)
	String region = "";

	@Parameter(label = "Path-style addressing",
			description = "endpoint/bucket/key (on) versus bucket.endpoint/key (off). "
					+ "Most non-Amazon stores need this on.")
	boolean pathStyle = true;

	@Parameter(label = "Access key",
			description = "Leave empty for a public bucket, or to use the ambient AWS "
					+ "credentials (environment variables, ~/.aws/credentials, ...).",
			required = false)
	String accessKey = "";

	@Parameter(label = "Secret key",
			description = "Used for this session only — never written to a BDV XML.",
			style = TextWidget.PASSWORD_STYLE, required = false)
	String secretKey = "";

	@Parameter(required = false,
			label = "World coordinate units",
			description = "Unit for the coordinate system where images will be positioned.",
			choices = { "MILLIMETER", "MICROMETER", "NANOMETER", "PIXEL", "BIGSTITCHER COMPATIBLE" })
	public String unit = "MILLIMETER";

	@Parameter(required = false,
			label = "Open label images",
			description = "Also open the segmentations stored in each image's 'labels' group, "
					+ "as extra sources next to the image they annotate.")
	public boolean labels = false;

	@Parameter(type = ItemIO.OUTPUT)
	AbstractSpimData<?> spimData;

	@Override
	public void run() {
		spimData = OmeZarrOpener.open(url,
				new S3Options(endpoint, region, pathStyle, accessKey, secretKey),
				null, WorldUnit.fromChoice(unit), labels);
	}
}
