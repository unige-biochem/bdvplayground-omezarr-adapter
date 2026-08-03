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

import org.jdom2.Element;
import org.jdom2.output.XMLOutputter;
import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Offline tests for how {@link XmlIoOmeZarrImageLoader} persists {@link S3Options}.
 * <p>
 * A BDV XML is a plain-text document meant to be shared, so the invariant under
 * test is as much a security property as a serialization one: the connection is
 * written, the secret never is. These only exercise the XML element handling, so
 * no container is opened and nothing goes over the network.
 */
public class XmlIoS3OptionsTest {

	private static final String ENDPOINT = "https://livingobjects.ebi.ac.uk";
	private static final String ACCESS_KEY = "AKIAEXAMPLE";
	private static final String SECRET_KEY = "d0-n0t-serialize-me";

	private static final URI CONTAINER = URI.create("s3://idr/zarr/v0.4/idr0062A/6001240.zarr");

	@Test
	public void credentialsNeverReachTheXml() {
		final String xml = asString(toXml(
				new S3Options(ENDPOINT, "eu-west-2", true, ACCESS_KEY, SECRET_KEY)));

		assertFalse("secret key leaked into the XML: " + xml, xml.contains(SECRET_KEY));
		assertFalse("access key leaked into the XML: " + xml, xml.contains(ACCESS_KEY));
	}

	@Test
	public void connectionSettingsRoundTrip() {
		final Element elem = toXml(new S3Options(ENDPOINT, "eu-west-2", true, ACCESS_KEY, SECRET_KEY));
		final S3Options reloaded = XmlIoOmeZarrImageLoader.s3OptionsFromXml(elem);

		assertEquals("endpoint", ENDPOINT, reloaded.getEndpoint());
		assertEquals("region", "eu-west-2", reloaded.getRegion());
		assertTrue("path style", reloaded.isPathStyle());
		// The reload has to fall back to the ambient AWS credentials.
		assertFalse("credentials must not come back", reloaded.hasCredentials());
	}

	@Test
	public void virtualHostStyleRoundTrips() {
		// false is the non-default here, so it has to be written explicitly rather
		// than inferred from the element's absence.
		final Element elem = toXml(new S3Options(ENDPOINT, null, false, null, null));

		assertFalse("path style", XmlIoOmeZarrImageLoader.s3OptionsFromXml(elem).isPathStyle());
	}

	@Test
	public void nonS3ContainerWritesNoS3Elements() {
		final Element elem = toXml(null);

		assertEquals("only the <zarr> element is expected", 1, elem.getChildren().size());
		assertNull(XmlIoOmeZarrImageLoader.s3OptionsFromXml(elem));
	}

	@Test
	public void anXmlWithoutS3ElementsStillReads() {
		// Backwards compatibility: XMLs written before S3 support carry no such
		// elements, and must keep reloading as plain (non-S3) containers.
		final Element legacy = new Element("ImageLoader");
		legacy.addContent(new Element("zarr").setText(CONTAINER.toString()));

		assertNull(XmlIoOmeZarrImageLoader.s3OptionsFromXml(legacy));
	}

	/**
	 * Serializes a loader carrying {@code s3}. The loader needs no reader here: the
	 * XML is written from the URI and the S3 settings alone.
	 */
	private static Element toXml(final S3Options s3) {
		final OmeZarrImageLoader loader =
				new OmeZarrImageLoader(null, CONTAINER, null, null, null, s3);
		return new XmlIoOmeZarrImageLoader().toXml(loader, (java.io.File) null);
	}

	private static String asString(final Element elem) {
		return new XMLOutputter().outputString(elem);
	}
}
