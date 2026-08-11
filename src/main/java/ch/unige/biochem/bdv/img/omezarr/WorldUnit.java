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

import java.util.HashMap;
import java.util.Map;

/**
 * The unit of the world coordinate system a dataset is opened into.
 * <p>
 * OME-NGFF states its own unit per spatial axis ({@code micrometer}, say), and by
 * default ({@link #AS_STORED}) that is what the dataset keeps. Choosing a unit
 * here converts the voxel sizes and the pixel&rarr;physical registrations into it
 * instead, which is what lets an OME-Zarr sit in the same world space as a
 * dataset opened by another importer — BigDataViewer-Playground's Bio-Formats
 * command offers the same choice, and defaults to millimeter.
 * <p>
 * Two of the values are not lengths:
 * <ul>
 * <li>{@link #PIXEL} drops the physical calibration altogether: voxel size
 *     {@code 1,1,1} and no scale or translation in the registration. Anisotropy
 *     is lost with it, exactly as in the Bio-Formats command.</li>
 * <li>{@link #BIGSTITCHER_COMPATIBLE} is a preset rather than a unit. It rescales
 *     the whole dataset so that one pixel along {@code x} measures 1 — keeping the
 *     {@code z/x} anisotropy in the model, which is what BigStitcher expects — and
 *     drops the {@link spimdata.util.Displaysettings} entities. BigStitcher will
 *     not fuse tiles whose entities differ, even for an entity that has nothing to
 *     do with the grouping, and {@code Displaysettings} differs per setup by
 *     construction: it carries that channel's own color and contrast. The
 *     normalisation factor is taken from the first image, so a multi-image
 *     container or a plate stays internally consistent.</li>
 * </ul>
 * <p>
 * An image with no length unit (NGFF axes carry no {@code unit}) cannot be
 * converted to a metric world unit. Rather than inventing a calibration, such an
 * image is left exactly as stored and a warning is logged.
 */
public enum WorldUnit {

	/** Keep the unit the NGFF axes declare. The default, and the only lossless choice. */
	AS_STORED(null, Double.NaN),

	/** Convert to millimeters. */
	MILLIMETER("millimeter", 1e-3),

	/** Convert to micrometers. */
	MICROMETER("micrometer", 1e-6),

	/** Convert to nanometers. */
	NANOMETER("nanometer", 1e-9),

	/** Drop the physical calibration: voxel size {@code 1,1,1}, identity registration. */
	PIXEL("pixel", Double.NaN),

	/** Normalise so one pixel along {@code x} is 1, and drop the display settings. */
	BIGSTITCHER_COMPATIBLE("pixel", Double.NaN);

	/** The dialog choices, in the order the Bio-Formats command lists them. */
	public static final String[] CHOICES = {
			"MILLIMETER", "MICROMETER", "NANOMETER", "PIXEL", "BIGSTITCHER COMPATIBLE" };

	/** The unit name reported by the resulting {@code VoxelDimensions}, or {@code null}. */
	private final String unitName;

	/** How many meters one of this unit is, or {@code NaN} when it is not a length. */
	private final double meters;

	WorldUnit(final String unitName, final double meters) {
		this.unitName = unitName;
		this.meters = meters;
	}

	/**
	 * The name this unit is reported under, or {@code null} for {@link #AS_STORED},
	 * which keeps whatever the file declared.
	 */
	public String unitName() {
		return unitName;
	}

	/** Whether this is a metric length the NGFF unit can be converted into. */
	public boolean isLength() {
		return !Double.isNaN(meters);
	}

	/**
	 * The factor that converts a length expressed in {@code ngffUnit} into this
	 * unit, or {@code NaN} when either side is not a known length — in which case
	 * the caller must leave the calibration alone.
	 *
	 * @param ngffUnit the {@code unit} of an NGFF spatial axis, e.g. {@code "micrometer"}.
	 */
	public double factorFrom(final String ngffUnit) {
		if (!isLength()) return Double.NaN;
		final Double source = METERS.get(normalize(ngffUnit));
		return source == null ? Double.NaN : source / meters;
	}

	/**
	 * Maps a dialog choice ({@link #CHOICES}) onto a value. Unknown or empty text
	 * yields {@link #AS_STORED} rather than failing, so a script passing something
	 * unexpected still opens its data.
	 */
	public static WorldUnit fromChoice(final String choice) {
		if (choice == null) return AS_STORED;
		final String c = choice.trim().toUpperCase().replace(' ', '_');
		for (final WorldUnit u : values()) {
			if (u.name().equals(c)) return u;
		}
		return AS_STORED;
	}

	/** Length of every unit NGFF may name, in meters. */
	private static final Map<String, Double> METERS = new HashMap<>();

	static {
		// The UDUNITS-2 names the NGFF spec allows on a spatial axis, plus the
		// symbols that turn up in the wild.
		METERS.put("meter", 1.0);
		METERS.put("m", 1.0);
		METERS.put("decimeter", 1e-1);
		METERS.put("centimeter", 1e-2);
		METERS.put("cm", 1e-2);
		METERS.put("millimeter", 1e-3);
		METERS.put("mm", 1e-3);
		METERS.put("micrometer", 1e-6);
		METERS.put("micron", 1e-6);
		METERS.put("um", 1e-6);
		METERS.put("µm", 1e-6);
		METERS.put("nanometer", 1e-9);
		METERS.put("nm", 1e-9);
		METERS.put("angstrom", 1e-10);
		METERS.put("picometer", 1e-12);
		METERS.put("femtometer", 1e-15);
		METERS.put("attometer", 1e-18);
		METERS.put("zeptometer", 1e-21);
		METERS.put("yoctometer", 1e-24);
		METERS.put("hectometer", 1e2);
		METERS.put("kilometer", 1e3);
		METERS.put("megameter", 1e6);
		METERS.put("gigameter", 1e9);
		METERS.put("terameter", 1e12);
		METERS.put("petameter", 1e15);
		METERS.put("exameter", 1e18);
		METERS.put("zettameter", 1e21);
		METERS.put("yottameter", 1e24);
		METERS.put("inch", 0.0254);
		METERS.put("foot", 0.3048);
		METERS.put("yard", 0.9144);
		METERS.put("mile", 1609.344);
		METERS.put("parsec", 3.0856775814913673e16);
	}

	private static String normalize(final String unit) {
		return unit == null ? "" : unit.trim().toLowerCase();
	}
}
