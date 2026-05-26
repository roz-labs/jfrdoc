import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import airhacks.zsmith.tools.control.Tool;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import org.json.JSONArray;
import org.json.JSONObject;

public class JfrAllocationTool implements Tool {

    static final int DEFAULT_TOP_N = 20;
    static final int MIN_TOP_N = 1;
    static final int MAX_TOP_N = 100;
    static final int OUTSIDE_TLAB_TOP_CLASSES = 5;
    static final String DEFAULT_FRAMEWORK = "other";

    @Override
    public String toolName() {
        return "jfr_allocation";
    }

    @Override
    public String description() {
        return "Analyzes object allocation hotspots from jdk.ObjectAllocationSample events. "
                + "Returns: estimated allocation rate (MB/s), top allocated classes by estimated bytes, "
                + "top allocation sites (methods doing the most allocation), and allocation breakdown "
                + "by category (user_code / framework / jdk / native). "
                + "Call this when summary.notable_events_present.allocation is true.";
    }

    enum Field { path, top_n, framework }

    @Override
    public String inputSchema() {
        return Tool.schema(
                Prop.string(Field.path,
                        "Absolute or relative filesystem path to the .jfr file"),
                Prop.integer(Field.top_n,
                        "Number of top classes/sites to return (clamped to [1,100]; default 20)").optional(),
                Prop.stringEnum(Field.framework,
                        "Framework hint for categorization (default 'other')",
                        "spring", "quarkus", "other").optional()
        );
    }

    @Override
    public String execute(JSONObject input) {
        if (!input.has(Field.path.name())) {
            return "Error: Missing required parameter: path";
        }
        var path = Path.of(input.getString(Field.path.name()));
        if (!Files.exists(path)) {
            return "Error: JFR file not found: " + path;
        }
        if (!Files.isRegularFile(path)) {
            return "Error: Not a regular file: " + path;
        }

        int topN = DEFAULT_TOP_N;
        if (input.has(Field.top_n.name())) {
            topN = clamp(input.getInt(Field.top_n.name()), MIN_TOP_N, MAX_TOP_N);
        }

        String framework = DEFAULT_FRAMEWORK;
        if (input.has(Field.framework.name())) {
            String raw = input.getString(Field.framework.name());
            if (raw != null && !raw.isEmpty()) framework = raw;
        }

        FrameworkCategorizer categorizer;
        try {
            categorizer = FrameworkCategorizer.forFramework(framework);
        } catch (IOException e) {
            return "Error: Could not load categorization rules: " + e.getMessage();
        }

        try {
            return analyze(path, topN, framework, categorizer).toString(2);
        } catch (IOException e) {
            return "Error: Could not read JFR file: " + e.getMessage();
        }
    }

    static JSONObject analyze(Path path, int topN, String framework, FrameworkCategorizer categorizer) throws IOException {
        var classAgg = new LinkedHashMap<String, ClassStats>();
        var siteAgg = new LinkedHashMap<String, SiteStats>();
        var categoryAgg = new HashMap<String, long[]>();
        for (String c : List.of("user_code", "framework", "jdk", "native")) {
            categoryAgg.put(c, new long[2]);
        }

        var outsideTlabByClass = new LinkedHashMap<String, long[]>();
        long outsideTlabEvents = 0;

        long totalSamples = 0;
        long withTrace = 0;
        long skippedNoTrace = 0;
        long totalBytes = 0;
        Instant earliest = null;
        Instant latest = null;

        try (var rf = new RecordingFile(path)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String type = e.getEventType().getName();

                if ("jdk.ObjectAllocationSample".equals(type)) {
                    totalSamples++;
                    long weight = tryGetLong(e, "weight", 0L);
                    totalBytes += weight;

                    var ts = e.getStartTime();
                    if (earliest == null || ts.isBefore(earliest)) earliest = ts;
                    if (latest == null || ts.isAfter(latest)) latest = ts;

                    String className = prettyClassName(classNameFromEvent(e));
                    var cstats = classAgg.computeIfAbsent(className, k -> new ClassStats());
                    cstats.samples++;
                    cstats.totalBytes += weight;

                    RecordedStackTrace trace = e.getStackTrace();
                    List<RecordedFrame> frames = trace == null ? null : trace.getFrames();
                    if (trace == null || frames == null || frames.isEmpty()) {
                        skippedNoTrace++;
                        bumpCategory(categoryAgg, "native", weight);
                        recordSite(siteAgg, "<no-stack-trace>", "native", weight, className);
                        continue;
                    }
                    withTrace++;

                    RecordedFrame top = frames.get(0);
                    String siteKey;
                    String category;
                    if (!top.isJavaFrame()) {
                        siteKey = "<native frame>";
                        category = "native";
                    } else {
                        RecordedMethod method = top.getMethod();
                        if (method == null) {
                            siteKey = "<unknown method>";
                            category = "native";
                        } else {
                            RecordedClass cls = method.getType();
                            if (cls == null || cls.getName() == null) {
                                siteKey = "<unknown class>." + method.getName();
                                category = "native";
                            } else {
                                String fqcn = cls.getName();
                                String methodName = method.getName();
                                int line = top.getLineNumber();
                                siteKey = fqcn + "." + methodName + (line >= 0 ? ":" + line : "");
                                category = categorizer.categorize(fqcn);
                            }
                        }
                    }

                    bumpCategory(categoryAgg, category, weight);
                    recordSite(siteAgg, siteKey, category, weight, className);
                } else if ("jdk.ObjectAllocationOutsideTLAB".equals(type)) {
                    outsideTlabEvents++;
                    long size = tryGetLong(e, "allocationSize", 0L);
                    String className = prettyClassName(classNameFromEvent(e));
                    long[] ostats = outsideTlabByClass.computeIfAbsent(className, k -> new long[2]);
                    ostats[0]++;
                    ostats[1] += size;
                }
            }
        }

