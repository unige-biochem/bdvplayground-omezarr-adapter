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

import org.reflections.Reflections;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

public class BuildDocumentationForLLM {
    static String doc = "";

    public static void main(String... args) {
        //

        Reflections reflections = new Reflections("ch.unige.biochem");

        Set<Class<? extends Command>> commandClasses =
                reflections.getSubTypesOf(Command.class);

        HashMap<String, String> docPerClass = new HashMap<>();

        commandClasses.forEach(c -> {

            Plugin plugin = c.getAnnotation(Plugin.class);
            if (plugin!=null) {
                //String url = linkGitHubRepoPrefix+c.getName().replaceAll("\\.","\\/")+".java";
                doc = "# " + c.getName() + "\n";
                if (!plugin.label().isEmpty()) {
                    doc += "Label: " + plugin.label() + "\n";
                }
                if (!plugin.description().isEmpty()) {
                    doc += "Description: " + plugin.description() + "\n";
                }

                Field[] fields = c.getDeclaredFields();
                List<Field> inputFields = Arrays.stream(fields)
                        .filter(f -> f.isAnnotationPresent(Parameter.class))
                        .filter(f -> {
                            Parameter p = f.getAnnotation(Parameter.class);
                            return (p.type() == ItemIO.INPUT) || (p.type() == ItemIO.BOTH);
                        }).sorted(Comparator.comparing(Field::getName)).collect(Collectors.toList());
                if (!inputFields.isEmpty()) {
                    doc += "## Input\n";
                    inputFields.forEach(f -> {
                        doc += f.getType().getSimpleName()+" " + f.getName() + "; // " + f.getAnnotation(Parameter.class).label() + "\n";
                        if (!f.getAnnotation(Parameter.class).description().isEmpty())
                            doc += f.getAnnotation(Parameter.class).description() + "\n";
                    });
                }

                List<Field> outputFields = Arrays.stream(fields)
                        .filter(f -> f.isAnnotationPresent(Parameter.class))
                        .filter(f -> {
                            Parameter p = f.getAnnotation(Parameter.class);
                            return (p.type() == ItemIO.OUTPUT) || (p.type() == ItemIO.BOTH);
                        }).sorted(Comparator.comparing(Field::getName)).collect(Collectors.toList());
                if (!outputFields.isEmpty()) {
                    doc += "## Output\n";
                    outputFields.forEach(f -> {
                        doc += f.getType().getSimpleName()+" " + f.getName() + "; // " + f.getAnnotation(Parameter.class).label() + "\n";
                        if (!f.getAnnotation(Parameter.class).description().isEmpty())
                            doc += f.getAnnotation(Parameter.class).description() + "\n";
                    });
                } else {
                    doc += "## Output\n";
                }

                doc+="\n";

                docPerClass.put(c.getName(),doc);
            }
        });
        Object[] keys = docPerClass.keySet().toArray();
        Arrays.sort(keys);
        for (Object key:keys) {
            String k = (String) key;
            System.out.println(docPerClass.get(k));
        }
    }
}
