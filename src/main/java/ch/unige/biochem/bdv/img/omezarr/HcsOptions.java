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

/**
 * How much of an HCS plate to open, and how carefully.
 * <p>
 * A plate is big: a 49-well plate with 32 fields and 5 channels is 1568 field
 * images and 7840 {@link mpicbg.spim.data.sequence.ViewSetup}s. Two knobs keep
 * that manageable.
 * <p>
 * <b>Uniform fields (the default).</b> Every field of a plate comes from the same
 * acquisition, so all field images share their axes, dimensions, resolution
 * levels, channels and calibration. {@link ch.unige.biochem.bdv.img.omezarr.OmeZarrOpener}
 * therefore reads <em>one</em> field's metadata and reuses it for the whole plate,
 * which turns discovery from two HTTP round-trips per field into a constant three.
 * A plate whose fields genuinely differ (mixed magnifications, say) would be
 * mis-sized by that assumption; {@link #strictPerField()} turns it off and reads
 * every field, at the cost of the round-trips.
 * <p>
 * <b>Caps.</b> {@link #wells(int)} and {@link #fields(int)} open only the first
 * <em>n</em> wells (in row/column order) and the first <em>n</em> images of each
 * well — enough to look at a corner of a plate without building thousands of
 * sources. Both default to unlimited.
 * <p>
 * These settings are part of the identity of the resulting dataset: capped
 * discovery produces a specific set of setup ids, so
 * {@link XmlIoOmeZarrImageLoader} persists them in the BDV XML and replays them
 * when the dataset is reloaded. They are inert for a container that is not a
 * plate.
 * <p>
 * Instances are immutable; the fluent methods return copies.
 *
 * <pre>
 * OmeZarrOpener.open(url);                                        // whole plate, uniform fields
 * OmeZarrOpener.open(url, null, HcsOptions.wells(4).fields(2));   // a corner of it
 * OmeZarrOpener.open(url, null, HcsOptions.strictPerField());     // read every field
 * </pre>
 */
public final class HcsOptions {

	/** Cap value meaning "no limit". */
	public static final int UNLIMITED = -1;

	/** Whole plate, uniform fields — what {@code OmeZarrOpener.open(uri)} uses. */
	public static final HcsOptions DEFAULT = new HcsOptions(UNLIMITED, UNLIMITED, false);

	private final int maxWells;
	private final int maxFieldsPerWell;
	private final boolean strictPerField;

	private HcsOptions(final int maxWells, final int maxFieldsPerWell, final boolean strictPerField) {
		this.maxWells = normalize(maxWells);
		this.maxFieldsPerWell = normalize(maxFieldsPerWell);
		this.strictPerField = strictPerField;
	}

	/** The defaults: every well, every field, uniform-field metadata. */
	public static HcsOptions all() {
		return DEFAULT;
	}

	/**
	 * Opens at most {@code maxWells} wells, in row/column order.
	 *
	 * @param maxWells the cap, or {@link #UNLIMITED} (or any value &le; 0) for all.
	 */
	public static HcsOptions wells(final int maxWells) {
		return new HcsOptions(maxWells, UNLIMITED, false);
	}

	/**
	 * Reads every field's own metadata instead of assuming the plate's fields are
	 * uniform. Correct for any plate, but costs two HTTP round-trips per field.
	 */
	public static HcsOptions strictPerField() {
		return new HcsOptions(UNLIMITED, UNLIMITED, true);
	}

	/**
	 * A copy that opens at most {@code maxFieldsPerWell} images per well, in the
	 * order the well's {@code well} metadata lists them.
	 *
	 * @param maxFieldsPerWell the cap, or {@link #UNLIMITED} for all.
	 */
	public HcsOptions fields(final int maxFieldsPerWell) {
		return new HcsOptions(this.maxWells, maxFieldsPerWell, this.strictPerField);
	}

	/** A copy that also reads every field's own metadata (see {@link #strictPerField()}). */
	public HcsOptions strict() {
		return new HcsOptions(this.maxWells, this.maxFieldsPerWell, true);
	}

	/** Maximum number of wells to open, or {@link #UNLIMITED}. */
	public int getMaxWells() {
		return maxWells;
	}

	/** Maximum number of field images per well, or {@link #UNLIMITED}. */
	public int getMaxFieldsPerWell() {
		return maxFieldsPerWell;
	}

	/** Whether every field's metadata is read rather than reusing one field's. */
	public boolean isStrictPerField() {
		return strictPerField;
	}

	/** Whether these are the defaults, i.e. nothing worth persisting. */
	public boolean isDefault() {
		return maxWells == UNLIMITED && maxFieldsPerWell == UNLIMITED && !strictPerField;
	}

	/** Applies {@link #getMaxWells()} to a count. */
	int limitWells(final int count) {
		return maxWells == UNLIMITED ? count : Math.min(count, maxWells);
	}

	/** Applies {@link #getMaxFieldsPerWell()} to a count. */
	int limitFields(final int count) {
		return maxFieldsPerWell == UNLIMITED ? count : Math.min(count, maxFieldsPerWell);
	}

	@Override
	public String toString() {
		return "HcsOptions{maxWells=" + str(maxWells) + ", maxFieldsPerWell=" + str(maxFieldsPerWell)
				+ ", fields=" + (strictPerField ? "read individually" : "assumed uniform") + "}";
	}

	private static String str(final int cap) {
		return cap == UNLIMITED ? "all" : Integer.toString(cap);
	}

	/** Any non-positive cap means "no limit", so callers can pass 0 from a dialog. */
	private static int normalize(final int cap) {
		return cap <= 0 ? UNLIMITED : cap;
	}
}