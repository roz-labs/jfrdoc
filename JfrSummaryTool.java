import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import airhacks.zsmith.tools.control.Tool;
import jdk.jfr.consumer.RecordingFile;
import org.json.JSONArray;
import org.json.JSONObject;

public class JfrSummaryTool implements Tool {

    @Override
    public String toolName() {
        return "jfr_summary";
    }

    @Override
    public String description() {
        return "Reads a JVM Flight Recorder (.jfr) file and returns a high-level summary: "
                + "recording duration, JVM info, top event types by frequency, and total event count. "
                + "Always call this first when analyzing a JFR file — never request raw events.";
    }

    enum Field { path }

    @Override
    public String inputSchema() {
        return Tool.schema(Prop.string(Field.path,
                "Absolute or relative filesystem path to the .jfr file"));
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
        try {
            return summarize(path).toString(2);
        } catch (IOException e) {
            return "Error: Could not read JFR file: " + e.getMessage();
        }
    }

    static JSONObject summarize(Path path) throws IOException {
        var counts = new HashMap<String, Long>();
        long totalEvents = 0;
        Instant earliest = null;
        Instant latest = null;
        JSONObject jvmInfo = null;

        try (var rf = new RecordingFile(path)) {
            while (rf.hasMoreEvents()) {
                var e = rf.readEvent();
                var typeName = e.getEventType().getName();
                counts.merge(typeName, 1L, Long::sum);
                totalEvents++;

                var ts = e.getStartTime();
                if (earliest == null || ts.isBefore(earliest)) earliest = ts;
                if (latest == null || ts.isAfter(latest)) latest = ts;

                if (jvmInfo == null && "jdk.JVMInformation".equals(typeName)) {
                    jvmInfo = readJvmInfo(e);
                }
            }
        }

        var topEvents = new JSONArray();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .forEach(en -> topEvents.put(new JSONObject()
                        .put("type", en.getKey())
                        .put("count", en.getValue())));

        var summary = new JSONObject()
                .put("path", path.toString())
                .put("totalEvents", totalEvents)
                .put("distinctEventTypes", counts.size())
                .put("topEventTypes", topEvents);

        if (earliest != null) {
            summary.put("recordingStart", earliest.toString());
            summary.put("recordingEnd", latest.toString());
            double durationSec = Duration.between(earliest, latest).toMillis() / 1000.0;
            summary.put("recordingDurationSeconds", durationSec);
            summary.put("notable_events_present", notableEventsPresent(counts));
            summary.put("derived", derivedRates(counts, durationSec));
        }
        if (jvmInfo != null) {
            summary.put("jvm", jvmInfo);
        }
        return summary;
    }

    static JSONObject notableEventsPresent(Map<String, Long> counts) {
        return new JSONObject()
                .put("execution_samples", counts.getOrDefault("jdk.ExecutionSample", 0L) > 0)
                .put("gc", counts.keySet().stream().anyMatch(k -> k.startsWith("jdk.GC")))
                .put("allocation", counts.getOrDefault("jdk.ObjectAllocationSample", 0L) > 0
                        || counts.getOrDefault("jdk.ObjectAllocationInNewTLAB", 0L) > 0
                        || counts.getOrDefault("jdk.ObjectAllocationOutsideTLAB", 0L) > 0)
                .put("monitor_contention", counts.getOrDefault("jdk.JavaMonitorEnter", 0L) > 0)
                .put("thread_parking", counts.getOrDefault("jdk.ThreadPark", 0L) > 0)
                .put("io", counts.getOrDefault("jdk.FileRead", 0L) > 0
                        || counts.getOrDefault("jdk.FileWrite", 0L) > 0
                        || counts.getOrDefault("jdk.SocketRead", 0L) > 0
                        || counts.getOrDefault("jdk.SocketWrite", 0L) > 0)
                .put("exceptions", counts.getOrDefault("jdk.JavaExceptionThrow", 0L) > 0
                        || counts.getOrDefault("jdk.JavaErrorThrow", 0L) > 0);
    }

    static JSONObject derivedRates(Map<String, Long> counts, double durationSec) {
        var rates = new JSONObject();
        long execSamples = counts.getOrDefault("jdk.ExecutionSample", 0L);
        long exceptions = counts.getOrDefault("jdk.JavaExceptionThrow", 0L);
        rates.put("executionSamplesCount", execSamples);
        rates.put("javaExceptionThrowCount", exceptions);
        if (durationSec > 0) {
            rates.put("executionSamplesPerSecond", round1(execSamples / durationSec));
            rates.put("javaExceptionThrowPerSecond", round1(exceptions / durationSec));
        }
        return rates;
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static JSONObject readJvmInfo(jdk.jfr.consumer.RecordedEvent e) {
        var info = new JSONObject();
        putIfPresent(info, e, "jvmName", "jvmName");
        putIfPresent(info, e, "jvmVersion", "jvmVersion");
        putIfPresent(info, e, "jvmArguments", "jvmArguments");
        putIfPresent(info, e, "javaArguments", "javaArguments");
        if (e.hasField("jvmStartTime")) {
            var start = e.getLong("jvmStartTime");
            if (start > 0) {
                info.put("jvmStartTime", Instant.ofEpochMilli(start).toString());
            }
        }
        return info;
    }

    static void putIfPresent(JSONObject target, jdk.jfr.consumer.RecordedEvent e, String field, String jsonKey) {
        if (e.hasField(field)) {
            var v = e.getString(field);
            if (v != null && !v.isEmpty()) target.put(jsonKey, v);
        }
    }
}
