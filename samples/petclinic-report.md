# jfrdoc Analysis Report

## Executive Summary
The application is burning ~63% of on-CPU time and ~24% of allocated bytes inside Spring Boot's nested-jar URL/classloader machinery (`java.net.URL`, `JarFileUrlKey`, `URLClassPath.findResource`), driving an unusually high allocation rate of ~429 MB/s and a young-GC frequency of ~17/sec. The app is configured with the **Serial GC** (DefNew + SerialOld) on a 1-CPU container — workable here, but it produced one 127 ms SerialOld pause and is a poor match for an allocation-heavy web workload. Memory footprint is safe (~428 MB of 2048 MB) and exception throw rate is 258.9/sec, which is elevated and worth a look.

## Recording Context
- **File**: /Users/ridvanozcan/code/jfrdoc/samples/petclinic.jfr
- **Duration**: 120.136 s
- **JVM**: OpenJDK 64-Bit Server VM 25.0.3+9-LTS
- **OS**: linux-aarch64
- **Framework**: spring
- **Container limits**: memory=2Gi cpu=1
- **Total events captured**: 197,763

## Memory Footprint
Container fit: **SAFE** — 427.6 MB committed of 2048 MB limit (79.1% headroom). The dominant native category is JFR's own `Tracing` arena at 112.1 MB (26.2%), which is recording overhead, not application footprint; the real top consumers are Java Heap (97.4 MB), Metaspace (84.6 MB), and Code Cache (69.3 MB). Heap peaked at 65.9 MB against a 94.2 MB committed size — very small for a 2 GB container.

The metaspace signal `metaspace_near_committed` is set (97.6 MB used of 98.3 MB committed); this is normal post-startup behavior but indicates metaspace will expand on further class loading. Code cache has plenty of room (39.4 MB used of 240 MB committed). 33 threads peak — modest.

### Memory Breakdown
- **Tracing**: 112.1 MB committed (26.2%, 112.1 MB reserved)
- **Java Heap**: 97.4 MB committed (22.8%, 512 MB reserved)
- **Metaspace**: 84.6 MB committed (19.8%, 128.4 MB reserved)
- **Code**: 69.3 MB committed (16.2%, 271.9 MB reserved)
- **Symbol**: 19.4 MB committed (4.5%, 19.4 MB reserved)
- **Class**: 16 MB committed (3.7%, 1025.9 MB reserved)
- **Shared class space**: 13.7 MB committed (3.2%, 16 MB reserved)
- **Native Memory Tracking**: 10.8 MB committed (2.5%, 10.8 MB reserved)

## Garbage Collection
The JVM is running **Serial GC** (DefNew young + SerialOld old) — an unusual choice for a Spring Boot service. Young GCs fire at **1018.8/min (~17/sec)** with p99 = 2.44 ms and average 1.61 ms, totaling 2.74% pause overhead — acceptable on average. However, one **SerialOld full collection** ran for **127.28 ms**, and the heap reached **96.7% of committed** before that GC, indicating the small heap (94.2 MB committed, 65.9 MB peak used) is being pushed hard by the 429 MB/s allocation rate.

### GC Anomalies
- **1 long pause >100 ms** (the SerialOld at 127.28 ms) — a single tail-latency spike that any request landing in that window would feel.
- Note: `by_cause` reports 0 full GCs in the anomaly counter but `by_name` shows 1 SerialOld collection; either way, an old-gen collection occurring at all on a 2-minute recording with this much allocation pressure is a warning that the heap is undersized for the workload.

## CPU Profile
Sampling density is healthy at 61.1 samples/sec across 7,341 attributed samples (0% unattributed). The category split is **62.9% JDK / 34.3% framework / 2.8% user code** — extremely skewed away from user code for a Spring Boot app under load. The top 10 hotspots are all `java.net.URL` construction, `JarFileUrlKey` hashing/equals, and `URLClassPath.findResource`, indicating that Spring Boot's nested-jar URL handler (`org.springframework.boot.loader.net.protocol.jar.*`) is repeatedly resolving classpath resources during request processing rather than serving cached results.

### Top Hotspots
1. `java.util.Arrays.binarySearch0:1713` — 417 samples (5.7%, jdk) ← called from `java.util.Arrays.binarySearch`
   - Used by the nested-jar `ZipContent` index lookups — a symptom of repeated classpath resource resolution.
2. `java.net.URL.<init>:630` — 354 samples (4.8%, jdk) ← called from `java.net.URL.<init>`
   - URL object construction in the hot path; expensive because of parsing and authority handling.
