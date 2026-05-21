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

    String promptTemplate;
    try {
        promptTemplate = loadPrompt("analyze");
    } catch (IOException e) {
        System.err.println("Error: failed to load prompt template: " + e.getMessage());
        System.exit(1);
        return;
    }

    String systemPrompt = promptTemplate
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

String loadPrompt(String name) throws IOException {
    Path path = promptsDir().resolve(name + ".md");
    if (!Files.exists(path)) {
        throw new IOException("prompt file not found: " + path);
    }
    return Files.readString(path);
}

Path promptsDir() {
    String home = System.getProperty("jfrdoc.home");
    Path base = home != null ? Path.of(home) : Path.of("");
    return base.resolve("resources").resolve("prompts");
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

