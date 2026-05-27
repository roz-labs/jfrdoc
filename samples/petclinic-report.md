# jfrdoc Analysis Report

## Executive Summary
This Spring Boot PetClinic recording is dominated by Spring Boot Loader nested-JAR resource resolution overhead: ~97% of on-CPU Java samples are spent in JDK/framework URL parsing, classpath lookup, and JAR cache code rather than application logic, while allocation runs at ~430 MB/s with `byte[]`, `String`, and `java.net.URL` being churned by the same loader code paths. Container fit is safe (428 MB committed of 2048 MB), but there is real synchronized contention on Spring Boot loader monitors (`UrlNestedJarFile`, `UrlJarFiles$Cache`) and a high Java exception throw rate of 259/s.

## Recording Context
- **File**: /Users/ridvanozcan/code/jfrdoc/samples/petclinic.jfr
- **Duration**: 120.136 s
- **JVM**: OpenJDK 64-Bit Server VM 25.0.3+9-LTS
- **OS**: linux-aarch64
- **Framework**: spring
- **Container limits**: memory=2Gi cpu=1
- **Total events captured**: 197763

## Memory Footprint
Container fit: SAFE — 427.6 MB committed of 2048 MB limit (79.1% headroom). NMT is enabled, so we have full visibility into native memory categories. The dominant category is `Tracing` (112.1 MB, 26.2%) — this is overhead from the active JFR `profile` settings recording itself, not application code. Heap is small (94.2 MB committed, peak 65.9 MB used) and metaspace sits at 98.3 MB committed of 1152 MB reserved (signal `metaspace_unbounded` is set, but with only 97.6 MB used this is informational, not urgent).

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
The JVM is running the **Serial GC** (DefNew young + SerialOld old) — unusual and inappropriate for a server workload, almost certainly a container-sizing default (single-CPU container triggers SerialGC selection). GC ran 2040 times (1018.8/min) with 2.74% pause overhead; pauses are tiny on average (p50 1.33 ms, p99 2.44 ms), but one SerialOld full collection took 127.28 ms. Despite the `total_pause_time_ms` being modest, the high collection frequency reflects intense allocation churn against a small young generation.

### GC Anomalies
- **1 long pause > 100 ms** (127.28 ms): a single SerialOld stop-the-world full collection — the only old-gen collection in the recording.

## CPU Profile
On-CPU Java time is overwhelmingly in **JDK code (62.9%)** and **framework code (34.3%)**, with only **2.8% in user code** — extremely unusual for a Spring Boot application under load. The top hotspots are not business logic at all but Spring Boot's nested-JAR loader machinery (`org.springframework.boot.loader.net.protocol.jar.*`) and the JDK URL/URLClassPath plumbing it sits on top of (URL parsing, `Arrays.binarySearch`, `ThreadLocalMap.getEntryAfterMiss`, `ParseUtil.encodePath`). Sample density is healthy at 61.1 samples/s with 100% attribution.

### Top Hotspots
1. `java.util.Arrays.binarySearch0:1713` — 417 samples (5.7%, jdk) ← called from `java.util.Arrays.binarySearch`
   - Binary search inside JAR/classpath entry lookup — invoked transitively from resource resolution.
2. `java.net.URL.<init>:630` — 354 samples (4.8%, jdk) ← called from `java.net.URL.<init>`
   - URL object construction is being repeated thousands of times per second, pointing at uncached resource lookups through the Spring Boot nested-JAR handler.
3. `java.lang.ThreadLocal$ThreadLocalMap.getEntryAfterMiss:514` — 326 samples (4.4%, jdk) ← called from `java.lang.ThreadLocal$ThreadLocalMap.getEntry`
   - ThreadLocal map collisions (linear probe past the initial slot), typically a symptom of many ThreadLocals or thread reuse across heavy contexts.
4. `jdk.internal.loader.URLClassPath$Loader.findResource:534` — 308 samples (4.2%, jdk) ← called from `jdk.internal.loader.URLClassPath.findResource`
5. `java.util.Objects.hashCode:97` — 288 samples (3.9%, jdk) ← called from `org.springframework.boot.loader.net.protocol.jar.JarFileUrlKey.hashCode`
6. `java.net.URL.<init>:741` — 285 samples (3.9%, jdk) ← called from `java.net.URL.<init>`
7. `java.lang.String.equalsIgnoreCase:2056` — 234 samples (3.2%, jdk) ← called from `org.springframework.boot.loader.net.protocol.jar.JarFileUrlKey.equalsIgnoringCase`
8. `jdk.internal.util.ArraysSupport.mismatch:555` — 228 samples (3.1%, jdk) ← called from `java.lang.String.startsWith`
9. `sun.net.www.ParseUtil.firstEncodeIndex:95` — 212 samples (2.9%, jdk) ← called from `sun.net.www.ParseUtil.encodePath`
10. `java.net.URL.<init>:764` — 208 samples (2.8%, jdk) ← called from `java.net.URL.<init>`

