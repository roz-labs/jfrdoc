import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import airhacks.zsmith.tools.control.Tool;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.json.JSONObject;

public class JfrContainerTool implements Tool {

    // cgroup reports "no limit" as a large sentinel; anything at or above this
    // (≈ 8 PB) is treated as unlimited rather than a real byte count.
    static final long UNLIMITED_BYTES = 1L << 53;

    @Override
    public String toolName() {
        return "jfr_container";
    }

    @Override
    public String description() {
        return "Analyzes the container / Kubernetes cgroup view from JFR events "
                + "(jdk.ContainerConfiguration, jdk.ContainerCPUThrottling, jdk.ContainerCPUUsage, "
                + "jdk.ContainerMemoryUsage). Returns the limits the JVM actually saw from its cgroup "
                + "(effective CPU count, CPU quota, memory limit), CPU throttling (the fraction of CFS "
                + "slices the kubelet throttled — the #1 source of silent tail-latency in Kubernetes), "
                + "average CPU utilization vs the limit, peak memory vs the limit, and memory.failcnt "
                + "(the OOMKill precursor). Cross-checks the declared --container-cpu / --container-memory "
                + "against what the JVM observed. These events only fire when the JVM runs inside a cgroup "
                + "with limits, so absence means bare-metal / no limit set, not zero work — the tool says so "
                + "explicitly. Call this when summary.notable_events_present.container is true.";
    }

    enum Field { path, container_cpu, container_memory_mb }

    @Override
    public String inputSchema() {
        return Tool.schema(
                Prop.string(Field.path,
                        "Absolute or relative filesystem path to the .jfr file"),
                Prop.string(Field.container_cpu,
                        "Declared container CPU limit for cross-check (e.g. \"1\", \"500m\"). "
                                + "If omitted, the declared-vs-observed CPU check is skipped.").optional(),
                Prop.integer(Field.container_memory_mb,
                        "Declared container memory limit in MB (e.g. 2048 for 2Gi) for cross-check. "
                                + "If omitted, the declared-vs-observed memory check is skipped.").optional()
        );
    }

    @Override
    public String execute(JSONObject input) {
        if (!input.has(Field.path.name())) {
            return "Error: Missing required parameter: path";
        }
        var path = Path.of(input.getString(Field.path.name()));
        if (!Files.exists(path)) return "Error: JFR file not found: " + path;
        if (!Files.isRegularFile(path)) return "Error: Not a regular file: " + path;

        Double declaredCpu = null;
        if (input.has(Field.container_cpu.name())
                && !JSONObject.NULL.equals(input.get(Field.container_cpu.name()))) {
            declaredCpu = parseCpuToCores(String.valueOf(input.get(Field.container_cpu.name())));
        }

        Integer declaredMemMb = null;
        if (input.has(Field.container_memory_mb.name())
                && !JSONObject.NULL.equals(input.get(Field.container_memory_mb.name()))) {
            try {
                int v = input.getInt(Field.container_memory_mb.name());
                if (v > 0) declaredMemMb = v;
            } catch (RuntimeException ignored) {}
        }

        try {
            return analyze(path, declaredCpu, declaredMemMb).toString(2);
        } catch (IOException e) {
            return "Error: Could not read JFR file: " + e.getMessage();
        }
    }

