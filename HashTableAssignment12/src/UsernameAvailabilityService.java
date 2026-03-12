import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Real-time username availability checker
 * - O(1) average lookup via ConcurrentHashMap
 * - Bloom filter for fast negative checks
 * - Frequency counter for attempted usernames
 * - Suggestion generator with batch validation
 *
 * This is a self-contained demo suitable for unit testing and local runs.
 */
public class UsernameAvailabilityService {

    // Primary in-memory store: username -> userId
    private final ConcurrentHashMap<String, String> usernameMap = new ConcurrentHashMap<>();

    // Attempt frequency counter: username -> attempts
    private final ConcurrentHashMap<String, AtomicLong> attemptCounter = new ConcurrentHashMap<>();

    // Simple Bloom filter
    private final BloomFilter bloomFilter;

    // Executor for parallel validation (suggestions)
    private final ExecutorService validationExecutor;

    // Constructor
    public UsernameAvailabilityService(int expectedUsers, double falsePositiveRate, int validationThreads) {
        this.bloomFilter = new BloomFilter(expectedUsers, falsePositiveRate);
        this.validationExecutor = Executors.newFixedThreadPool(Math.max(2, validationThreads));
    }

    // Shutdown executor when done
    public void shutdown() {
        validationExecutor.shutdown();
    }

    // Register an existing username (simulate DB write + cache population)
    public boolean registerUsername(String username, String userId) {
        Objects.requireNonNull(username);
        Objects.requireNonNull(userId);

        // Ensure uniqueness at "DB" level: use putIfAbsent
        String prev = usernameMap.putIfAbsent(username, userId);
        if (prev == null) {
            bloomFilter.add(username);
            return true; // successfully registered
        } else {
            return false; // already taken
        }
    }

    // Check availability in O(1) average time
    public boolean checkAvailability(String username) {
        Objects.requireNonNull(username);

        // Track attempt frequency
        attemptCounter.computeIfAbsent(username, k -> new AtomicLong(0)).incrementAndGet();

        // Fast path: in-memory map
        if (usernameMap.containsKey(username)) {
            return false; // taken
        }

        // Bloom filter negative check: if not present in bloom -> definitely available
        if (!bloomFilter.mightContain(username)) {
            return true; // available
        }

        // Bloom says maybe present: double-check authoritative store (usernameMap)
        // In a real system this would be a DB call; here we re-check the map
        return !usernameMap.containsKey(username);
    }

    // Suggest alternatives: generate candidates and validate in batch
    public List<String> suggestAlternatives(String username, int maxSuggestions) {
        Objects.requireNonNull(username);
        if (maxSuggestions <= 0) return Collections.emptyList();

        // Generate candidate list (deterministic + some transformations)
        List<String> candidates = generateCandidates(username, 100);

        // Validate candidates in parallel and collect available ones
        List<Callable<Optional<String>>> tasks = new ArrayList<>();
        for (String candidate : candidates) {
            tasks.add(() -> {
                // Quick check: if in-memory map -> not available
                if (usernameMap.containsKey(candidate)) return Optional.empty();
                // Bloom negative check
                if (!bloomFilter.mightContain(candidate)) return Optional.of(candidate);
                // Final authoritative check
                if (!usernameMap.containsKey(candidate)) return Optional.of(candidate);
                return Optional.empty();
            });
        }

        List<String> available = new ArrayList<>();
        try {
            List<Future<Optional<String>>> futures = validationExecutor.invokeAll(tasks);
            for (Future<Optional<String>> f : futures) {
                Optional<String> opt = f.get();
                if (opt.isPresent()) {
                    available.add(opt.get());
                    if (available.size() >= maxSuggestions) break;
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            // In production, log the error. For demo, continue with what we have.
        }

        return available;
    }

    // Get most attempted username (popularity)
    public Map.Entry<String, Long> getMostAttempted() {
        return attemptCounter.entrySet()
                .stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().get()))
                .max(Comparator.comparingLong(Map.Entry::getValue))
                .orElse(null);
    }

    // Helper: generate candidate usernames
    private List<String> generateCandidates(String base, int limit) {
        List<String> out = new ArrayList<>(limit);

        // 1. Append numbers 1..N
        for (int i = 1; i <= 30 && out.size() < limit; i++) {
            out.add(base + i);
        }

        // 2. Insert dot and underscore variants
        if (out.size() < limit) {
            out.add(base.replace('_', '.'));
            out.add(base.replace('.', '_'));
            out.add(base + "_official");
            out.add(base + ".official");
        }

        // 3. Short adjectives
        String[] adjectives = {"the", "real", "official", "its", "my"};
        for (String adj : adjectives) {
            if (out.size() >= limit) break;
            out.add(base + adj);
            out.add(adj + base);
        }

        // 4. Swap adjacent characters (simple typo variants)
        for (int i = 0; i < base.length() - 1 && out.size() < limit; i++) {
            char[] arr = base.toCharArray();
            char tmp = arr[i];
            arr[i] = arr[i + 1];
            arr[i + 1] = tmp;
            out.add(new String(arr));
        }

        // 5. Remove vowels
        if (out.size() < limit) {
            out.add(base.replaceAll("[aeiouAEIOU]", ""));
        }

        // Deduplicate while preserving order
        LinkedHashSet<String> dedup = new LinkedHashSet<>(out);
        dedup.remove(base); // don't suggest the same name
        return dedup.stream().limit(limit).collect(Collectors.toList());
    }

