package org.heartbeat.scheduler.examples;

import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.task.HeartbeatTask;

import java.util.concurrent.ExecutionException;

/**
 * Parallel range reduction using a deliberately skewed (1:9) split.
 * <p>
 * Unlike {@link ParallelSumExample} which halves the range symmetrically,
 * this example peels off a small 10% chunk on the left and recurses on the
 * remaining 90%. The resulting task tree is a deep linear chain — analogous
 * to right-skewed quicksort — rather than a balanced binary tree.
 * <p>
 * This stresses the heartbeat's oldest-frame-first promotion policy: the
 * oldest (leftmost) frame in the chain is always the 10% chunk, while the
 * 90% remainder re-enters the stack at each level. In a balanced scheduler
 * these would all get roughly equal treatment; here the depth of the chain
 * (printed at startup as {@code depth≈N}) means the promotion tracker holds
 * a queue of small-chunk promotion points waiting to be either promoted or joined.
 * <p>
 * The result is verified against a sequential baseline with the same LCG
 * kernel used in {@link ParallelSumExample}, so results are directly comparable.
 * <p>
 * Usage: {@code java -cp target/classes org.heartbeat.scheduler.examples.RecursiveSumExample [size] [threshold]}
 * Defaults: size=1000000, threshold=10000.
 */
public final class RecursiveSumExample {

    private static final int DEFAULT_SIZE = 1_000_000;
    private static final int DEFAULT_THRESHOLD = 10_000;
    private static final int WORK_STEPS = ExamplesSupport.WORK_STEPS;
    // Skew factor: left chunk = SPLIT_PCT% of range, right = rest.
    private static final int SPLIT_NUMERATOR = 1;
    private static final int SPLIT_DENOMINATOR = 10; // 1/10 = 10%

    private RecursiveSumExample() {}

    public static void main(String[] args) throws ExecutionException {
        int size = ExamplesSupport.intArg(args, 0, DEFAULT_SIZE);
        int threshold = ExamplesSupport.intArg(args, 1, DEFAULT_THRESHOLD);
        if (size <= 0 || threshold <= 0 || threshold > size) {
            System.err.printf("Need size > 0 and 0 < threshold <= size; got size=%d threshold=%d%n",
                    size, threshold);
            System.exit(1);
        }
        int splitSize = Math.max(threshold, size * SPLIT_NUMERATOR / SPLIT_DENOMINATOR);
        int depth = size / splitSize;

        System.out.printf("Reducing %,d elements with skewed (%d:%d) split " +
                          "(threshold=%,d, depth≈%d, work_steps=%d)%n",
                size, SPLIT_NUMERATOR, SPLIT_DENOMINATOR - SPLIT_NUMERATOR,
                threshold, depth, WORK_STEPS);

        // Sequential baseline
        long seqStart = System.nanoTime();
        long seqResult = ExamplesSupport.rangeSum(0, size);
        long seqElapsed = System.nanoTime() - seqStart;

        var config = ExamplesSupport.defaultHeartbeatConfig();
        System.out.println(config);

        try (VirtualThreadExecutor executor = new VirtualThreadExecutor(config)) {
            long start = System.nanoTime();
            long parResult = executor.submit(new SkewedTask(0, size, threshold, splitSize));
            long parElapsed = System.nanoTime() - start;

            if (parResult != seqResult) {
                System.err.printf("Correctness failure: sequential=%d skewed=%d%n",
                        seqResult, parResult);
                System.exit(2);
            }

            System.out.printf("Sequential:  %.2f ms%n", ExamplesSupport.millis(seqElapsed));
            System.out.printf("Skewed:      %.2f ms%n", ExamplesSupport.millis(parElapsed));
            System.out.printf("Result:      %d (correct)%n", parResult);
            System.out.println(executor.getStatistics());
        }
    }

    /**
     * Task that peels a small left chunk (10%) and recurses on the remainder (90%).
     * The promotion tracker will accumulate a chain of small-chunk promotion points,
     * each eligible for oldest-first promotion when the heartbeat fires.
     */
    static final class SkewedTask extends HeartbeatTask<Long> {
        private final int start;
        private final int end;
        private final int threshold;
        private final int splitSize;

        SkewedTask(int start, int end, int threshold, int splitSize) {
            this.start = start;
            this.end = end;
            this.threshold = threshold;
            this.splitSize = splitSize;
        }

        @Override
        protected Long compute() {
            if (end - start <= threshold) {
                return ExamplesSupport.rangeSum(start, end);
            }
            // Peel off a small left chunk; recurse on the large right remainder.
            int mid = Math.min(start + splitSize, end - threshold);
            SkewedTask left = new SkewedTask(start, mid, threshold, splitSize);
            SkewedTask right = new SkewedTask(mid, end, threshold, splitSize);
            fork(left);
            fork(right);
            return join(left) + join(right);
        }
    }
}
