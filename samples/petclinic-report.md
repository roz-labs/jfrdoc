? [38;2;38;139;210mzsmith v0.0.0-jfrdoc-local[0m
? [38;2;42;161;152mgo[0m
[38;2;38;139;210m[?[0m[38;2;108;113;196m???????????????????[0m[38;2;38;139;210m][0m  1/20  [38;2;42;161;152mclaude: 0[0m  [38;2;211;54;130mtools: 0[0m
? [38;2;38;139;210musing claude model: claude-opus-4-7[0m
? [38;2;181;137;0min=2843 out=88/32000 (headroom=31912) cache_read=0 cache_create=0[0m
 ? [38;2;211;54;130mjfr_summary 1483ms[0m
[38;2;38;139;210m[??[0m[38;2;108;113;196m??????????????????[0m[38;2;38;139;210m][0m  2/20  [38;2;42;161;152mclaude: 1[0m  [38;2;211;54;130mtools: 1[0m
? [38;2;38;139;210musing claude model: claude-opus-4-7[0m
? [38;2;181;137;0min=3715 out=113/32000 (headroom=31887) cache_read=0 cache_create=0[0m
 ? [38;2;211;54;130mjfr_top_methods 496ms[0m
[38;2;38;139;210m[???[0m[38;2;108;113;196m?????????????????[0m[38;2;38;139;210m][0m  3/20  [38;2;42;161;152mclaude: 2[0m  [38;2;211;54;130mtools: 2[0m
? [38;2;38;139;210musing claude model: claude-opus-4-7[0m
? [38;2;181;137;0min=6577 out=3447/32000 (headroom=28553) cache_read=0 cache_create=0[0m
? [38;2;203;75;22m# jfrdoc Analysis Report

## Executive Summary
CPU in this Spring PetClinic recording is overwhelmingly consumed by Spring Boot's nested-JAR class/resource loading machinery, not by application logic: only **3.3%** of samples are user code while **32.6%** is framework loader code and **64.1%** is JDK code driven by that loader. The hottest paths center on `java.net.URL` construction, `String.hashCode`, and `UrlJarFiles` cache lookups ? a classic fat-JAR cold-start / classpath-resource-scanning signature.

## Recording Context
- **File**: /home/user/jfrdoc/samples/petclinic.jfr
- **Duration**: 60.093 s
- **JVM**: OpenJDK 64-Bit Server VM 25.0.2+10-Ubuntu-124.04 (OpenJDK)
- **OS**: linux-amd64
- **Framework**: spring
- **Container limits**: memory=2Gi cpu=1
- **Total events captured**: 130268

## CPU Profile
The category split is highly unusual for a steady-state Spring Boot app: **user_code = 3.3%**, **framework = 32.6%**, **jdk = 64.1%**, **native = 0%**. The expected 30?60% user_code range is missed by an order of magnitude. Drilling into the JDK bucket, the dominant callers are `java.net.URL.<init>`, `String.hashCode`, `Arrays.binarySearch`, `URLClassPath.getLoader`, and `HashMap.getNode` ? all reachable from Spring Boot's `org.springframework.boot.loader.net.protocol.jar.*` layer. This is the fat-JAR `LaunchedURLClassLoader` resolving resources, not request handling.

### Top Hotspots
1. `java.lang.StringLatin1.hashCode:195` ? 601 samples (8.6%, jdk) ? called from `java.lang.String.hashCode`
   - Hashing URL/path strings repeatedly, almost certainly as cache keys inside the Spring Boot JAR URL handler and class loader maps.
2. `java.util.Arrays.binarySearch0:1713` ? 351 samples (5.0%, jdk) ? called from `java.util.Arrays.binarySearch`
   - Typical of JAR central-directory / entry-name lookups during resource resolution inside the nested JAR loader.
3. `java.net.URL.<init>:741` ? 327 samples (4.7%, jdk) ? called from `java.net.URL.<init>`
   - Construction of `jar:` URLs by the Spring Boot loader; URL parsing is expensive and is happening on a hot path.
4. `java.util.HashMap.getNode:586` ? 293 samples (4.2%, jdk) ? called from `java.util.HashMap.get`
5. `java.net.URL.<init>:694` ? 283 samples (4.1%, jdk) ? called from `java.net.URL.<init>`
6. `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles.getCached:81` ? 267 samples (3.8%, framework) ? called from `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles.getOrCreate`
7. `jdk.internal.loader.URLClassPath.getLoader:396` ? 246 samples (3.5%, jdk) ? called from `jdk.internal.loader.URLClassPath.findResource`
8. `java.lang.ThreadLocal$ThreadLocalMap.getEntry:491` ? 184 samples (2.6%, jdk) ? called from `java.lang.ThreadLocal.get`
9. `java.lang.Class.getName:951` ? 157 samples (2.2%, jdk) ? called from `java.net.URL.isBuiltinStreamHandler`
10. `java.lang.String.equalsIgnoreCase:2056` ? 134 samples (1.9%, jdk) ? called from `org.springframework.boot.loader.net.protocol.jar.JarFileUrlKey.equalsIgnoringCase`

## Findings
- **? Spring Boot nested-JAR loader dominates CPU**: The `org.springframework.boot.loader.net.protocol.jar.*` machinery and the JDK URL/classloader code it invokes consume the majority of CPU. **Evidence**: framework=32.6% + jdk=64.1% = 96.7% of 6985 samples; `UrlJarFiles.getCached` 3.8%, `Handler.indexOfSeparator` 1.2%, `Handler.parseURL` 1.1%, `JarFileUrlKey.equalsIgnoringCase`-driven `String.equalsIgnoreCase` 1.9%, plus `java.net.URL.<init>` accounting for ~9.7% across lines 741/694/630. **Why it matters**: every CPU cycle spent re-resolving `jar:` URLs is a cycle not serving HTTP requests; on a 1-vCPU container this directly caps throughput.
- **? User code is only 3.3% of CPU**: Application logic is barely visible in the profile. **Evidence**: user_code=231 samples / 6985 total = 3.3%, vs the 30?60% norm for a Spring Boot app under load. **Why it matters**: either the workload during recording was not exercising business logic (cold start / light load), or framework overhead is so high it crowds out application work ? both warrant action.
- **? Heavy `String.hashCode` and `HashMap`/`ConcurrentHashMap` traffic from classloader caches**: Hash-based cache lookups are themselves a top cost. **Evidence**: `StringLatin1.hashCode` 8.6%, `HashMap.getNode` 4.2%, `ConcurrentHashMap.get` 1.4%, `ArraysSupport.hashCodeOfUnsigned` 1.4%, `ThreadLocalMap.getEntry`(+miss) combined 3.5%. **Why it matters**: this is the signature of repeated `Class.forName` / `getResource` lookups, often from reflective framework scanning or per-request bean/resource resolution.
- **? High exception throw rate during the window**: 12,010 `jdk.JavaExceptionThrow` events in 60s (~200/s). **Evidence**: `topEventTypes` shows `jdk.JavaExceptionThrow` = 12010 over 60.093 s. **Why it matters**: exception construction fills stack traces and is a known CPU/allocation tax; it can also indicate control-flow-by-exception in a hot path (e.g. `ClassNotFoundException` swallowed during resource probing ? consistent with the classloader hotspot above).
- **? Monitor enter events are very frequent**: 34,771 `jdk.JavaMonitorEnter` events. **Evidence**: `topEventTypes` count = 34771 over 60 s. **Why it matters**: informational only ? this tool does not yet break down contention duration, but the volume is worth a deeper lock-contention pass.

## Recommendations
1. **(Spring Boot nested-JAR loader dominates CPU)** Stop running PetClinic from the fat JAR in production. Either: (a) repackage with `spring-boot-maven-plugin` using `<layers>` and run the **extracted** layout (`java -jar` on the exploded directory or use the `extract` goal), or (b) build a JLink/CDS-enabled image. Both eliminate the `jar:` URL handler hot path that accounts for ~96% of CPU here.
2. **(Spring Boot nested-JAR loader dominates CPU)** Enable **Application Class Data Sharing (AppCDS)** for the PetClinic JVM: run once with `-XX:ArchiveClassesAtExit=app.jsa`, then add `-XX:SharedArchiveFile=app.jsa` to the container start command. This caches resolved classes and bypasses much of the `URLClassPath.getLoader` / `UrlJarFiles.getCached` work seen at ranks 6?7.
3. **(User code is only 3.3% of CPU)** Re-record under representative load (e.g. a sustained `wrk`/`k6` workload against `/owners`, `/vets`) so the profile reflects request handling, not startup/scanning. If the 3.3% user_code ratio persists under real load, the loader overhead in recommendation #1 is the cause and fixing it will reclaim throughput.
4. **(Heavy `String.hashCode` and `HashMap` traffic from classloader caches)** Audit for repeated `getClass().getClassLoader().getResource(...)` or `Class.forName(...)` calls on hot request paths and cache results at the bean level; combined with AppCDS this removes the dominant hashing cost.
5. **(High exception throw rate)** Enable `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints` and capture `jdk.JavaExceptionThrow` by type in the next recording to identify the ~200/s exception source; if it is `ClassNotFoundException`/`FileNotFoundException` from resource probing, recommendations #1 and #2 will also resolve it.

## Analysis Limitations
This Day 4 prototype analyzes CPU samples only. The following are NOT yet covered and would change the picture if data is available:
- Garbage collection behavior (pause times, frequency, generational pressure) ? note 37,333 `jdk.GCPhaseParallel` events were present but not analyzed
- Heap and off-heap memory pressure vs container limits
- Object allocation hotspots (allocation rate, TLAB pressure) ? 12,864 `jdk.ObjectAllocationSample` events present but not analyzed
- Lock contention and thread blocking ? 34,771 `jdk.JavaMonitorEnter` and 4,157 `jdk.ThreadPark` events present but not analyzed
- I/O wait (file, socket) ? 2,347 `jdk.SocketWrite` events present but not analyzed
- Class loading and JIT compilation overhead[0m
[38;2;133;153;0m--- summary ---[0m  [38;2;42;161;152mclaude: 3[0m  [38;2;211;54;130mtools: 2[0m
# jfrdoc Analysis Report

## Executive Summary
CPU in this Spring PetClinic recording is overwhelmingly consumed by Spring Boot's nested-JAR class/resource loading machinery, not by application logic: only **3.3%** of samples are user code while **32.6%** is framework loader code and **64.1%** is JDK code driven by that loader. The hottest paths center on `java.net.URL` construction, `String.hashCode`, and `UrlJarFiles` cache lookups ? a classic fat-JAR cold-start / classpath-resource-scanning signature.

## Recording Context
- **File**: /home/user/jfrdoc/samples/petclinic.jfr
- **Duration**: 60.093 s
- **JVM**: OpenJDK 64-Bit Server VM 25.0.2+10-Ubuntu-124.04 (OpenJDK)
- **OS**: linux-amd64
- **Framework**: spring
- **Container limits**: memory=2Gi cpu=1
- **Total events captured**: 130268

## CPU Profile
The category split is highly unusual for a steady-state Spring Boot app: **user_code = 3.3%**, **framework = 32.6%**, **jdk = 64.1%**, **native = 0%**. The expected 30?60% user_code range is missed by an order of magnitude. Drilling into the JDK bucket, the dominant callers are `java.net.URL.<init>`, `String.hashCode`, `Arrays.binarySearch`, `URLClassPath.getLoader`, and `HashMap.getNode` ? all reachable from Spring Boot's `org.springframework.boot.loader.net.protocol.jar.*` layer. This is the fat-JAR `LaunchedURLClassLoader` resolving resources, not request handling.

### Top Hotspots
1. `java.lang.StringLatin1.hashCode:195` ? 601 samples (8.6%, jdk) ? called from `java.lang.String.hashCode`
   - Hashing URL/path strings repeatedly, almost certainly as cache keys inside the Spring Boot JAR URL handler and class loader maps.
2. `java.util.Arrays.binarySearch0:1713` ? 351 samples (5.0%, jdk) ? called from `java.util.Arrays.binarySearch`
   - Typical of JAR central-directory / entry-name lookups during resource resolution inside the nested JAR loader.
3. `java.net.URL.<init>:741` ? 327 samples (4.7%, jdk) ? called from `java.net.URL.<init>`
   - Construction of `jar:` URLs by the Spring Boot loader; URL parsing is expensive and is happening on a hot path.
4. `java.util.HashMap.getNode:586` ? 293 samples (4.2%, jdk) ? called from `java.util.HashMap.get`
5. `java.net.URL.<init>:694` ? 283 samples (4.1%, jdk) ? called from `java.net.URL.<init>`
6. `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles.getCached:81` ? 267 samples (3.8%, framework) ? called from `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles.getOrCreate`
7. `jdk.internal.loader.URLClassPath.getLoader:396` ? 246 samples (3.5%, jdk) ? called from `jdk.internal.loader.URLClassPath.findResource`
8. `java.lang.ThreadLocal$ThreadLocalMap.getEntry:491` ? 184 samples (2.6%, jdk) ? called from `java.lang.ThreadLocal.get`
9. `java.lang.Class.getName:951` ? 157 samples (2.2%, jdk) ? called from `java.net.URL.isBuiltinStreamHandler`
10. `java.lang.String.equalsIgnoreCase:2056` ? 134 samples (1.9%, jdk) ? called from `org.springframework.boot.loader.net.protocol.jar.JarFileUrlKey.equalsIgnoringCase`

## Findings
- **? Spring Boot nested-JAR loader dominates CPU**: The `org.springframework.boot.loader.net.protocol.jar.*` machinery and the JDK URL/classloader code it invokes consume the majority of CPU. **Evidence**: framework=32.6% + jdk=64.1% = 96.7% of 6985 samples; `UrlJarFiles.getCached` 3.8%, `Handler.indexOfSeparator` 1.2%, `Handler.parseURL` 1.1%, `JarFileUrlKey.equalsIgnoringCase`-driven `String.equalsIgnoreCase` 1.9%, plus `java.net.URL.<init>` accounting for ~9.7% across lines 741/694/630. **Why it matters**: every CPU cycle spent re-resolving `jar:` URLs is a cycle not serving HTTP requests; on a 1-vCPU container this directly caps throughput.
- **? User code is only 3.3% of CPU**: Application logic is barely visible in the profile. **Evidence**: user_code=231 samples / 6985 total = 3.3%, vs the 30?60% norm for a Spring Boot app under load. **Why it matters**: either the workload during recording was not exercising business logic (cold start / light load), or framework overhead is so high it crowds out application work ? both warrant action.
- **? Heavy `String.hashCode` and `HashMap`/`ConcurrentHashMap` traffic from classloader caches**: Hash-based cache lookups are themselves a top cost. **Evidence**: `StringLatin1.hashCode` 8.6%, `HashMap.getNode` 4.2%, `ConcurrentHashMap.get` 1.4%, `ArraysSupport.hashCodeOfUnsigned` 1.4%, `ThreadLocalMap.getEntry`(+miss) combined 3.5%. **Why it matters**: this is the signature of repeated `Class.forName` / `getResource` lookups, often from reflective framework scanning or per-request bean/resource resolution.
- **? High exception throw rate during the window**: 12,010 `jdk.JavaExceptionThrow` events in 60s (~200/s). **Evidence**: `topEventTypes` shows `jdk.JavaExceptionThrow` = 12010 over 60.093 s. **Why it matters**: exception construction fills stack traces and is a known CPU/allocation tax; it can also indicate control-flow-by-exception in a hot path (e.g. `ClassNotFoundException` swallowed during resource probing ? consistent with the classloader hotspot above).
- **? Monitor enter events are very frequent**: 34,771 `jdk.JavaMonitorEnter` events. **Evidence**: `topEventTypes` count = 34771 over 60 s. **Why it matters**: informational only ? this tool does not yet break down contention duration, but the volume is worth a deeper lock-contention pass.

## Recommendations
1. **(Spring Boot nested-JAR loader dominates CPU)** Stop running PetClinic from the fat JAR in production. Either: (a) repackage with `spring-boot-maven-plugin` using `<layers>` and run the **extracted** layout (`java -jar` on the exploded directory or use the `extract` goal), or (b) build a JLink/CDS-enabled image. Both eliminate the `jar:` URL handler hot path that accounts for ~96% of CPU here.
2. **(Spring Boot nested-JAR loader dominates CPU)** Enable **Application Class Data Sharing (AppCDS)** for the PetClinic JVM: run once with `-XX:ArchiveClassesAtExit=app.jsa`, then add `-XX:SharedArchiveFile=app.jsa` to the container start command. This caches resolved classes and bypasses much of the `URLClassPath.getLoader` / `UrlJarFiles.getCached` work seen at ranks 6?7.
3. **(User code is only 3.3% of CPU)** Re-record under representative load (e.g. a sustained `wrk`/`k6` workload against `/owners`, `/vets`) so the profile reflects request handling, not startup/scanning. If the 3.3% user_code ratio persists under real load, the loader overhead in recommendation #1 is the cause and fixing it will reclaim throughput.
4. **(Heavy `String.hashCode` and `HashMap` traffic from classloader caches)** Audit for repeated `getClass().getClassLoader().getResource(...)` or `Class.forName(...)` calls on hot request paths and cache results at the bean level; combined with AppCDS this removes the dominant hashing cost.
5. **(High exception throw rate)** Enable `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints` and capture `jdk.JavaExceptionThrow` by type in the next recording to identify the ~200/s exception source; if it is `ClassNotFoundException`/`FileNotFoundException` from resource probing, recommendations #1 and #2 will also resolve it.

## Analysis Limitations
This Day 4 prototype analyzes CPU samples only. The following are NOT yet covered and would change the picture if data is available:
- Garbage collection behavior (pause times, frequency, generational pressure) ? note 37,333 `jdk.GCPhaseParallel` events were present but not analyzed
- Heap and off-heap memory pressure vs container limits
- Object allocation hotspots (allocation rate, TLAB pressure) ? 12,864 `jdk.ObjectAllocationSample` events present but not analyzed
- Lock contention and thread blocking ? 34,771 `jdk.JavaMonitorEnter` and 4,157 `jdk.ThreadPark` events present but not analyzed
- I/O wait (file, socket) ? 2,347 `jdk.SocketWrite` events present but not analyzed
- Class loading and JIT compilation overhead