    static JSONObject analyze(Path path, Double declaredCpu, Integer declaredMemMb) throws IOException {
        // jdk.ContainerConfiguration — first one seen (limits are stable per recording).
        boolean haveConfig = false;
        String containerType = null;
        Long effectiveCpuCount = null, cpuQuota = null, cpuSlicePeriod = null, cpuShares = null;
        Long memoryLimit = null, memorySoftLimit = null, swapMemoryLimit = null;

        // Periodic cgroup counters are cumulative-since-container-start; take the
        // first and last snapshot by timestamp and use the window delta.
        var throttle = new Counters();   // elapsed, throttled (slices), throttledTime (nanos)
        var cpuUsage = new Counters();   // cpuTime, userTime, systemTime (nanos)
        var memFail = new Counters();    // failCount (cumulative)
        long peakMemoryUsage = -1;
        long peakSwapUsage = -1;

        try (var rf = new RecordingFile(path)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String type = e.getEventType().getName();
                switch (type) {
                    case "jdk.ContainerConfiguration" -> {
                        if (!haveConfig) {
                            haveConfig = true;
                            containerType = tryGetString(e, "containerType");
                            effectiveCpuCount = tryGetLong(e, "effectiveCpuCount");
                            cpuQuota = tryGetLong(e, "cpuQuota");
                            cpuSlicePeriod = tryGetLong(e, "cpuSlicePeriod");
                            cpuShares = tryGetLong(e, "cpuShares");
                            memoryLimit = tryGetLong(e, "memoryLimit");
                            memorySoftLimit = tryGetLong(e, "memorySoftLimit");
                            swapMemoryLimit = tryGetLong(e, "swapMemoryLimit");
                        }
                    }
                    case "jdk.ContainerCPUThrottling" -> {
                        Long elapsed = tryGetLong(e, "cpuElapsedSlices");
                        Long throttled = tryGetLong(e, "cpuThrottledSlices");
                        Long throttledTime = tryGetLong(e, "cpuThrottledTime");
                        throttle.observe(e.getStartTime(), elapsed, throttled, throttledTime);
                    }
                    case "jdk.ContainerCPUUsage" -> {
                        Long cpuTime = tryGetLong(e, "cpuTime");
                        Long userTime = tryGetLong(e, "cpuUserTime");
                        Long systemTime = tryGetLong(e, "cpuSystemTime");
                        cpuUsage.observe(e.getStartTime(), cpuTime, userTime, systemTime);
                    }
                    case "jdk.ContainerMemoryUsage" -> {
                        Long failCount = tryGetLong(e, "memoryFailCount");
                        memFail.observe(e.getStartTime(), failCount, null, null);
                        Long usage = tryGetLong(e, "memoryUsage");
                        if (usage != null && usage > peakMemoryUsage) peakMemoryUsage = usage;
                        Long swap = tryGetLong(e, "swapMemoryUsage");
                        if (swap != null && swap > peakSwapUsage) peakSwapUsage = swap;
                    }
                    default -> {}
                }
            }
        }

        boolean present = haveConfig || throttle.count > 0 || cpuUsage.count > 0 || memFail.count > 0;

        var result = new JSONObject();
        var recording = new JSONObject();
        recording.put("path", path.toAbsolutePath().toString());
        result.put("recording", recording);

        if (!present) {
            result.put("container_events_present", false);
            result.put("reason",
                    "No jdk.Container* events in this recording. The JVM was not running inside a "
                            + "cgroup with limits (bare-metal or unconstrained host), the JDK predates "
                            + "container event support (<17), or the events were disabled. Absence of "
                            + "these events is not evidence of a healthy container — it means the cgroup "
                            + "view is unavailable. Re-run inside the limited container (Docker --cpus/"
                            + "--memory or a K8s Pod with resources.limits) for K8s-aware diagnostics.");
            result.put("container_config", JSONObject.NULL);
            result.put("cpu_throttling", JSONObject.NULL);
            result.put("cpu_usage", JSONObject.NULL);
            result.put("memory_pressure", JSONObject.NULL);
            result.put("declared_vs_observed", JSONObject.NULL);
            result.put("signals", new JSONObject()
                    .put("container_events_present", false)
                    .put("cpu_throttling_high", JSONObject.NULL)
                    .put("memory_fail_count_present", JSONObject.NULL)
                    .put("declared_limit_mismatch", JSONObject.NULL)
                    .put("cpu_overcommit", JSONObject.NULL));
            return result;
        }
        result.put("container_events_present", true);

        // --- container_config ---
        Double cpuLimitCores = null;
        if (cpuQuota != null && cpuQuota > 0 && cpuSlicePeriod != null && cpuSlicePeriod > 0) {
            cpuLimitCores = (double) cpuQuota / cpuSlicePeriod;
        }
        Long memLimitMb = limitToMb(memoryLimit);
        var config = new JSONObject();
        config.put("type", orNull(containerType));
        config.put("effective_cpu_count", orNullLong(effectiveCpuCount));
        config.put("cpu_quota", cpuQuota == null ? JSONObject.NULL
                : (cpuQuota <= 0 ? JSONObject.NULL : cpuQuota));
        config.put("cpu_slice_period", orNullLong(cpuSlicePeriod));
        config.put("cpu_limit_cores", cpuLimitCores == null ? JSONObject.NULL : round2(cpuLimitCores));
        config.put("cpu_shares", cpuShares == null || cpuShares <= 0 ? JSONObject.NULL : cpuShares);
        config.put("memory_limit_mb", memLimitMb == null ? JSONObject.NULL : memLimitMb);
        config.put("memory_soft_limit_mb", orNullMb(limitToMb(memorySoftLimit)));
        config.put("swap_limit_mb", orNullMb(limitToMb(swapMemoryLimit)));
        result.put("container_config", config);

