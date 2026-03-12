import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MultiLevelVideoCache {

    public static class VideoData {
        public final String videoId;
        public final byte[] payload;
        public final long version;
        public final long lastUpdatedTs;

        public VideoData(String videoId, byte[] payload, long version) {
            this.videoId = videoId;
            this.payload = payload;
            this.version = version;
            this.lastUpdatedTs = System.currentTimeMillis();
        }
    }

    private static class LRUCache extends LinkedHashMap<String, VideoData> {
        private final int capacity;
        LRUCache(int capacity) {
            super(capacity + 1, 0.75f, true);
            this.capacity = capacity;
        }
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, VideoData> eldest) {
            return size() > capacity;
        }
    }

    private final LRUCache l1;
    private final LRUCache l2;
    private final ConcurrentHashMap<String, VideoData> l3;
    private final ConcurrentHashMap<String, AtomicInteger> accessCounts;
    private final ScheduledExecutorService metricsScheduler;

    private final AtomicLong l1Hits = new AtomicLong();
    private final AtomicLong l2Hits = new AtomicLong();
    private final AtomicLong l3Hits = new AtomicLong();
    private final AtomicLong l1Misses = new AtomicLong();
    private final AtomicLong l2Misses = new AtomicLong();
    private final AtomicLong l3Misses = new AtomicLong();
    private final AtomicLong l1LatencyNs = new AtomicLong();
    private final AtomicLong l2LatencyNs = new AtomicLong();
    private final AtomicLong l3LatencyNs = new AtomicLong();
    private final AtomicLong totalRequests = new AtomicLong();

    private final Object l1Lock = new Object();
    private final Object l2Lock = new Object();

    private final int PROMOTE_THRESHOLD;
    private final int L1_CAPACITY;
    private final int L2_CAPACITY;

    public MultiLevelVideoCache(int l1Capacity, int l2Capacity, int promoteThreshold) {
        this.L1_CAPACITY = l1Capacity;
        this.L2_CAPACITY = l2Capacity;
        this.PROMOTE_THRESHOLD = promoteThreshold;
        this.l1 = new LRUCache(l1Capacity);
        this.l2 = new LRUCache(l2Capacity);
        this.l3 = new ConcurrentHashMap<>();
        this.accessCounts = new ConcurrentHashMap<>();
        this.metricsScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cache-metrics");
            t.setDaemon(true);
            return t;
        });
        this.metricsScheduler.scheduleAtFixedRate(this::logMetrics, 30, 30, TimeUnit.SECONDS);
    }

    public void shutdown() {
        metricsScheduler.shutdownNow();
    }

    public void putToDatabase(String videoId, byte[] payload, long version) {
        VideoData v = new VideoData(videoId, payload, version);
        l3.put(videoId, v);
    }

    public void putVideo(String videoId, byte[] payload, long version) {
        VideoData v = new VideoData(videoId, payload, version);
        l3.put(videoId, v);
        synchronized (l2Lock) {
            l2.put(videoId, v);
        }
    }

    public VideoData getVideo(String videoId) {
        totalRequests.incrementAndGet();
        long start = System.nanoTime();
        VideoData v;
        synchronized (l1Lock) {
            v = l1.get(videoId);
            if (v != null) {
                l1Hits.incrementAndGet();
                l1LatencyNs.addAndGet(System.nanoTime() - start);
                incrementAccess(videoId);
                return v;
            } else {
                l1Misses.incrementAndGet();
            }
        }
        long afterL1 = System.nanoTime();
        synchronized (l2Lock) {
            v = l2.get(videoId);
            if (v != null) {
                l2Hits.incrementAndGet();
                l2LatencyNs.addAndGet(System.nanoTime() - afterL1);
                promoteToL1IfNeeded(videoId, v);
                incrementAccess(videoId);
                return v;
            } else {
                l2Misses.incrementAndGet();
            }
        }
        long afterL2 = System.nanoTime();
        v = l3.get(videoId);
        if (v != null) {
            l3Hits.incrementAndGet();
            l3LatencyNs.addAndGet(System.nanoTime() - afterL2);
            synchronized (l2Lock) {
                l2.put(videoId, v);
            }
            incrementAccess(videoId);
            return v;
        } else {
            l3Misses.incrementAndGet();
            l3LatencyNs.addAndGet(System.nanoTime() - afterL2);
            return null;
        }
    }

    private void incrementAccess(String videoId) {
        AtomicInteger ai = accessCounts.computeIfAbsent(videoId, k -> new AtomicInteger(0));
        int val = ai.incrementAndGet();
        if (val >= PROMOTE_THRESHOLD) {
            VideoData v;
            synchronized (l2Lock) {
                v = l2.get(videoId);
            }
            if (v != null) {
                synchronized (l1Lock) {
                    l1.put(videoId, v);
                }
            } else {
                v = l3.get(videoId);
                if (v != null) {
                    synchronized (l1Lock) {
                        l1.put(videoId, v);
                    }
                    synchronized (l2Lock) {
                        l2.put(videoId, v);
                    }
                }
            }
            ai.set(0);
        }
    }

    private void promoteToL1IfNeeded(String videoId, VideoData v) {
        AtomicInteger ai = accessCounts.computeIfAbsent(videoId, k -> new AtomicInteger(0));
        int val = ai.incrementAndGet();
        if (val >= PROMOTE_THRESHOLD) {
            synchronized (l1Lock) {
                l1.put(videoId, v);
            }
            ai.set(0);
        }
    }

    public void invalidate(String videoId, long newVersion) {
        l3.compute(videoId, (k, old) -> {
            if (old == null) return null;
            if (newVersion > old.version) {
                return new VideoData(videoId, old.payload, newVersion);
            } else {
                return old;
            }
        });
        synchronized (l1Lock) {
            VideoData v = l1.get(videoId);
            if (v != null && v.version < newVersion) {
                l1.remove(videoId);
            }
        }
        synchronized (l2Lock) {
            VideoData v = l2.get(videoId);
            if (v != null && v.version < newVersion) {
                l2.remove(videoId);
            }
        }
        accessCounts.remove(videoId);
    }

    public CacheStats getStatistics() {
        long l1Reqs = l1Hits.get() + l1Misses.get();
        long l2Reqs = l2Hits.get() + l2Misses.get();
        long l3Reqs = l3Hits.get() + l3Misses.get();
        double l1HitRate = l1Reqs == 0 ? 0.0 : (double) l1Hits.get() / l1Reqs;
        double l2HitRate = l2Reqs == 0 ? 0.0 : (double) l2Hits.get() / l2Reqs;
        double l3HitRate = l3Reqs == 0 ? 0.0 : (double) l3Hits.get() / l3Reqs;
        double l1AvgMs = l1Reqs == 0 ? 0.0 : (l1LatencyNs.get() / 1_000_000.0) / l1Reqs;
        double l2AvgMs = l2Reqs == 0 ? 0.0 : (l2LatencyNs.get() / 1_000_000.0) / l2Reqs;
        double l3AvgMs = l3Reqs == 0 ? 0.0 : (l3LatencyNs.get() / 1_000_000.0) / l3Reqs;
        long totalReqs = totalRequests.get();
        long totalHits = l1Hits.get() + l2Hits.get() + l3Hits.get();
        double overallHitRate = totalReqs == 0 ? 0.0 : (double) totalHits / totalReqs;
        double overallAvgMs = totalReqs == 0 ? 0.0 : ((l1LatencyNs.get() + l2LatencyNs.get() + l3LatencyNs.get()) / 1_000_000.0) / totalReqs;
        return new CacheStats(l1HitRate, l2HitRate, l3HitRate, l1AvgMs, l2AvgMs, l3AvgMs, overallHitRate, overallAvgMs,
                l1.size(), l2.size(), l3.size());
    }

    public static class CacheStats {
        public final double l1HitRate;
        public final double l2HitRate;
        public final double l3HitRate;
        public final double l1AvgMs;
        public final double l2AvgMs;
        public final double l3AvgMs;
        public final double overallHitRate;
        public final double overallAvgMs;
        public final int l1Size;
        public final int l2Size;
        public final int l3Size;

        CacheStats(double l1HitRate, double l2HitRate, double l3HitRate, double l1AvgMs, double l2AvgMs, double l3AvgMs,
                   double overallHitRate, double overallAvgMs, int l1Size, int l2Size, int l3Size) {
            this.l1HitRate = l1HitRate;
            this.l2HitRate = l2HitRate;
            this.l3HitRate = l3HitRate;
            this.l1AvgMs = l1AvgMs;
            this.l2AvgMs = l2AvgMs;
            this.l3AvgMs = l3AvgMs;
            this.overallHitRate = overallHitRate;
            this.overallAvgMs = overallAvgMs;
            this.l1Size = l1Size;
            this.l2Size = l2Size;
            this.l3Size = l3Size;
        }

        @Override
        public String toString() {
            return String.format("L1 Hit Rate: %.2f%%, Avg L1: %.3fms%nL2 Hit Rate: %.2f%%, Avg L2: %.3fms%nL3 Hit Rate: %.2f%%, Avg L3: %.3fms%nOverall Hit Rate: %.2f%%, Overall Avg: %.3fms%nSizes L1:%d L2:%d L3:%d",
                    l1HitRate * 100.0, l1AvgMs, l2HitRate * 100.0, l2AvgMs, l3HitRate * 100.0, l3AvgMs, overallHitRate * 100.0, overallAvgMs, l1Size, l2Size, l3Size);
        }
    }

    private void logMetrics() {
        CacheStats s = getStatistics();
        System.out.println(Instant.now() + " CacheStats:\n" + s.toString());
    }

    public static void main(String[] args) throws InterruptedException {
        MultiLevelVideoCache cache = new MultiLevelVideoCache(10_000, 100_000, 50);
        for (int i = 1; i <= 200_000; i++) {
            String id = "video_" + i;
            byte[] payload = ("payload_for_" + id).getBytes();
            cache.putToDatabase(id, payload, 1L);
        }
        for (int i = 1; i <= 120_000; i++) {
            String id = "video_" + i;
            cache.putVideo(id, ("vdata_" + id).getBytes(), 1L);
        }
        Random rnd = new Random();
        for (int t = 0; t < 5000; t++) {
            String id = "video_" + (1 + rnd.nextInt(120_000));
            cache.getVideo(id);
        }
        cache.invalidate("video_100", 2L);
        CacheStats stats = cache.getStatistics();
        System.out.println("Final stats:\n" + stats.toString());
        cache.shutdown();
    }
}
