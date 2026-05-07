package server.bots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class BotPerformanceMonitor {
    static final class Config {
        public boolean ENABLED = true;
        public int LOG_INTERVAL_MS = 15000;
        public double SLOW_SAMPLE_MS = 50.0;
        public double REPORT_MAX_MS = 250.0;
    }

    private static final class Stat {
        long count = 0;
        long totalNs = 0;
        long maxNs = 0;
        long slowCount = 0;
        long slowTotalNs = 0;
    }

    private static final Logger log = LoggerFactory.getLogger(BotPerformanceMonitor.class);
    private static final Object LOCK = new Object();
    private static final int MAX_LOGGED_SECTIONS = 12;
    static Config cfg = new Config();

    private static long lastLogAtMs = System.currentTimeMillis();
    private static long nextLogAtMs = lastLogAtMs + cfg.LOG_INTERVAL_MS;
    private static final Map<String, Stat> statsBySection = new LinkedHashMap<>();
    private static final Map<String, String> SECTION_NOTES = Map.of(
            "tick-total", "complete bot tick, including common systems, AI, combat, navigation, and movement",
            "move-ground", "ground physics, foothold collision, fallback steering, and movement packet sync",
            "move-air", "air physics, foothold landing checks, and air steering",
            "move-climb", "rope/ladder attachment, climb movement, and dismount checks",
            "move-swim", "swim physics, vertical hold selection, and movement packet sync",
            "nav-resolve", "region lookup, graph/path selection, edge reuse, and waypoint selection",
            "pathfind", "A* search over the current bot navigation graph",
            "pathfind-target-score", "A* called while ranking grind target regions",
            "combat-target-search", "monster scan, distance filtering, foothold lookup, and candidate sorting",
            "combat-plan", "skill/basic attack route selection and hitbox construction"
    );

    private BotPerformanceMonitor() {
    }

    static boolean enabled() {
        return cfg.ENABLED;
    }

    static void record(String section, long elapsedNs) {
        if (!cfg.ENABLED || elapsedNs < 0) {
            return;
        }

        synchronized (LOCK) {
            Stat stat = statsBySection.computeIfAbsent(section, ignored -> new Stat());
            stat.count++;
            stat.totalNs += elapsedNs;
            stat.maxNs = Math.max(stat.maxNs, elapsedNs);
            if (elapsedNs >= slowThresholdNs()) {
                stat.slowCount++;
                stat.slowTotalNs += elapsedNs;
            }
            maybeLog();
        }
    }

    static void recordPathfind(long elapsedNs) {
        record("pathfind", elapsedNs);
    }

    static void recordPathfind(String caller, long elapsedNs) {
        if (caller == null || caller.isBlank()) {
            recordPathfind(elapsedNs);
            return;
        }
        record("pathfind-" + caller, elapsedNs);
    }

    private static void maybeLog() {
        long now = System.currentTimeMillis();
        if (now < nextLogAtMs || statsBySection.isEmpty()) {
            return;
        }

        double intervalSeconds = Math.max(0.001, (now - lastLogAtMs) / 1000.0);
        List<Map.Entry<String, Stat>> reportSections = new ArrayList<>();
        for (Map.Entry<String, Stat> entry : statsBySection.entrySet()) {
            if (entry.getValue().maxNs >= reportThresholdNs()) {
                reportSections.add(entry);
            }
        }
        reportSections.sort(Comparator.comparingLong((Map.Entry<String, Stat> entry) -> entry.getValue().maxNs).reversed());

        if (reportSections.isEmpty()) {
            statsBySection.clear();
            lastLogAtMs = now;
            nextLogAtMs = now + cfg.LOG_INTERVAL_MS;
            return;
        }

        StringBuilder line = new StringBuilder("bot-perf report>=")
                .append(formatMs(cfg.REPORT_MAX_MS))
                .append("ms")
                .append(" slow>=")
                .append(formatMs(cfg.SLOW_SAMPLE_MS))
                .append("ms ");
        boolean first = true;
        int loggedSections = 0;
        for (Map.Entry<String, Stat> entry : reportSections) {
            Stat stat = entry.getValue();
            if (loggedSections >= MAX_LOGGED_SECTIONS) {
                break;
            }
            if (!first) {
                line.append(" | ");
            }
            first = false;
            loggedSections++;

            double averageMs = stat.totalNs / (double) Math.max(1L, stat.count) / 1_000_000.0;
            double totalMs = stat.totalNs / 1_000_000.0;
            double cpuMsPerSec = totalMs / intervalSeconds;
            double cpuCore = cpuMsPerSec / 1000.0;
            double maxMs = stat.maxNs / 1_000_000.0;
            double slowAverageMs = stat.slowTotalNs / (double) Math.max(1L, stat.slowCount) / 1_000_000.0;
            double slowPct = stat.slowCount * 100.0 / Math.max(1L, stat.count);
            line.append(entry.getKey())
                    .append(" avg=")
                    .append(formatMs(averageMs))
                    .append("ms")
                    .append(" cps=")
                    .append(String.format(Locale.ROOT, "%.1f", stat.count / intervalSeconds))
                    .append(" cpu=")
                    .append(formatMs(cpuMsPerSec))
                    .append("ms/s")
                    .append(" core=")
                    .append(formatCore(cpuCore))
                    .append(" max=")
                    .append(formatMs(maxMs))
                    .append("ms")
                    .append(" n=")
                    .append(stat.count)
                    .append(" slow=")
                    .append(stat.slowCount)
                    .append("/")
                    .append(stat.count)
                    .append(" slow%=")
                    .append(formatPct(slowPct))
                    .append("%")
                    .append(" slowAvg=")
                    .append(formatMs(slowAverageMs))
                    .append("ms")
                    .append(" note=")
                    .append(noteFor(entry.getKey()));
        }
        if (reportSections.size() > loggedSections) {
            line.append(" | omitted=")
                    .append(reportSections.size() - loggedSections)
                    .append(" lower-max report sections");
        }

        if (!first) {
            log.info(line.toString());
        }
        statsBySection.clear();
        lastLogAtMs = now;
        nextLogAtMs = now + cfg.LOG_INTERVAL_MS;
    }

    private static String noteFor(String section) {
        String note = SECTION_NOTES.get(section);
        if (note != null) {
            return note;
        }
        if (section != null && section.startsWith("pathfind-")) {
            return "A* search over the current bot navigation graph";
        }
        return "instrumented bot subsystem";
    }

    private static long slowThresholdNs() {
        return (long) (Math.max(0.0, cfg.SLOW_SAMPLE_MS) * 1_000_000.0);
    }

    private static long reportThresholdNs() {
        return (long) (Math.max(cfg.SLOW_SAMPLE_MS, cfg.REPORT_MAX_MS) * 1_000_000.0);
    }

    private static String formatMs(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String formatPct(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String formatCore(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
