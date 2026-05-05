package org.heartbeat.scheduler.bench.pbbs;

import java.util.Locale;
import java.util.Map;

/**
 * Structured metadata attached to PBBS benchmark results.
 */
record PbbsResultMetadata(
        PbbsResultMetadata.Family family,
        String distribution,
        int size,
        int threshold,
        PbbsResultMetadata.Mode mode) {

    PbbsResultMetadata {
        if (family == null) {
            throw new IllegalArgumentException("benchmark family must be set");
        }
        distribution = normalizeDistribution(distribution);
        if (size <= 0) {
            throw new IllegalArgumentException("input size must be positive");
        }
        if (threshold <= 0) {
            throw new IllegalArgumentException("task threshold must be positive");
        }
        if (mode == null) {
            throw new IllegalArgumentException("benchmark mode must be set");
        }
    }

    static PbbsResultMetadata of(Family family, PbbsDataset.Descriptor descriptor,
                                 int threshold, Mode mode) {
        return new PbbsResultMetadata(family, descriptor.distribution(),
                descriptor.size(), threshold, mode);
    }

    String benchmarkClassName() {
        return family.benchmarkClassName();
    }

    String jmhMethodName() {
        return mode.jmhMethodName();
    }

    String resultKey() {
        return family.shortName() + "/" + distribution + "/n=" + size
                + "/t=" + threshold + "/" + mode.id();
    }

    PaperContext paperContext() {
        return family.paperContext();
    }

    Map<String, String> tags() {
        return Map.of(
                "family", family.shortName(),
                "distribution", distribution,
                "size", Integer.toString(size),
                "threshold", Integer.toString(threshold),
                "mode", mode.id(),
                "paperContext", paperContext().upstreamName(),
                "comparisonScope", paperContext().comparisonScope());
    }

    enum Mode {
        SEQUENTIAL("sequential"),
        FORK_JOIN_POOL("forkJoinPool"),
        HEARTBEAT("heartbeat");

        private final String jmhMethodName;

        Mode(String jmhMethodName) {
            this.jmhMethodName = jmhMethodName;
        }

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        String jmhMethodName() {
            return jmhMethodName;
        }
    }

    enum Family {
        RADIX_SORT("radix-sort", "PbbsRadixSortBench", "radixsort"),
        SAMPLE_SORT("sample-sort", "PbbsSampleSortBench", "samplesort"),
        REMOVE_DUPLICATES("remove-duplicates", "PbbsRemoveDuplicatesBench", "removeduplicates"),
        CONVEX_HULL("convex-hull", "PbbsConvexHullBench", "convexhull"),
        SPANNING("spanning", "PbbsSpanningBench", "spanning"),
        MST("mst", "PbbsMstBench", "mst");

        private static final String QUALITATIVE_SCOPE = "paper-context-only";

        private final String shortName;
        private final String benchmarkClassName;
        private final PaperContext paperContext;

        Family(String shortName, String benchmarkClassName, String upstreamName) {
            this.shortName = shortName;
            this.benchmarkClassName = benchmarkClassName;
            this.paperContext = new PaperContext(upstreamName, QUALITATIVE_SCOPE);
        }

        String shortName() {
            return shortName;
        }

        String benchmarkClassName() {
            return benchmarkClassName;
        }

        PaperContext paperContext() {
            return paperContext;
        }
    }

    record PaperContext(String upstreamName, String comparisonScope) {
        PaperContext {
            if (upstreamName == null || upstreamName.isBlank()) {
                throw new IllegalArgumentException("paper context name must be non-blank");
            }
            if (!"paper-context-only".equals(comparisonScope)) {
                throw new IllegalArgumentException("PBBS paper context is qualitative only");
            }
        }
    }

    private static String normalizeDistribution(String distribution) {
        if (distribution == null || distribution.isBlank()) {
            throw new IllegalArgumentException("input distribution must be non-blank");
        }
        return distribution.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }
}
