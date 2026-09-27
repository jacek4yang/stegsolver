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
            List<Integer> results = new ArrayList<>();
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
        AtomicInteger computed = new AtomicInteger();
        List<Integer> results = new ArrayList<>();
        try (CoalescingJobRunner runner = new CoalescingJobRunner("test", DIRECT)) {
            runner.submit("slow", () -> {
                release.await(5, TimeUnit.SECONDS);
                computed.incrementAndGet();
                return 1;
            }, results::add, error -> {
                throw new AssertionError(error);
            });
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
        List<Throwable> failures = new ArrayList<>();
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
        List<Throwable> failures = new ArrayList<>();
        List<Integer> results = new ArrayList<>();
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
