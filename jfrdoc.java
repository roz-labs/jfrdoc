import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import airhacks.zsmith.agent.boundary.Agent;
import airhacks.zsmith.tools.control.Tool;

record Config(String jfrFile, String memory, String cpu, String framework) {}

List<Tool> TOOLS = List.of(new JfrSummaryTool(), new JfrTopMethodsTool(), new JfrGcStatsTool(), new JfrAllocationTool(), new JfrMemoryTool(), new JfrLockContentionTool(), new JfrExceptionsTool(), new JfrIoTool(), new JfrNativeMethodsTool(), new JfrContainerTool());

void main(String[] args) {
    if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
        System.out.print(usage());
        return;
    }

    String cmd = args[0];
    if (cmd.equals("debug-tool")) {
        DebugCommand.run(args, TOOLS);
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

    String containerMemoryMb = "not specified";
    if (config.memory() != null) {
        Integer mb = JfrMemoryTool.parseMemoryToMb(config.memory());
        if (mb != null) containerMemoryMb = mb.toString();
    }

    String systemPrompt = promptTemplate
            .replace("{{jfr_path}}", jfrPath.toAbsolutePath().toString())
            .replace("{{framework}}", config.framework())
            .replace("{{container_memory}}", config.memory() == null ? "not specified" : config.memory())
            .replace("{{container_memory_mb}}", containerMemoryMb)
            .replace("{{container_cpu}}", config.cpu() == null ? "not specified" : config.cpu());

    PrintStream originalOut = System.out;
    System.setOut(System.err);
    String report;
    try {
        var agent = new Agent("jfrdoc", systemPrompt)
                .withTools(TOOLS);
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
    Path file = Path.of(System.getProperty("user.home"), ".zsmith", "app.properties");
    if (!Files.exists(file)) return false;
    var props = new Properties();
    try (var in = Files.newInputStream(file)) {
        props.load(in);
    } catch (IOException e) {
        return false;
    }
    return !props.getProperty("anthropic.api.key", "").trim().isEmpty();
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

