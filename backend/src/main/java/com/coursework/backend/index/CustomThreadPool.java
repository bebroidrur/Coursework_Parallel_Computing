package com.coursework.backend.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small fixed-size executor used by the index builder. It owns its worker
 * threads and queue; it does not delegate execution to another executor.
 */
public final class CustomThreadPool extends AbstractExecutorService {
    private enum State { RUNNING, SHUTDOWN, STOP, TERMINATED }
    private static final Runnable POISON = () -> { };

    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final List<Thread> workers = new CopyOnWriteArrayList<>();
    private final CountDownLatch termination;
    private final Object lifecycle = new Object();
    private final AtomicLong completedTasks = new AtomicLong();
    private volatile State state = State.RUNNING;

    public CustomThreadPool(int threadCount) {
        if (threadCount < 1) throw new IllegalArgumentException("threadCount must be positive");
        termination = new CountDownLatch(threadCount);
        for (int i = 0; i < threadCount; i++) {
            Thread worker = new Thread(this::runWorker, "custom-index-worker-" + (i + 1));
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
    }

    @Override public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        synchronized (lifecycle) {
            if (state != State.RUNNING) throw new java.util.concurrent.RejectedExecutionException("pool is shut down");
            queue.add(command);
        }
    }

    private void runWorker() {
        try {
            while (true) {
                Runnable task;
                try {
                    task = queue.take();
                } catch (InterruptedException interrupted) {
                    if (state == State.STOP) return;
                    continue; // shutdown() does not interrupt active/idle workers
                }
                if (task == POISON) {
                    if (state != State.RUNNING) return;
                    continue;
                }
                try { task.run(); }
                catch (Throwable ignored) { /* a failed task must not kill the worker */ }
                finally { completedTasks.incrementAndGet(); }
                if (state == State.STOP) return;
            }
        } finally {
            termination.countDown();
            if (termination.getCount() == 0) state = State.TERMINATED;
        }
    }

    @Override public void shutdown() {
        synchronized (lifecycle) {
            if (state != State.RUNNING) return;
            state = State.SHUTDOWN;
            for (int i = 0; i < workers.size(); i++) queue.add(POISON);
        }
    }

    @Override public List<Runnable> shutdownNow() {
        List<Runnable> notStarted = new ArrayList<>();
        synchronized (lifecycle) {
            if (state == State.TERMINATED) return notStarted;
            state = State.STOP;
            queue.drainTo(notStarted);
            for (Thread worker : workers) worker.interrupt();
        }
        return notStarted;
    }

    @Override public boolean isShutdown() { return state != State.RUNNING; }
    @Override public boolean isTerminated() { return state == State.TERMINATED; }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        return termination.await(timeout, unit);
    }

    public long getCompletedTaskCount() { return completedTasks.get(); }
    public int getWorkerCount() { return workers.size(); }
}