## Allocation Hotspots
Allocation rate is **429.5 MB/s** sustained over 120 s — very high for a small heap, and the root reason GC fires every ~60 ms. **75.3% of allocated bytes are categorized as JDK** and **23.8% as framework**, with user code at only **0.9%**. The top allocated class is `byte[]` (43.9%), followed by `String` (17.8%), `java.net.URL` (16.9%), and Spring Boot's `JarFileUrlKey` (8.2%) — the same nested-JAR loader pattern visible in the CPU profile, manifesting as constant garbage from URL construction, byte-buffer copies, and cache key creation.

### Top Allocators
1. `java.util.Arrays.copyOfRange:3849` — 14275 MB (27.7%, jdk) allocating mostly `byte[]`
   - Generic byte-array copy hot path; given the call sites visible elsewhere, this is feeding nested-JAR entry reads and stream buffering.
2. `jdk.internal.misc.Unsafe.allocateUninitializedArray:1396` — 7223.4 MB (14%, jdk) allocating mostly `byte[]`
   - Low-level raw byte buffer allocation, again driven downstream by repeated JAR entry reads.
3. `java.lang.StringLatin1.newString:760` — 7017.3 MB (13.6%, jdk) allocating mostly `java.lang.String`
4. `jdk.internal.loader.URLClassPath$Loader.findResource:514` — 4400.1 MB (8.5%, jdk) allocating mostly `java.net.URL`
5. `org.springframework.boot.loader.net.protocol.jar.JarUrlConnection.open:340` — 4308.2 MB (8.4%, framework) allocating mostly `java.net.URL`

## Concurrency & Locks
Real `synchronized` contention is present: **813 JavaMonitorEnter events totaling 33.5 s of waiting**, concentrated on Spring Boot Loader's `UrlNestedJarFile` (16.4 s) and `UrlJarFiles$Cache` (13.0 s) monitors — the same nested-JAR machinery dominating CPU and allocation. The connection pool is **not** under pressure (no `connection_pool_wait` events). 100% of ThreadPark time falls in `pool_idle_wait` (1059 s on `awaitNanos`, typical executor/HikariCP idle threads waiting for work) — this is **benign**, not contention, despite the 36,745 ThreadPark events.

### Contended Monitors
1. `org.springframework.boot.loader.net.protocol.jar.UrlNestedJarFile` — 395 events, 16448.6 ms total (41.6 ms avg, max 83.3 ms) at `org.springframework.boot.loader.jar.NestedJarFile.hasEntry:251`
2. `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles$Cache` — 326 events, 12988.9 ms total (39.8 ms avg, max 79.4 ms) at `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles$Cache.get:158`
3. `java.lang.Object` — 36 events, 1691.3 ms total (47 ms avg, max 74.2 ms) at `sun.nio.ch.EPollSelectorImpl.clearInterrupt:295`
4. `java.util.Hashtable` — 39 events, 1668.5 ms total (42.8 ms avg, max 71.8 ms) at `java.util.Hashtable.get:382`
5. `java.util.jar.JarFile` — 7 events, 311 ms total (44.4 ms avg, max 49.9 ms) at `java.util.zip.ZipFile.getEntry:289`

### Notable Park Sites
All thread parking matches normal pool-idle or scheduled-task patterns — no findings.

