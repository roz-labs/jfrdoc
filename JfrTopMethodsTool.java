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
import java.util.Set;

import airhacks.zsmith.tools.control.Tool;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import org.json.JSONArray;
import org.json.JSONObject;

public class JfrTopMethodsTool implements Tool {

    static final int DEFAULT_TOP_N = 20;
    static final int MIN_TOP_N = 1;
    static final int MAX_TOP_N = 100;
    static final String DEFAULT_FRAMEWORK = "other";

    static final Set<String> ALWAYS_FRAMEWORK_PREFIXES = Set.of(
            "org.springframework.",
            "org.apache.",
            "org.hibernate.",
            "org.eclipse.",
            "io.quarkus.",
            "io.smallrye.",
            "io.vertx.",
            "io.netty.",
            "jakarta.",
            "javax.",
            "com.fasterxml.",
            "com.google.",
            "reactor.",
            "kotlin.",
            "scala."
    );

    static final Set<String> SPRING_EXTRA_PREFIXES = Set.of(
            "org.thymeleaf.",
            "org.slf4j.",
            "ch.qos.logback."
    );

    static final Set<String> QUARKUS_EXTRA_PREFIXES = Set.of(
            "org.jboss.",
            "io.agroal.",
            "org.graalvm."
    );

    static final Set<String> JDK_PREFIXES = Set.of(
            "java.",
            "jdk.",
            "sun.",
            "com.sun."
    );

    @Override
    public String toolName() {
        return "jfr_top_methods";
    }

    @Override
    public String description() {
        return "Aggregates jdk.ExecutionSample events from a JFR file to identify CPU hotspots. "
                + "Returns the top N methods by sample count with category (user_code / framework / jdk / native), "
                + "dominant caller, and category breakdown over total samples. "
                + "Use this to answer 'where is CPU being spent?'";
    }

    enum Field { path, top_n, framework }

