import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import airhacks.zsmith.agent.boundary.Agent;
import org.json.JSONObject;

record Config(String jfrFile, String memory, String cpu, String framework) {}

void main(String[] args) {
    if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
        System.out.print(usage());
        return;
    }

    String cmd = args[0];
    if (cmd.equals("debug-tool")) {
        runDebugTool(args);
        return;
    }
    if (!cmd.equals("analyze")) {
        System.err.println("Error: unknown command '" + cmd + "'");
        System.err.print(usage());
        System.exit(1);
    }

    if (args.length < 2) {
        System.err.println("Error: 'analyze' requires a <jfr-file> argument");
        System.err.print(usage());
        System.exit(1);
    }

    String jfrFile = args[1];
    String memory = null;
    String cpu = null;
    String framework = "other";

    int i = 2;
    while (i < args.length) {
        String flag = args[i];
        switch (flag) {
            case "--container-memory" -> {
                if (i + 1 >= args.length) {
                    System.err.println("Error: --container-memory requires a value");
                    System.err.print(usage());
                    System.exit(1);
                }
                memory = args[++i];
            }
            case "--container-cpu" -> {
                if (i + 1 >= args.length) {
                    System.err.println("Error: --container-cpu requires a value");
                    System.err.print(usage());
                    System.exit(1);
                }
                cpu = args[++i];
            }
            case "--framework" -> {
                if (i + 1 >= args.length) {
                    System.err.println("Error: --framework requires a value");
                    System.err.print(usage());
                    System.exit(1);
                }
                framework = args[++i];
            }
            default -> {
                System.err.println("Error: unknown flag '" + flag + "'");
                System.err.print(usage());
                System.exit(1);
            }
        }
        i++;
    }

    Config config = new Config(jfrFile, memory, cpu, framework);
    runAnalyze(config);
}

void runAnalyze(Config config) {
    Path jfrPath = Path.of(config.jfrFile());
    if (!Files.exists(jfrPath)) {
        System.err.println("Error: JFR file not found: " + config.jfrFile());
        System.exit(1);
    }
    if (!Files.isRegularFile(jfrPath)) {
        System.err.println("Error: Not a regular file: " + config.jfrFile());
        System.exit(1);
    }

    if (!apiKeyAvailable()) {
        System.err.println("""
                Error: anthropic.api.key not found.
                Please configure it in ~/.zsmith/app.properties:
                  anthropic.api.key=sk-ant-...
                Or pass via system property:
                  java -Danthropic.api.key=sk-ant-... ...""");
        System.exit(1);
    }

    System.setProperty("tools.permissions.default", "allow");

    String systemPrompt = SYSTEM_PROMPT
            .replace("{{jfr_path}}", jfrPath.toAbsolutePath().toString())
            .replace("{{framework}}", config.framework())
            .replace("{{container_memory}}", config.memory() == null ? "not specified" : config.memory())
            .replace("{{container_cpu}}", config.cpu() == null ? "not specified" : config.cpu());

    PrintStream originalOut = System.out;
    System.setOut(System.err);
    String report;
    try {
        var agent = new Agent("jfrdoc", systemPrompt)
                .withTools(new JfrSummaryTool(), new JfrTopMethodsTool());
        report = agent.act();
    } catch (RuntimeException e) {
        System.setOut(originalOut);
        System.err.println("Error: agent invocation failed: " + e.getMessage());
        System.exit(1);
        return;
    } finally {
        System.setOut(originalOut);
    }
    System.out.println(report);
}

boolean apiKeyAvailable() {
    if (System.getProperty("anthropic.api.key") != null) return true;
    Path props = Path.of(System.getProperty("user.home"), ".zsmith", "app.properties");
    if (!Files.exists(props)) return false;
    try {
        for (String line : Files.readAllLines(props)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) continue;
            if (trimmed.startsWith("anthropic.api.key") && trimmed.contains("=")) {
                String value = trimmed.substring(trimmed.indexOf('=') + 1).trim();
                if (!value.isEmpty()) return true;
            }
        }
    } catch (IOException ignored) {
    }
    return false;
}

void runDebugTool(String[] args) {
    if (args.length < 3) {
        System.err.println("Error: 'debug-tool' requires <tool-name> and tool args");
        System.err.println("Usage:");
        System.err.println("  jfrdoc debug-tool jfr-summary <jfr-file>");
        System.err.println("  jfrdoc debug-tool jfr-top-methods <jfr-file> [--top-n N] [--framework spring|quarkus|other]");
        System.exit(1);
    }
    String toolName = args[1];
    switch (toolName) {
        case "jfr-summary" -> {
            String jfrPath = args[2];
            var tool = new JfrSummaryTool();
            var input = new JSONObject().put("path", jfrPath);
            String result = tool.execute(input);
            System.out.println(result);
            if (result.startsWith("Error:")) System.exit(1);
        }
        case "jfr-top-methods" -> runJfrTopMethods(args);
        default -> {
            System.err.println("Error: unknown tool '" + toolName + "'");
            System.err.println("Available debug tools: jfr-summary, jfr-top-methods");
            System.exit(1);
        }
    }
}

void runJfrTopMethods(String[] args) {
    String jfrPath = args[2];
    Integer topN = null;
    String framework = null;

    int i = 3;
    while (i < args.length) {
        String flag = args[i];
        switch (flag) {
            case "--top-n" -> {
                if (i + 1 >= args.length) {
                    System.err.println("Error: --top-n requires a value");
                    System.exit(1);
                }
                try {
                    topN = Integer.parseInt(args[++i]);
                } catch (NumberFormatException ex) {
                    System.err.println("Error: --top-n must be an integer");
                    System.exit(1);
                }
            }
            case "--framework" -> {
                if (i + 1 >= args.length) {
                    System.err.println("Error: --framework requires a value");
                    System.exit(1);
                }
                framework = args[++i];
            }
            default -> {
                System.err.println("Error: unknown flag '" + flag + "' for jfr-top-methods");
                System.exit(1);
            }
        }
        i++;
    }

    var tool = new JfrTopMethodsTool();
    var input = new JSONObject().put("path", jfrPath);
    if (topN != null) input.put("top_n", topN.intValue());
    if (framework != null) input.put("framework", framework);
    String result = tool.execute(input);
    System.out.println(result);
    if (result.startsWith("Error:")) System.exit(1);
}

