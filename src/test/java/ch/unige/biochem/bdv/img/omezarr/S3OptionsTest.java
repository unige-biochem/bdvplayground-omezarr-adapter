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

import org.junit.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3ServiceClientConfiguration;

import java.net.URI;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Offline unit tests for {@link S3Options} — the settings that let an
 * {@code s3://bucket/key} URI reach an object store that is not AWS.
 * <p>
 * These build a real {@code S3Client} to check what the settings actually do to
 * the AWS SDK, but never issue a request, so they run in the default build.
 */
public class S3OptionsTest {

	private static final String ENDPOINT = "https://livingobjects.ebi.ac.uk";

	@Test
	public void endpointRegionAndPathStyleReachTheClient() {
		final S3ServiceClientConfiguration conf =
				configure(new S3Options(ENDPOINT, "eu-west-2", true, null, null));

		assertEquals("endpoint", URI.create(ENDPOINT), conf.endpointOverride().orElse(null));
		assertEquals("region", "eu-west-2", conf.region().id());
	}

	@Test
	public void missingRegionDefaultsSoTheSdkCanBuild() {
		// An s3:// URI carries no region, and the SDK refuses to build without one
		// even against a store that ignores regions entirely.
		final S3ServiceClientConfiguration conf =
				configure(S3Options.anonymous(ENDPOINT));

		assertEquals("region", "us-east-1", conf.region().id());
		assertEquals("endpoint", URI.create(ENDPOINT), conf.endpointOverride().orElse(null));
	}

	@Test
	public void credentialsAreUsedWhenGiven() {
		final S3ServiceClientConfiguration conf =
				configure(new S3Options(ENDPOINT, null, true, "AKIAEXAMPLE", "s3cr3t"));

		final AwsCredentials creds =
				((AwsCredentialsProvider) conf.credentialsProvider()).resolveCredentials();
		assertEquals("access key", "AKIAEXAMPLE", creds.accessKeyId());
		assertEquals("secret key", "s3cr3t", creds.secretAccessKey());
	}

	@Test
	public void noCredentialsLeavesTheProviderAlone() {
		// n5-universe pre-sets an anonymous provider, applies our consumer, then
		// checks by identity whether that provider survived: if it did, and the
		// bucket turns out not to be readable anonymously, it retries with the AWS
		// default chain. Overwriting the provider here would silently disable that
		// fallback, so replicate n5's own check.
		final AnonymousCredentialsProvider anonymous = AnonymousCredentialsProvider.create();
		final S3ClientBuilder builder = S3Client.builder().credentialsProvider(anonymous);
		S3Options.anonymous(ENDPOINT).asBuilderConfig().accept(builder);

		try (S3Client client = builder.build()) {
			assertSame("the pre-set provider must survive",
					anonymous, client.serviceClientConfiguration().credentialsProvider());
		}
	}

	@Test
	public void emptyOptionsProduceNoConfiguration() {
		// Nothing to say => no consumer, so N5Factory keeps its own defaults.
		assertTrue(new S3Options("", "  ", false, null, "").isEmpty());
		assertNull(new S3Options(null, null, false, null, null).asBuilderConfig());
	}

	@Test
	public void pathStyleAloneIsNotEmpty() {
		assertFalse(new S3Options(null, null, true, null, null).isEmpty());
	}

	@Test
	public void credentialsNeedBothHalves() {
		assertFalse("access key alone",
				new S3Options(ENDPOINT, null, true, "AKIAEXAMPLE", null).hasCredentials());
		assertFalse("secret alone",
				new S3Options(ENDPOINT, null, true, null, "s3cr3t").hasCredentials());
		assertTrue(new S3Options(ENDPOINT, null, true, "AKIAEXAMPLE", "s3cr3t").hasCredentials());
	}

	@Test
	public void withoutCredentialsKeepsTheConnectionButDropsTheSecret() {
		final S3Options full = new S3Options(ENDPOINT, "eu-west-2", true, "AKIAEXAMPLE", "s3cr3t");
		final S3Options stripped = full.withoutCredentials();

		assertEquals(ENDPOINT, stripped.getEndpoint());
		assertEquals("eu-west-2", stripped.getRegion());
		assertTrue(stripped.isPathStyle());
		assertFalse("credentials must be dropped", stripped.hasCredentials());
		assertTrue("the original is untouched", full.hasCredentials());
	}

	@Test
	public void toStringNeverLeaksTheSecret() {
		final String s = new S3Options(ENDPOINT, null, true, "AKIAEXAMPLE", "s3cr3t").toString();
		assertFalse("secret key in toString: " + s, s.contains("s3cr3t"));
	}

	/** Applies the options to a real client builder and returns what the SDK made of them. */
	private static S3ServiceClientConfiguration configure(final S3Options options) {
		final Consumer<S3ClientBuilder> config = options.asBuilderConfig();
		final S3ClientBuilder builder = S3Client.builder();
		config.accept(builder);
		try (S3Client client = builder.build()) {
			return client.serviceClientConfiguration();
		}
	}
}
