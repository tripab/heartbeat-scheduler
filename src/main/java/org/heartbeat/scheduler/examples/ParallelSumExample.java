package org.heartbeat.scheduler.examples;

import org.heartbeat.scheduler.annotations.Parallel;
import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.task.HeartbeatTask;

import java.util.concurrent.ExecutionException;

/**
 * Parallel range reduction via heartbeat-scheduled recursive bisection.
 * <p>
 * Applies a moderately expensive per-element kernel (a short LCG chain) to
 * each index and reduces via addition. The recursive structure demonstrates
 * the heartbeat fork/join API: the scheduler decides at runtime which halves
 * get promoted to virtual threads.
 * <p>
 * <b>Note on expected performance:</b> the Phase 4 executor runs the root task
 * on the calling thread; when a forked subtask is promoted, the caller blocks
 * on {@code join()} and sibling tasks cannot run in parallel (there is no
 * work-stealer to pick them up). Real sibling parallelism, and a visible
 * wall-clock speedup, will arrive with the Phase R2 multi-threaded executor.
 * This example is intended to show correctness and heartbeat API usage;
 * compare results rather than timings until then.
 * <p>
 * Usage: {@code java -cp target/classes org.heartbeat.scheduler.examples.ParallelSumExample [size] [threshold]}
 * Defaults: size=1000000, threshold=10000.
 */
public final class ParallelSumExample {

    private static final int DEFAULT_SIZE = 1_000_000;
    private static final int DEFAULT_THRESHOLD = 10_000;
    // 100 multiply-add steps per element makes leaf work CPU-bound.
    private static final int WORK_STEPS = ExamplesSupport.WORK_STEPS;

    private ParallelSumExample() {}

    public static void main(String[] args) throws ExecutionException {
        int size = ExamplesSupport.intArg(args, 0, DEFAULT_SIZE);
        int threshold = ExamplesSupport.intArg(args, 1, DEFAULT_THRESHOLD);
        if (size <= 0 || threshold <= 0 || threshold > size) {
            System.err.printf("Need size > 0 and 0 < threshold <= size; got size=%d threshold=%d%n",
                    size, threshold);
            System.exit(1);
        }

        System.out.printf("Reducing %,d elements (threshold=%,d, work_steps=%d)%n",
                size, threshold, WORK_STEPS);

        // Sequential baseline — establishes the correct expected result.
        long seqStart = System.nanoTime();
        long seqResult = ExamplesSupport.rangeSum(0, size);
        long seqElapsed = System.nanoTime() - seqStart;

        var config = ExamplesSupport.defaultHeartbeatConfig();
        System.out.println(config);

        try (VirtualThreadExecutor executor = new VirtualThreadExecutor(config)) {
            long start = System.nanoTime();
            long parResult = executor.submit(new ReductionTask(0, size, threshold));
            long parElapsed = System.nanoTime() - start;

            if (parResult != seqResult) {
                System.err.printf("Correctness failure: sequential=%d parallel=%d%n",
                        seqResult, parResult);
                System.exit(2);
            }

            System.out.printf("Sequential:  %.2f ms%n", ExamplesSupport.millis(seqElapsed));
            System.out.printf("Heartbeat:   %.2f ms (promotions add overhead until Phase R2)%n",
                    ExamplesSupport.millis(parElapsed));
            System.out.printf("Result:      %d (correct)%n", parResult);
            System.out.println(executor.getStatistics());
        }
    }

    static final class ReductionTask extends HeartbeatTask<Long> {
        private final int start;
        private final int end;
        private final int threshold;

        ReductionTask(int start, int end, int threshold) {
            this.start = start;
            this.end = end;
            this.threshold = threshold;
        }

        @Override
        @Parallel
        protected Long compute() {
            if (end - start <= threshold) {
                return ExamplesSupport.rangeSum(start, end);
            }
            int mid = (start + end) / 2;
            ReductionTask left = new ReductionTask(start, mid, threshold);
            ReductionTask right = new ReductionTask(mid, end, threshold);
            fork(left);
            fork(right);
            return join(left) + join(right);
        }
    }
}
