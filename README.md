# Heartbeat Scheduling for Java

A Java implementation of Heartbeat Scheduling using Virtual Threads (Project Loom).

## Overview

Heartbeat Scheduling is a provably efficient scheduling technique for nested parallel programs that delivers bounded
overhead without manual tuning. This implementation uses Java 25+ virtual threads and continuations.

### Key Features

- **Provable bounds**: Work overhead ≤ (1 + τ/N) × sequential work
- **Preserved parallelism**: Span ≤ (1 + N/τ) × fully-parallel span
- **No manual tuning**: Automatic granularity control
- **Virtual threads**: Lightweight threading with millions of threads possible
- **Continuation-based**: Explicit stack control via Project Loom

## Requirements

- **Java 25 or later** (for virtual threads and Continuations API)
- Maven 3.6+ (for building)

## Building

```bash
mvn clean compile
```

## Running Tests

```bash
mvn test
```

## Project Structure

```
src/main/java/org/heartbeat/scheduler
├── core/               # Core scheduler components
│   ├── HeartbeatConfig.java
│   ├── HeartbeatTimer.java
│   ├── HeartbeatContext.java
│   ├── PollingStrategy.java
│   ├── PromotionTracker.java
│   ├── CountBasedPolling.java
│   └── TimeBasedPolling.java
├── vthread/            # Virtual thread support
│   ├── ContinuationScope.java
│   └── HeartbeatContinuation.java
├── task/               # Task API
│   └── HeartbeatTask.java
├── sync/               # Synchronization primitives
│   ├── JoinCounter.java
│   └── PromotionPoint.java
└── util/               # Utilities
    └── TimingCalibration.java
    └── SimpleCalibration.java
```

## What's implemented until now

**Phase 1: Core Abstractions**

- HeartbeatConfig with builder pattern
- ContinuationScope wrapper
- HeartbeatContinuation for yield/resume
- HeartbeatTask base class
- JoinCounter for fork-join coordination
- PromotionPoint for tracking promotable frames

**Phase 2: Timing and Polling**

- HeartbeatTimer with calibration
- PollingStrategy abstraction
- CountBasedPolling implementation
- TimeBasedPolling implementation
- TimingCalibration utilities
- HeartbeatContext for thread-local state

**Phase 3: Frame Management**

- PromotionTracker with doubly-linked list
- O(1) push/pop/promote operations
- Frame lifecycle management
- Integration with HeartbeatContext
- Statistics tracking

**Phase 4: Basic Executor**

- VirtualThreadExecutor for task execution
- Fork/join implementation in HeartbeatTask
- Actual promotion logic (frame → virtual thread)
- Fibonacci benchmark example
- Parallel sum benchmark example
- Comprehensive integration tests

## Configuration

### Creating a Configuration

```java
// Default configuration (30μs heartbeat, 1.5μs promotion cost, 5% overhead)
HeartbeatConfig config = HeartbeatConfig.newBuilder()
                .build();

// Custom configuration
HeartbeatConfig config = HeartbeatConfig.newBuilder()
        .heartbeatPeriodMicros(50)  // 50μs heartbeat
        .promotionCostMicros(2)      // 2μs promotion cost
        .numCarrierThreads(8)        // 8 platform threads
        .enableStatistics(true)
        .build();

// Target specific overhead percentage
HeartbeatConfig config = HeartbeatConfig.newBuilder()
        .promotionCostMicros(2)
        .targetOverheadPercent(3.0)  // 3% overhead (N = 100/3 * τ ≈ 67μs)
        .build();
```

### Calibration

```java
// Run calibration to determine optimal parameters
CalibrationResults results = TimingCalibration.calibrateAndPrint();

// Use calibration results
HeartbeatConfig config = HeartbeatConfig.newBuilder()
        .promotionCostNanos(results.promotionCost)
        .heartbeatPeriodNanos(results.recommendedHeartbeatPeriod)
        .build();
```

## Usage

### Basic Example