        // --- cpu_throttling (window deltas of cumulative slice counters) ---
        long elapsedSlices = throttle.windowDelta(0);
        long throttledSlices = throttle.windowDelta(1);
        long throttledTimeNanos = throttle.windowDelta(2);
        double throttledPct = elapsedSlices > 0 ? 100.0 * throttledSlices / elapsedSlices : 0.0;
        boolean throttlingHigh = throttledPct >= 10.0;
        var cpuThrottling = new JSONObject();
        if (throttle.count == 0) {
            cpuThrottling.put("available", false);
        } else {
            cpuThrottling.put("available", true);
            cpuThrottling.put("elapsed_slices", elapsedSlices);
            cpuThrottling.put("throttled_slices", throttledSlices);
            cpuThrottling.put("throttled_pct", round2(throttledPct));
            cpuThrottling.put("throttled_time_ms", round2(throttledTimeNanos / 1_000_000.0));
            cpuThrottling.put("verdict", throttleVerdict(throttledPct));
        }
        result.put("cpu_throttling", cpuThrottling);

        // --- cpu_usage (avg cores = consumed cpu-time / wall-time over the window) ---
        var cpuUsageJson = new JSONObject();
        Double avgCores = null;
        Double utilOfLimitPct = null;
        if (cpuUsage.count >= 2 && cpuUsage.windowNanos() > 0) {
            avgCores = (double) cpuUsage.windowDelta(0) / cpuUsage.windowNanos();
            double basis = cpuLimitCores != null ? cpuLimitCores
                    : (effectiveCpuCount != null && effectiveCpuCount > 0 ? effectiveCpuCount : 0.0);
            if (basis > 0) utilOfLimitPct = 100.0 * avgCores / basis;
        }
        cpuUsageJson.put("available", avgCores != null);
        cpuUsageJson.put("avg_cores_used", avgCores == null ? JSONObject.NULL : round2(avgCores));
        cpuUsageJson.put("utilization_pct_of_limit",
                utilOfLimitPct == null ? JSONObject.NULL : round1(utilOfLimitPct));
        result.put("cpu_usage", cpuUsageJson);

        // --- memory_pressure ---
        long failCount = memFail.windowDelta(0);
        boolean failPresent = failCount > 0;
        Double memUtilPct = null;
        if (peakMemoryUsage >= 0 && memLimitMb != null && memLimitMb > 0) {
            memUtilPct = 100.0 * (peakMemoryUsage / (1024.0 * 1024.0)) / memLimitMb;
        }
        var memoryPressure = new JSONObject();
        if (memFail.count == 0 && peakMemoryUsage < 0) {
            memoryPressure.put("available", false);
        } else {
            memoryPressure.put("available", true);
            memoryPressure.put("peak_usage_mb",
                    peakMemoryUsage < 0 ? JSONObject.NULL : round1(peakMemoryUsage / (1024.0 * 1024.0)));
            memoryPressure.put("utilization_pct", memUtilPct == null ? JSONObject.NULL : round1(memUtilPct));
            memoryPressure.put("fail_count", failCount);
            memoryPressure.put("swap_usage_mb",
                    peakSwapUsage < 0 ? JSONObject.NULL : round1(peakSwapUsage / (1024.0 * 1024.0)));
            memoryPressure.put("verdict", memoryVerdict(memUtilPct, failPresent));
        }
        result.put("memory_pressure", memoryPressure);

        // --- declared_vs_observed cross-check ---
        var declared = new JSONObject();
        Boolean cpuMismatch = null;
        Boolean memMismatch = null;
        if (declaredCpu != null && cpuLimitCores != null) {
            cpuMismatch = relativeDiff(declaredCpu, cpuLimitCores) > 0.10;
        }
        if (declaredMemMb != null && memLimitMb != null) {
            memMismatch = relativeDiff(declaredMemMb, memLimitMb) > 0.10;
        }
        declared.put("declared_cpu_cores", declaredCpu == null ? JSONObject.NULL : round2(declaredCpu));
        declared.put("observed_cpu_limit_cores", cpuLimitCores == null ? JSONObject.NULL : round2(cpuLimitCores));
        declared.put("cpu_mismatch", cpuMismatch == null ? JSONObject.NULL : cpuMismatch);
        declared.put("declared_memory_mb", declaredMemMb == null ? JSONObject.NULL : declaredMemMb);
        declared.put("observed_memory_limit_mb", memLimitMb == null ? JSONObject.NULL : memLimitMb);
        declared.put("memory_mismatch", memMismatch == null ? JSONObject.NULL : memMismatch);
        result.put("declared_vs_observed", declared);

