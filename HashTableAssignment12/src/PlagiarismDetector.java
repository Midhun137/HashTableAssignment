import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * PlagiarismDetector
 *
 * - Tokenizes documents and extracts k-grams (default k=5)
 * - Uses rolling hash (Rabin-Karp style) over tokens to produce 64-bit fingerprints
 * - Index: hash -> set of document IDs (ConcurrentHashMap with concurrent sets)
 * - Query: count matching n-grams per document and compute similarity
 *
 * Usage:
 *   PlagiarismDetector pd = new PlagiarismDetector(5);
 *   pd.indexDocument("essay_089.txt", textOfEssay089);
 *   pd.indexDocument("essay_092.txt", textOfEssay092);
 *   List<Similarity> top = pd.findMostSimilar("essay_123.txt", textOfEssay123, 5);
 */
public class PlagiarismDetector {

    // Index: ngramHash -> set of document IDs containing that ngram
    private final ConcurrentHashMap<Long, ConcurrentHashMap.KeySetView<String, Boolean>> index = new ConcurrentHashMap<>();

    // Document metadata: docId -> total n-gram count
    private final ConcurrentHashMap<String, Integer> docNGramCounts = new ConcurrentHashMap<>();

    // Parameters for n-gram size and rolling hash
    private final int k; // n-gram size (e.g., 5)
    private final long base; // base for rolling hash
    private final long mod;  // modulus for hash (use large 64-bit mix)

    // Constructor
    public PlagiarismDetector(int k) {
        if (k <= 0) throw new IllegalArgumentException("k must be > 0");
        this.k = k;
        this.base = 1000003L; // prime base for token hashing
        this.mod = (1L << 61) - 1; // use Mersenne-like modulus for 64-bit mixing
    }

    // Public API: index a document into the corpus
    public void indexDocument(String docId, String text) {
        List<String> tokens = tokenize(text);
        if (tokens.size() < k) {
            docNGramCounts.put(docId, 0);
            return;
        }

        List<Long> ngramHashes = rollingNGramHashes(tokens);
        // Insert docId into index for each ngram hash
        for (Long h : ngramHashes) {
            index.computeIfAbsent(h, key -> ConcurrentHashMap.newKeySet()).add(docId);
        }
        docNGramCounts.put(docId, ngramHashes.size());
    }

    // Public API: analyze a new document and return top K similar documents
    public List<Similarity> findMostSimilar(String queryDocId, String text, int topK) {
        List<String> tokens = tokenize(text);
        if (tokens.size() < k) return Collections.emptyList();

        List<Long> queryHashes = rollingNGramHashes(tokens);
        int totalQueryNgrams = queryHashes.size();

        // Map docId -> matched n-gram count
        ConcurrentHashMap<String, Integer> matchCounts = new ConcurrentHashMap<>();

        // For each n-gram hash, get candidate docs and increment their match count
        for (Long h : queryHashes) {
            Set<String> docs = index.get(h);
            if (docs == null) continue;
            for (String docId : docs) {
                matchCounts.merge(docId, 1, Integer::sum);
            }
        }

        // Convert to similarity list
        PriorityQueue<Similarity> pq = new PriorityQueue<>(Comparator.comparingDouble(s -> s.similarity));
        for (Map.Entry<String, Integer> e : matchCounts.entrySet()) {
            String docId = e.getKey();
            int matches = e.getValue();
            double similarity = (matches * 100.0) / totalQueryNgrams; // percent of query n-grams matched
            Similarity s = new Similarity(docId, matches, totalQueryNgrams, similarity);
            pq.offer(s);
            if (pq.size() > topK) pq.poll();
        }

        List<Similarity> out = new ArrayList<>();
        while (!pq.isEmpty()) out.add(pq.poll());
        Collections.reverse(out); // highest first
        return out;
    }

