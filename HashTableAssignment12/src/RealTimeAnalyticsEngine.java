import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * RealTimeAnalyticsEngine
 *
 * - Ingests page view events via processEvent(Event)
 * - Maintains page view counts, unique visitors per page, and source counts
 * - Emits dashboard snapshot every snapshotIntervalSeconds (default 5s)
 * - Computes Top-N pages by views at snapshot time
 *
 * Notes:
 * - For exact unique visitor tracking we keep a concurrent set per page.
 *   For very high cardinality (millions of unique users across many pages),
 *   switch to an approximate distinct counter (HyperLogLog) to save memory.
 */
public class RealTimeAnalyticsEngine {

    // Counters for page views: pageUrl -> total views (since last reset or since start)
    private final ConcurrentHashMap<String, LongAdder> pageViewCounts = new ConcurrentHashMap<>();

    // Unique visitors per page (exact): pageUrl -> concurrent set of userIds
    private final ConcurrentHashMap<String, ConcurrentHashMap.KeySetView<String, Boolean>> uniqueVisitorsPerPage = new ConcurrentHashMap<>();

    // Traffic source counts: source -> count
    private final ConcurrentHashMap<String, LongAdder> sourceCounts = new ConcurrentHashMap<>();

    // Global counters
    private final LongAdder totalEvents = new LongAdder();

    // Snapshot interval (seconds)
    private final int snapshotIntervalSeconds;

    // Top N pages to compute
    private final int topN;

