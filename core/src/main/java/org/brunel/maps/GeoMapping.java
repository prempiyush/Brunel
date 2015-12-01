/*
 * Copyright (c) 2015 IBM Corporation and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.brunel.maps;

import org.brunel.geom.Point;
import org.brunel.geom.Poly;
import org.brunel.geom.Rect;
import org.brunel.util.MappedLists;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Defines how we will map feature names to features within files
 */
public class GeoMapping {

    public static GeoMapping createGeoMapping(Poly polygon, List<GeoFile> required, GeoData geoAnalysis) {
        HashSet<Object> unmatched = new HashSet<Object>();
        MappedLists<GeoFile, Object> map = mapBoundsToFiles(polygon, geoAnalysis.getGeoFiles());
        return new GeoMapping(required, unmatched, map);
    }

    // Create a map from GeoFile index to the points that file contains.
    private static MappedLists<GeoFile, Object> mapBoundsToFiles(Poly poly, GeoFile[] geoFiles) {
        MappedLists<GeoFile, Object> map = new MappedLists<GeoFile, Object>();
        if (poly.count() == 0) return map;
        Rect bounds = poly.bounds;
        for (GeoFile f : geoFiles) {
            if (!bounds.intersects(f.bounds)) continue;             // Ignore if outside the bounds
            for (Point p : poly.points)
                if (f.covers(p)) map.add(f, p);
        }
        return map;
    }

    static GeoMapping createGeoMapping(Object[] names, List<GeoFile> required, GeoData geoAnalysis) {
        HashSet<Object> unmatched = new HashSet<Object>();
        MappedLists<GeoFile, Object> map = geoAnalysis.mapFeaturesToFiles(names, unmatched);
        GeoMapping mapping = new GeoMapping(required, unmatched, map);
        mapping.buildFeatureMap();
        return mapping;
    }

    public boolean isReference() {
        return featureMap.isEmpty();
    }

    private void buildFeatureMap() {
        // Build featureMap -- only needed for features, not points
        for (int i = 0; i < result.length; i++) {
            List<Object> use = potential.get(result[i]);        // Features used in this file
            if (use == null) continue;                          // Required, but contains no features
            for (Object o : use) {                              // Record match information
                IndexedFeature s = (IndexedFeature) o;
                featureMap.put(s.name, new int[]{i, s.indexWithinFile});
            }
        }
    }

    private final Map<Object, int[]> featureMap = new TreeMap<Object, int[]>();     // Feature -> [file, featureKey]
    private final Set<Object> unmatched;                                            // Features we did not map
    private final GeoFile[] result;                                                 // The files to use
    private final MappedLists<GeoFile, Object> potential;                           // Files that contain wanted items
    private GeoFileGroup best;                                                      // We search to determine this

    private GeoMapping(List<GeoFile> required, Set<Object> unmatched, MappedLists<GeoFile, Object> potential) {
        this.potential = filter(potential, required);   // If required is defined, filter to only show those files
        this.unmatched = unmatched;                     // Unmatched items
        searchForBestSubset();                          // Calculate the best collection of files for those features.

        // Define the desired files to use
        if (required != null) {
            result = required.toArray(new GeoFile[required.size()]);
        } else {
            result = best.files.toArray(new GeoFile[best.files.size()]);
            Arrays.sort(result);
        }
    }

    public int fileCount() {
        return result.length;
    }

    public Map<Object, int[]> getFeatureMap() {
        return featureMap;
    }

    public String[] getFiles() {
        String[] strings = new String[result.length];
        for (int i=0; i<strings.length; i++) strings[i] = result[i].name;
        return strings;
    }

    public Set<Object> getUnmatched() {
        return unmatched;
    }

    public Rect totalBounds() {
        Rect bounds = null;
        for (GeoFile i : result) bounds = Rect.union(i.bounds, bounds);
        return bounds;
    }

    // Remove any not mentioned by the user's required list
    private MappedLists<GeoFile, Object> filter(MappedLists<GeoFile, Object> desired, List<GeoFile> required) {
        if (required == null || required.isEmpty()) return desired;

        MappedLists<GeoFile, Object> filtered = new MappedLists<GeoFile, Object>();
        for (Map.Entry<GeoFile, List<Object>> e : desired.entrySet())
            if (required.contains(e.getKey())) filtered.addAll(e.getKey(), e.getValue());
        return filtered;
    }

    // Recursively search for the best files to add
    private void searchForBestAdditions(GeoFileGroup current, List<GeoFile> possibles) {
        // System.out.println("Searching w/ current=" + current + ", base=" +  best+ ", possibles=" + possibles);
        if (current.isBetter(best)) {
            // System.out.println(" *** improved");
            best = current;                     // If we are the best, update
        }
        if (possibles.isEmpty()) return;                                    // No more we can do
        int maxImprovement = potential.get(possibles.get(0)).size();        // Additions can at best add this many
        if (current.cannotImprove(best, maxImprovement)) return;            // If we cannot get better, stop searching

        // recurse to search for best combination
        LinkedList<GeoFile> working = new LinkedList<GeoFile>(possibles);
        while (!working.isEmpty()) {
            GeoFile k = working.removeFirst();
            GeoFileGroup trial = current.add(k);                            // Will be null if known to be useless
            if (trial != null) searchForBestAdditions(trial, working);
        }
    }

    private void searchForBestSubset() {
        best = GeoFileGroup.makeEmpty(potential);

        // Create list of possible ones to use, sorted with the most features first
        List<GeoFile> possibles = new ArrayList<GeoFile>(potential.keySet());
        Collections.sort(possibles, new Comparator<GeoFile>() {
            public int compare(GeoFile a, GeoFile b) {
                return potential.get(b).size() - potential.get(a).size();
            }
        });
        searchForBestAdditions(best, possibles);
    }

}
