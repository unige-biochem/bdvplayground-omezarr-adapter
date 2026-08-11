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
package ch.unige.biochem.bdv.img.omezarr;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Offline unit tests for {@link WorldUnit}, the world-coordinate unit conversion
 * behind the importers' "World coordinate units" choice. No network involved:
 * this is pure unit arithmetic and choice parsing.
 */
public class WorldUnitTest {

	private static final double EPS = 1e-12;

	@Test
	public void convertsBetweenMetricUnits() {
		// The IDR reference dataset is in micrometer; 0.36 um is 0.00036 mm.
		assertEquals(1e-3, WorldUnit.MILLIMETER.factorFrom("micrometer"), EPS);
		assertEquals(1.0, WorldUnit.MICROMETER.factorFrom("micrometer"), EPS);
		assertEquals(1e3, WorldUnit.NANOMETER.factorFrom("micrometer"), EPS);

		assertEquals(1e3, WorldUnit.MICROMETER.factorFrom("millimeter"), EPS);
		assertEquals(1e-6, WorldUnit.MILLIMETER.factorFrom("nanometer"), 1e-18);
		assertEquals(1e3, WorldUnit.MILLIMETER.factorFrom("meter"), EPS);
	}

	@Test
	public void acceptsTheUnitNamesAndSymbolsNgffUses() {
		assertEquals(WorldUnit.MILLIMETER.factorFrom("micrometer"),
				WorldUnit.MILLIMETER.factorFrom("um"), EPS);
		assertEquals(WorldUnit.MILLIMETER.factorFrom("micrometer"),
				WorldUnit.MILLIMETER.factorFrom("MICROMETER"), EPS);
		assertEquals(WorldUnit.MILLIMETER.factorFrom("micrometer"),
				WorldUnit.MILLIMETER.factorFrom(" micron "), EPS);
		// Angstrom is a legal NGFF spatial unit and is not a "-meter" name.
		assertEquals(1e-7, WorldUnit.MILLIMETER.factorFrom("angstrom"), 1e-19);
	}

	/**
	 * An image whose axes carry no unit cannot be placed in a metric world. The
	 * factor is NaN so that the opener leaves such a dataset exactly as stored
	 * rather than inventing a calibration for it.
	 */
	@Test
	public void refusesToConvertAnUncalibratedImage() {
		assertTrue(Double.isNaN(WorldUnit.MILLIMETER.factorFrom("pixel")));
		assertTrue(Double.isNaN(WorldUnit.MILLIMETER.factorFrom(null)));
		assertTrue(Double.isNaN(WorldUnit.MILLIMETER.factorFrom("")));
		assertTrue(Double.isNaN(WorldUnit.MICROMETER.factorFrom("arbitrary unit")));
	}

	/** The non-length values never yield a conversion factor. */
	@Test
	public void pixelAndBigStitcherAreNotLengths() {
		assertFalse(WorldUnit.PIXEL.isLength());
		assertFalse(WorldUnit.BIGSTITCHER_COMPATIBLE.isLength());
		assertFalse(WorldUnit.AS_STORED.isLength());
		assertTrue(WorldUnit.MILLIMETER.isLength());

		assertTrue(Double.isNaN(WorldUnit.PIXEL.factorFrom("micrometer")));
		assertTrue(Double.isNaN(WorldUnit.BIGSTITCHER_COMPATIBLE.factorFrom("micrometer")));

		assertEquals("pixel", WorldUnit.PIXEL.unitName());
		assertEquals("pixel", WorldUnit.BIGSTITCHER_COMPATIBLE.unitName());
	}

	/** Every dialog choice has to map onto a value, spaces and all. */
	@Test
	public void parsesEveryDialogChoice() {
		assertEquals(WorldUnit.MILLIMETER, WorldUnit.fromChoice("MILLIMETER"));
		assertEquals(WorldUnit.MICROMETER, WorldUnit.fromChoice("MICROMETER"));
		assertEquals(WorldUnit.NANOMETER, WorldUnit.fromChoice("NANOMETER"));
		assertEquals(WorldUnit.PIXEL, WorldUnit.fromChoice("PIXEL"));
		assertEquals(WorldUnit.BIGSTITCHER_COMPATIBLE,
				WorldUnit.fromChoice("BIGSTITCHER COMPATIBLE"));

		for (final String choice : WorldUnit.CHOICES) {
			assertEquals("choice \"" + choice + "\" must not fall back to AS_STORED",
					false, WorldUnit.fromChoice(choice) == WorldUnit.AS_STORED);
		}
	}

	/** Anything unexpected keeps the file's own unit rather than failing the open. */
	@Test
	public void unknownChoiceFallsBackToAsStored() {
		assertEquals(WorldUnit.AS_STORED, WorldUnit.fromChoice(null));
		assertEquals(WorldUnit.AS_STORED, WorldUnit.fromChoice(""));
		assertEquals(WorldUnit.AS_STORED, WorldUnit.fromChoice("PARSEC"));
		assertEquals("AS_STORED reports no unit of its own", null, WorldUnit.AS_STORED.unitName());
	}
}