    @Override
    public String inputSchema() {
        return Tool.schema(
                Prop.string(Field.path,
                        "Absolute or relative filesystem path to the .jfr file"),
                Prop.integer(Field.top_n,
                        "Number of top methods to return (clamped to [1,100]; default 20)").optional(),
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

        try {
            return analyze(path, topN, framework).toString(2);
        } catch (IOException e) {
            return "Error: Could not read JFR file: " + e.getMessage();
        }
    }

    static JSONObject analyze(Path path, int topN, String framework) throws IOException {
        var extraFrameworkPrefixes = extraFrameworkPrefixes(framework);

        var methodAgg = new LinkedHashMap<String, MethodStats>();
        var categoryCounts = new HashMap<String, Long>();
        for (String c : List.of("user_code", "framework", "jdk", "native")) {
            categoryCounts.put(c, 0L);
        }

        long totalSamples = 0;
        long withTrace = 0;
        long skippedNoTrace = 0;
        Instant earliest = null;
        Instant latest = null;

        try (var rf = new RecordingFile(path)) {
            while (rf.hasMoreEvents()) {
                var e = rf.readEvent();
                if (!e.getEventType().getName().equals("jdk.ExecutionSample")) continue;

                totalSamples++;
                var ts = e.getStartTime();
                if (earliest == null || ts.isBefore(earliest)) earliest = ts;
                if (latest == null || ts.isAfter(latest)) latest = ts;

                RecordedStackTrace trace = e.getStackTrace();
                if (trace == null) {
                    skippedNoTrace++;
                    categoryCounts.merge("native", 1L, Long::sum);
                    var key = "<no-stack-trace>";
                    var stats = methodAgg.computeIfAbsent(key, k -> new MethodStats("native"));
                    stats.samples++;
                    continue;
                }
                var frames = trace.getFrames();
                if (frames.isEmpty()) {
                    skippedNoTrace++;
                    categoryCounts.merge("native", 1L, Long::sum);
                    var key = "<no-stack-trace>";
                    var stats = methodAgg.computeIfAbsent(key, k -> new MethodStats("native"));
                    stats.samples++;
                    continue;
                }
                withTrace++;

                RecordedFrame top = frames.get(0);
                String methodKey;
                String category;
                if (!top.isJavaFrame()) {
                    methodKey = "<native frame>";
                    category = "native";
                } else {
                    RecordedMethod method = top.getMethod();
                    if (method == null) {
                        methodKey = "<unknown method>";
                        category = "native";
                    } else {
                        RecordedClass cls = method.getType();
                        if (cls == null || cls.getName() == null) {
                            methodKey = "<unknown class>." + method.getName();
                            category = "native";
                        } else {
                            String fqcn = cls.getName();
                            String methodName = method.getName();
                            int line = top.getLineNumber();
                            methodKey = fqcn + "." + methodName + (line >= 0 ? ":" + line : "");
                            category = categorize(fqcn, extraFrameworkPrefixes);
                        }
                    }
                }

                categoryCounts.merge(category, 1L, Long::sum);
                var stats = methodAgg.computeIfAbsent(methodKey, k -> new MethodStats(category));
                stats.samples++;

                String callerSig = callerSignature(frames);
                if (callerSig != null) {
                    stats.callerCounts.merge(callerSig, 1L, Long::sum);
                }
            }
        }

        var result = new JSONObject();

        var recording = new JSONObject();
        recording.put("path", path.toAbsolutePath().toString());
        if (earliest != null && latest != null) {
            recording.put("duration_seconds",
                    round1(Duration.between(earliest, latest).toMillis() / 1000.0));
        } else {
            recording.put("duration_seconds", 0.0);
        }
        result.put("recording", recording);

        var samples = new JSONObject();
        samples.put("total", totalSamples);
        samples.put("with_stack_trace", withTrace);
        samples.put("skipped_no_trace", skippedNoTrace);
        result.put("execution_samples", samples);

        var categories = new JSONObject();
        for (String c : List.of("user_code", "framework", "jdk", "native")) {
            long count = categoryCounts.getOrDefault(c, 0L);
            var co = new JSONObject();
            co.put("samples", count);
            co.put("pct", totalSamples == 0 ? 0.0 : round1(100.0 * count / totalSamples));
            categories.put(c, co);
        }
        result.put("categories", categories);

        var sorted = new ArrayList<>(methodAgg.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, MethodStats>>comparingLong(en -> en.getValue().samples).reversed());

        var topMethods = new JSONArray();
        int rank = 1;
        for (var en : sorted) {
            if (rank > topN) break;
            var stats = en.getValue();
            var entry = new JSONObject();
            entry.put("rank", rank);
            entry.put("method", en.getKey());
            entry.put("category", stats.category);
            entry.put("samples", stats.samples);
            entry.put("pct_of_total", totalSamples == 0 ? 0.0 : round1(100.0 * stats.samples / totalSamples));

            String topCaller = null;
            long topCallerCount = 0;
            for (var ce : stats.callerCounts.entrySet()) {
                if (ce.getValue() > topCallerCount) {
                    topCallerCount = ce.getValue();
                    topCaller = ce.getKey();
                }
            }
            if (topCaller != null) {
                entry.put("top_caller", topCaller);
                entry.put("top_caller_share_pct",
                        stats.samples == 0 ? 0.0 : round1(100.0 * topCallerCount / stats.samples));
            } else {
                entry.put("top_caller", JSONObject.NULL);
                entry.put("top_caller_share_pct", 0.0);
            }

            topMethods.put(entry);
            rank++;
        }
        result.put("top_methods", topMethods);

        result.put("framework_used_for_categorization", framework);
        return result;
    }

    static String callerSignature(List<RecordedFrame> frames) {
        if (frames.size() < 2) return null;
        RecordedFrame caller = frames.get(1);
        if (!caller.isJavaFrame()) return null;
        var cm = caller.getMethod();
        if (cm == null) return null;
        var ct = cm.getType();
        if (ct == null || ct.getName() == null) return null;
        return ct.getName() + "." + cm.getName();
    }

    static String categorize(String fqcn, Set<String> extraFrameworkPrefixes) {
        if (fqcn == null) return "native";
        if (startsWithAny(fqcn, JDK_PREFIXES)) return "jdk";
        if (startsWithAny(fqcn, ALWAYS_FRAMEWORK_PREFIXES)) return "framework";
        if (startsWithAny(fqcn, extraFrameworkPrefixes)) return "framework";
        return "user_code";
    }

    static boolean startsWithAny(String s, Set<String> prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) return true;
        }
        return false;
    }

    static Set<String> extraFrameworkPrefixes(String framework) {
        return switch (framework) {
            case "spring" -> SPRING_EXTRA_PREFIXES;
            case "quarkus" -> QUARKUS_EXTRA_PREFIXES;
            default -> Set.of();
        };
    }

    static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static final class MethodStats {
        final String category;
        long samples;
        final Map<String, Long> callerCounts = new HashMap<>();

        MethodStats(String category) {
            this.category = category;
        }
    }
}