        // --- signals ---
        boolean mismatch = Boolean.TRUE.equals(cpuMismatch) || Boolean.TRUE.equals(memMismatch);
        boolean overcommit = throttlingHigh
                && utilOfLimitPct != null && utilOfLimitPct >= 90.0;
        var signals = new JSONObject();
        signals.put("container_events_present", true);
        signals.put("cpu_throttling_high", throttle.count > 0 ? throttlingHigh : JSONObject.NULL);
        signals.put("memory_fail_count_present", memFail.count > 0 ? failPresent : JSONObject.NULL);
        signals.put("declared_limit_mismatch",
                (cpuMismatch == null && memMismatch == null) ? JSONObject.NULL : mismatch);
        signals.put("cpu_overcommit", overcommit);
        result.put("signals", signals);

        return result;
    }

    // ----- pure helpers (unit-testable without a JFR) -----

    static String throttleVerdict(double throttledPct) {
        if (throttledPct >= 10.0) return "high";
        if (throttledPct >= 1.0) return "elevated";
        return "negligible";
    }

    static String memoryVerdict(Double utilPct, boolean failPresent) {
        if (failPresent) return "exceeded";
        if (utilPct == null) return "unknown";
        if (utilPct >= 100.0) return "exceeded";
        if (utilPct >= 90.0) return "at_risk";
        if (utilPct >= 75.0) return "tight";
        return "safe";
    }

    static double relativeDiff(double a, double b) {
        if (b == 0.0) return a == 0.0 ? 0.0 : 1.0;
        return Math.abs(a - b) / Math.abs(b);
    }

    /** Parse a Kubernetes-style CPU quantity ("1", "500m", "0.5", "1500m") to cores. */
    static Double parseCpuToCores(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        try {
            if (v.endsWith("m") || v.endsWith("M")) {
                long milli = Long.parseLong(v.substring(0, v.length() - 1).trim());
                return milli <= 0 ? null : milli / 1000.0;
            }
            double cores = Double.parseDouble(v);
            return cores <= 0 ? null : cores;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Convert a cgroup byte limit to MB, returning null for "unlimited" sentinels. */
    static Long limitToMb(Long bytes) {
        if (bytes == null || bytes <= 0 || bytes >= UNLIMITED_BYTES) return null;
        return bytes / (1024L * 1024L);
    }

    static Object orNullMb(Long mb) { return mb == null ? JSONObject.NULL : mb; }
    static Object orNull(String v) { return v == null ? JSONObject.NULL : v; }
    static Object orNullLong(Long v) { return v == null ? JSONObject.NULL : v; }

    static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    static String tryGetString(RecordedEvent e, String field) {
        try {
            if (e.hasField(field)) return e.getString(field);
        } catch (RuntimeException ignored) {}
        return null;
    }

    static Long tryGetLong(RecordedEvent e, String field) {
        try {
            if (e.hasField(field)) return e.getLong(field);
        } catch (RuntimeException ignored) {}
        return null;
    }

    /**
     * Tracks up to three cumulative counters across periodic events, keyed by the
     * earliest and latest event timestamps, so the window delta (last − first)
     * reflects what happened during the recording, not since container start.
     */
    static final class Counters {
        long count;
        Instant firstTs, lastTs;
        final long[] first = new long[3];
        final long[] last = new long[3];

        void observe(Instant ts, Long a, Long b, Long c) {
            if (ts == null) return;
            long[] vals = { a == null ? 0L : a, b == null ? 0L : b, c == null ? 0L : c };
            count++;
            if (firstTs == null || ts.isBefore(firstTs)) {
                firstTs = ts;
                System.arraycopy(vals, 0, first, 0, 3);
            }
            if (lastTs == null || ts.isAfter(lastTs)) {
                lastTs = ts;
                System.arraycopy(vals, 0, last, 0, 3);
            }
        }

        /** Window delta for counter i; falls back to the single absolute value when only one sample exists. */
        long windowDelta(int i) {
            if (count == 0) return 0L;
            if (count == 1) return last[i];
            long d = last[i] - first[i];
            return d < 0 ? last[i] : d; // guard against counter reset / reordering
        }

        long windowNanos() {
            if (firstTs == null || lastTs == null) return 0L;
            return Duration.between(firstTs, lastTs).toNanos();
        }
    }
}
