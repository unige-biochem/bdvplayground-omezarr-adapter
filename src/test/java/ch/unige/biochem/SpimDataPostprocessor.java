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

package ch.unige.biochem;

import bdv.util.BdvFunctions;
import bdv.util.BdvHandle;
import bdv.util.BdvOptions;
import mpicbg.spim.data.generic.AbstractSpimData;
import org.scijava.module.Module;
import org.scijava.module.process.AbstractPostprocessorPlugin;
import org.scijava.module.process.PostprocessorPlugin;
import org.scijava.object.ObjectService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("unused")
@Plugin(type = PostprocessorPlugin.class)
public class SpimDataPostprocessor extends AbstractPostprocessorPlugin {

	protected static final Logger logger = LoggerFactory.getLogger(
		SpimDataPostprocessor.class);


	@Parameter
	ObjectService objectService;

	@Override
	public void process(Module module) {

		module.getOutputs().forEach((name, object) -> {
			// log.accept("input:\t"+name+"\tclass:\t"+object.getClass().getSimpleName());
			if (object instanceof AbstractSpimData) {
				AbstractSpimData<?> asd = (AbstractSpimData<?>) object;
				BdvFunctions.show(asd);
				module.resolveOutput(name);
				objectService.addObject(asd);
			}
			if (object instanceof AbstractSpimData<?>[]) {
				BdvHandle bdvh = null;
				AbstractSpimData<?>[] asds = (AbstractSpimData<?>[]) object;
				module.resolveOutput(name);
				for (AbstractSpimData<?> asd : asds) {
					if (bdvh == null) {
						bdvh = BdvFunctions.show(asd).get(0).getBdvHandle();
					} else {
						BdvFunctions.show(asd, BdvOptions.options().addTo(bdvh));
					}
					objectService.addObject(asd);
				}
			}
		});
	}
}