        double durationSeconds = 0.0;
        if (earliest != null && latest != null) {
            durationSeconds = Duration.between(earliest, latest).toMillis() / 1000.0;
        }
        double totalMb = toMb(totalBytes);
        double mbPerSecond = durationSeconds <= 0 ? 0.0 : totalMb / durationSeconds;

        var result = new JSONObject();

        var recording = new JSONObject();
        recording.put("path", path.toAbsolutePath().toString());
        recording.put("duration_seconds", round1(durationSeconds));
        result.put("recording", recording);

        var samples = new JSONObject();
        samples.put("total", totalSamples);
        samples.put("with_stack_trace", withTrace);
        samples.put("skipped_no_trace", skippedNoTrace);
        result.put("samples", samples);

        var rate = new JSONObject();
        rate.put("total_estimated_bytes", totalBytes);
        rate.put("total_estimated_mb", round1(totalMb));
        rate.put("mb_per_second", round1(mbPerSecond));
        result.put("estimated_allocation_rate", rate);

        var categories = new JSONObject();
        for (String c : List.of("user_code", "framework", "jdk", "native")) {
            long[] arr = categoryAgg.get(c);
            var co = new JSONObject();
            co.put("samples", arr[0]);
            co.put("estimated_mb", round1(toMb(arr[1])));
            co.put("pct_of_bytes", totalBytes == 0 ? 0.0 : round1(100.0 * arr[1] / totalBytes));
            categories.put(c, co);
        }
        result.put("categories", categories);

