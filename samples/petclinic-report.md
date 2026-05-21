# jfrdoc Analysis Report

## Executive Summary
This Spring Boot application is running on the **Serial GC** (DefNew + SerialOld) — a single-threaded collector that is inappropriate for a server workload, producing 903 GCs/minute and a 115.7 ms SerialOld pause. CPU time is dominated by **Spring Boot's nested-JAR URL loader** (URL construction, JarFileUrlKey hashing/equality, ZIP lookups), with only 4.4% of samples in user code and a sustained **244 exceptions thrown per second** indicating a hot exception-driven control path.

## Recording Context
- **File**: /Users/ridvanozcan/code/jfrdoc/samples/petclinic.jfr
- **Duration**: 60.134 s
- **JVM**: OpenJDK 64-Bit Server VM 25.0.3+9-LTS (linux-aarch64)
- **OS**: Linux aarch64
- **Framework**: spring
- **Container limits**: memory=2Gi cpu=1
- **Total events captured**: 88,505

## CPU Profile
CPU is heavily skewed toward JDK and Spring Boot loader internals: **56.3% JDK / 39.3% framework / 4.4% user code / 0% native**. The user-code share is far below the typical 30–60% band for a Spring Boot app under load, and the framework share is concentrated in `org.springframework.boot.loader.net.protocol.jar.*` — the nested-JAR URL handler that resolves classpath resources at runtime. The top 8 hotspots are all either `java.net.URL.<init>` variants, `JarFileUrlKey` hash/equality, or `Arrays.binarySearch` from JAR entry lookups, which is consistent with repeated `ClassLoader.getResource(...)` / URL parsing on the request path rather than business logic.

### Top Hotspots
1. `java.util.Arrays.binarySearch0:1713` — 191 samples (5.9%, jdk) ← called from `java.util.Arrays.binarySearch`
   - Binary searches over sorted arrays, dominantly invoked from JAR entry lookups inside the Spring Boot loader.
2. `java.net.URL.<init>:630` — 172 samples (5.3%, jdk) ← called from `java.net.URL.<init>`
   - Repeated URL object construction; consistent with resource resolution against nested JARs on every request.
3. `java.util.Objects.hashCode:97` — 134 samples (4.1%, jdk) ← called from `org.springframework.boot.loader.net.protocol.jar.JarFileUrlKey.hashCode`
   - Hashing `JarFileUrlKey` instances for the URL→JarFile cache; indicates the cache is being keyed/probed at high frequency.
4. `java.net.URL.<init>:741` — 125 samples (3.8%, jdk) ← called from `java.net.URL.<init>`
5. `java.lang.ThreadLocal$ThreadLocalMap.getEntryAfterMiss:514` — 125 samples (3.8%, jdk) ← called from `java.lang.ThreadLocal$ThreadLocalMap.getEntry`
6. `jdk.internal.util.ArraysSupport.mismatch:555` — 124 samples (3.8%, jdk) ← called from `java.lang.String.startsWith`
7. `java.lang.String.equalsIgnoreCase:2056` — 102 samples (3.1%, jdk) ← called from `org.springframework.boot.loader.net.protocol.jar.JarFileUrlKey.equalsIgnoringCase`
8. `java.net.URL.<init>:764` — 69 samples (2.1%, jdk) ← called from `java.net.URL.<init>`
9. `org.springframework.boot.loader.net.protocol.jar.Handler.indexOfSeparator:174` — 64 samples (2.0%, framework) ← called from `org.springframework.boot.loader.net.protocol.jar.Handler.indexOfSeparator`
10. `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles.getCached:81` — 53 samples (1.6%, framework) ← called from `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles.getOrCreate`

## Memory & GC
The JVM is configured with the **Serial collector** (`DefNew` young + `SerialOld` old) — a stop-the-world, single-threaded GC. Over the 60 s recording there were **905 GCs (903/minute)**, virtually all "Allocation Failure", consuming **2.95% of wall time** in pauses. The average and p95 young pauses are tiny (1.96 ms / 1.92 ms), but the tail is poor: **p99 = 3.3 ms** and a **single SerialOld pause of 115.7 ms**. Heap committed is only **94.3 MB** with used ranging **39–65.7 MB** (peaking at 94.9% of committed), indicating the JVM has not been told it may use anything close to the 2Gi container limit.

### GC Anomalies
- **1 long pause over 100 ms** (115.7 ms SerialOld) — a single full-old collection that stalled the application for >100 ms, an SLO risk for latency-sensitive endpoints.

