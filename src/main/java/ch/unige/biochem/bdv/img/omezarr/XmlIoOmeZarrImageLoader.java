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

import static mpicbg.spim.data.XmlKeys.IMGLOADER_FORMAT_ATTRIBUTE_NAME;

import mpicbg.spim.data.XmlHelpers;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.ImgLoaderIo;
import mpicbg.spim.data.generic.sequence.XmlIoBasicImgLoader;
import org.jdom2.Element;

import java.io.File;

/**
 * XML (de)serializer for {@link OmeZarrImageLoader}, registered with SpimData via
 * {@link ImgLoaderIo} so an OME-Zarr-backed {@code SpimData} can be saved to and
 * reloaded from a BigDataViewer XML (enabling BigStitcher / BigWarp interop).
 * <p>
 * Only the container URI is persisted; the OME-NGFF layout (dataset paths, mipmap
 * resolutions, channel/time hyperslicing) is <em>re-discovered</em> on load by
 * {@link OmeZarrOpener#openLoader(String, AbstractSequenceDescription)}, mirroring
 * how {@code XmlIoN5ImageLoader} re-opens its store rather than persisting the
 * whole view&rarr;metadata map. This keeps the XML small and authoritative: the
 * container is the single source of truth for pixel layout, while the XML carries
 * the SpimData view model (registrations, entities, …).
 * <p>
 * The URI is stored verbatim (not relativized against the XML's base path), so
 * remote containers ({@code https://}, S3) round-trip correctly. Local containers
 * are therefore stored as absolute URIs.
 */
@ImgLoaderIo(format = XmlIoOmeZarrImageLoader.FORMAT, type = OmeZarrImageLoader.class)
public class XmlIoOmeZarrImageLoader implements XmlIoBasicImgLoader<OmeZarrImageLoader> {

	/** Unique format id written to the {@code ImageLoader/@format} attribute. */
	public static final String FORMAT = "bdv.omezarr";

	/** Child element holding the container URI. */
	private static final String URI_TAG = "zarr";

	@Override
	public Element toXml(final OmeZarrImageLoader imgLoader, final File basePath) {
		final Element elem = new Element("ImageLoader");
		elem.setAttribute(IMGLOADER_FORMAT_ATTRIBUTE_NAME, FORMAT);
		elem.setAttribute("version", "1.0");
		elem.addContent(XmlHelpers.textElement(URI_TAG, imgLoader.getN5URI().toString()));
		return elem;
	}

	@Override
	public OmeZarrImageLoader fromXml(final Element elem, final File basePath,
			final AbstractSequenceDescription<?, ?, ?> sequenceDescription) {
		final String uri = XmlHelpers.getText(elem, URI_TAG);
		return OmeZarrOpener.openLoader(uri, sequenceDescription);
	}
}
