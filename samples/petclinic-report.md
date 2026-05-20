# jfrdoc Analysis Report

## Executive Summary
The Spring PetClinic JVM is spending nearly all of its CPU in JDK plumbing (63.2%) and Spring Boot loader code (33.6%), with only 3.2% in actual user code. The hotspot signature — `URL.<init>`, `HashMap.getNode`, `String.startsWith`, and `spring-boot-loader` `Handler.indexOfSeparator` — is classic nested-jar classpath resolution overhead from the Spring Boot fat-jar launcher. Additionally, 16,778 `JavaExceptionThrow` events in 60 seconds (~280/sec) suggests exception-driven control flow somewhere in the stack.

## Recording Context
- **File**: /home/user/jfrdoc/samples/petclinic.jfr
- **Duration**: 60.103 s
- **JVM**: OpenJDK 64-Bit Server VM 25.0.2+10-LTS (OpenJDK)
- **OS**: linux-amd64
- **Framework**: spring
- **Container limits**: memory=2Gi cpu=1
- **Total events captured**: 125,031

## CPU Profile
The category split is highly unusual for a steady-state Spring Boot app: **user_code is only 3.2%**, while **JDK code dominates at 63.2%** and **framework (Spring) at 33.6%**. A healthy PetClinic under load typically shows 30–60% user_code; 3.2% indicates the JVM is either idle/lightly loaded or burning cycles on resource/class lookup rather than business logic. The top 10 hotspots are entirely infrastructure: hash lookups, string comparisons, URL construction, and Spring Boot's nested-jar `Handler`. No native code samples were recorded.

### Top Hotspots
1. `java.util.HashMap.getNode:579` — 346 samples (7.1%, jdk) ← called from `java.util.HashMap.get`
   - Heavy `HashMap.get` traffic combined with the URL/classpath hotspots below strongly suggests classloader resource-cache lookups during request handling.
2. `java.lang.StringLatin1.hashCode:195` — 221 samples (4.5%, jdk) ← called from `java.lang.String.hashCode`
   - Repeated hashing of the same strings — likely resource paths or URLs being re-resolved rather than cached.
3. `java.util.Arrays.binarySearch0:1713` — 207 samples (4.2%, jdk) ← called from `java.util.Arrays.binarySearch`
   - Binary search in hot path is consistent with sorted resource/entry tables in the JAR index — again pointing at classpath scanning.
4. `jdk.internal.util.ArraysSupport.mismatch:563` — 203 samples (4.2%, jdk) ← called from `java.lang.String.startsWith` (92.1%)
5. `org.springframework.boot.loader.net.protocol.jar.Handler.indexOfSeparator:174` — 195 samples (4.0%, framework) ← called from `org.springframework.boot.loader.net.protocol.jar.Handler.indexOfSeparator`
6. `java.net.URL.<init>:741` — 175 samples (3.6%, jdk) ← called from `java.net.URL.<init>`
7. `java.net.URL.<init>:694` — 168 samples (3.4%, jdk) ← called from `java.net.URL.<init>`
8. `java.lang.ThreadLocal$ThreadLocalMap.getEntry:491` — 153 samples (3.1%, jdk) ← called from `java.lang.ThreadLocal.get`
9. `java.lang.StringLatin1.regionMatchesCI:387` — 141 samples (2.9%, jdk) ← called from `java.lang.String.regionMatches`
10. `jdk.internal.loader.URLClassPath.getLoader:396` — 125 samples (2.6%, jdk) ← called from `jdk.internal.loader.URLClassPath.findResource`

## Findings
- **🔴 Spring Boot nested-jar classpath resolution dominates CPU**: Hotspots #4–#7 and #10 are all URL parsing, `startsWith`, and `URLClassPath.findResource` paths from the Spring Boot fat-jar launcher. **Evidence**: `Handler.indexOfSeparator` 4.0%, `URL.<init>` 3.6%+3.4%=7.0%, `URLClassPath.getLoader` 2.6%, plus `String.startsWith` (via `ArraysSupport.mismatch`) 4.2% — together >20% of CPU in nested-jar URL handling. **Why it matters**: Every request appears to trigger repeated resource lookups through the loader, wasting CPU that should go to business logic on a 1-CPU container.
- **🟡 Extremely low user-code share**: Only 3.2% of CPU samples are in application code. **Evidence**: `categories.user_code.pct = 3.2` (154 of 4,877 samples). **Why it matters**: Either load is too light for this profile to be meaningful, or infrastructure overhead is starving the app — both warrant validating the test scenario before drawing perf conclusions.
- **🟡 High exception-throw rate**: 16,778 `JavaExceptionThrow` events in 60 s (~280/sec). **Evidence**: 4th most frequent event type in the recording. **Why it matters**: Exception construction fills stack traces and is expensive; if used as control flow it can silently consume CPU and pollute logs. Not visible in top CPU methods but worth confirming.
- **🔵 GC and allocation activity present but not in top CPU**: `GCPhaseParallel` (28,632) and `ObjectAllocationSample` (17,364) are heavily represented as events. **Evidence**: event counts only; no GC method appears in top 10 CPU samples. **Why it matters**: Context for follow-up — GC is active but not currently a CPU hotspot in this 60 s window.

## Recommendations
1. **Address "Spring Boot nested-jar classpath resolution dominates CPU"**: Repackage or run the app in a way that avoids per-request nested-jar URL resolution. Options, in order of preference: (a) build with `spring-boot-maven-plugin` `<layout>ZIP</layout>` plus `spring-boot.run.unpack=true`, or extract the jar at image build time (`java -Djarmode=tools -jar app.jar extract`) and run the exploded layout — this is the standard Spring Boot 3.2+ recommendation and eliminates the `org.springframework.boot.loader.net.protocol.jar.Handler` hot path entirely; (b) build a native image with Spring Boot AOT if startup/throughput on 1 CPU is critical.
2. **Address "Extremely low user-code share"**: Before tuning further, confirm the recording captured representative load. Re-run JFR during a sustained load test (e.g., k6/JMeter at target RPS) so user-code samples are statistically meaningful; the current profile may be dominated by warm-up or idle classloading.
3. **Address "High exception-throw rate"**: Enable `-XX:+UnlockDiagnosticVMOptions -XX:+PrintConcurrentLocks` is not needed here — instead, add a JFR view or run `jfr print --events jdk.JavaExceptionThrow petclinic.jfr | head -50` to identify the exception classes and call sites. If they are from validation, auth, or `NoSuchElementException` in Optional flows, refactor to non-exceptional control flow.

## Analysis Limitations
This Day 4 prototype analyzes CPU samples only. The following are NOT yet covered and would change the picture if data is available:
- Garbage collection behavior (pause times, frequency, generational pressure)
- Heap and off-heap memory pressure vs container limits
- Object allocation hotspots (allocation rate, TLAB pressure)
- Lock contention and thread blocking
- I/O wait (file, socket)
- Class loading and JIT compilation overhead