    // Utility: tokenize text into normalized word tokens
    private List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        // Normalize: lowercase, remove punctuation (keep internal apostrophes), split on whitespace
        String normalized = text.toLowerCase()
                .replaceAll("[^a-z0-9'\\s]", " ") // remove punctuation except apostrophe
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isEmpty()) return Collections.emptyList();
        String[] parts = normalized.split(" ");
        return Arrays.asList(parts);
    }

    // Compute rolling n-gram hashes over token list
    private List<Long> rollingNGramHashes(List<String> tokens) {
        int n = tokens.size();
        List<Long> out = new ArrayList<>();
        if (n < k) return out;

        // Precompute token hashes
        long[] tHash = new long[n];
        for (int i = 0; i < n; i++) {
            tHash[i] = mix64(tokens.get(i).hashCode());
        }

        // Precompute base^(k-1) mod
        long basePow = 1;
        for (int i = 0; i < k - 1; i++) basePow = modMul(basePow, base);

        // Compute initial hash for first window
        long h = 0;
        for (int i = 0; i < k; i++) {
            h = modAdd(modMul(h, base), tHash[i]);
        }
        out.add(h);

        // Slide window
        for (int i = k; i < n; i++) {
            // remove tHash[i-k] * base^(k-1), multiply by base, add tHash[i]
            long left = modMul(tHash[i - k], basePow);
            h = modSub(h, left);
            h = modMul(h, base);
            h = modAdd(h, tHash[i]);
            out.add(h);
        }
        return out;
    }

    // Simple 64-bit mixing for token hash to reduce collisions
    private long mix64(long x) {
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= (x >>> 33);
        return x & 0x7fffffffffffffffL; // keep positive
    }

    // Modular arithmetic helpers using 64-bit modulus
    private long modAdd(long a, long b) {
        long res = a + b;
        if (res >= mod) res -= mod;
        return res;
    }

    private long modSub(long a, long b) {
        long res = a - b;
        if (res < 0) res += mod;
        return res;
    }

    private long modMul(long a, long b) {
        // 128-bit multiply emulation using Java's unsigned long wrap is acceptable for fingerprinting
        // Use simple multiplication and reduce by mod via remainder
        long res = (a * b) % mod;
        return res;
    }

    // Optional: remove a document from the index (e.g., when deleting)
    public void removeDocument(String docId) {
        // Remove docId from all index sets (costly but needed for deletions)
        for (Map.Entry<Long, ConcurrentHashMap.KeySetView<String, Boolean>> e : index.entrySet()) {
            e.getValue().remove(docId);
        }
        docNGramCounts.remove(docId);
    }

    // DTO for similarity result
    public static class Similarity {
        public final String docId;
        public final int matchedNGrams;
        public final int queryNGrams;
        public final double similarity; // percent

        public Similarity(String docId, int matchedNGrams, int queryNGrams, double similarity) {
            this.docId = docId;
            this.matchedNGrams = matchedNGrams;
            this.queryNGrams = queryNGrams;
            this.similarity = similarity;
        }

        @Override
        public String toString() {
            return String.format("%s → %d matching n-grams, Similarity: %.2f%%", docId, matchedNGrams, similarity);
        }
    }

    // -------------------------
    // Demo main
    // -------------------------
    public static void main(String[] args) {
        PlagiarismDetector pd = new PlagiarismDetector(5);

        // Example corpus documents (in production load 100k docs)
        String docA = "This is a sample essay about data structures and algorithms. It explains hashing and n-grams.";
        String docB = "An essay on data structures and algorithms. It explains hashing, rolling hash, and n-grams in detail.";
        String docC = "Completely different topic about cooking recipes and ingredients for a cake.";

        pd.indexDocument("essay_089.txt", docA);
        pd.indexDocument("essay_092.txt", docB);
        pd.indexDocument("essay_010.txt", docC);

        // Query document similar to docB
        String query = "This essay explains hashing and rolling hash for n-grams in algorithms and data structures.";
        List<Similarity> top = pd.findMostSimilar("essay_123.txt", query, 5);

        System.out.println("Analysis for essay_123.txt");
        for (Similarity s : top) {
            System.out.println("→ " + s);
        }
    }
}
