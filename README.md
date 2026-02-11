# Heartbeat Scheduling for Java

A Java implementation of Heartbeat Scheduling using Virtual Threads (Project Loom).

## Overview

Heartbeat Scheduling is a provably efficient scheduling technique for nested parallel programs that delivers bounded overhead without manual tuning. This implementation uses Java 21+ virtual threads and continuations.

### Key Features

- **Provable bounds**: Work overhead ≤ (1 + τ/N) × sequential work
- **Preserved parallelism**: Span ≤ (1 + N/τ) × fully-parallel span  
- **No manual tuning**: Automatic granularity control
- **Virtual threads**: Lightweight threading with millions of threads possible
- **Continuation-based**: Explicit stack control via Project Loom

## Requirements

- **Java 21 or later** (for virtual threads and Continuations API)
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
Coming up!

## Phase 1 - Targted Features

**Phase 1: Core Abstractions** (Complete)
- HeartbeatConfig with builder pattern
- ContinuationScope wrapper
- HeartbeatContinuation for yield/resume
- HeartbeatTask base class
- JoinCounter for fork-join coordination
- PromotionPoint for tracking promotable frames

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
