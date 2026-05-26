import java.util.List;

import airhacks.zsmith.tools.control.Tool;
import org.json.JSONObject;

final class DebugCommand {

    private DebugCommand() {}

    static void run(String[] args, List<Tool> tools) {
        if (args.length < 3) {
            System.err.println("Error: 'debug-tool' requires <tool-name> and a <jfr-file>");
            System.err.print(usage(tools));
            System.exit(1);
        }

        String requested = args[1];
        String name = requested.replace('-', '_');
        Tool tool = tools.stream()
                .filter(t -> t.toolName().equals(name))
                .findFirst()
                .orElse(null);
        if (tool == null) {
            System.err.println("Error: unknown tool '" + requested + "'");
            System.err.print(usage(tools));
            System.exit(1);
        }

        var input = new JSONObject().put("path", args[2]);
        int i = 3;
        while (i < args.length) {
            String flag = args[i];
            if (!flag.startsWith("--")) {
                System.err.println("Error: expected --<flag>, got '" + flag + "'");
                System.exit(1);
            }
            if (i + 1 >= args.length) {
                System.err.println("Error: " + flag + " requires a value");
                System.exit(1);
            }
            String val = args[++i];
            if (flag.equals("--container-memory")) {
                Integer mb = JfrMemoryTool.parseMemoryToMb(val);
                if (mb == null) {
                    System.err.println("Error: invalid --container-memory value: " + val);
                    System.exit(1);
                }
                input.put("container_memory_mb", mb.intValue());
            } else {
                String key = flag.substring(2).replace('-', '_');
                input.put(key, val);
            }
            i++;
        }

        String result = tool.execute(input);
        System.out.println(result);
        if (result.startsWith("Error:")) System.exit(1);
    }

    static String usage(List<Tool> tools) {
        var sb = new StringBuilder("Usage:\n");
        for (Tool t : tools) {
            sb.append("  jfrdoc debug-tool ")
              .append(t.toolName().replace('_', '-'))
              .append(" <jfr-file> [--key value ...]\n");
        }
        return sb.toString();
    }
}
