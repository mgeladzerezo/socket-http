package io.github.mgeladzerezo.sockethttp.concurrent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * A bounded thread pool written from the primitives up: one lock, three conditions, a ring
 * buffer and a set of worker threads. It does not use {@code ThreadPoolExecutor} or any
 * {@code BlockingQueue}.
 *
 * <h2>Admission</h2>
 * A submitted task is, in order of preference,
 * <ol>
 *   <li>queued for an idle worker, if one is waiting that no queued task has already claimed;</li>
 *   <li>given to a <em>new</em> worker as its first task, while fewer than {@code maxThreads} exist;</li>
 *   <li>queued, while fewer than {@code queueCapacity} tasks are waiting without a worker;</li>
 *   <li>handled by the {@link RejectionPolicy}.</li>
 * </ol>
 * Step 2 before step 3 is deliberate and differs from {@code ThreadPoolExecutor}, which
 * queues first and only grows past its core size once the queue is full. For a server that
 * ordering means latency climbs while idle capacity sits unused; here the queue only fills
 * when every permitted thread is already busy.
 *
 * <h2>Why one lock</h2>
 * The queue, the worker count, the idle count and the lifecycle state are all guarded by the
 * same {@link ReentrantLock}. Every decision that spans more than one of them ("is there an
 * idle worker <em>and</em> no task already destined for it", "is the queue empty <em>and</em>
 * are we shutting down") is therefore a plain read under the lock, and the invariants below
 * can be checked by inspection instead of by reasoning about interleavings of atomics. Tasks
 * run outside the lock, so it is held for nanoseconds; under the workloads this server sees
 * (tasks that do socket I/O) it is not the bottleneck.
 *
 * <h2>Invariants (hold whenever the lock is free)</h2>
 * <ul>
 *   <li>{@code 0 <= idle <= workers <= maxThreads}</li>
 *   <li>{@code count - idle <= queueCapacity}: tasks beyond those idle workers are about to
 *       take never exceed the configured capacity</li>
 *   <li>an accepted task is run exactly once, or returned by {@link #shutdownNow()}</li>
 *   <li>after {@link #shutdown()} no task is accepted, and queued tasks still run</li>
 * </ul>
 */
public final class BoundedThreadPool implements Executor, AutoCloseable {

    private enum State { RUNNING, SHUTDOWN, STOP, TERMINATED }

    private final int coreThreads;
    private final int maxThreads;
    private final int queueCapacity;
    private final long keepAliveNanos;
    private final RejectionPolicy policy;
    private final ThreadFactory threadFactory;

    private final ReentrantLock lock = new ReentrantLock();
    /** Signalled when a task is queued or the state changes; workers wait here. */
    private final Condition notEmpty = lock.newCondition();
    /** Signalled when a slot may have freed or the state changes; BLOCK submitters wait here. */
    private final Condition notFull = lock.newCondition();
    /** Signalled once, when the last worker exits after a shutdown. */
    private final Condition termination = lock.newCondition();

    // Everything below is guarded by lock.
    private final Runnable[] queue;
    private int head;
    private int count;
    private final Set<Worker> workerSet = new HashSet<>();
    private int workers;
    private int idle;
    private State state = State.RUNNING;
    private int largestPoolSize;
    private long completedTasks;
    private long rejectedTasks;

    /**
     * @param coreThreads   workers kept alive when idle; they are still created lazily
     * @param maxThreads    upper bound on workers
     * @param queueCapacity tasks that may wait when every worker is busy; 0 for none
     * @param keepAlive     how long a worker above {@code coreThreads} waits for work before it exits
     * @param policy        what to do when saturated
     * @param threadFactory creates worker threads
     */
    public BoundedThreadPool(int coreThreads, int maxThreads, int queueCapacity, Duration keepAlive,
                             RejectionPolicy policy, ThreadFactory threadFactory) {
        if (coreThreads < 0 || maxThreads < 1 || coreThreads > maxThreads || queueCapacity < 0) {
            throw new IllegalArgumentException("need 0 <= coreThreads <= maxThreads, maxThreads >= 1, queueCapacity >= 0");
        }
        this.coreThreads = coreThreads;
        this.maxThreads = maxThreads;
        this.queueCapacity = queueCapacity;
        this.keepAliveNanos = Objects.requireNonNull(keepAlive, "keepAlive").toNanos();
        this.policy = Objects.requireNonNull(policy, "policy");
        this.threadFactory = Objects.requireNonNull(threadFactory, "threadFactory");
        // Up to maxThreads queued tasks can be "in transit" to idle workers on top of the
        // tasks that are really waiting, so the ring needs room for both.
        this.queue = new Runnable[queueCapacity + maxThreads];
    }

    /** A fixed-size pool of daemon threads named {@code name-N}. */
    public static BoundedThreadPool fixed(String name, int threads, int queueCapacity, RejectionPolicy policy) {
        return new BoundedThreadPool(threads, threads, queueCapacity, Duration.ofSeconds(60), policy, namedDaemonFactory(name));
    }

    /** Daemon threads named {@code name-1}, {@code name-2}, ... */
    public static ThreadFactory namedDaemonFactory(String name) {
        return Thread.ofPlatform().name(name + "-", 1).daemon(true).factory();
    }

    /**
     * Submits a task.
     *
     * @throws RejectedExecutionException if the pool has been shut down, or it is saturated
     *                                    and the policy is {@link RejectionPolicy#ABORT}, or
     *                                    the caller was interrupted while blocked under
     *                                    {@link RejectionPolicy#BLOCK}
     */
    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        lock.lock();
        try {
            while (true) {
                if (state != State.RUNNING) {
                    rejectedTasks++;
                    throw new RejectedExecutionException("pool is shut down");
                }
                if (tryAdmit(task)) {
                    return;
                }
                switch (policy) {
                    case ABORT -> {
                        rejectedTasks++;
                        throw new RejectedExecutionException(
                                "saturated: " + workers + " workers busy, " + (count - idle) + " tasks queued");
                    }
                    case CALLER_RUNS -> {
                        break;
                    }
                    case BLOCK -> {
                        try {
                            notFull.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            rejectedTasks++;
                            throw new RejectedExecutionException("interrupted while waiting for a free slot", e);
                        }
                        continue;
                    }
                }
                break;
            }
        } finally {
            lock.unlock();
        }
        // Only CALLER_RUNS reaches this point; the task runs without the lock held.
        task.run();
    }

    /**
     * Submits a task without ever blocking or running it on the caller, whatever the
     * configured policy.
     *
     * @return {@code false} if the pool is saturated or shut down
     */
    public boolean tryExecute(Runnable task) {
        Objects.requireNonNull(task, "task");
        lock.lock();
        try {
            if (state == State.RUNNING && tryAdmit(task)) {
                return true;
            }
            rejectedTasks++;
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Steps 1 to 3 of the admission order. Caller holds the lock. */
    private boolean tryAdmit(Runnable task) {
        if (count < idle) {
            enqueue(task);
            notEmpty.signal();
            return true;
        }
        if (workers < maxThreads) {
            startWorker(task);
            return true;
        }
        if (count - idle < queueCapacity) {
            enqueue(task);
            return true;
        }
        return false;
    }

    private void startWorker(Runnable firstTask) {
        Worker worker = new Worker(firstTask);
        Thread thread = threadFactory.newThread(worker);
        if (thread == null) {
            throw new RejectedExecutionException("thread factory returned null");
        }
        worker.thread = thread;
        workerSet.add(worker);
        workers++;
        largestPoolSize = Math.max(largestPoolSize, workers);
        try {
            thread.start();
        } catch (Throwable t) {
            // Could not start (out of native threads, for instance): undo the bookkeeping so
            // the pool does not believe in a worker that will never take a task.
            workerSet.remove(worker);
            workers--;
            throw new RejectedExecutionException("could not start worker thread", t);
        }
    }

    private void enqueue(Runnable task) {
        queue[(head + count) % queue.length] = task;
        count++;
    }

    private Runnable dequeue() {
        Runnable task = queue[head];
        queue[head] = null;
        head = (head + 1) % queue.length;
        count--;
        return task;
    }

    /**
     * Blocks until a task is available, or returns {@code null} to tell the worker to exit:
     * after {@code shutdownNow}, after {@code shutdown} once the queue is empty, or when the
     * worker is above the core size and has been idle for the keep-alive time. The worker
     * count is decremented here, under the same lock hold as the decision, so two surplus
     * workers cannot both conclude "I may leave" and take the pool below its core size.
     */
    private Runnable awaitTask(Worker self) {
        boolean timedOut = false;
        lock.lock();
        try {
            while (true) {
                if (state == State.STOP) {
                    return retire(self);
                }
                if (count > 0) {
                    Runnable task = dequeue();
                    notFull.signal();
                    return task;
                }
                if (state == State.SHUTDOWN) {
                    return retire(self);
                }
                if (timedOut && workers > coreThreads) {
                    return retire(self);
                }
                idle++;
                try {
                    if (workers > coreThreads) {
                        timedOut = notEmpty.awaitNanos(keepAliveNanos) <= 0;
                    } else {
                        notEmpty.await();
                        timedOut = false;
                    }
                } catch (InterruptedException e) {
                    // Only shutdownNow interrupts idle workers; the loop re-reads the state.
                } finally {
                    idle--;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** Removes the worker from the books. Caller holds the lock. Always returns {@code null}. */
    private Runnable retire(Worker self) {
        workerSet.remove(self);
        workers--;
        // A departing worker changes what tryAdmit would answer, so let blocked submitters re-check.
        notFull.signalAll();
        if (workers == 0 && state != State.RUNNING) {
            state = State.TERMINATED;
            termination.signalAll();
        }
        return null;
    }

    /**
     * Stops accepting tasks. Tasks already queued still run; workers exit once the queue is
     * empty. Returns immediately; use {@link #awaitTermination} to wait.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (state == State.RUNNING) {
                state = State.SHUTDOWN;
                wakeEveryoneForStateChange();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops accepting tasks, interrupts every worker and removes the tasks that had not
     * started.
     *
     * @return the tasks that were queued and will never run, in submission order
     */
    public List<Runnable> shutdownNow() {
        lock.lock();
        try {
            List<Runnable> unstarted = new ArrayList<>(count);
            while (count > 0) {
                unstarted.add(dequeue());
            }
            if (state == State.RUNNING || state == State.SHUTDOWN) {
                state = State.STOP;
                for (Worker worker : workerSet) {
                    worker.thread.interrupt();
                }
                wakeEveryoneForStateChange();
            }
            return unstarted;
        } finally {
            lock.unlock();
        }
    }

    private void wakeEveryoneForStateChange() {
        notEmpty.signalAll();
        notFull.signalAll();
        if (workers == 0) {
            state = State.TERMINATED;
            termination.signalAll();
        }
    }

    /** Waits until every worker has exited after a shutdown. */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lock();
        try {
            while (state != State.TERMINATED) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = termination.awaitNanos(nanos);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** {@link #shutdown()}, then waits for queued tasks to finish. */
    @Override
    public void close() {
        shutdown();
        boolean interrupted = false;
        while (true) {
            try {
                if (awaitTermination(1, TimeUnit.DAYS)) {
                    break;
                }
            } catch (InterruptedException e) {
                interrupted = true;
                shutdownNow();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isShutdown() {
        return read(() -> state != State.RUNNING ? 1L : 0L) == 1L;
    }

    public boolean isTerminated() {
        return read(() -> state == State.TERMINATED ? 1L : 0L) == 1L;
    }

    /** Worker threads currently alive. */
    public int poolSize() {
        return (int) read(() -> workers);
    }

    /** Workers currently running a task. */
    public int activeCount() {
        return (int) read(() -> workers - idle);
    }

    /** Workers currently waiting for a task. */
    public int idleCount() {
        return (int) read(() -> idle);
    }

    /** Tasks accepted but not yet taken by a worker. */
    public int queueSize() {
        return (int) read(() -> count);
    }

    /** The most workers that ever existed at once. */
    public int largestPoolSize() {
        return (int) read(() -> largestPoolSize);
    }

    /** Tasks that finished, normally or by throwing. */
    public long completedTaskCount() {
        return read(() -> completedTasks);
    }

    /** Submissions refused because the pool was saturated or shut down. */
    public long rejectedTaskCount() {
        return read(() -> rejectedTasks);
    }

    public int maxThreads() {
        return maxThreads;
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    private long read(LongSupplier value) {
        lock.lock();
        try {
            return value.getAsLong();
        } finally {
            lock.unlock();
        }
    }

    private void taskFinished() {
        lock.lock();
        try {
            completedTasks++;
        } finally {
            lock.unlock();
        }
    }

    /** The loop each worker thread runs: first task, then whatever {@link #awaitTask} hands out. */
    private final class Worker implements Runnable {

        private Runnable firstTask;
        private Thread thread;

        Worker(Runnable firstTask) {
            this.firstTask = firstTask;
        }

        @Override
        public void run() {
            Runnable task = firstTask;
            firstTask = null;
            while (task != null || (task = awaitTask(this)) != null) {
                try {
                    task.run();
                } catch (Throwable t) {
                    // A failing task must not take a worker (and its share of capacity) with
                    // it. Report it the way an uncaught exception would be reported and go on.
                    Thread current = Thread.currentThread();
                    current.getUncaughtExceptionHandler().uncaughtException(current, t);
                } finally {
                    task = null;
                    taskFinished();
                    // An interrupt aimed at the finished task must not leak into the next one.
                    Thread.interrupted();
                }
            }
        }
    }
}