String usage() {
    return """
            jfrdoc - AI-powered JFR analyzer

            Usage:
              jfrdoc                       Show this help
              jfrdoc --help | -h           Show this help
              jfrdoc analyze <jfr-file> [flags]

            Flags (for analyze):
              --container-memory <value>   Container memory limit (e.g. 2Gi, 512Mi)
              --container-cpu <value>      Container CPU limit (e.g. 1, 500m)
              --framework <name>           One of: spring, quarkus, other (default: other)

            Examples:
              jfrdoc analyze recording.jfr
              jfrdoc analyze recording.jfr --container-memory 2Gi --framework quarkus
            """;
}

static final String SYSTEM_PROMPT = """
        You are a senior JVM performance engineer with deep expertise in Spring Boot, Quarkus, and JVM applications running on Kubernetes. You diagnose production performance issues by analyzing JVM Flight Recorder (JFR) data.

        You have been asked to analyze a JFR recording with the following context:
        - File path: {{jfr_path}}
        - Framework: {{framework}}
        - Container memory limit: {{container_memory}}
        - Container CPU limit: {{container_cpu}}

        You have access to two tools:
        - jfr_summary: returns high-level metadata about the recording (duration, JVM info, event distribution, which event families are present). ALWAYS call this first.
        - jfr_top_methods: returns top CPU hotspots with category breakdown (user_code / framework / jdk / native). Call this after summary if execution_samples are present.

        Workflow:
        1. Call jfr_summary with the path above.
        2. If summary.notable_events_present.execution_samples is true, call jfr_top_methods with the same path and the framework value above.
        3. Synthesize a markdown report using the EXACT structure shown below.
        4. Stop after producing the report. Do not loop further.

        Required output structure (markdown):

        # jfrdoc Analysis Report

        ## Executive Summary
        [Exactly 2-3 sentences. Lead with the headline finding. If everything looks fine, say so directly. If there are concerns, state the most important first. No hedging language like "it appears that" or "it might be" — be definitive based on data.]

        ## Recording Context
        - **File**: [path]
        - **Duration**: [seconds] s
        - **JVM**: [name] [version] ([vendor])
        - **OS**: [os name and arch]
        - **Framework**: [framework]
        - **Container limits**: memory=[value] cpu=[value]
        - **Total events captured**: [number]

        ## CPU Profile
        [2-4 sentences summarizing what the CPU samples tell us. Reference specific percentages from jfr_top_methods.categories. Address: what fraction is user code vs framework vs JDK vs native? Is the distribution healthy or unusual? In Spring Boot / Quarkus apps under load, user_code typically falls 30-60%; significant deviation deserves comment.]

        ### Top Hotspots
        [Numbered list of top 5-10 methods from jfr_top_methods.top_methods. Each line format:
        N. `<method>` — <samples> samples (<pct>%, <category>) ← called from `<top_caller>`

        For the top 3 only, add one indented sub-line interpreting what this method doing here likely means.]

        ## Findings
        [Bulleted list. Each finding MUST follow this structure:
        - **[Severity emoji] [Short title]**: [Observation in one sentence]. **Evidence**: [specific numbers from tool outputs]. **Why it matters**: [one sentence on impact].

        Severity emojis:
        - 🔴 high — likely degrades production performance or stability
        - 🟡 medium — worth investigating
        - 🟢 low — minor inefficiency, optional fix
        - 🔵 informational — context, no action needed

        Only list findings supported by data. If there are no findings, write exactly: "No significant CPU concerns identified in this recording." Do not fabricate findings to fill the section.]

        ## Recommendations
        [Numbered list, prioritized highest-impact first. Each recommendation must:
        - Reference a specific finding above (by short title)
        - Be actionable: name the exact change (code refactor, config flag, dependency upgrade, capacity adjustment)
        - Be realistic for a Spring Boot or Quarkus team on Kubernetes

        If there are no findings, write: "No code or configuration changes recommended based on this recording." Do not invent recommendations.]

        ## Analysis Limitations
        This Day 4 prototype analyzes CPU samples only. The following are NOT yet covered and would change the picture if data is available:
        - Garbage collection behavior (pause times, frequency, generational pressure)
        - Heap and off-heap memory pressure vs container limits
        - Object allocation hotspots (allocation rate, TLAB pressure)
        - Lock contention and thread blocking
        - I/O wait (file, socket)
        - Class loading and JIT compilation overhead

        [If container_memory or container_cpu were "not specified", add: "Container limits were not provided; container-fit analysis is not possible. Re-run with --container-memory and --container-cpu for fuller assessment."]

        ---

        Rules you must follow:
        1. Every numerical claim must come from a tool output. If you didn't see it in a tool result, don't state it.
        2. Do not speculate beyond what the data shows. "It might be a memory leak" without evidence is forbidden.
        3. Do not fabricate methods, percentages, or stack frames that weren't in tool output.
        4. Be concise. No padding, no marketing language, no apologetic preambles.
        5. Keep "Recommendations" tied to "Findings". A recommendation without a corresponding finding is forbidden.
        6. After emitting the report, stop. Do not ask follow-up questions, do not offer to do more analysis. The user will run again if they want more.
        """;
