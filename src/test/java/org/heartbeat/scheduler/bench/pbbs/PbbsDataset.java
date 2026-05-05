package org.heartbeat.scheduler.bench.pbbs;

/**
 * PBBS benchmark dataset metadata used by generated in-memory inputs.
 *
 * <p>This is benchmark-local scaffolding, not part of the scheduler runtime API.
 */
final class PbbsDataset {

    enum Scale {
        SMALL(1_024),
        MEDIUM(65_536);

        private final int defaultSize;

        Scale(int defaultSize) {
            this.defaultSize = defaultSize;
        }

        int defaultSize() {
            return defaultSize;
        }
    }

    record Descriptor(String name, int size, String distribution, long seed) {
        Descriptor {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("dataset name must be non-blank");
            }
            if (size <= 0) {
                throw new IllegalArgumentException("dataset size must be positive");
            }
            if (distribution == null || distribution.isBlank()) {
                throw new IllegalArgumentException("dataset distribution must be non-blank");
            }
        }

        static Descriptor of(String benchmarkName, Scale scale, String distribution, long seed) {
            return new Descriptor(benchmarkName + "-" + scale.name().toLowerCase(),
                    scale.defaultSize(), distribution, seed);
        }

        Descriptor withSize(int newSize) {
            return new Descriptor(name, newSize, distribution, seed);
        }
    }

    @FunctionalInterface
    interface Factory<T> {
        T generate(Descriptor descriptor);
    }

    private PbbsDataset() {}
}
