package com.coursework.backend.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class CustomThreadPoolTests {
    @Test void executesSubmittedTasksOnConfiguredWorkers() throws Exception {
        CustomThreadPool pool = new CustomThreadPool(4);
        AtomicInteger count = new AtomicInteger();
        for (int i = 0; i < 40; i++) pool.execute(count::incrementAndGet);
        pool.shutdown();
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        assertEquals(40, count.get());
        assertEquals(4, pool.getWorkerCount());
        assertTrue(pool.getCompletedTaskCount() >= 40);
        assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> {}));
    }

    @Test void taskFailureDoesNotKillOtherWorkers() throws Exception {
        CustomThreadPool pool = new CustomThreadPool(2);
        AtomicInteger count = new AtomicInteger();
        pool.execute(() -> { throw new IllegalStateException("expected"); });
        pool.execute(count::incrementAndGet);
        pool.shutdown();
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        assertEquals(1, count.get());
        assertTrue(pool.isTerminated());
    }

    @Test void shutdownNowCancelsQueuedTasksAndInterruptsRunningTask() throws Exception {
        CustomThreadPool pool = new CustomThreadPool(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pool.execute(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        pool.execute(() -> { throw new AssertionError("must not start"); });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        List<Runnable> pending = pool.shutdownNow();
        release.countDown();
        assertEquals(1, pending.size());
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        assertTrue(pool.isTerminated());
    }

    @Test void rejectsInvalidWorkerCount() {
        assertThrows(IllegalArgumentException.class, () -> new CustomThreadPool(0));
        assertThrows(IllegalArgumentException.class, () -> new CustomThreadPool(-2));
    }
}