## Findings
- **🔴 Spring Boot nested-JAR resolution dominates CPU, allocation, and lock contention**: Resource/URL resolution through `org.springframework.boot.loader.net.protocol.jar.*` and the JDK `URL`/`URLClassPath` it calls accounts for the top 10 CPU hotspots, ~30%+ of allocated bytes, and the top two contended monitors. **Evidence**: top CPU samples include `URL.<init>` (multiple lines, ~13% combined), `JarFileUrlKey.hashCode`/`equalsIgnoringCase` (~7%), `URLClassPath.findResource` (~6%); allocation: `java.net.URL` 16.9% and `JarFileUrlKey` 8.2% of bytes; monitor contention: `UrlNestedJarFile` (16.4 s) and `UrlJarFiles$Cache` (13.0 s) account for 88% of all monitor wait time. **Why it matters**: only 2.8% of CPU and 0.9% of allocation reach user/business code — the application is paying enormous overhead per request to locate resources inside the fat JAR.
- **🔴 Serial GC selected on a server workload**: Young collector is `DefNew` and old is `SerialOld`. **Evidence**: `configuration.young_collector="DefNew"`, `configuration.old_collector="SerialOld"`; 2040 GCs in 120 s (1018.8/min); one 127.28 ms SerialOld stop-the-world pause. **Why it matters**: SerialGC is single-threaded and inappropriate for any latency-sensitive service; it is being auto-selected because the container CPU limit is 1, and any future allocation crisis will produce long full-GC pauses (already saw 127 ms).
- **🔴 Allocation rate of 429.5 MB/s on a 94 MB heap**: Sustained allocation pressure is driving GC every ~60 ms. **Evidence**: `estimated_allocation_rate.mb_per_second=429.5`; heap committed 94.2 MB, peak used 65.9 MB (96.7% of committed); 2039 Allocation Failure GCs. **Why it matters**: The throughput cost (2.74% pause overhead is the visible part — the implicit cost is the CPU time inside the allocator and GC threads) is being paid by classpath/URL machinery, not user code.
- **🟡 High Java exception throw rate**: 259 exceptions/second sustained. **Evidence**: `derived.javaExceptionThrowPerSecond=258.9` (31,099 events / 120 s); top allocator #11 is `java.io.EOFException` from `NioEndpoint.fillReadBuffer` (333 MB allocated, 124 samples). **Why it matters**: Exception construction (with stack capture) is expensive and using exceptions for control flow — visible here in Tomcat's NIO read path — is a known anti-pattern; this rate suggests either control-flow exception use or repeated I/O EOFs on partial reads.
- **🟡 Contention on legacy `Hashtable` and `Collections.SynchronizedMap`**: Small but non-zero. **Evidence**: `Hashtable.get:382` 39 events / 1668.5 ms; `Collections$SynchronizedMap.get` 2 events / 67.9 ms. **Why it matters**: These types are coarse-grained `synchronized`; under higher load they will scale poorly compared to `ConcurrentHashMap`.
- **🟢 Single SerialOld full GC pause of 127 ms**: One-off event. **Evidence**: `anomalies.long_pauses_over_100ms=1`, `by_name SerialOld max_pause_ms=127.28`. **Why it matters**: Tail latency spike candidate; not actionable on its own but tied to the GC-selection finding above.
- **🔵 JFR Tracing overhead is the largest native category**: `Tracing` 112.1 MB committed (26.2% of total). **Evidence**: NMT category table. **Why it matters**: Expected because the recording is taken with `settings=profile`; not an application issue, but worth knowing this overhead is not present in production unless JFR is always-on at profile settings.

## Recommendations
1. **Spring Boot nested-JAR resolution dominates** — Repackage the application to avoid the nested-JAR loader at runtime. Options: (a) build an exploded image (`java -cp 'BOOT-INF/lib/*:BOOT-INF/classes' org.springframework.boot.loader.launch.JarLauncher` after `jar -xf`), (b) use Spring Boot's `layertools` and a layered Dockerfile so classes/libs live as plain files in the image, or (c) build a native image with Spring Boot AOT. Any of these eliminates `JarFileUrlKey` / `UrlNestedJarFile` / `URLClassPath` hot paths entirely. Also audit application/library code (likely Thymeleaf or a custom `ResourceLoader`) that is calling `getResource()` per request rather than caching, since the loader cache itself is being hammered.
2. **Serial GC selected on server workload** — Raise the container CPU request/limit to at least 2 (this alone causes the JVM to auto-select G1), or explicitly add `-XX:+UseG1GC` to JVM args. With a 2 GiB container, also set `-Xmx` explicitly (e.g. `-Xmx1g`) so the heap can grow beyond 94 MB and reduce GC frequency.
3. **Allocation rate of 429.5 MB/s** — Largely resolved by recommendation #1 (the allocation is being driven by the loader paths). After repackaging, re-profile; if allocation pressure persists, the next suspect is repeated `String` concatenation in hot paths (`StringConcatHelper.doConcat` is allocator #8 at 3.5%).
4. **High Java exception throw rate** — Investigate the source. Likely two contributors: (a) `EOFException` from Tomcat NIO reads (visible at allocator #11) indicating clients dropping connections or pipelined-read edge cases — check access logs for short/incomplete requests; (b) application code or a Spring component using exceptions for control flow. Enable an exception-class breakdown (e.g. `-Xlog:exceptions` briefly, or a JFR custom filter) to identify the dominant exception type.
5. **Contention on `Hashtable` / `SynchronizedMap`** — Trace the `Hashtable.get:382` call site (likely a JDK-internal or older library); if it lives in your code, migrate to `ConcurrentHashMap`. Low priority while it is < 5% of total monitor wait time.

## Analysis Limitations
This build analyzes CPU samples, GC behavior, object allocation, total memory footprint (with NMT for per-category native breakdown), and lock contention / thread parking. The following are NOT yet covered and would change the picture if data is available:
- I/O wait (file, socket) — jdk.FileRead, jdk.FileWrite, jdk.SocketRead, jdk.SocketWrite events not yet analyzed
- Native-method sampling (JNI compute, native I/O syscalls, park/wait)
- Class loading and JIT compilation overhead
- Exception throw analysis (raw counts visible in summary but no per-class breakdown yet)
