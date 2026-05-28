import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record FrameworkCategorizer(
        Set<String> alwaysFramework,
        Set<String> extraFramework,
        Set<String> jdk) {

    public FrameworkCategorizer {
        alwaysFramework = Set.copyOf(alwaysFramework);
        extraFramework = Set.copyOf(extraFramework);
        jdk = Set.copyOf(jdk);
    }

    public String categorize(String fqcn) {
        if (fqcn == null) {
            throw new IllegalArgumentException("fqcn must not be null; callers should route unresolved samples to sample_quality, not categorize()");
        }
        if (fqcn.startsWith("org.springframework.samples.petclinic")) {
            // Pet Clinic Package was added because its prefix is the same as Spring Framework.
            return "user_code";
        }
        if (startsWithAny(fqcn, jdk)) return "jdk";
        if (startsWithAny(fqcn, alwaysFramework)) return "framework";
        if (startsWithAny(fqcn, extraFramework)) return "framework";
        return "user_code";
    }

    public static FrameworkCategorizer forFramework(String framework) throws IOException {
        Set<String> common = loadPrefixes("framework-common");
        Set<String> jdk = loadPrefixes("jdk");
        Set<String> extra = loadPrefixesIfExists(framework);
        return new FrameworkCategorizer(common, extra, jdk);
    }

    static Set<String> loadPrefixes(String name) throws IOException {
        Path path = frameworksDir().resolve(name + ".txt");
        if (!Files.exists(path)) {
            throw new IOException("framework definition not found: " + path);
        }
        return parsePrefixes(Files.readAllLines(path));
    }

    static Set<String> loadPrefixesIfExists(String name) throws IOException {
        if (name == null || name.isBlank()) return Set.of();
        Path path = frameworksDir().resolve(name + ".txt");
        if (!Files.exists(path)) return Set.of();
        return parsePrefixes(Files.readAllLines(path));
    }

    static Set<String> parsePrefixes(List<String> lines) {
        var set = new LinkedHashSet<String>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            set.add(trimmed);
        }
        return set;
    }

    static Path frameworksDir() {
        String home = System.getProperty("jfrdoc.home");
        Path base = home != null ? Path.of(home) : Path.of("");
        return base.resolve("resources").resolve("frameworks");
    }

    static boolean startsWithAny(String s, Set<String> prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) return true;
        }
        return false;
    }
}
