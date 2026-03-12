import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * DNSCache
 *
 * Features:
 * - domain -> DNSEntry (ip, ttl, expiry)
 * - TTL-based expiration and background cleaner
 * - LRU eviction when capacity exceeded
 * - Upstream DNS lookup on cache miss
 * - Hit/miss metrics and average lookup time
 *
 * Usage:
 * DNSCache cache = new DNSCache(10000, Duration.ofSeconds(300), Duration.ofSeconds(1));
 * cache.resolve("google.com");
 * cache.getCacheStats();
 */
public class DNSCache {

    // Internal DNS entry
    public static class DNSEntry {
        public final String domain;
        public final String ipAddress;
        public final long expiryTimeMillis; // epoch millis when this entry expires
        public final long createdAtMillis;

        public DNSEntry(String domain, String ipAddress, long ttlSeconds) {
            this.domain = domain;
            this.ipAddress = ipAddress;
            this.createdAtMillis = System.currentTimeMillis();
            this.expiryTimeMillis = this.createdAtMillis + ttlSeconds * 1000L;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiryTimeMillis;
        }

        public long timeToLiveMillis() {
            return Math.max(0, expiryTimeMillis - System.currentTimeMillis());
        }
    }

    // Primary storage for O(1) lookups
    private final ConcurrentHashMap<String, DNSEntry> map = new ConcurrentHashMap<>();

    // LRU order: key -> Boolean (value unused). Access order LinkedHashMap.
    // Protected by lruLock for thread safety.
    private final LinkedHashMap<String, Boolean> lru;
    private final Object lruLock = new Object();
    private final int capacity;

    // Default TTL for entries when upstream doesn't provide TTL (seconds)
    private final long defaultTtlSeconds;