    // For demo/testing: pre-populate some usernames
    public void preloadUsernames(Collection<String> usernames) {
        for (String u : usernames) {
            usernameMap.put(u, UUID.randomUUID().toString());
            bloomFilter.add(u);
        }
    }

    // Simple Bloom filter implementation (k hash functions using double hashing)
    static class BloomFilter {
        private final BitSet bitset;
        private final int bitSize;
        private final int numHashFunctions;
        private final int expectedElements;
        private final double falsePositiveRate;

        public BloomFilter(int expectedElements, double falsePositiveRate) {
            this.expectedElements = Math.max(1, expectedElements);
            this.falsePositiveRate = Math.max(1e-6, Math.min(falsePositiveRate, 0.5));
            // m = - (n * ln(p)) / (ln2^2)
            double m = - (this.expectedElements * Math.log(this.falsePositiveRate)) / (Math.pow(Math.log(2), 2));
            this.bitSize = (int) Math.max(64, Math.ceil(m));
            // k = (m/n) * ln2
            this.numHashFunctions = Math.max(1, (int) Math.round((m / this.expectedElements) * Math.log(2)));
            this.bitset = new BitSet(bitSize);
        }

        public void add(String value) {
            long[] hashes = hash(value);
            for (int i = 0; i < numHashFunctions; i++) {
                int idx = Math.abs((int) ((hashes[0] + i * hashes[1]) % bitSize));
                bitset.set(idx);
            }
        }

        public boolean mightContain(String value) {
            long[] hashes = hash(value);
            for (int i = 0; i < numHashFunctions; i++) {
                int idx = Math.abs((int) ((hashes[0] + i * hashes[1]) % bitSize));
                if (!bitset.get(idx)) return false;
            }
            return true;
        }

        // Double hashing using two 64-bit hashes
        private long[] hash(String s) {
            byte[] data = s.getBytes(StandardCharsets.UTF_8);
            long h1 = fnv1a64(data);
            long h2 = murmur64(data);
            return new long[]{h1, h2 == 0 ? 0x9e3779b97f4a7c15L : h2};
        }

        // FNV-1a 64-bit
        private static long fnv1a64(byte[] data) {
            long hash = 0xcbf29ce484222325L;
            for (byte b : data) {
                hash ^= (b & 0xff);
                hash *= 0x100000001b3L;
            }
            return hash;
        }

        // Murmur-inspired 64-bit mix (not full Murmur3, but good enough for demo)
        private static long murmur64(byte[] data) {
            long h = 0x9368e53c2f6af274L ^ data.length;
            for (byte b : data) {
                h ^= (b & 0xff);
                h *= 0x9e3779b97f4a7c15L;
                h = Long.rotateLeft(h, 31);
            }
            h ^= (h >>> 33);
            h *= 0xff51afd7ed558ccdL;
            h ^= (h >>> 33);
            h *= 0xc4ceb9fe1a85ec53L;
            h ^= (h >>> 33);
            return h;
        }
    }

    // Demo main
    public static void main(String[] args) throws InterruptedException {
        // Expect ~10 million users in production; for demo use smaller numbers
        UsernameAvailabilityService svc = new UsernameAvailabilityService(10_000_000, 0.01, 8);

        // Preload some usernames
        svc.preloadUsernames(Arrays.asList("john_doe", "admin", "root", "alice", "bob", "john.doe"));

        // Sample checks
        System.out.println("checkAvailability(\"john_doe\") → " + svc.checkAvailability("john_doe")); // false
        System.out.println("checkAvailability(\"jane_smith\") → " + svc.checkAvailability("jane_smith")); // true

        // Simulate many attempts on "admin"
        for (int i = 0; i < 10543; i++) {
            svc.checkAvailability("admin");
        }

        // Suggest alternatives for taken username
        List<String> suggestions = svc.suggestAlternatives("john_doe", 5);
        System.out.println("suggestAlternatives(\"john_doe\") → " + suggestions);

        // Get most attempted
        Map.Entry<String, Long> most = svc.getMostAttempted();
        if (most != null) {
            System.out.println("getMostAttempted() → \"" + most.getKey() + "\" (" + most.getValue() + " attempts)");
        } else {
            System.out.println("getMostAttempted() → none");
        }

        svc.shutdown();
    }
}