## Findings
- **🔴 Serial GC selected on a server workload**: The JVM is using DefNew + SerialOld, a single-threaded collector intended for tiny/client workloads. **Evidence**: `configuration.young_collector=DefNew`, `configuration.old_collector=SerialOld`, 905 GCs in 60 s (903/min), one 115.7 ms SerialOld pause. **Why it matters**: Serial GC scales poorly and produces unbounded full-GC pauses; on a 1-CPU container it also blocks the only worker thread during collection.
- **🔴 Heap committed far below container limit**: Heap is committed at only 94.3 MB out of a 2Gi container, and peaks at 94.9% of committed, driving the extreme GC frequency. **Evidence**: `heap.committed_size_mb=94.3`, `heap.max_used_mb=65.7`, `max_used_pct_of_committed=94.9`, 904 Allocation Failure GCs. **Why it matters**: An undersized heap on an oversized container wastes the memory budget and forces ~15 GCs/second, inflating CPU overhead and tail latency.
- **🟡 Spring Boot nested-JAR URL handler dominates CPU**: ~25–30% of samples are inside `org.springframework.boot.loader.net.protocol.jar.*` and `java.net.URL.<init>` paths, while user code is only 4.4%. **Evidence**: top 10 methods include `JarFileUrlKey.hashCode/equalsIgnoringCase`, `Handler.indexOfSeparator`, `UrlJarFiles.getCached`, `Canonicalizer.canonicalizeAfter`, plus four `URL.<init>` frames; `categories.user_code.pct=4.4`. **Why it matters**: Hot resource/URL resolution against the fat-jar layout means the application pays classpath-lookup cost on the request path instead of doing work.
- **🟡 High Java exception throw rate (244/s)**: Exceptions are being thrown at 244.2/second sustained over the recording. **Evidence**: `derived.javaExceptionThrowPerSecond=244.2`, `javaExceptionThrowCount=14,683` in 60 s. **Why it matters**: Exception construction captures stack traces and is expensive; rates this high almost always indicate exception-driven control flow (e.g., `ClassNotFoundException` probing, parsing failures) rather than real error conditions.
- **🔵 Recording sampling density is adequate**: 54.1 execution samples/second over 60 s yields 3,256 samples with stack traces and 0 skipped. **Evidence**: `derived.executionSamplesPerSecond=54.1`, `with_stack_trace=3256`. **Why it matters**: Confidence in the hotspot ranking is reasonable; no caveat needed.

## Recommendations
1. **Switch off Serial GC** (addresses *Serial GC selected on a server workload*): Add `-XX:+UseG1GC` (default on modern JDKs but evidently overridden here) or `-XX:+UseZGC` to the container JVM args. Verify no explicit `-XX:+UseSerialGC` is set in the Dockerfile, Helm chart, or `JAVA_TOOL_OPTIONS`.
2. **Right-size the heap to the container** (addresses *Heap committed far below container limit*): Set `-XX:MaxRAMPercentage=70` (or explicit `-Xmx1400m`) so the JVM uses the 2Gi budget. This alone should cut GC frequency from ~903/min to a small fraction.
3. **Reduce nested-JAR URL resolution cost** (addresses *Spring Boot nested-JAR URL handler dominates CPU*): Repackage the app with `spring-boot-maven-plugin` layout `CLASSIC` extracted (`java -Djarmode=tools -jar app.jar extract`) and run from the exploded layout, or migrate to Spring Boot's AOT / native build. Also audit application code and libraries for repeated `ClassLoader.getResource(...)` calls on the request path and cache the results.
4. **Diagnose and eliminate the exception hot path** (addresses *High Java exception throw rate*): Enable `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints` and capture a second JFR with `jdk.JavaExceptionThrow` stack traces enabled (`settings=profile` already captures these) — group by exception type, then fix the offending library or code (commonly Jackson type probing, `Class.forName` fallback chains, or Tomcat request attribute lookups).

## Analysis Limitations
This build analyzes CPU samples and GC behavior. The following are NOT yet covered and would change the picture if data is available:
- Heap and off-heap memory pressure vs container limits (partial — see Memory & GC above for heap data)
- Object allocation hotspots (allocation rate, TLAB pressure) — note `jdk.ObjectAllocationSample` is the top event type in this file (17,212 events), so dedicated allocation analysis would likely be high-yield
- Lock contention and thread blocking (13,641 `jdk.ThreadPark` events present)
- I/O wait (file, socket)
- Class loading and JIT compilation overhead