```java
// Configure the scheduler
HeartbeatConfig config = HeartbeatConfig.newBuilder()
                .targetOverheadPercent(5.0)  // 5% overhead
                .build();

// Create executor
VirtualThreadExecutor executor = new VirtualThreadExecutor(config);

// Define a task
HeartbeatTask<Integer> task = new HeartbeatTask<>() {
    @Override
    protected Integer compute() {
        return 42;
    }
};

// Execute and get result
Integer result = executor.submit(task);
System.out.

println("Result: "+result);

executor.

shutdown();
```

### Fork-Join Example

```java
// Recursive Fibonacci with fork-join
class FibTask extends HeartbeatTask<Long> {
    private final int n;

    FibTask(int n) {
        this.n = n;
    }

    @Override
    protected Long compute() {
        if (n <= 1) return (long) n;

        FibTask f1 = new FibTask(n - 1);
        FibTask f2 = new FibTask(n - 2);

        fork(f1);  // May be promoted to parallel
        fork(f2);

        return join(f1) + join(f2);
    }
}

// Execute
VirtualThreadExecutor executor = new VirtualThreadExecutor(config);
Long result = executor.submit(new FibTask(20));
System.out.

println("Fib(20) = "+result);
```

### Parallel Array Sum

```java
class SumTask extends HeartbeatTask<Long> {
    private final int[] array;
    private final int start, end, threshold;

    SumTask(int[] array, int start, int end, int threshold) {
        this.array = array;
        this.start = start;
        this.end = end;
        this.threshold = threshold;
    }

    @Override
    protected Long compute() {
        if (end - start <= threshold) {
            // Base case: sequential sum
            long sum = 0;
            for (int i = start; i < end; i++) {
                sum += array[i];
            }
            return sum;
        }

        // Divide and conquer
        int mid = (start + end) / 2;
        SumTask left = new SumTask(array, start, mid, threshold);
        SumTask right = new SumTask(array, mid, end, threshold);

        fork(left);
        fork(right);

        return join(left) + join(right);
    }
}
```

## Running Examples

```bash
# Compile
mvn clean compile

# Run Fibonacci benchmark
java -cp target/classes org.heartbeat.examples.FibonacciExample 20

# Run parallel sum benchmark
java -cp target/classes org.heartbeat.examples.ParallelSumExample 1000000 10000
```

## Implementation Details

### Heartbeat Algorithm

The core heartbeat mechanism:

1. **Sequential execution**: Parallel calls initially execute sequentially using stack frames
2. **Periodic polling**: Check heartbeat timer at regular intervals
3. **Promotion**: When timer expires (elapsed ≥ N), promote oldest parallel frame to virtual thread
4. **Load balancing**: Virtual threads scheduled on carrier threads with work-stealing

### Theoretical Guarantees

For heartbeat period N and promotion cost τ:

- **Work bound**: W ≤ (1 + τ/N) × w
    - Example: N = 20τ → overhead = 5%

- **Span bound**: S ≤ (1 + N/τ) × s
    - Example: N = 20τ → span increase = 21×

### Virtual Threads Benefits

- **Lightweight**: Millions of virtual threads possible
- **Natural stack management**: JVM manages continuation stacks
- **Graceful blocking**: Virtual thread blocking doesn't block carrier
- **Mature implementation**: Built into Java 21+

## Testing

Run all tests with coverage:

```bash
mvn clean test jacoco:report
```

View coverage report: `target/site/jacoco/index.html`

## Next Steps

### Phase 4: Basic Executor

- Single-threaded executor
- Frame promotion logic
- Simple fork/join support

## References

- [Heartbeat Scheduling Paper](https://www.andrew.cmu.edu/user/mrainey/papers/heartbeat.pdf)
- [Project Loom](https://openjdk.org/projects/loom/)
- [Virtual Threads (JEP 444)](https://openjdk.org/jeps/444)

## License

This is an educational implementation of the Heartbeat Scheduling algorithm.

## Authors

Implementation based on the paper:
*Heartbeat Scheduling: Provable Efficiency for Nested Parallelism*  
Umut A. Acar, Arthur Charguéraud, Adrien Guatto, Mike Rainey, Filip Sieczkowski  
PLDI 2018