    // Scheduled executor for snapshotting
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "analytics-snapshot");
        t.setDaemon(true);
        return t;
    });

    // Optional: whether to clear per-interval counters after snapshot (keeps cumulative if false)
    private final boolean resetAfterSnapshot;

    // Formatter for timestamps
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    // Constructor
    public RealTimeAnalyticsEngine(int snapshotIntervalSeconds, int topN, boolean resetAfterSnapshot) {
        if (snapshotIntervalSeconds <= 0) throw new IllegalArgumentException("snapshotIntervalSeconds > 0");
        this.snapshotIntervalSeconds = snapshotIntervalSeconds;
        this.topN = Math.max(1, topN);
        this.resetAfterSnapshot = resetAfterSnapshot;

        // Start scheduled snapshot task
        scheduler.scheduleAtFixedRate(this::emitSnapshot, snapshotIntervalSeconds, snapshotIntervalSeconds, TimeUnit.SECONDS);
    }

    // Event DTO
    public static class Event {
        public final String url;
        public final String userId;
        public final String source; // e.g., "google", "facebook", "direct", "twitter", "other"
        public final long timestampMillis;

        public Event(String url, String userId, String source) {
            this.url = Objects.requireNonNull(url);
            this.userId = Objects.requireNonNull(userId);
            this.source = source == null ? "other" : source.toLowerCase();
            this.timestampMillis = System.currentTimeMillis();
        }
    }

    /**
     * Ingest a page view event. This method is non-blocking and O(1) average.
     */
    public void processEvent(Event e) {
        // Increment page view count
        pageViewCounts.computeIfAbsent(e.url, k -> new LongAdder()).increment();

        // Track unique visitor for the page (exact)
        uniqueVisitorsPerPage.computeIfAbsent(e.url, k -> ConcurrentHashMap.newKeySet()).add(e.userId);

        // Increment source count
        sourceCounts.computeIfAbsent(e.source, k -> new LongAdder()).increment();

        // Global counter
        totalEvents.increment();
    }

    /**
     * Emit a dashboard snapshot: compute top N pages, unique visitor counts, source distribution.
     * This method runs periodically on scheduler thread.
     */
    private void emitSnapshot() {
        try {
            Instant now = Instant.now();
            String ts = TS_FMT.format(now);

            // Snapshot page counts into a local map to avoid holding references while computing top N
            Map<String, Long> pageCountsSnapshot = new HashMap<>();
            for (Map.Entry<String, LongAdder> e : pageViewCounts.entrySet()) {
                pageCountsSnapshot.put(e.getKey(), e.getValue().sum());
            }

            // Compute top N using a min-heap of size topN (O(M log topN) where M = number of pages)
            PriorityQueue<Map.Entry<String, Long>> minHeap = new PriorityQueue<>(Comparator.comparingLong(Map.Entry::getValue));
            for (Map.Entry<String, Long> e : pageCountsSnapshot.entrySet()) {
                if (minHeap.size() < topN) {
                    minHeap.offer(e);
                } else if (e.getValue() > Objects.requireNonNull(minHeap.peek()).getValue()) {
                    minHeap.poll();
                    minHeap.offer(e);
                }
            }

            List<Map.Entry<String, Long>> topList = new ArrayList<>();
            while (!minHeap.isEmpty()) topList.add(minHeap.poll());
            Collections.reverse(topList); // highest first

            // Unique visitors snapshot (exact counts)
            Map<String, Integer> uniqueCountsSnapshot = new HashMap<>();
            for (Map.Entry<String, ConcurrentHashMap.KeySetView<String, Boolean>> e : uniqueVisitorsPerPage.entrySet()) {
                uniqueCountsSnapshot.put(e.getKey(), e.getValue().size());
            }

            // Source distribution snapshot
            Map<String, Long> sourceSnapshot = new HashMap<>();
            long totalSources = 0;
            for (Map.Entry<String, LongAdder> e : sourceCounts.entrySet()) {
                long v = e.getValue().sum();
                sourceSnapshot.put(e.getKey(), v);
                totalSources += v;
            }

            // Build dashboard output (could be pushed to websocket, UI, etc.)
            StringBuilder sb = new StringBuilder();
            sb.append("=== Dashboard Snapshot @ ").append(ts).append(" ===\n");
            sb.append("Total events processed: ").append(totalEvents.sum()).append("\n\n");

            sb.append("Top Pages:\n");
            int rank = 1;
            for (Map.Entry<String, Long> e : topList) {
                String url = e.getKey();
                long views = e.getValue();
                int uniques = uniqueCountsSnapshot.getOrDefault(url, 0);
                sb.append(String.format("%2d. %s - %,d views (%,d unique)%n", rank++, url, views, uniques));
            }
            if (topList.isEmpty()) sb.append("No page views yet.\n");

            sb.append("\nTraffic Sources:\n");
            if (totalSources == 0) {
                sb.append("No source data yet.\n");
            } else {
                // Sort sources by count desc
                List<Map.Entry<String, Long>> sourcesSorted = new ArrayList<>(sourceSnapshot.entrySet());
                sourcesSorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                for (Map.Entry<String, Long> s : sourcesSorted) {
                    double pct = (s.getValue() * 100.0) / totalSources;
                    sb.append(String.format("%s: %.1f%% (%d)%n", capitalize(s.getKey()), pct, s.getValue()));
                }
            }

            // Print snapshot (replace with push to UI in real system)
            System.out.println(sb.toString());

            // Optionally reset counters for next interval if resetAfterSnapshot is true
            if (resetAfterSnapshot) {
                // Reset page counts
                pageViewCounts.clear();
                // Reset unique visitor sets
                uniqueVisitorsPerPage.clear();
                // Reset source counts
                sourceCounts.clear();
                // Reset total events
                totalEvents.reset();
            }
        } catch (Throwable t) {
            // In production log the error; keep scheduler alive
            t.printStackTrace();
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // Shutdown engine
    public void shutdown() {
        scheduler.shutdownNow();
    }

    // -------------------------
    // Demo / simple load simulation
    // -------------------------
    public static void main(String[] args) throws InterruptedException {
        // Configure engine: snapshot every 5 seconds, top 10 pages, do not reset after snapshot (cumulative)
        RealTimeAnalyticsEngine engine = new RealTimeAnalyticsEngine(5, 10, false);

        // Simulate incoming events using a thread pool
        int producerThreads = 8;
        ExecutorService producers = Executors.newFixedThreadPool(producerThreads);

        // Simulate realistic traffic: 1,000,000 page views per hour => ~278 events/sec
        final int targetEventsPerSecond = 300; // slightly above target to stress
        final int runSeconds = 30; // run for 30 seconds demo
        final int totalEvents = targetEventsPerSecond * runSeconds;

        // Prepare a small set of pages and sources to create hot keys
        String[] pages = {
                "/article/breaking-news", "/sports/championship", "/tech/ai-update",
                "/world/politics", "/opinion/editorial", "/lifestyle/travel", "/business/markets",
                "/entertainment/movie-review", "/science/discovery", "/health/wellness"
        };
        String[] sources = {"google", "facebook", "direct", "twitter", "newsletter", "referral"};

        CountDownLatch latch = new CountDownLatch(totalEvents);
        Random rnd = new Random();

        long start = System.nanoTime();
        for (int i = 0; i < totalEvents; i++) {
            final int idx = i;
            producers.submit(() -> {
                try {
                    // Create event with some skew: first two pages are hot
                    String page = (rnd.nextDouble() < 0.5) ? pages[rnd.nextInt(2)] : pages[rnd.nextInt(pages.length)];
                    String userId = "user_" + (rnd.nextInt(50_000)); // 50k unique users
                    String source = sources[rnd.nextInt(sources.length)];
                    Event e = new Event(page, userId, source);
                    engine.processEvent(e);
                } finally {
                    latch.countDown();
                }
            });

            // Pace producers to approximate targetEventsPerSecond
            if (i % targetEventsPerSecond == 0) {
                try {
                    Thread.sleep(1000L); // crude pacing
                } catch (InterruptedException ignored) {
                }
            }
        }

        latch.await();
        long elapsed = System.nanoTime() - start;
        System.out.printf("Produced %d events in %.2f seconds%n", totalEvents, elapsed / 1e9);

        // Let engine run a few more snapshots
        Thread.sleep(10_000);

        // Shutdown
        producers.shutdownNow();
        engine.shutdown();
    }
}
