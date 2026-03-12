import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AutocompleteService {

    static class Suggestion implements Comparable<Suggestion> {
        final String query;
        final int freq;
        Suggestion(String q, int f) { query = q; freq = f; }
        public int compareTo(Suggestion o) {
            if (freq != o.freq) return Integer.compare(o.freq, freq);
            return query.compareTo(o.query);
        }
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Suggestion)) return false;
            Suggestion s = (Suggestion) o;
            return query.equals(s.query) && freq == s.freq;
        }
        public int hashCode() { return Objects.hash(query, freq); }
    }

    static class TrieNode {
        final Map<Character, TrieNode> children = new HashMap<>();
        boolean isWord;
        final StringBuilder prefixBuilder = new StringBuilder();
        final TreeSet<Suggestion> topSet = new TreeSet<>();
    }

    private final TrieNode root = new TrieNode();
    private final ConcurrentHashMap<String, AtomicInteger> freqMap = new ConcurrentHashMap<>();
    private final int perNodeTopK;
    private final ConcurrentHashMap<String, List<String>> prefixCache = new ConcurrentHashMap<>();

    public AutocompleteService(int perNodeTopK) {
        this.perNodeTopK = Math.max(5, perNodeTopK);
    }

    public void addQuery(String query, int frequency) {
        if (query == null || query.isEmpty()) return;
        freqMap.compute(query, (k, v) -> {
            if (v == null) return new AtomicInteger(Math.max(0, frequency));
            v.addAndGet(Math.max(0, frequency));
            return v;
        });
        insertIntoTrie(query);
        prefixCache.clear();
    }

    public void recordSearch(String query) {
        if (query == null || query.isEmpty()) return;
        AtomicInteger ai = freqMap.computeIfAbsent(query, k -> new AtomicInteger(0));
        int newFreq = ai.incrementAndGet();
        updateTrieOnFrequencyChange(query, newFreq);
        prefixCache.clear();
    }

    public List<String> suggest(String prefix, int k) {
        if (prefix == null) return Collections.emptyList();
        String p = prefix.toLowerCase();
        List<String> cached = prefixCache.get(p + "|" + k);
        if (cached != null) return cached;
        TrieNode node = traverseToNode(p);
        PriorityQueue<Suggestion> heap = new PriorityQueue<>();
        if (node != null) {
            synchronized (node) {
                for (Suggestion s : node.topSet) {
                    heap.offer(s);
                    if (heap.size() > k) heap.poll();
                }
            }
        }
        if (heap.isEmpty()) {
            Set<String> merged = new TreeSet<>();
            for (String alt : edits(p)) {
                TrieNode n2 = traverseToNode(alt);
                if (n2 == null) continue;
                synchronized (n2) {
                    for (Suggestion s : n2.topSet) {
                        merged.add(s.query);
                        if (merged.size() >= k) break;
                    }
                }
                if (merged.size() >= k) break;
            }
            List<String> out = new ArrayList<>(merged);
            prefixCache.put(p + "|" + k, out);
            return out;
        } else {
            List<Suggestion> list = new ArrayList<>();
            while (!heap.isEmpty()) list.add(heap.poll());
            Collections.reverse(list);
            List<String> out = new ArrayList<>();
            for (Suggestion s : list) out.add(s.query);
            prefixCache.put(p + "|" + k, out);
            return out;
        }
    }

    private TrieNode traverseToNode(String prefix) {
        TrieNode cur = root;
        for (char c : prefix.toCharArray()) {
            synchronized (cur) {
                TrieNode nxt = cur.children.get(c);
                if (nxt == null) return null;
                cur = nxt;
            }
        }
        return cur;
    }

    private void insertIntoTrie(String query) {
        String q = query.toLowerCase();
        TrieNode cur = root;
        for (char c : q.toCharArray()) {
            synchronized (cur) {
                TrieNode nxt = cur.children.get(c);
                if (nxt == null) {
                    nxt = new TrieNode();
                    cur.children.put(c, nxt);
                }
                cur = nxt;
            }
            addSuggestionToNode(cur, query);
        }
        synchronized (cur) {
            cur.isWord = true;
        }
    }

    private void updateTrieOnFrequencyChange(String query, int newFreq) {
        String q = query.toLowerCase();
        TrieNode cur = root;
        for (char c : q.toCharArray()) {
            synchronized (cur) {
                TrieNode nxt = cur.children.get(c);
                if (nxt == null) return;
                cur = nxt;
            }
            synchronized (cur) {
                cur.topSet.removeIf(s -> s.query.equals(query));
                cur.topSet.add(new Suggestion(query, newFreq));
                if (cur.topSet.size() > perNodeTopK) {
                    while (cur.topSet.size() > perNodeTopK) {
                        cur.topSet.pollLast();
                    }
                }
            }
        }
    }

    private void addSuggestionToNode(TrieNode node, String query) {
        int f = freqMap.getOrDefault(query, new AtomicInteger(0)).get();
        synchronized (node) {
            node.topSet.removeIf(s -> s.query.equals(query));
            node.topSet.add(new Suggestion(query, f));
            if (node.topSet.size() > perNodeTopK) {
                while (node.topSet.size() > perNodeTopK) {
                    node.topSet.pollLast();
                }
            }
        }
    }

    private Set<String> edits(String word) {
        Set<String> res = new LinkedHashSet<>();
        for (int i = 0; i < word.length(); i++) {
            res.add(word.substring(0, i) + word.substring(i + 1));
        }
        for (int i = 0; i <= word.length(); i++) {
            for (char c = 'a'; c <= 'z'; c++) {
                res.add(word.substring(0, i) + c + word.substring(i));
            }
        }
        for (int i = 0; i < word.length(); i++) {
            for (char c = 'a'; c <= 'z'; c++) {
                res.add(word.substring(0, i) + c + word.substring(i + 1));
            }
        }
        for (int i = 0; i < word.length() - 1; i++) {
            char[] arr = word.toCharArray();
            char tmp = arr[i];
            arr[i] = arr[i + 1];
            arr[i + 1] = tmp;
            res.add(new String(arr));
        }
        return res;
    }

    public static void main(String[] args) {
        AutocompleteService svc = new AutocompleteService(10);
        svc.addQuery("java tutorial", 1234567);
        svc.addQuery("javascript", 987654);
        svc.addQuery("java download", 456789);
        svc.addQuery("java 21 features", 1);
        svc.addQuery("java 21 features", 1);
        svc.addQuery("java 21 features", 1);
        svc.addQuery("python tutorial", 800000);
        svc.addQuery("php tutorial", 200000);
        svc.addQuery("java concurrency", 300000);
        svc.addQuery("java stream api", 250000);
        List<String> s1 = svc.suggest("jav", 10);
        System.out.println("Suggestions for jav:");
        for (int i = 0; i < s1.size(); i++) {
            System.out.println((i + 1) + ". " + s1.get(i) + " (" + svc.freqMap.getOrDefault(s1.get(i), new AtomicInteger(0)).get() + ")");
        }
        svc.recordSearch("java 21 features");
        svc.recordSearch("java 21 features");
        List<String> s2 = svc.suggest("java 21", 5);
        System.out.println("\nSuggestions for java 21:");
        for (int i = 0; i < s2.size(); i++) {
            System.out.println((i + 1) + ". " + s2.get(i) + " (" + svc.freqMap.getOrDefault(s2.get(i), new AtomicInteger(0)).get() + ")");
        }
        List<String> typo = svc.suggest("jva", 5);
        System.out.println("\nSuggestions for typo jva:");
        for (int i = 0; i < typo.size(); i++) {
            System.out.println((i + 1) + ". " + typo.get(i) + " (" + svc.freqMap.getOrDefault(typo.get(i), new AtomicInteger(0)).get() + ")");
        }
    }
}