        result.put("top_allocated_classes", topClasses(classAgg, topN, totalBytes));
        result.put("top_allocation_sites", topSites(siteAgg, topN, totalBytes));
        result.put("large_object_allocations", largeObjectAllocations(outsideTlabEvents, outsideTlabByClass));
        result.put("framework_used_for_categorization", framework);
        return result;
    }

    static JSONArray topClasses(Map<String, ClassStats> classAgg, int topN, long totalBytes) {
        var list = new ArrayList<>(classAgg.entrySet());
        list.sort(Comparator.<Map.Entry<String, ClassStats>>comparingLong(en -> en.getValue().totalBytes).reversed());
        var out = new JSONArray();
        int rank = 1;
        for (var en : list) {
            if (rank > topN) break;
            var s = en.getValue();
            var entry = new JSONObject();
            entry.put("rank", rank);
            entry.put("class", en.getKey());
            entry.put("samples", s.samples);
            entry.put("estimated_mb", round1(toMb(s.totalBytes)));
            entry.put("pct_of_bytes", totalBytes == 0 ? 0.0 : round1(100.0 * s.totalBytes / totalBytes));
            out.put(entry);
            rank++;
        }
        return out;
    }

    static JSONArray topSites(Map<String, SiteStats> siteAgg, int topN, long totalBytes) {
        var list = new ArrayList<>(siteAgg.entrySet());
        list.sort(Comparator.<Map.Entry<String, SiteStats>>comparingLong(en -> en.getValue().totalBytes).reversed());
        var out = new JSONArray();
        int rank = 1;
        for (var en : list) {
            if (rank > topN) break;
            var s = en.getValue();
            var entry = new JSONObject();
            entry.put("rank", rank);
            entry.put("method", en.getKey());
            entry.put("category", s.category);
            entry.put("samples", s.samples);
            entry.put("estimated_mb", round1(toMb(s.totalBytes)));
            entry.put("pct_of_bytes", totalBytes == 0 ? 0.0 : round1(100.0 * s.totalBytes / totalBytes));
            entry.put("top_class_allocated", dominantClass(s.classWeights));
            out.put(entry);
            rank++;
        }
        return out;
    }

    static JSONObject largeObjectAllocations(long outsideTlabEvents, Map<String, long[]> outsideTlabByClass) {
        var obj = new JSONObject();
        obj.put("outside_tlab_events", outsideTlabEvents);
        var list = new ArrayList<>(outsideTlabByClass.entrySet());
        list.sort(Comparator.<Map.Entry<String, long[]>>comparingLong(en -> en.getValue()[1]).reversed());
        var arr = new JSONArray();
        int i = 0;
        for (var en : list) {
            if (i++ >= OUTSIDE_TLAB_TOP_CLASSES) break;
            long[] v = en.getValue();
            arr.put(new JSONObject()
                    .put("class", en.getKey())
                    .put("events", v[0])
                    .put("total_bytes", v[1])
                    .put("total_mb", round1(toMb(v[1]))));
        }
        obj.put("top_classes", arr);
        return obj;
    }

    static Object dominantClass(Map<String, Long> classWeights) {
        String top = null;
        long topBytes = -1;
        for (var en : classWeights.entrySet()) {
            if (en.getValue() > topBytes) {
                topBytes = en.getValue();
                top = en.getKey();
            }
        }
        return top == null ? JSONObject.NULL : top;
    }

    static void recordSite(Map<String, SiteStats> siteAgg, String key, String category, long weight, String className) {
        var s = siteAgg.computeIfAbsent(key, k -> new SiteStats(category));
        s.samples++;
        s.totalBytes += weight;
        s.classWeights.merge(className, weight, Long::sum);
    }

    static void bumpCategory(Map<String, long[]> agg, String cat, long bytes) {
        long[] arr = agg.computeIfAbsent(cat, k -> new long[2]);
        arr[0]++;
        arr[1] += bytes;
    }

    static String classNameFromEvent(RecordedEvent e) {
        try {
            if (e.hasField("objectClass")) {
                RecordedClass cls = e.getClass("objectClass");
                if (cls != null) {
                    String n = cls.getName();
                    if (n != null) return n;
                }
            }
        } catch (RuntimeException ignored) {}
        return "<unknown>";
    }

    static String prettyClassName(String raw) {
        if (raw == null || raw.isEmpty()) return "<unknown>";
        int dims = 0;
        int i = 0;
        while (i < raw.length() && raw.charAt(i) == '[') {
            dims++;
            i++;
        }
        if (dims == 0) return raw;
        if (i >= raw.length()) return raw;
        char c = raw.charAt(i);
        String base = switch (c) {
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'D' -> "double";
            case 'F' -> "float";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'S' -> "short";
            case 'Z' -> "boolean";
            case 'V' -> "void";
            case 'L' -> {
                int semi = raw.indexOf(';', i + 1);
                if (semi < 0) yield null;
                yield raw.substring(i + 1, semi).replace('/', '.');
            }
            default -> null;
        };
        if (base == null) return raw;
        var sb = new StringBuilder(base);
        for (int d = 0; d < dims; d++) sb.append("[]");
        return sb.toString();
    }

    static long tryGetLong(RecordedEvent e, String field, long fallback) {
        try {
            if (e.hasField(field)) return e.getLong(field);
        } catch (RuntimeException ignored) {}
        return fallback;
    }

    static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    static double round1(double v) { return Math.round(v * 10.0) / 10.0; }

    static double toMb(double bytes) { return bytes / (1024.0 * 1024.0); }

    static final class ClassStats {
        long samples;
        long totalBytes;
    }

    static final class SiteStats {
        final String category;
        long samples;
        long totalBytes;
        final Map<String, Long> classWeights = new HashMap<>();

        SiteStats(String category) {
            this.category = category;
        }
    }
}
