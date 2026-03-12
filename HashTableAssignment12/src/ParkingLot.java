import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class ParkingLot {

    public enum SpotStatus { EMPTY, OCCUPIED, DELETED }

    public static class Spot {
        volatile SpotStatus status;
        volatile String licensePlate;
        volatile long entryTsMillis;
        volatile int lastParkProbes;

        public Spot() {
            this.status = SpotStatus.EMPTY;
            this.licensePlate = null;
            this.entryTsMillis = 0L;
            this.lastParkProbes = 0;
        }
    }

    private final Spot[] spots;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final ConcurrentHashMap<String, Integer> plateToIndex = new ConcurrentHashMap<>();
    private final AtomicInteger totalParks = new AtomicInteger(0);
    private final AtomicInteger totalProbes = new AtomicInteger(0);
    private final AtomicInteger currentOccupied = new AtomicInteger(0);
    private final AtomicInteger[] hourlyOccupancy;
    private final DateTimeFormatter hourFmt = DateTimeFormatter.ofPattern("HH:00").withZone(ZoneId.systemDefault());
    private final double ratePerHour;

    public ParkingLot(int capacity, double ratePerHour) {
        this.capacity = capacity;
        this.spots = new Spot[capacity];
        for (int i = 0; i < capacity; i++) spots[i] = new Spot();
        this.hourlyOccupancy = new AtomicInteger[24];
        for (int i = 0; i < 24; i++) hourlyOccupancy[i] = new AtomicInteger(0);
        this.ratePerHour = ratePerHour;
    }

    private int preferredIndex(String licensePlate) {
        int h = licensePlate.hashCode();
        if (h == Integer.MIN_VALUE) h = 0;
        return Math.abs(h) % capacity;
    }

    public static class ParkResult {
        public final boolean success;
        public final int spotIndex;
        public final int probes;
        public final String message;

        public ParkResult(boolean success, int spotIndex, int probes, String message) {
            this.success = success;
            this.spotIndex = spotIndex;
            this.probes = probes;
            this.message = message;
        }

        @Override
        public String toString() {
            if (success) return "Assigned spot #" + spotIndex + " (" + probes + " probes)";
            return "Failed to park: " + message;
        }
    }

    public ParkResult parkVehicle(String licensePlate) {
        if (licensePlate == null || licensePlate.isEmpty()) return new ParkResult(false, -1, 0, "invalid license plate");
        lock.lock();
        try {
            if (plateToIndex.containsKey(licensePlate)) {
                int idx = plateToIndex.get(licensePlate);
                return new ParkResult(false, idx, 0, "vehicle already parked at spot " + idx);
            }
            int start = preferredIndex(licensePlate);
            int probes = 0;
            int firstDeleted = -1;
            for (int i = 0; i < capacity; i++) {
                int idx = (start + i) % capacity;
                probes++;
                Spot s = spots[idx];
                SpotStatus st = s.status;
                if (st == SpotStatus.EMPTY) {
                    int useIdx = firstDeleted >= 0 ? firstDeleted : idx;
                    Spot useSpot = spots[useIdx];
                    useSpot.status = SpotStatus.OCCUPIED;
                    useSpot.licensePlate = licensePlate;
                    useSpot.entryTsMillis = System.currentTimeMillis();
                    useSpot.lastParkProbes = probes;
                    plateToIndex.put(licensePlate, useIdx);
                    totalParks.incrementAndGet();
                    totalProbes.addAndGet(probes);
                    currentOccupied.incrementAndGet();
                    recordHourlyOccupancyIncrement();
                    return new ParkResult(true, useIdx, probes, "parked");
                } else if (st == SpotStatus.DELETED) {
                    if (firstDeleted < 0) firstDeleted = idx;
                } else if (st == SpotStatus.OCCUPIED) {
                    // continue probing
                }
            }
            if (firstDeleted >= 0) {
                Spot useSpot = spots[firstDeleted];
                useSpot.status = SpotStatus.OCCUPIED;
                useSpot.licensePlate = licensePlate;
                useSpot.entryTsMillis = System.currentTimeMillis();
                useSpot.lastParkProbes = probes;
                plateToIndex.put(licensePlate, firstDeleted);
                totalParks.incrementAndGet();
                totalProbes.addAndGet(probes);
                currentOccupied.incrementAndGet();
                recordHourlyOccupancyIncrement();
                return new ParkResult(true, firstDeleted, probes, "parked at recycled slot");
            }
            return new ParkResult(false, -1, probes, "parking full");
        } finally {
            lock.unlock();
        }
    }

    public static class ExitResult {
        public final boolean success;
        public final int spotIndex;
        public final Duration duration;
        public final double fee;
        public final String message;

        public ExitResult(boolean success, int spotIndex, Duration duration, double fee, String message) {
            this.success = success;
            this.spotIndex = spotIndex;
            this.duration = duration;
            this.fee = fee;
            this.message = message;
        }

        @Override
        public String toString() {
            if (success) {
                long hours = duration.toHours();
                long minutes = duration.toMinutesPart();
                return String.format("Spot #%d freed, Duration: %dh %dm, Fee: $%.2f", spotIndex, hours, minutes, fee);
            }
            return "Exit failed: " + message;
        }
    }

    public ExitResult exitVehicle(String licensePlate) {
        if (licensePlate == null || licensePlate.isEmpty()) return new ExitResult(false, -1, Duration.ZERO, 0.0, "invalid license plate");
        lock.lock();
        try {
            Integer idxObj = plateToIndex.remove(licensePlate);
            if (idxObj == null) return new ExitResult(false, -1, Duration.ZERO, 0.0, "vehicle not found");
            int idx = idxObj;
            Spot s = spots[idx];
            if (s.status != SpotStatus.OCCUPIED || !licensePlate.equals(s.licensePlate)) {
                return new ExitResult(false, idx, Duration.ZERO, 0.0, "inconsistent state");
            }
            long now = System.currentTimeMillis();
            long entry = s.entryTsMillis;
            Duration dur = Duration.ofMillis(Math.max(0, now - entry));
            double hours = Math.ceil(dur.toMinutes() / 60.0);
            double fee = hours * ratePerHour;
            s.status = SpotStatus.DELETED;
            s.licensePlate = null;
            s.entryTsMillis = 0L;
            s.lastParkProbes = 0;
            currentOccupied.decrementAndGet();
            return new ExitResult(true, idx, dur, fee, "exited");
        } finally {
            lock.unlock();
        }
    }

    public int findNearestAvailableSpotToEntrance() {
        lock.lock();
        try {
            int entrance = 0;
            for (int d = 0; d <= capacity / 2; d++) {
                int left = entrance - d;
                int right = entrance + d;
                if (left >= 0) {
                    if (spots[left].status == SpotStatus.EMPTY || spots[left].status == SpotStatus.DELETED) return left;
                }
                if (right < capacity) {
                    if (spots[right].status == SpotStatus.EMPTY || spots[right].status == SpotStatus.DELETED) return right;
                }
            }
            return -1;
        } finally {
            lock.unlock();
        }
    }

    private void recordHourlyOccupancyIncrement() {
        int hour = Instant.now().atZone(ZoneId.systemDefault()).getHour();
        hourlyOccupancy[hour].incrementAndGet();
    }

    public static class Statistics {
        public final double occupancyPercent;
        public final double avgProbes;
        public final String peakHour;
        public final int occupiedSpots;

        public Statistics(double occupancyPercent, double avgProbes, String peakHour, int occupiedSpots) {
            this.occupancyPercent = occupancyPercent;
            this.avgProbes = avgProbes;
            this.peakHour = peakHour;
            this.occupiedSpots = occupiedSpots;
        }

        @Override
        public String toString() {
            return String.format("Occupancy: %.1f%%, Avg Probes: %.2f, Peak Hour: %s, Occupied: %d/%d",
                    occupancyPercent, avgProbes, peakHour, occupiedSpots, 0);
        }
    }

    public Statistics getStatistics() {
        lock.lock();
        try {
            int occupied = currentOccupied.get();
            double occupancyPercent = (occupied * 100.0) / capacity;
            int parks = totalParks.get();
            double avgProbes = parks == 0 ? 0.0 : (double) totalProbes.get() / parks;
            int peakHourIndex = 0;
            int peakVal = -1;
            for (int i = 0; i < 24; i++) {
                int v = hourlyOccupancy[i].get();
                if (v > peakVal) {
                    peakVal = v;
                    peakHourIndex = i;
                }
            }
            String peakHourStr = String.format("%02d:00-%02d:00", peakHourIndex, (peakHourIndex + 1) % 24);
            return new Statistics(occupancyPercent, avgProbes, peakHourStr, occupied);
        } finally {
            lock.unlock();
        }
    }

    public void printLayoutSummary(int showFirstN) {
        StringBuilder sb = new StringBuilder();
        sb.append("Parking layout snapshot:\n");
        for (int i = 0; i < Math.min(showFirstN, capacity); i++) {
            Spot s = spots[i];
            sb.append(String.format("#%03d: %s", i, s.status));
            if (s.status == SpotStatus.OCCUPIED) sb.append(" [" + s.licensePlate + "]");
            sb.append("\n");
        }
        System.out.println(sb.toString());
    }

    public static void main(String[] args) throws InterruptedException {
        ParkingLot lot = new ParkingLot(500, 6.25);
        System.out.println(lot.parkVehicle("ABC-1234"));
        System.out.println(lot.parkVehicle("ABC-1235"));
        System.out.println(lot.parkVehicle("XYZ-9999"));
        int nearest = lot.findNearestAvailableSpotToEntrance();
        System.out.println("Nearest available to entrance: " + (nearest >= 0 ? "#" + nearest : "none"));
        Thread.sleep(1000);
        System.out.println(lot.exitVehicle("ABC-1234"));
        System.out.println(lot.exitVehicle("NOT-FOUND"));
        System.out.println("Statistics: ");
        Statistics s = lot.getStatistics();
        System.out.println("Occupancy percent: " + s.occupancyPercent);
        System.out.println("Average probes: " + s.avgProbes);
        System.out.println("Peak hour: " + s.peakHour);
        lot.printLayoutSummary(20);
    }
}
