package org.heartbeat.scheduler.sync;

import org.heartbeat.scheduler.task.HeartbeatTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class JoinCounterTest {

    private static class DummyTask extends HeartbeatTask<Void> {
        @Override
        protected Void compute() {
            return null;
        }
    }

    @Test
    void testInitialState() {
        HeartbeatTask<Void> task = new DummyTask();
        JoinCounter counter = new JoinCounter(2, task);

        assertThat(counter.getCount()).isEqualTo(2);
        assertThat(counter.isReady()).isFalse();
        assertThat(counter.getJoinTask()).isSameAs(task);
    }

    @Test
    void testInvalidInitialCount() {
        HeartbeatTask<Void> task = new DummyTask();
        
        assertThatThrownBy(() -> new JoinCounter(0, task))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new JoinCounter(-1, task))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testNullTask() {
        assertThatThrownBy(() -> new JoinCounter(2, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testSimpleDecrement() {
        JoinCounter counter = new JoinCounter(2, new DummyTask());

        boolean ready1 = counter.decrement();
        assertThat(ready1).isFalse();
        assertThat(counter.getCount()).isEqualTo(1);
        assertThat(counter.isReady()).isFalse();

        boolean ready2 = counter.decrement();
        assertThat(ready2).isTrue();
        assertThat(counter.getCount()).isEqualTo(0);
        assertThat(counter.isReady()).isTrue();
    }

    @Test
    void testDecrementBelowZero() {
        JoinCounter counter = new JoinCounter(1, new DummyTask());

        counter.decrement(); // Now at 0
        
        assertThatThrownBy(counter::decrement)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("below zero");
    }

    @Test
    void testAwaitBlocking() throws InterruptedException {
        JoinCounter counter = new JoinCounter(1, new DummyTask());
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger(0);

        // Start thread that waits
        Thread waiter = new Thread(() -> {
            try {
                latch.countDown(); // Signal we've started waiting
                counter.await();
                result.set(42);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        waiter.start();
        latch.await(); // Wait for waiter to start

        // Give waiter time to block
        Thread.sleep(10);
        assertThat(result.get()).isEqualTo(0); // Should still be waiting

        // Now complete the counter
        counter.decrement();

        // Wait for waiter to complete
        waiter.join(1000);
        assertThat(result.get()).isEqualTo(42); // Should have completed
    }

    @Test
    void testAwaitWithTimeout() throws InterruptedException {
        JoinCounter counter = new JoinCounter(1, new DummyTask());

        // Should timeout without decrement
        boolean completed = counter.await(50);
        assertThat(completed).isFalse();

        // Decrement and should not timeout
        counter.decrement();
        completed = counter.await(50);
        assertThat(completed).isTrue();
    }

    @RepeatedTest(10) // Run multiple times to catch race conditions
    void testConcurrentDecrements() throws InterruptedException {
        int numThreads = 10;
        JoinCounter counter = new JoinCounter(numThreads, new DummyTask());

        try (ExecutorService executor = Executors.newFixedThreadPool(numThreads)) {
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger readyCount = new AtomicInteger(0);

            // Submit all decrements
            for (int i = 0; i < numThreads; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await(); // Wait for signal to start
                        boolean ready = counter.decrement();
                        if (ready) {
                            readyCount.incrementAndGet();
                        }
                        doneLatch.countDown();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            // Start all threads simultaneously
            startLatch.countDown();

            // Wait for completion
            boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            // Exactly one thread should see ready=true
            assertThat(readyCount.get()).isEqualTo(1);
            assertThat(counter.getCount()).isEqualTo(0);
            assertThat(counter.isReady()).isTrue();

            executor.shutdown();
        }
    }

    @Test
    void testMultipleWaiters() throws InterruptedException {
        JoinCounter counter = new JoinCounter(1, new DummyTask());
        
        int numWaiters = 5;
        CountDownLatch allWaiting = new CountDownLatch(numWaiters);
        CountDownLatch allCompleted = new CountDownLatch(numWaiters);

        // Start multiple waiters
        for (int i = 0; i < numWaiters; i++) {
            new Thread(() -> {
                try {
                    allWaiting.countDown();
                    counter.await();
                    allCompleted.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }

        // Wait for all to start waiting
        allWaiting.await();
        Thread.sleep(10); // Give them time to block

        // Complete counter - should wake all waiters
        counter.decrement();

        // All waiters should complete
        boolean completed = allCompleted.await(1, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
    }

    @Test
    void testToString() {
        JoinCounter counter = new JoinCounter(3, new DummyTask());
        String str = counter.toString();
        
        assertThat(str).contains("count=3");
        assertThat(str).contains("ready=false");
        assertThat(str).contains("DummyTask");
    }
}