3. `java.lang.ThreadLocal$ThreadLocalMap.getEntryAfterMiss:514` — 326 samples (4.4%, jdk) ← called from `ThreadLocalMap.getEntry`
   - ThreadLocal misses suggest many distinct ThreadLocals per request (common with Spring's request-scoped context, MDC, tracing).
4. `jdk.internal.loader.URLClassPath$Loader.findResource:534` — 308 samples (4.2%, jdk) ← called from `URLClassPath.findResource`
5. `java.util.Objects.hashCode:97` — 288 samples (3.9%, jdk) ← called from `JarFileUrlKey.hashCode`
6. `java.net.URL.<init>:741` — 285 samples (3.9%, jdk) ← called from `java.net.URL.<init>`
7. `java.lang.String.equalsIgnoreCase:2056` — 234 samples (3.2%, jdk) ← called from `JarFileUrlKey.equalsIgnoringCase`
8. `jdk.internal.util.ArraysSupport.mismatch:555` — 228 samples (3.1%, jdk) ← called from `String.startsWith`
9. `sun.net.www.ParseUtil.firstEncodeIndex:95` — 212 samples (2.9%, jdk) ← called from `ParseUtil.encodePath`
10. `java.net.URL.<init>:764` — 208 samples (2.8%, jdk) ← called from `java.net.URL.<init>`

## Allocation Hotspots
Allocation rate is **429.5 MB/s** — high for a sample app and the primary driver of the 17/sec young-GC cadence. `byte[]` alone accounts for **43.9% (22.6 GB over 2 min)**, followed by `java.lang.String` (17.8%) and `java.net.URL` (16.9%). The top allocation site is `Arrays.copyOfRange:3849` (14.3 GB, 27.7%) — typical of JAR/ZIP reads and `String.getBytes`. By category, **75.3% of bytes come from the JDK and 23.8% from the framework**, with user code at only 0.9% — i.e., almost no allocation pressure originates from Petclinic's own business code. `java.net.URL` (16.9%) and `JarFileUrlKey` (8.2%) appearing this high in *runtime* allocation (not startup) confirms the nested-jar handler is being invoked per-request.

### Top Allocators
1. `java.util.Arrays.copyOfRange:3849` — 14275 MB (27.7%, jdk) allocating mostly `byte[]`
   - Backing-array slicing during nested-jar entry reads and string construction; reducing the URL/jar lookups upstream will cut this dramatically.
2. `jdk.internal.misc.Unsafe.allocateUninitializedArray:1396` — 7223.4 MB (14.0%, jdk) allocating mostly `byte[]`
   - General `byte[]` factory path; same upstream cause — buffer allocations triggered by repeated resource I/O.
3. `java.lang.StringLatin1.newString:760` — 7017.3 MB (13.6%, jdk) allocating mostly `java.lang.String`
4. `jdk.internal.loader.URLClassPath$Loader.findResource:514` — 4400.1 MB (8.5%, jdk) allocating mostly `java.net.URL`
5. `org.springframework.boot.loader.net.protocol.jar.JarUrlConnection.open:340` — 4308.2 MB (8.4%, framework) allocating mostly `java.net.URL`

## Findings
- **🔴 Spring Boot nested-jar URL/classpath resolution dominates CPU and allocation**: The Spring Boot loader's `JarFileUrlKey` / `URLClassPath.findResource` path is the largest on-CPU and allocation hotspot, suggesting classpath-resource lookups (e.g., `ClassLoader.getResource`, Thymeleaf template lookups, `ResourceLoader.getResource`) are being invoked per request rather than cached. **Evidence**: top 10 CPU hotspots are all URL/jar/classpath frames (62.9% jdk + framework jar handler frames); `java.net.URL` is 16.9% of allocated bytes and `JarFileUrlKey` 8.2%; `URLClassPath$Loader.findResource` allocates 4.4 GB in 120 s. **Why it matters**: a runnable-jar app burns the bulk of its CPU on classloader bookkeeping instead of business logic, capping throughput on the 1-CPU container.
- **🔴 Serial GC on a web service with 429 MB/s allocation rate**: Configuration is `DefNew` + `SerialOld`, which is the default only on very small/low-CPU JVMs; it produced a 127 ms SerialOld pause. **Evidence**: `configuration.young_collector = DefNew`, `old_collector = SerialOld`, `max_pause_ms = 127.28`, `gcs_per_minute = 1018.8`, heap peak 96.7% of committed. **Why it matters**: SerialOld pauses are STW and unpredictable; under load this will produce visible request tail-latency spikes and offers no concurrency for old-gen collection.
- **🟡 Very high allocation rate (429.5 MB/s) on a small heap (94 MB committed)**: Allocation throughput is forcing young GCs every ~60 ms. **Evidence**: `estimated_allocation_rate.mb_per_second = 429.5`, `gcs_per_minute = 1018.8`. **Why it matters**: even with sub-2 ms young pauses, this volume of GC inflates CPU overhead (2.74% pause time, plus mutator slowdown from allocation-path pressure) and limits headroom for traffic spikes.
- **🟡 Elevated exception throw rate (258.9/sec)**: Java exceptions are being constructed at a sustained high rate. **Evidence**: `derived.javaExceptionThrowPerSecond = 258.9`; `java.io.EOFException` shows up directly in allocation top-classes (333 MB) coming from `NioEndpoint$NioSocketWrapper.fillReadBuffer`. **Why it matters**: exception construction captures stack traces and is expensive; using EOFException as control flow for socket reads is a known Tomcat-NIO cost and contributes to allocation pressure.
- **🟡 ThreadLocalMap miss path appears in top-3 CPU**: `ThreadLocalMap.getEntryAfterMiss` at 4.4% suggests many distinct ThreadLocal keys per thread. **Evidence**: rank 3 hotspot, 326 samples (4.4%). **Why it matters**: cumulative ThreadLocal lookup cost on every request adds up; can indicate Spring context propagation, MDC, or observability libraries thrashing the cache.
- **🔵 Memory footprint comfortably within container limit**: 427.6 MB committed of 2048 MB, 79.1% headroom. **Evidence**: `container_fit.verdict = safe`. **Why it matters**: no OOMKill risk; container is significantly over-provisioned for current workload.

## Recommendations
1. **Address the nested-jar URL hotspot** (re: "Spring Boot nested-jar URL/classpath resolution dominates"): identify the per-request code path triggering `ClassLoader.getResource` / `ResourceLoader.getResource` calls — common culprits are Thymeleaf template resolution without caching, custom resource lookups, or libraries calling `getResource` per invocation. Verify `spring.thymeleaf.cache=true` in production profile. Consider running with `java -jar app.jar` only for dev; for production, **extract the fat jar** (`java -Djarmode=tools -jar app.jar extract`) and run from the exploded layout, which bypasses the nested-jar URL handler entirely and typically removes most of this CPU/allocation cost.
2. **Switch off Serial GC** (re: "Serial GC on a web service"): explicitly select G1 with `-XX:+UseG1GC` (or ZGC with `-XX:+UseZGC` if pauses matter more than throughput). Serial GC was likely auto-selected because the container is detected as "small"; on a 1-CPU/2Gi pod G1 will still work and gives concurrent old-gen collection. While doing so, bump heap with `-Xmx512m -Xms512m` (or higher) to reduce young-GC frequency — there is 1.5 GB of container headroom.
3. **Investigate exception-throw rate** (re: "Elevated exception throw rate"): enable `jdk.JavaExceptionThrow` per-class aggregation in a follow-up recording, or add temporary logging. If `EOFException` from Tomcat NIO dominates, this is benign client-disconnect handling and can be ignored; if app-thrown exceptions dominate, fix the control-flow misuse.
4. **Profile ThreadLocal usage** (re: "ThreadLocalMap miss path"): identify which observability/tracing/context-propagation library is installing many ThreadLocals. If using Micrometer Tracing or Sleuth, ensure context propagation is configured efficiently; consider scoped values (JEP 446) if on a JDK that supports them in a future upgrade.
5. **Right-size the container** (re: "Memory footprint comfortably within container limit"): once GC and allocation are fixed, the 2 GB limit can likely be reduced to 1 GB to save cluster capacity — but only after re-measuring with G1 and an exploded jar layout.

## Analysis Limitations
This build analyzes CPU samples, GC behavior, object allocation, and total memory footprint (with NMT for per-category native breakdown). The following are NOT yet covered and would change the picture if data is available:
- Lock contention and thread blocking (`jdk.ThreadPark` is the #1 event at 36,745 — worth a dedicated look)
- I/O wait (file, socket)
- Class loading and JIT compilation overhead
- Exception throw analysis (raw counts and rate visible, but no per-class breakdown yet — relevant given the 258.9/sec rate)
