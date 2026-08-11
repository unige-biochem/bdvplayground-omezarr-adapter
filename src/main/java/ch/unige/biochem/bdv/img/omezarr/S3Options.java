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

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;
import java.util.function.Consumer;

/**
 * S3 connection settings for opening an {@code s3://bucket/key} OME-Zarr on a
 * non-AWS (or private) endpoint.
 * <p>
 * An {@code s3://} URI carries a bucket and a key but <em>no endpoint</em>, so
 * the AWS SDK resolves it against Amazon's own hosts. Object stores that speak
 * the S3 protocol without being AWS — EBI Embassy, Ceph/RadosGW, MinIO, SWITCH,
 * … — therefore need the endpoint supplied out of band, which is what this class
 * carries. It is applied through
 * {@link org.janelia.saalfeldlab.n5.universe.N5Factory#s3Configuration(Consumer)},
 * whose consumer n5 runs <em>after</em> its own defaults, so every field here
 * wins over what n5 inferred from the URI.
 * <p>
 * Credentials are optional: leave them empty for a public bucket. When they are
 * empty this class sets no credentials provider at all, which leaves n5's own
 * behaviour in place — try anonymous, and fall back to the AWS default provider
 * chain (environment variables, {@code ~/.aws/credentials}, instance profile, …)
 * if the bucket is not readable anonymously.
 * <p>
 * <b>Credentials are never serialized.</b> {@link XmlIoOmeZarrImageLoader} writes
 * only {@link #withoutCredentials()} into the BDV XML, so a saved dataset on a
 * private bucket reloads through the AWS default provider chain rather than from
 * a secret sitting in plain text on disk.
 */
public final class S3Options {

	/** Region to assume when an endpoint is set but no region was given. */
	private static final String DEFAULT_REGION = "us-east-1";

	private final String endpoint;
	private final String region;
	private final boolean pathStyle;
	private final String accessKey;
	private final String secretKey;

	/**
	 * @param endpoint  the S3 endpoint URL, e.g.
	 *                  {@code https://livingobjects.ebi.ac.uk}; {@code null} or
	 *                  empty to let the SDK resolve an AWS endpoint from the URI.
	 * @param region    the region name; {@code null} or empty falls back to
	 *                  {@value #DEFAULT_REGION} when an endpoint is set. Non-AWS
	 *                  stores ignore it, but the SDK insists on having one.
	 * @param pathStyle {@code true} for {@code endpoint/bucket/key} addressing
	 *                  (what most non-AWS stores serve), {@code false} for
	 *                  virtual-host style {@code bucket.endpoint/key}.
	 * @param accessKey the access key id, or {@code null}/empty for a public bucket.
	 * @param secretKey the secret access key, or {@code null}/empty for a public bucket.
	 */
	public S3Options(final String endpoint, final String region, final boolean pathStyle,
			final String accessKey, final String secretKey) {
		this.endpoint = emptyToNull(endpoint);
		this.region = emptyToNull(region);
		this.pathStyle = pathStyle;
		this.accessKey = emptyToNull(accessKey);
		this.secretKey = emptyToNull(secretKey);
	}

	/** Anonymous access to a path-style endpoint — the usual public-bucket case. */
	public static S3Options anonymous(final String endpoint) {
		return new S3Options(endpoint, null, true, null, null);
	}

	public String getEndpoint() {
		return endpoint;
	}

	public String getRegion() {
		return region;
	}

	public boolean isPathStyle() {
		return pathStyle;
	}

	/** Whether an explicit key pair was supplied (as opposed to anonymous / ambient). */
	public boolean hasCredentials() {
		return accessKey != null && secretKey != null;
	}

	/** Whether there is nothing to configure, i.e. plain AWS defaults would do. */
	public boolean isEmpty() {
		return endpoint == null && region == null && !pathStyle && !hasCredentials();
	}

	/** A copy carrying the connection settings but no secret, safe to serialize. */
	public S3Options withoutCredentials() {
		return new S3Options(endpoint, region, pathStyle, null, null);
	}

	/**
	 * Renders these settings as the {@code S3ClientBuilder} consumer n5-universe
	 * expects, or {@code null} when there is nothing to override.
	 */
	Consumer<S3ClientBuilder> asBuilderConfig() {
		if (isEmpty()) return null;
		return builder -> {
			if (endpoint != null) {
				builder.endpointOverride(URI.create(endpoint));
			}
			if (region != null) {
				builder.region(Region.of(region));
			} else if (endpoint != null) {
				// The SDK requires a region even where the store ignores it, and
				// an s3:// URI carries none.
				builder.region(Region.of(DEFAULT_REGION));
			}
			builder.forcePathStyle(pathStyle);
			if (hasCredentials()) {
				builder.credentialsProvider(StaticCredentialsProvider.create(
						AwsBasicCredentials.create(accessKey, secretKey)));
			}
			// Deliberately no else-branch: leaving the provider untouched keeps
			// n5's anonymous-then-default-chain fallback.
		};
	}

	/** Never includes the secret key. */
	@Override
	public String toString() {
		return "S3Options{endpoint=" + endpoint + ", region=" + region
				+ ", pathStyle=" + pathStyle
				+ ", credentials=" + (hasCredentials() ? "explicit" : "anonymous/ambient") + "}";
	}

	private static String emptyToNull(final String s) {
		return (s == null || s.trim().isEmpty()) ? null : s.trim();
	}
}
