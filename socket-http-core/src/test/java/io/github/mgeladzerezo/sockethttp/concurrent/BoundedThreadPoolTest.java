package io.github.mgeladzerezo.sockethttp.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The hand-written pool on its own, without a server around it: admission order, each
 * rejection policy, lifecycle, and the exactly-once guarantee under contention.
 */
@Timeout(60)
class BoundedThreadPoolTest {

    private BoundedThreadPool pool;

    @AfterEach
    void stopPool() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private BoundedThreadPool pool(int core, int max, int queue, RejectionPolicy policy) {
        pool = new BoundedThreadPool(core, max, queue, Duration.ofSeconds(30), policy,
                BoundedThreadPool.namedDaemonFactory("test-pool"));
        return pool;
    }

    // ------------------------------------------------------------ contention

    @Test
    void runsEveryTaskExactlyOnceUnderContention() throws Exception {
        int producers = 8;
        int perProducer = 25_000;
        BoundedThreadPool p = pool(2, 6, 16, RejectionPolicy.BLOCK);
        AtomicIntegerArray runs = new AtomicIntegerArray(producers * perProducer);
        CyclicBarrier start = new CyclicBarrier(producers);

        List<Thread> threads = new ArrayList<>();
        for (int producer = 0; producer < producers; producer++) {
            int base = producer * perProducer;
            threads.add(Thread.ofPlatform().start(() -> {
                await(start);
                for (int i = 0; i < perProducer; i++) {
                    int slot = base + i;
                    p.execute(() -> runs.incrementAndGet(slot));
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        p.shutdown();
        assertThat(p.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (int i = 0; i < runs.length(); i++) {
            if (runs.get(i) != 1) {
                throw new AssertionError("task " + i + " ran " + runs.get(i) + " times");
            }
        }
        assertThat(p.completedTaskCount()).isEqualTo((long) producers * perProducer);
        assertThat(p.largestPoolSize()).isLessThanOrEqualTo(6);
        assertThat(p.rejectedTaskCount()).isZero();
    }

    @Test
    void neverRunsMoreTasksAtOnceThanMaxThreads() throws Exception {
        int max = 4;
        BoundedThreadPool p = pool(1, max, 64, RejectionPolicy.BLOCK);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        for (int i = 0; i < 2_000; i++) {
            p.execute(() -> {
                int now = running.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                Thread.onSpinWait();
                running.decrementAndGet();
            });
        }
        p.shutdown();
        assertThat(p.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(peak.get()).isBetween(1, max);
        assertThat(p.largestPoolSize()).isLessThanOrEqualTo(max);
    }

    @Test
    void underAbortPolicyEveryTaskIsEitherRunOrRejectedNeverLost() throws Exception {
        int producers = 6;
        int perProducer = 20_000;
        BoundedThreadPool p = pool(2, 3, 8, RejectionPolicy.ABORT);
        AtomicLong accepted = new AtomicLong();
        AtomicLong rejected = new AtomicLong();
        AtomicLong executed = new AtomicLong();

        List<Thread> threads = new ArrayList<>();
        for (int producer = 0; producer < producers; producer++) {
            threads.add(Thread.ofPlatform().start(() -> {
                for (int i = 0; i < perProducer; i++) {
                    try {
                        p.execute(executed::incrementAndGet);
                        accepted.incrementAndGet();
                    } catch (RejectedExecutionException e) {
                        rejected.incrementAndGet();
                    }
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        p.shutdown();
        assertThat(p.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(accepted.get() + rejected.get()).isEqualTo((long) producers * perProducer);
        assertThat(executed.get()).isEqualTo(accepted.get());
        assertThat(p.rejectedTaskCount()).isEqualTo(rejected.get());
    }

    @Test
    void shutdownRacingWithSubmittersLosesNoAcceptedTask() throws Exception {
        for (int round = 0; round < 20; round++) {
            BoundedThreadPool p = new BoundedThreadPool(1, 4, 32, Duration.ofSeconds(30), RejectionPolicy.BLOCK,
                    BoundedThreadPool.namedDaemonFactory("race-pool"));
            AtomicLong accepted = new AtomicLong();
            AtomicLong executed = new AtomicLong();
            CountDownLatch warmedUp = new CountDownLatch(200);

            List<Thread> threads = new ArrayList<>();
            for (int producer = 0; producer < 4; producer++) {
                threads.add(Thread.ofPlatform().start(() -> {
                    while (true) {
                        try {
                            p.execute(executed::incrementAndGet);
                            accepted.incrementAndGet();
                            warmedUp.countDown();
                        } catch (RejectedExecutionException e) {
                            return;
                        }
                    }
                }));
            }
            warmedUp.await();
            p.shutdown();
            for (Thread thread : threads) {
                thread.join();
            }
            assertThat(p.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            assertThat(executed.get()).as("round %d", round).isEqualTo(accepted.get());
            assertThat(p.poolSize()).isZero();
        }
    }

    // -------------------------------------------------------------- admission

    @Test
    void startsThreadsUpToMaxBeforeQueueingAnything() throws Exception {
        BoundedThreadPool p = pool(1, 3, 5, RejectionPolicy.ABORT);
        CountDownLatch release = new CountDownLatch(1);

        for (int i = 0; i < 3; i++) {
            p.execute(() -> awaitQuietly(release));
        }
        assertThat(p.poolSize()).isEqualTo(3);
        assertThat(p.queueSize()).isZero();

        p.execute(() -> awaitQuietly(release));
        assertThat(p.poolSize()).isEqualTo(3);
        assertThat(p.queueSize()).isEqualTo(1);
        release.countDown();
    }

    @Test
    void reusesAnIdleWorkerInsteadOfStartingAnotherThread() throws Exception {
        BoundedThreadPool p = pool(1, 8, 8, RejectionPolicy.ABORT);

        for (int i = 0; i < 50; i++) {
            CountDownLatch done = new CountDownLatch(1);
            p.execute(done::countDown);
            done.await();
            waitUntil(() -> p.idleCount() == 1);
        }

        assertThat(p.largestPoolSize()).isEqualTo(1);
        assertThat(p.completedTaskCount()).isEqualTo(50);
    }

    @Test
    void workersAreCreatedLazily() {
        BoundedThreadPool p = pool(4, 8, 8, RejectionPolicy.ABORT);

        assertThat(p.poolSize()).isZero();
    }

    // -------------------------------------------------------------- rejection

    @Test
    void abortPolicyAcceptsExactlyMaxThreadsPlusQueueCapacity() throws Exception {
        BoundedThreadPool p = pool(2, 2, 3, RejectionPolicy.ABORT);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executed = new AtomicInteger();
        Runnable blocking = () -> {
            awaitQuietly(release);
            executed.incrementAndGet();
        };

        for (int i = 0; i < 5; i++) {
            p.execute(blocking);
        }
        assertThatThrownBy(() -> p.execute(blocking)).isInstanceOf(RejectedExecutionException.class)
                .hasMessageContaining("saturated");
        assertThat(p.tryExecute(blocking)).isFalse();
        assertThat(p.rejectedTaskCount()).isEqualTo(2);
        assertThat(p.queueSize()).isEqualTo(3);

        release.countDown();
        p.shutdown();
        assertThat(p.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(executed.get()).isEqualTo(5);
    }

    @Test
    void zeroCapacityQueueHandsOffDirectlyOrRejects() throws Exception {
        BoundedThreadPool p = pool(1, 1, 0, RejectionPolicy.ABORT);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        p.execute(() -> awaitQuietly(release));
        assertThatThrownBy(() -> p.execute(() -> { })).isInstanceOf(RejectedExecutionException.class);

        release.countDown();
        waitUntil(() -> p.idleCount() == 1);
        // With no queue, a task is still accepted when a worker is idle and waiting for it.
        p.execute(done::countDown);
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void callerRunsPolicyRunsTheTaskOnTheSubmittingThread() throws Exception {
        BoundedThreadPool p = pool(1, 1, 0, RejectionPolicy.CALLER_RUNS);
        CountDownLatch release = new CountDownLatch(1);
        p.execute(() -> awaitQuietly(release));

        Thread[] ranOn = new Thread[1];
        p.execute(() -> ranOn[0] = Thread.currentThread());

        assertThat(ranOn[0]).isSameAs(Thread.currentThread());
        release.countDown();
    }

    @Test
    void blockPolicyHoldsTheSubmitterUntilASlotFrees() throws Exception {
        BoundedThreadPool p = pool(1, 1, 1, RejectionPolicy.BLOCK);
        CountDownLatch release = new CountDownLatch(1);
        p.execute(() -> awaitQuietly(release));
        p.execute(() -> { });

        AtomicBoolean submitted = new AtomicBoolean();
        Thread submitter = Thread.ofPlatform().start(() -> {
            p.execute(() -> { });
            submitted.set(true);
        });
        waitUntil(() -> submitter.getState() == Thread.State.WAITING);
        assertThat(submitted).isFalse();

        release.countDown();
        submitter.join(10_000);
        assertThat(submitted).isTrue();
    }

    @Test
    void aSubmitterBlockedOnAFullPoolIsRejectedWhenThePoolShutsDown() throws Exception {
        BoundedThreadPool p = pool(1, 1, 0, RejectionPolicy.BLOCK);
        CountDownLatch release = new CountDownLatch(1);
        p.execute(() -> awaitQuietly(release));

        List<Throwable> failure = new CopyOnWriteArrayList<>();
        Thread submitter = Thread.ofPlatform().start(() -> {
            try {
                p.execute(() -> { });
            } catch (RejectedExecutionException e) {
                failure.add(e);
            }
        });
        waitUntil(() -> submitter.getState() == Thread.State.WAITING);
        p.shutdown();
        submitter.join(10_000);

        assertThat(failure).hasSize(1);
        release.countDown();
    }

    @Test
    void anInterruptedBlockedSubmitterGetsARejectionAndKeepsItsInterruptFlag() throws Exception {
        BoundedThreadPool p = pool(1, 1, 0, RejectionPolicy.BLOCK);
        CountDownLatch release = new CountDownLatch(1);
        p.execute(() -> awaitQuietly(release));

        AtomicBoolean rejectedWithFlag = new AtomicBoolean();
        Thread submitter = Thread.ofPlatform().start(() -> {
            try {
                p.execute(() -> { });
            } catch (RejectedExecutionException e) {
                rejectedWithFlag.set(Thread.currentThread().isInterrupted());
            }
        });
        waitUntil(() -> submitter.getState() == Thread.State.WAITING);
        submitter.interrupt();
        submitter.join(10_000);

        assertThat(rejectedWithFlag).isTrue();
        release.countDown();
    }

    // -------------------------------------------------------------- lifecycle

    @Test
    void shutdownRunsQueuedTasksThenTerminates() throws Exception {
        BoundedThreadPool p = pool(1, 1, 10, RejectionPolicy.ABORT);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executed = new AtomicInteger();
        p.execute(() -> awaitQuietly(release));
        for (int i = 0; i < 10; i++) {
            p.execute(executed::incrementAndGet);
        }

        p.shutdown();
        assertThat(p.isShutdown()).isTrue();
        assertThat(p.isTerminated()).isFalse();
        assertThatThrownBy(() -> p.execute(() -> { })).isInstanceOf(RejectedExecutionException.class)
                .hasMessageContaining("shut down");

        release.countDown();
        assertThat(p.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(executed.get()).isEqualTo(10);
        assertThat(p.isTerminated()).isTrue();
        assertThat(p.poolSize()).isZero();
    }

    @Test
    void shutdownNowInterruptsRunningTasksAndReturnsTheQueuedOnes() throws Exception {
        BoundedThreadPool p = pool(1, 1, 10, RejectionPolicy.ABORT);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        p.execute(() -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        AtomicInteger executed = new AtomicInteger();
        Runnable queued = executed::incrementAndGet;
        for (int i = 0; i < 4; i++) {
            p.execute(queued);
        }
        started.await();

        List<Runnable> unstarted = p.shutdownNow();

        assertThat(unstarted).hasSize(4).containsOnly(queued);
        assertThat(p.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isTrue();
        assertThat(executed.get()).isZero();
    }

    @Test
    void anIdlePoolTerminatesImmediatelyOnShutdown() throws Exception {
        BoundedThreadPool p = pool(2, 2, 2, RejectionPolicy.ABORT);
        CountDownLatch done = new CountDownLatch(2);
        p.execute(done::countDown);
        p.execute(done::countDown);
        done.await();

        p.shutdown();

        assertThat(p.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(p.awaitTermination(0, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void awaitTerminationTimesOutWhileATaskIsStillRunning() throws Exception {
        BoundedThreadPool p = pool(1, 1, 1, RejectionPolicy.ABORT);
        CountDownLatch release = new CountDownLatch(1);
        p.execute(() -> awaitQuietly(release));
        p.shutdown();

        assertThat(p.awaitTermination(100, TimeUnit.MILLISECONDS)).isFalse();
        release.countDown();
        assertThat(p.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aThrowingTaskDoesNotKillItsWorker() throws Exception {
        List<Throwable> reported = new CopyOnWriteArrayList<>();
        pool = new BoundedThreadPool(1, 1, 100, Duration.ofSeconds(30), RejectionPolicy.ABORT,
                Thread.ofPlatform().name("throwing-", 1).daemon(true)
                        .uncaughtExceptionHandler((t, e) -> reported.add(e)).factory());
        AtomicInteger executed = new AtomicInteger();

        for (int i = 0; i < 20; i++) {
            pool.execute(() -> {
                throw new IllegalStateException("boom");
            });
            pool.execute(executed::incrementAndGet);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(executed.get()).isEqualTo(20);
        assertThat(reported).hasSize(20);
        assertThat(pool.largestPoolSize()).as("the same worker survived every failure").isEqualTo(1);
        assertThat(pool.completedTaskCount()).isEqualTo(40);
    }

    @Test
    void workersAboveCoreSizeRetireAfterTheKeepAliveTime() throws Exception {
        pool = new BoundedThreadPool(1, 4, 0, Duration.ofMillis(100), RejectionPolicy.ABORT,
                BoundedThreadPool.namedDaemonFactory("retiring"));
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) {
            pool.execute(() -> awaitQuietly(release));
        }
        assertThat(pool.poolSize()).isEqualTo(4);

        release.countDown();
        waitUntil(() -> pool.poolSize() == 1);
        Thread.sleep(300);

        assertThat(pool.poolSize()).as("core workers stay").isEqualTo(1);
        CountDownLatch done = new CountDownLatch(1);
        pool.execute(done::countDown);
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void anInterruptLeftByOneTaskDoesNotLeakIntoTheNext() throws Exception {
        BoundedThreadPool p = pool(1, 1, 4, RejectionPolicy.ABORT);
        AtomicBoolean secondSawInterrupt = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        p.execute(() -> Thread.currentThread().interrupt());
        p.execute(() -> {
            secondSawInterrupt.set(Thread.currentThread().isInterrupted());
            done.countDown();
        });

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(secondSawInterrupt).isFalse();
    }

    @Test
    void rejectsNonsensicalConfiguration() {
        assertThatThrownBy(() -> new BoundedThreadPool(2, 1, 0, Duration.ZERO, RejectionPolicy.ABORT, Thread::new))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedThreadPool(0, 0, 0, Duration.ZERO, RejectionPolicy.ABORT, Thread::new))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedThreadPool(1, 1, -1, Duration.ZERO, RejectionPolicy.ABORT, Thread::new))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- helpers

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 10 s");
            }
            Thread.sleep(2);
        }
    }
}
