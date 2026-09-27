package io.github.jacek4yang.stegsolver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoalescingJobRunnerTest {

    /** Runs the task immediately on the calling thread, which keeps the tests deterministic. */
    private static final Executor DIRECT = Runnable::run;

    @Test
    @DisplayName("a single job delivers its result")
    void singleJob() throws Exception {
        try (CoalescingJobRunner runner = new CoalescingJobRunner("test", DIRECT)) {
            List<Integer> results = new java.util.concurrent.CopyOnWriteArrayList<>();
            runner.submit("one", () -> 42, results::add, error -> {
                throw new AssertionError(error);
            });
            waitFor(() -> !results.isEmpty());
            assertEquals(List.of(42), results);
            assertEquals(1, runner.submittedCount());
        }
    }

    @Test
    @DisplayName("rapid submissions only deliver the newest result")
    void newestResultWins() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger computed = new AtomicInteger();
        List<Integer> results = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (CoalescingJobRunner runner = new CoalescingJobRunner("test", DIRECT)) {
            runner.submit("slow", () -> {
                started.countDown();
                while (release.getCount() > 0) {
                    try { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { /* hold the worker to test queue coalescing */ }
                }
                computed.incrementAndGet();
                return 1;
            }, results::add, error -> {
                throw new AssertionError(error);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int i = 2; i <= 20; i++) {
                int value = i;
                runner.submit("job " + value, () -> {
                    computed.incrementAndGet();
                    return value;
                }, results::add, error -> {
                    throw new AssertionError(error);
                });
            }
            release.countDown();
            waitFor(() -> !results.isEmpty());
            Thread.sleep(100);
            assertEquals(List.of(20), results, "only the newest request may be delivered");
            // The job that was already running is allowed to finish (its result is discarded), and the
            // newest request is computed. Everything in between is dropped without being computed, which
            // is what keeps rapid transform navigation responsive on large images.
            assertTrue(computed.get() <= 2, () -> "computed " + computed.get() + " jobs, expected at most 2");
        }
    }

    @Test
    @DisplayName("failures of the newest job are reported")
    void failuresAreReported() throws Exception {
        List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (CoalescingJobRunner runner = new CoalescingJobRunner("test", DIRECT)) {
            runner.submit("boom", () -> {
                throw new IllegalStateException("nope");
            }, result -> {
                throw new AssertionError("must not succeed");
            }, failures::add);
            waitFor(() -> !failures.isEmpty());
            assertEquals(1, failures.size());
            assertTrue(failures.get(0) instanceof IllegalStateException);
        }
    }

    @Test
    @DisplayName("a failure of a superseded job is not reported")
    void staleFailuresAreIgnored() throws Exception {
        List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Integer> results = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        try (CoalescingJobRunner runner = new CoalescingJobRunner("test", DIRECT)) {
            runner.submit("stale failure", () -> {
                release.await(5, TimeUnit.SECONDS);
                throw new IllegalStateException("stale");
            }, value -> {
                throw new AssertionError("must not succeed");
            }, failures::add);
            runner.submit("newer", () -> 7, results::add, failures::add);
            release.countDown();
            waitFor(() -> !results.isEmpty());
            Thread.sleep(50);
            assertEquals(List.of(7), results);
            assertTrue(failures.isEmpty(), () -> "unexpected failures: " + failures);
        }
    }

    @Test
    @DisplayName("a runner that is closed ignores further submissions")
    void closedRunner() {
        CoalescingJobRunner runner = new CoalescingJobRunner("test", DIRECT);
        runner.close();
        runner.submit("ignored", () -> 1, value -> {
            throw new AssertionError("must not run");
        }, error -> {
            throw new AssertionError("must not run");
        });
        assertEquals(0, runner.submittedCount());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void staleQueuedSuccessIsNotDelivered() throws Exception {
        var queue = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        var results = new ArrayList<Integer>();
        try (var runner = new CoalescingJobRunner("test", queue::add)) {
            runner.submit("old", () -> 1, results::add, error -> {});
            Runnable old = queue.poll(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNotNull(old);
            runner.submit("new", () -> 2, results::add, error -> {});
            old.run();
            queue.poll(5, TimeUnit.SECONDS).run();
            assertEquals(List.of(2), results);
        }
    }

    @Test
    void cancelAndCloseSuppressQueuedFailures() throws Exception {
        var queue = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        var failures = new ArrayList<Throwable>();
        try (var runner = new CoalescingJobRunner("test", queue::add)) {
            runner.submit("failure", () -> { throw new IllegalStateException(); }, result -> {}, failures::add);
            Runnable callback = queue.poll(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNotNull(callback);
            runner.cancel();
            callback.run();
            assertTrue(failures.isEmpty());
            runner.submit("close", () -> 1, result -> failures.add(new Exception()), failures::add);
            callback = queue.poll(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNotNull(callback);
            runner.close();
            callback.run();
            assertTrue(failures.isEmpty());
        }
    }

    @Test void supersededWorkReceivesInterruption() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        try (var runner = new CoalescingJobRunner("test", DIRECT)) {
            runner.submit("old", () -> {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.countDown(); }
                return 1;
            }, value -> {}, error -> {});
            assertTrue(started.await(5, TimeUnit.SECONDS));
            runner.submit("new", () -> 2, value -> delivered.countDown(), error -> {});
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("condition was not met within the timeout");
    }
}
