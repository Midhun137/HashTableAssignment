import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class TransactionAnalytics {

    public static class Transaction {
        public final String id;
        public final long amountCents;
        public final String merchant;
        public final String accountId;
        public final long timestampMillis;

        public Transaction(String id, long amountCents, String merchant, String accountId, long timestampMillis) {
            this.id = id;
            this.amountCents = amountCents;
            this.merchant = merchant;
            this.accountId = accountId;
            this.timestampMillis = timestampMillis;
        }

        @Override
        public String toString() {
            return String.format("Tx{id=%s,amt=%.2f,merch=%s,acct=%s,time=%s}",
                    id, amountCents / 100.0, merchant, accountId, Instant.ofEpochMilli(timestampMillis));
        }
    }

    public static class Pair {
        public final Transaction a;
        public final Transaction b;
        public Pair(Transaction a, Transaction b) { this.a = a; this.b = b; }
        @Override public String toString() { return "(" + a.id + "," + b.id + ")"; }
    }

    public static class KTuple {
        public final List<Transaction> items;
        public KTuple(List<Transaction> items) { this.items = items; }
        @Override public String toString() {
            return items.stream().map(t -> t.id).collect(Collectors.joining(",", "[", "]"));
        }
    }

    public static List<Pair> findTwoSum(List<Transaction> txs, long targetCents, int maxResults) {
        Map<Long, List<Transaction>> map = new HashMap<>();
        List<Pair> out = new ArrayList<>();
        for (Transaction t : txs) {
            long complement = targetCents - t.amountCents;
            List<Transaction> matches = map.get(complement);
            if (matches != null) {
                for (Transaction m : matches) {
                    out.add(new Pair(m, t));
                    if (out.size() >= maxResults) return out;
                }
            }
            map.computeIfAbsent(t.amountCents, k -> new ArrayList<>()).add(t);
        }
        return out;
    }

    public static List<Pair> findTwoSumWithinWindow(List<Transaction> txs, long targetCents, long windowMillis, int maxResults) {
        List<Transaction> sorted = new ArrayList<>(txs);
        sorted.sort(Comparator.comparingLong(a -> a.timestampMillis));
        Deque<Transaction> window = new ArrayDeque<>();
        Map<Long, List<Transaction>> amountMap = new HashMap<>();
        List<Pair> out = new ArrayList<>();
        for (Transaction t : sorted) {
            long cutoff = t.timestampMillis - windowMillis;
            while (!window.isEmpty() && window.peekFirst().timestampMillis < cutoff) {
                Transaction old = window.pollFirst();
                List<Transaction> list = amountMap.get(old.amountCents);
                if (list != null) {
                    list.remove(old);
                    if (list.isEmpty()) amountMap.remove(old.amountCents);
                }
            }
            long complement = targetCents - t.amountCents;
            List<Transaction> matches = amountMap.get(complement);
            if (matches != null) {
                for (Transaction m : matches) {
                    out.add(new Pair(m, t));
                    if (out.size() >= maxResults) return out;
                }
            }
            window.addLast(t);
            amountMap.computeIfAbsent(t.amountCents, k -> new ArrayList<>()).add(t);
        }
        return out;
    }

    public static List<KTuple> findKSum(List<Transaction> txs, int k, long targetCents, int maxResults) {
        if (k < 2) return Collections.emptyList();
        List<Transaction> sorted = new ArrayList<>(txs);
        sorted.sort(Comparator.comparingLong(a -> a.amountCents));
        List<KTuple> results = new ArrayList<>();
        kSumRecursive(sorted, 0, k, targetCents, new ArrayList<>(), results, maxResults);
        return results;
    }

    private static void kSumRecursive(List<Transaction> sorted, int start, int k, long target, List<Transaction> path, List<KTuple> results, int maxResults) {
        if (results.size() >= maxResults) return;
        int n = sorted.size();
        if (k == 2) {
            int l = start, r = n - 1;
            while (l < r) {
                long sum = sorted.get(l).amountCents + sorted.get(r).amountCents;
                if (sum == target) {
                    List<Transaction> found = new ArrayList<>(path);
                    found.add(sorted.get(l));
                    found.add(sorted.get(r));
                    results.add(new KTuple(found));
                    if (results.size() >= maxResults) return;
                    long leftVal = sorted.get(l).amountCents;
                    long rightVal = sorted.get(r).amountCents;
                    while (l < r && sorted.get(l).amountCents == leftVal) l++;
                    while (l < r && sorted.get(r).amountCents == rightVal) r--;
                } else if (sum < target) {
                    l++;
                } else {
                    r--;
                }
            }
            return;
        }
        for (int i = start; i < n - k + 1; i++) {
            if (i > start && sorted.get(i).amountCents == sorted.get(i - 1).amountCents) continue;
            long val = sorted.get(i).amountCents;
            if ((long) val + (long) (k - 1) * sorted.get(n - 1).amountCents < target) continue;
            if ((long) val + (long) (k - 1) * sorted.get(i + 1).amountCents > target) break;
            path.add(sorted.get(i));
            kSumRecursive(sorted, i + 1, k - 1, target - val, path, results, maxResults);
            path.remove(path.size() - 1);
            if (results.size() >= maxResults) return;
        }
    }

    public static List<Map<String, Object>> detectDuplicates(List<Transaction> txs, int minDistinctAccounts, int maxGroups) {
        Map<String, List<Transaction>> group = new HashMap<>();
        for (Transaction t : txs) {
            String key = t.amountCents + "||" + (t.merchant == null ? "" : t.merchant.toLowerCase());
            group.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> e : group.entrySet()) {
            Set<String> accounts = e.getValue().stream().map(x -> x.accountId).filter(Objects::nonNull).collect(Collectors.toSet());
            if (accounts.size() >= minDistinctAccounts) {
                Map<String, Object> rec = new HashMap<>();
                String[] parts = e.getKey().split("\\|\\|", 2);
                long amount = Long.parseLong(parts[0]);
                String merchant = parts.length > 1 ? parts[1] : "";
                rec.put("amountCents", amount);
                rec.put("merchant", merchant);
                rec.put("accounts", accounts);
                rec.put("transactions", e.getValue().stream().map(tx -> tx.id).collect(Collectors.toList()));
                out.add(rec);
                if (out.size() >= maxGroups) break;
            }
        }
        return out;
    }

    public static List<Pair> findAllPairsWithLimit(List<Transaction> txs, long targetCents, int maxResults) {
        return findTwoSum(txs, targetCents, maxResults);
    }

    public static void main(String[] args) {
        List<Transaction> txs = new ArrayList<>();
        long now = System.currentTimeMillis();
        txs.add(new Transaction("1", 50000, "Store A", "acc1", now - 60 * 60 * 1000)); // 500.00
        txs.add(new Transaction("2", 30000, "Store B", "acc2", now - 45 * 60 * 1000)); // 300.00
        txs.add(new Transaction("3", 20000, "Store C", "acc3", now - 30 * 60 * 1000)); // 200.00
        txs.add(new Transaction("4", 50000, "Store A", "acc2", now - 10 * 60 * 1000)); // duplicate amount+merchant diff account
        txs.add(new Transaction("5", 25000, "Store D", "acc4", now - 5 * 60 * 1000));  // 250.00
        txs.add(new Transaction("6", 25000, "Store E", "acc5", now - 4 * 60 * 1000));  // 250.00

        System.out.println("Two-Sum target 500.00:");
        List<Pair> pairs = findTwoSum(txs, 50000, 10);
        pairs.forEach(System.out::println);

        System.out.println("\nTwo-Sum within 1 hour target 500.00:");
        List<Pair> windowPairs = findTwoSumWithinWindow(txs, 50000, 60 * 60 * 1000L, 10);
        windowPairs.forEach(System.out::println);

        System.out.println("\nK-Sum k=3 target 1000.00:");
        List<KTuple> ktuples = findKSum(txs, 3, 100000, 10);
        ktuples.forEach(System.out::println);

        System.out.println("\nDuplicate detection (amount+merchant across accounts):");
        List<Map<String, Object>> dups = detectDuplicates(txs, 2, 10);
        for (Map<String, Object> g : dups) {
            System.out.println(g);
        }
    }
}
