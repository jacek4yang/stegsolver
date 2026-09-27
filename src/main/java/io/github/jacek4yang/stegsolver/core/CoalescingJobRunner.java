package io.github.jacek4yang.stegsolver.core;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Runs expensive work off the user interface thread while guaranteeing that only the most recent
 * request produces a result.
 *
 * <p>This is what makes fast transform navigation usable: holding the right arrow key can queue
 * dozens of requests, but superseded work is dropped instead of being computed, and a result that
 * has already been overtaken is never delivered.</p>
 *
 * <p>The runner is not tied to JavaFX: the executor used to deliver results is injected, which keeps
 * the class unit testable without a toolkit.</p>
 */
public final class CoalescingJobRunner implements AutoCloseable {

    /** A unit of background work. */
    @FunctionalInterface
    public interface Job<T> {
        T run() throws Exception;
    }

    private final ExecutorService worker;
    private final Executor deliveryExecutor;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private final Object lock = new Object();
    private Pending<?> pending;
    private Thread activeThread;

    public CoalescingJobRunner(String threadName, Executor deliveryExecutor) {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        };
        this.worker = Executors.newSingleThreadExecutor(factory);
        this.deliveryExecutor = deliveryExecutor;
    }

    /**
     * Submits a job. Any job that has not started yet is replaced by this one, and this job's result
     * is discarded if a newer request appears before it completes.
     *
     * @param label short description used in error messages
     * @param job the work, executed on the runner thread; must not touch the UI
     * @param onSuccess called on the delivery executor with the result of the newest job
     * @param onFailure called on the delivery executor if the newest job throws
     */
    public <T> void submit(String label, Job<T> job, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        synchronized (lock) {
            if (closed.get()) {
                return;
            }
            long id = sequence.incrementAndGet();
            pending = new Pending<>(id, label, job, onSuccess, onFailure);
            if (activeThread != null) activeThread.interrupt();
            if (scheduled.compareAndSet(false, true)) {
                worker.execute(this::drain);
            }
        }
    }

    /** Invalidates queued work and callbacks without blocking the calling thread. */
    public void cancel() {
        synchronized (lock) {
            sequence.incrementAndGet();
            pending = null;
            if (activeThread != null) activeThread.interrupt();
        }
    }

    /** The number of requests submitted so far; mostly useful for diagnostics and tests. */
    public long submittedCount() {
        return sequence.get();
    }

    private void drain() {
        while (true) {
            Pending<?> request;
            synchronized (lock) {
                request = pending;
                pending = null;
            }
            if (request == null) {
                scheduled.set(false);
                // Re-check: a request may have arrived between reading and clearing the flag.
                synchronized (lock) {
                    if (pending != null && scheduled.compareAndSet(false, true)) {
                        continue;
                    }
                }
                return;
            }
            run(request);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void run(Pending<T> request) {
        T result;
        synchronized (lock) {
            if (!isCurrent(request)) return;
            activeThread = Thread.currentThread();
        }
        try {
            result = request.job().run();
        } catch (Exception e) {
            if (isCurrent(request)) {
                deliveryExecutor.execute(() -> {
                    if (isCurrent(request)) request.onFailure().accept(e);
                });
            }
            return;
        } finally {
            synchronized (lock) {
                activeThread = null;
                Thread.interrupted();
            }
        }
        if (isCurrent(request)) {
            deliveryExecutor.execute(() -> {
                if (isCurrent(request)) request.onSuccess().accept(result);
            });
        }
    }

    /** True when no newer request has been submitted since {@code request}. */
    private boolean isCurrent(Pending<?> request) {
        return !closed.get() && sequence.get() == request.id();
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed.compareAndSet(false, true)) {
                pending = null;
                worker.shutdownNow();
            }
        }
    }

    /** For diagnostics: waits for the worker to become idle. */
    public void awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            synchronized (lock) {
                if (pending == null && !scheduled.get()) {
                    return;
                }
            }
            Thread.sleep(5);
        }
    }

    private record Pending<T>(long id, String label, Job<T> job, Consumer<T> onSuccess,
            Consumer<Throwable> onFailure) {
    }
}
