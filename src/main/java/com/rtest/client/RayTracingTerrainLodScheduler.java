package com.rtest.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * A Minecraft-independent, bounded scheduler for asynchronous terrain LOD work.
 * The generator is called only by worker threads; callers must do all Minecraft and
 * Vulkan interaction when consuming the values returned by {@link #poll(int)}.
 */
public final class RayTracingTerrainLodScheduler<T> implements AutoCloseable {
    private static final AtomicLong NEXT_TOKEN = new AtomicLong();

    public record NodeKey(long nodeId) { }

    /** Immutable cancellation identity. Cancellation state is owned by the scheduler. */
    public record Token(long id) {
        public Token {
            if (id < 0) {
                throw new IllegalArgumentException("token id must be non-negative");
            }
        }
    }

    public record Request(
            NodeKey nodeKey,
            long worldGeneration,
            long windowGeneration,
            long nodeGeneration,
            long sourceFingerprint,
            int priority,
            double cameraDistance,
            Token token) {
        public Request {
            Objects.requireNonNull(nodeKey, "nodeKey");
            Objects.requireNonNull(token, "token");
            if (!Double.isFinite(cameraDistance) || cameraDistance < 0.0) {
                throw new IllegalArgumentException("cameraDistance must be finite and non-negative");
            }
        }

        public static Request of(NodeKey nodeKey, long worldGeneration, long windowGeneration,
                                 long nodeGeneration, long sourceFingerprint, int priority,
                                 double cameraDistance) {
            return new Request(nodeKey, worldGeneration, windowGeneration, nodeGeneration,
                    sourceFingerprint, priority, cameraDistance,
                    new Token(NEXT_TOKEN.getAndIncrement()));
        }
    }

    public record NodeVersion(long nodeGeneration, long sourceFingerprint) { }

    /** Worker failure retained until the render thread polls it; failures are no longer silent. */
    public record Failure(Request request, Throwable cause) {
        public Failure {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(cause, "cause");
        }
    }

    /** Immutable value delivered to the polling thread. */
    public record Result<T>(Request request, T value) {
        public Result {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(value, "value");
        }
    }

    private final Object lock = new Object();
    private final int capacity;
    private final Function<Request, T> generator;
    private final ThreadPoolExecutor executor;
    private final Map<NodeKey, Job> current = new HashMap<>();
    private final ArrayList<Completed<T>> ready = new ArrayList<>();
    private final ArrayList<Failure> failures = new ArrayList<>();
    private boolean closed;

    public RayTracingTerrainLodScheduler(int workerCount, int maxQueuedRequests,
                                         Function<Request, T> generator) {
        if (workerCount <= 0 || maxQueuedRequests <= 0) {
            throw new IllegalArgumentException("workerCount and maxQueuedRequests must be positive");
        }
        this.capacity = maxQueuedRequests;
        this.generator = Objects.requireNonNull(generator, "generator");
        this.executor = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>());
        this.executor.prestartAllCoreThreads();
    }

    /** Alternate constructor for dependency-injection styles that put the generator first. */
    public RayTracingTerrainLodScheduler(Function<Request, T> generator, int workerCount,
                                         int maxQueuedRequests) {
        this(workerCount, maxQueuedRequests, generator);
    }

    /** Creates a request with a fresh immutable token. */
    public Request request(NodeKey nodeKey, long worldGeneration, long windowGeneration,
                           long nodeGeneration, long sourceFingerprint, int priority,
                           double cameraDistance) {
        return Request.of(nodeKey, worldGeneration, windowGeneration, nodeGeneration,
                sourceFingerprint, priority, cameraDistance);
    }

    /**
     * Enqueues the newest request for a node. A request for an occupied node replaces
     * the older one without consuming another queue slot.
     */
    public boolean submit(Request request) {
        Objects.requireNonNull(request, "request");
        synchronized (lock) {
            if (closed || (current.size() >= capacity && !current.containsKey(request.nodeKey()))) {
                return false;
            }
            ready.removeIf(completed -> completed.request.nodeKey().equals(request.nodeKey()));
            Job old = current.get(request.nodeKey());
            if (old != null) {
                old.cancelled = true;
                if (old.work != null) {
                    executor.getQueue().remove(old.work);
                }
            }
            Job job = new Job(request);
            job.work = new Work(job);
            current.put(request.nodeKey(), job);
            executor.execute(job.work);
            return true;
        }
    }

    /** Convenience overload for callers that do not need to retain the request object. */
    public boolean submit(NodeKey nodeKey, long worldGeneration, long windowGeneration,
                          long nodeGeneration, long sourceFingerprint, int priority,
                          double cameraDistance) {
        return submit(request(nodeKey, worldGeneration, windowGeneration, nodeGeneration,
                sourceFingerprint, priority, cameraDistance));
    }

    /** Returns whether a worker may still publish work for this request. */
    public boolean isCurrent(Request request) {
        synchronized (lock) {
            Job job = current.get(request.nodeKey());
            return !closed && job != null && job.request.equals(request) && !job.cancelled;
        }
    }

    /** Cancels work for one node without disturbing independent hierarchy builds. */
    public void cancel(NodeKey nodeKey) {
        Objects.requireNonNull(nodeKey, "nodeKey");
        synchronized (lock) {
            Job job = current.remove(nodeKey);
            if (job == null) return;
            job.cancelled = true;
            if (job.work != null) executor.getQueue().remove(job.work);
            ready.removeIf(completed -> completed.request.nodeKey().equals(nodeKey));
        }
    }

    /** Polls only completed work that is still the current request for its node. */
    public List<Result<T>> poll(int resultBudget) {
        return pollInternal(resultBudget, null, null, null);
    }

    /**
     * Polls with a snapshot of the caller's world/window and node versions. Any result
     * whose snapshot differs is discarded, even if it completed successfully.
     */
    public List<Result<T>> poll(int resultBudget, long worldGeneration, long windowGeneration,
                                Map<NodeKey, NodeVersion> nodeVersions) {
        return pollInternal(resultBudget, worldGeneration, windowGeneration,
                Objects.requireNonNull(nodeVersions, "nodeVersions"));
    }

    private List<Result<T>> pollInternal(int budget, Long world, Long window, Map<NodeKey, NodeVersion> versions) {
        if (budget <= 0) {
            return List.of();
        }
        ArrayList<Result<T>> output = new ArrayList<>(Math.min(budget, 16));
        synchronized (lock) {
            while (output.size() < budget && !ready.isEmpty()) {
                Completed<T> completed = ready.remove(0);
                Job job = current.get(completed.request.nodeKey());
                if (job == null || job.request != completed.request || job.cancelled
                        || (world != null && !matches(completed.request, world, window, versions))) {
                    if (job != null && job.request == completed.request) {
                        current.remove(completed.request.nodeKey());
                    }
                    continue;
                }
                current.remove(completed.request.nodeKey());
                output.add(new Result<>(completed.request, completed.value));
            }
        }
        return List.copyOf(output);
    }

    private static boolean matches(Request request, long world, long window,
                                   Map<NodeKey, NodeVersion> versions) {
        NodeVersion version = versions.get(request.nodeKey());
        return request.worldGeneration() == world && request.windowGeneration() == window
                && version != null && request.nodeGeneration() == version.nodeGeneration()
                && request.sourceFingerprint() == version.sourceFingerprint();
    }

    /** Returns and clears worker failures for diagnostics on the polling thread. */
    public List<Failure> pollFailures(int budget) {
        if (budget <= 0) return List.of();
        synchronized (lock) {
            int count = Math.min(budget, failures.size());
            ArrayList<Failure> result = new ArrayList<>(failures.subList(0, count));
            failures.subList(0, count).clear();
            return List.copyOf(result);
        }
    }

    /** Cancels queued, running, and ready work. */
    public void cancelAll() {
        synchronized (lock) {
            for (Job job : current.values()) {
                job.cancelled = true;
            }
            current.clear();
            ready.clear();
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            for (Job job : current.values()) {
                job.cancelled = true;
            }
            current.clear();
            ready.clear();
            failures.clear();
        }
        executor.shutdownNow();
    }

    private final class Job {
        private final Request request;
        private volatile boolean cancelled;
        private volatile Work work;

        private Job(Request request) {
            this.request = request;
        }
    }

    private record Completed<T>(Request request, T value) { }

    private final class Work implements Runnable, Comparable<Work> {
        private final Job job;

        private Work(Job job) {
            this.job = job;
        }

        @Override
        public int compareTo(Work other) {
            int priority = Integer.compare(other.job.request.priority(), job.request.priority());
            return priority != 0 ? priority
                    : Double.compare(job.request.cameraDistance(), other.job.request.cameraDistance());
        }

        @Override
        public void run() {
            if (!isCurrent(job.request)) {
                return;
            }
            T value;
            try {
                value = Objects.requireNonNull(generator.apply(job.request), "generator returned null");
            } catch (RuntimeException | Error failure) {
                synchronized (lock) {
                    if (current.get(job.request.nodeKey()) == job) {
                        current.remove(job.request.nodeKey());
                    }
                    if (!closed) {
                        if (failures.size() >= 64) failures.remove(0);
                        failures.add(new Failure(job.request, failure));
                    }
                }
                return;
            }
            synchronized (lock) {
                if (!closed && !job.cancelled && current.get(job.request.nodeKey()) == job
                        && isCurrentLocked(job.request)) {
                    ready.add(new Completed<>(job.request, value));
                }
            }
        }
    }

    private boolean isCurrentLocked(Request request) {
        Job job = current.get(request.nodeKey());
        return job != null && job.request.equals(request) && !job.cancelled;
    }
}