    // Background cleaner
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dns-cache-cleaner");
        t.setDaemon(true);
        return t;
    });

    // Metrics
    private final LongAdder hitCount = new LongAdder();
    private final LongAdder missCount = new LongAdder();
    private final LongAdder totalLookupTimeMicros = new LongAdder(); // microseconds
    private final AtomicLong lookupOps = new AtomicLong();

    // Constructor
    public DNSCache(int capacity, Duration defaultTtl, Duration cleanerInterval) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.defaultTtlSeconds = Math.max(1, defaultTtl.getSeconds());

        // LinkedHashMap with accessOrder=true to maintain LRU
        this.lru = new LinkedHashMap<>(capacity, 0.75f, true);

        // Start cleaner
        cleaner.scheduleAtFixedRate(this::cleanExpiredEntries,
                cleanerInterval.toMillis(),
                cleanerInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * Resolve a domain to an IP address.
     * - If cache hit and not expired -> return cached IP.
     * - If missing or expired -> query upstream DNS, insert into cache, return IP.
     *
     * This method is thread-safe and aims for O(1) average time.
     */
    public String resolve(String domain) {
        long start = System.nanoTime();
        try {
            // 1) Fast path: check map
            DNSEntry entry = map.get(domain);
            if (entry != null && !entry.isExpired()) {
                // Update LRU
                touchLru(domain);
                hitCount.increment();
                return entry.ipAddress;
            }

            // If entry exists but expired, remove it
            if (entry != null && entry.isExpired()) {
                removeInternal(domain);
            }

            // Miss: query upstream
            missCount.increment();
            String ip = queryUpstream(domain);
            long ttl = defaultTtlSeconds;

            // Insert into cache
            DNSEntry newEntry = new DNSEntry(domain, ip, ttl);
            putInternal(domain, newEntry);

            return ip;
        } finally {
            long elapsedMicros = (System.nanoTime() - start) / 1_000;
            totalLookupTimeMicros.add(elapsedMicros);
            lookupOps.incrementAndGet();
        }
    }

    // Internal: query upstream DNS (blocking). Replaceable for async or custom resolver.
    private String queryUpstream(String domain) {
        try {
            InetAddress addr = InetAddress.getByName(domain);
            return addr.getHostAddress();
        } catch (UnknownHostException e) {
            // In production, handle errors more gracefully; here we rethrow as runtime
            throw new RuntimeException("Upstream DNS lookup failed for " + domain, e);
        }
    }

    // Put entry into map and update LRU; evict if necessary
    private void putInternal(String domain, DNSEntry entry) {
        map.put(domain, entry);
        synchronized (lruLock) {
            lru.put(domain, Boolean.TRUE);
            // Evict while over capacity
            while (lru.size() > capacity) {
                Iterator<Map.Entry<String, Boolean>> it = lru.entrySet().iterator();
                if (!it.hasNext()) break;
                Map.Entry<String, Boolean> eldest = it.next();
                String eldestKey = eldest.getKey();
                it.remove(); // remove from LRU
                map.remove(eldestKey); // remove from map
            }
        }
    }

    // Remove entry from both structures
    private void removeInternal(String domain) {
        map.remove(domain);
        synchronized (lruLock) {
            lru.remove(domain);
        }
    }

    // Update LRU on access
    private void touchLru(String domain) {
        synchronized (lruLock) {
            // Accessing the key moves it to the end because accessOrder=true
            if (lru.containsKey(domain)) {
                lru.get(domain);
            } else {
                lru.put(domain, Boolean.TRUE);
                // If adding pushes over capacity, evict
                if (lru.size() > capacity) {
                    Iterator<Map.Entry<String, Boolean>> it = lru.entrySet().iterator();
                    if (it.hasNext()) {
                        String eldest = it.next().getKey();
                        it.remove();
                        map.remove(eldest);
                    }
                }
            }
        }
    }

    // Background cleaner: remove expired entries
    private void cleanExpiredEntries() {
        try {
            List<String> toRemove = new ArrayList<>();
            for (Map.Entry<String, DNSEntry> e : map.entrySet()) {
                if (e.getValue().isExpired()) {
                    toRemove.add(e.getKey());
                }
            }
            if (!toRemove.isEmpty()) {
                synchronized (lruLock) {
                    for (String k : toRemove) {
                        map.remove(k);
                        lru.remove(k);
                    }
                }
            }
        } catch (Throwable t) {
            // Swallow exceptions to keep scheduled task alive; in prod log this
            t.printStackTrace();
        }
    }

    // Forcefully insert an entry with explicit TTL (seconds). Useful for tests.
    public void put(String domain, String ipAddress, long ttlSeconds) {
        DNSEntry entry = new DNSEntry(domain, ipAddress, ttlSeconds);
        putInternal(domain, entry);
    }

    // Remove a domain from cache
    public void invalidate(String domain) {
        removeInternal(domain);
    }

    // Get cache statistics
    public CacheStats getCacheStats() {
        long hits = hitCount.sum();
        long misses = missCount.sum();
        long ops = lookupOps.get();
        double hitRate = ops == 0 ? 0.0 : (double) hits / ops;
        double missRate = ops == 0 ? 0.0 : (double) misses / ops;
        double avgLookupMicros = ops == 0 ? 0.0 : (double) totalLookupTimeMicros.sum() / ops;
        int size = map.size();
        return new CacheStats(hits, misses, hitRate, missRate, avgLookupMicros, size);
    }

    public static class CacheStats {
        public final long hits;
        public final long misses;
        public final double hitRate;
        public final double missRate;
        public final double avgLookupMicros;
        public final int currentSize;

        public CacheStats(long hits, long misses, double hitRate, double missRate, double avgLookupMicros, int currentSize) {
            this.hits = hits;
            this.misses = misses;
            this.hitRate = hitRate;
            this.missRate = missRate;
            this.avgLookupMicros = avgLookupMicros;
            this.currentSize = currentSize;
        }

        @Override
        public String toString() {
            return String.format("hits=%d, misses=%d, hitRate=%.2f%%, missRate=%.2f%%, avgLookup=%.2fµs, size=%d",
                    hits, misses, hitRate * 100.0, missRate * 100.0, avgLookupMicros, currentSize);
        }
    }

    // Shutdown cleaner thread
    public void shutdown() {
        cleaner.shutdownNow();
    }

    // -------------------------
    // Demo main
    // -------------------------
    public static void main(String[] args) throws InterruptedException {
        // Create cache: capacity 1000 entries, default TTL 300s, cleaner every 1s
        DNSCache cache = new DNSCache(1000, Duration.ofSeconds(300), Duration.ofSeconds(1));

        // Example usage
        System.out.println("Resolving google.com (expected MISS then upstream):");
        String ip1 = cache.resolve("google.com");
        System.out.println(" -> " + ip1);

        System.out.println("Resolving google.com again (expected HIT):");
        String ip2 = cache.resolve("google.com");
        System.out.println(" -> " + ip2);

        // Simulate expiry by inserting with short TTL
        cache.put("short.example", "1.2.3.4", 1); // TTL 1 second
        System.out.println("short.example resolved (from put): " + cache.resolve("short.example"));
        Thread.sleep(1500);
        System.out.println("short.example after TTL (should be MISS and query upstream or fail):");
        try {
            String ipShort = cache.resolve("short.example");
            System.out.println(" -> " + ipShort);
        } catch (RuntimeException e) {
            System.out.println("Upstream lookup failed: " + e.getMessage());
        }

        // Print stats
        System.out.println("Cache stats: " + cache.getCacheStats());

        // Stress test: many concurrent resolves to measure hit/miss
        int threads = 50;
        int requestsPerThread = 200;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            ex.submit(() -> {
                Random rnd = new Random();
                for (int i = 0; i < requestsPerThread; i++) {
                    // 80% chance to request google.com (hot key), 20% random
                    String domain = rnd.nextInt(10) < 8 ? "google.com" : ("random" + rnd.nextInt(1000) + ".example");
                    try {
                        cache.resolve(domain);
                    } catch (RuntimeException ignored) {
                    }
                }
                latch.countDown();
            });
        }
        latch.await();
        ex.shutdownNow();

        System.out.println("Cache stats after stress: " + cache.getCacheStats());

        cache.shutdown();
    }
}
