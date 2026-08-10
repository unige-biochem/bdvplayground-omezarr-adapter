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
 * <p>
 * For an HCS plate the {@link HcsOptions} caps are persisted too. Re-running
 * discovery has to reproduce the very same list of field images, since that list
 * is what assigns the setup ids the XML refers to; a plate opened with
 * {@code wells(4).fields(2)} would otherwise reload as the whole plate and the
 * ids would no longer mean the same thing. Whether the images' {@code labels}
 * groups were opened is persisted for exactly the same reason — label images are
 * setups too.
 * <p>
 * For an {@code s3://} container the {@link S3Options} endpoint / region /
 * addressing style are persisted alongside the URI, since an {@code s3://} URI on
 * its own does not say which object store to talk to.
 * <b>Credentials are deliberately not written</b> — a BDV XML is a shareable
 * plain-text document. A private bucket therefore reloads through the AWS default
 * credential chain ({@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY},
 * {@code ~/.aws/credentials}, instance profile, …), which must be in place on
 * whichever machine opens the XML.
 */
@ImgLoaderIo(format = XmlIoOmeZarrImageLoader.FORMAT, type = OmeZarrImageLoader.class)
public class XmlIoOmeZarrImageLoader implements XmlIoBasicImgLoader<OmeZarrImageLoader> {

	/** Unique format id written to the {@code ImageLoader/@format} attribute. */
	public static final String FORMAT = "ch.unige.bdv.omezarr";

	/** Child element holding the container URI. */
	private static final String URI_TAG = "zarr";

	/** Child element holding the S3 endpoint URL, absent unless one was needed. */
	private static final String S3_ENDPOINT_TAG = "s3endpoint";

	/** Child element holding the S3 region, absent unless one was given. */
	private static final String S3_REGION_TAG = "s3region";

	/** Child element holding the S3 addressing style ({@code true} = path style). */
	private static final String S3_PATH_STYLE_TAG = "s3pathstyle";

	/** Child element holding the HCS well cap, absent when the plate was opened whole. */
	private static final String HCS_MAX_WELLS_TAG = "hcsmaxwells";

	/** Child element holding the HCS field cap, absent when every field was opened. */
	private static final String HCS_MAX_FIELDS_TAG = "hcsmaxfields";

	/** Child element holding the HCS per-field metadata flag, absent when uniform. */
	private static final String HCS_STRICT_TAG = "hcsstrictperfield";

	/** Child element holding the label flag, absent when labels were not opened. */
	private static final String LABELS_TAG = "labels";

	@Override
	public Element toXml(final OmeZarrImageLoader imgLoader, final File basePath) {
		final Element elem = new Element("ImageLoader");
		elem.setAttribute(IMGLOADER_FORMAT_ATTRIBUTE_NAME, FORMAT);
		elem.setAttribute("version", "1.0");
		elem.addContent(XmlHelpers.textElement(URI_TAG, imgLoader.getN5URI().toString()));

		// Only the connection settings, never the secret (see the class javadoc).
		final S3Options s3 = imgLoader.getS3Options();
		if (s3 != null && !s3.withoutCredentials().isEmpty()) {
			if (s3.getEndpoint() != null) {
				elem.addContent(XmlHelpers.textElement(S3_ENDPOINT_TAG, s3.getEndpoint()));
			}
			if (s3.getRegion() != null) {
				elem.addContent(XmlHelpers.textElement(S3_REGION_TAG, s3.getRegion()));
			}
			elem.addContent(XmlHelpers.textElement(
					S3_PATH_STYLE_TAG, Boolean.toString(s3.isPathStyle())));
		}

		// The HCS caps, which are part of the dataset's identity (see the javadoc).
		final HcsOptions hcs = imgLoader.getHcsOptions();
		if (hcs != null && !hcs.isDefault()) {
			if (hcs.getMaxWells() != HcsOptions.UNLIMITED) {
				elem.addContent(XmlHelpers.intElement(HCS_MAX_WELLS_TAG, hcs.getMaxWells()));
			}
			if (hcs.getMaxFieldsPerWell() != HcsOptions.UNLIMITED) {
				elem.addContent(XmlHelpers.intElement(HCS_MAX_FIELDS_TAG, hcs.getMaxFieldsPerWell()));
			}
			if (hcs.isStrictPerField()) {
				elem.addContent(XmlHelpers.textElement(HCS_STRICT_TAG, "true"));
			}
		}

		// Likewise for the labels: they contribute setups of their own, so reloading
		// without them would shift every setup id after the first label image.
		if (imgLoader.isLabelsOpened()) {
			elem.addContent(XmlHelpers.textElement(LABELS_TAG, "true"));
		}
		return elem;
	}

	@Override
	public OmeZarrImageLoader fromXml(final Element elem, final File basePath,
			final AbstractSequenceDescription<?, ?, ?> sequenceDescription) {
		final String uri = XmlHelpers.getText(elem, URI_TAG);
		return OmeZarrOpener.openLoader(uri, s3OptionsFromXml(elem), hcsOptionsFromXml(elem),
				labelsFromXml(elem), sequenceDescription);
	}

	/**
	 * Whether the dataset was opened with its label images. An XML written without
	 * them, or before label support, has no such element and yields {@code false} —
	 * the same default as {@link OmeZarrOpener#open(String)}.
	 */
	static boolean labelsFromXml(final Element elem) {
		return Boolean.parseBoolean(XmlHelpers.getText(elem, LABELS_TAG, "false"));
	}

	/**
	 * Rebuilds the connection settings, without credentials — an XML written before
	 * S3 support, or for a non-S3 container, simply has none of these elements and
	 * yields {@code null}.
	 */
	static S3Options s3OptionsFromXml(final Element elem) {
		final String endpoint = XmlHelpers.getText(elem, S3_ENDPOINT_TAG, null);
		final String region = XmlHelpers.getText(elem, S3_REGION_TAG, null);
		final String pathStyle = XmlHelpers.getText(elem, S3_PATH_STYLE_TAG, null);
		if (endpoint == null && region == null && pathStyle == null) return null;
		return new S3Options(endpoint, region, Boolean.parseBoolean(pathStyle), null, null);
	}

	/**
	 * Rebuilds the HCS discovery settings. An XML written for a whole plate, for a
	 * container that is not a plate, or before HCS support simply has none of these
	 * elements and yields {@code null}, i.e. {@link HcsOptions#DEFAULT}.
	 */
	static HcsOptions hcsOptionsFromXml(final Element elem) {
		final String maxWells = XmlHelpers.getText(elem, HCS_MAX_WELLS_TAG, null);
		final String maxFields = XmlHelpers.getText(elem, HCS_MAX_FIELDS_TAG, null);
		final String strict = XmlHelpers.getText(elem, HCS_STRICT_TAG, null);
		if (maxWells == null && maxFields == null && strict == null) return null;

		HcsOptions hcs = maxWells != null
				? HcsOptions.wells(Integer.parseInt(maxWells.trim()))
				: HcsOptions.all();
		if (maxFields != null) hcs = hcs.fields(Integer.parseInt(maxFields.trim()));
		if (Boolean.parseBoolean(strict)) hcs = hcs.strict();
		return hcs;
	}
}
