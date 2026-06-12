# jfrdoc Analysis Report

## Executive Summary
Spring Boot PetClinic is paying a severe tax for nested-jar classloading: **58% of on-CPU Java time is in `ZipFile$Source.getEntryPos`**, allocation runs at **~466 MB/s dominated by `byte[]`/`String`/`URL`/`JarFileUrlKey` churn**, and the same `UrlJarFiles$Cache` monitor accounts for 770 contention events. This is the classic "running Spring Boot from a fat jar via `java -jar`" pattern — extracting the jar (or using a layered/exploded layout) is the single highest-leverage fix. Container fit cannot be evaluated because NMT, although requested via JVM args, did not produce category data in this recording.

## Recording Context
- **File**: /Users/ridvanozcan/code/jfrdoc/samples/petclinic-ubi8/before/petclinic.jfr
- **Duration**: 122.384 s
- **JVM**: OpenJDK 64-Bit Server VM 17.0.19+10-LTS
- **OS**: linux-aarch64
- **Framework**: spring
- **Container limits**: memory=2Gi cpu=1
- **Total events captured**: 169,921

## Memory Footprint

Limited memory analysis: NMT was requested in JVM args (`-XX:NativeMemoryTracking=summary`) but no NMT category data is present in this recording, so total committed memory and per-category native breakdown are not evaluable. Heap is comfortably small (84.8 MB peak used against 311.5 MB committed and a 1368 MB `-Xmx`), but **metaspace is near committed (98.5 MB used of 99.1 MB committed, capped at 200 MB)** — expansion is likely under continued classloading pressure. Code cache is heavily over-provisioned (240 MB committed, ~36 MB used).

### Memory Breakdown
- **Heap**: 311.5 MB committed, 84.8 MB peak used
- **Metaspace**: 99.1 MB committed, 98.5 MB used
- **Code Cache**: 240 MB committed, ~36.1 MB used
- **Thread stacks (estimated)**: ~31 MB across 31 threads (using 1024 KB default; actual may differ)
- **Other native memory**: unknown (NMT data not present)

▶ NMT was requested but did not emit category data — verify `jdk.NativeMemoryUsage`/`jdk.NativeMemoryUsageTotal` events are enabled in the recording template to enable full container-fit analysis.

## Garbage Collection

ParallelGC (ParallelScavenge young + ParallelOld old) with a single GC thread (CPU-limit=1 constraining it) ran **2,234 collections in 122 s (1,095/min)**, contributing **3.61% pause overhead**. Pauses are short on average (p50=1.55 ms, p99=3.27 ms) — typical for the small heap — but one ParallelOld collection spiked to **229.5 ms**. Heap occupancy stays low (36–85 MB), so GC pressure is driven by allocation rate, not heap exhaustion.

### GC Anomalies
- **1 long pause >100ms**: a single 229.5 ms ParallelOld collection. With CPU limit=1, parallel GC has only one worker, amplifying single-threaded compaction time.

## CPU Profile
Attribution quality is perfect (100% attributed). On-CPU Java is **overwhelmingly JDK code (82.3%)**, with framework code at 16% and **user code at just 1.7%** — meaning almost none of the CPU is being spent in PetClinic business logic. The signature is clear: a single method (`ZipFile$Source.getEntryPos`) consumes **58% of all CPU samples**, and the surrounding hotspots (`URL.<init>`, `URLClassPath`, `Handler.indexOfSeparator`, `JarFileUrlKey.equalsIgnoringCase`) are all part of the Spring Boot nested-jar classloader resource-lookup path. The 49.7 samples/s rate is right at the threshold — adequate but not generous.

### Top Hotspots
1. `java.util.zip.ZipFile$Source.getEntryPos:1816` — 3530 samples (58%, jdk) ← called from `java.util.zip.ZipFile.getEntry`
   - Every nested-jar resource lookup linearly scans/hashes the central directory; this dominates the whole profile.
2. `java.net.URL.<init>:703` — 153 samples (2.5%, jdk) ← called from `java.net.URL.<init>`
   - URL construction during classloader/resource resolution — same root cause as #1.
3. `java.util.concurrent.ConcurrentHashMap.get:946` — 141 samples (2.3%, jdk) ← called from `java.net.URLConnection.getDefaultUseCaches`
   - URLConnection caching lookups — also classloader path.
4. `java.net.URL.<init>:569` — 125 samples (2.1%, jdk) ← called from `java.net.URL.<init>`
5. `java.util.HashMap.getNode:570` — 123 samples (2%, jdk) ← called from `java.util.HashMap.get`
6. `org.springframework.boot.loader.zip.ZipContent.getFirstLookupIndex:283` — 117 samples (1.9%, framework) ← called from `org.springframework.boot.loader.zip.ZipContent.hasEntry`
7. `java.lang.String.equalsIgnoreCase:1968` — 106 samples (1.7%, jdk) ← called from `org.springframework.boot.loader.net.protocol.jar.JarFileUrlKey.equalsIgnoringCase`
8. `java.net.URLStreamHandler.setURL:514` — 103 samples (1.7%, jdk) ← called from `java.net.URLStreamHandler.parseURL`
9. `java.util.Arrays.binarySearch0:1716` — 87 samples (1.4%, jdk) ← called from `java.util.Arrays.binarySearch`
10. `org.springframework.boot.loader.net.protocol.jar.Handler.indexOfSeparator:174` — 82 samples (1.3%, framework) ← called from same

## Native Execution

These samples represent time in JVM native execution — mostly blocked-in-syscall / wait time, not on-CPU work. **94.8% of native samples are in wait frames**: `Net.accept` (48%) from the Tomcat acceptor thread and `EPoll.wait` (46.8%) from the NIO selector event loop. This is normal idle/wait behavior for a server — the acceptor and the selector sit in syscalls waiting for connections and events — **not** a performance problem and should not be added to the CPU profile. No genuinely on-CPU native work (compression, crypto, JNI compute) was detected.

### Top Native Methods
1. `sun.nio.ch.Net.accept` — 2293 samples (48%, jdk) ← caller `sun.nio.ch.ServerSocketChannelImpl.implAccept` (100%)
   - Blocked in syscall waiting — benign Tomcat acceptor behavior, not a hotspot.
2. `sun.nio.ch.EPoll.wait` — 2238 samples (46.8%, jdk) ← caller `sun.nio.ch.EPollSelectorImpl.doSelect` (100%)
   - Blocked in epoll_wait — benign NIO event-loop idle wait, not a hotspot.
3. `sun.nio.ch.FileDispatcherImpl.write0` — 96 samples (2%, jdk) ← caller `sun.nio.ch.SocketDispatcher.write` (100%)
4. `sun.nio.ch.EventFD.set0` — 68 samples (1.4%, jdk) ← caller `sun.nio.ch.EventFD.set` (100%)
5. `sun.nio.ch.Net.shutdown` — 29 samples (0.6%, jdk) ← caller `sun.nio.ch.SocketChannelImpl.implCloseNonBlockingMode` (100%)

## Allocation Hotspots

Allocation runs at **~465.7 MB/s**, which is high and directly explains the 1,095 GCs/min rate. Top class is `byte[]` (44.1% of bytes) followed by `java.lang.String` (16.9%), `java.net.URL` (16.5%), and `JarFileUrlKey` (8.3%). The **#1 and #4 allocation sites are `Arrays.copyOfRange` (28.7%, in zip entry reads) and `JarUrlConnection.open` (8.4%)** — same classloader/nested-jar path that dominates CPU. JDK code accounts for 75.1% of allocated bytes; framework code 23.8%; **user code only 1.1%**. Sample attribution is essentially perfect.

### Top Allocators
1. `java.util.Arrays.copyOfRange:3822` — 16,347 MB (28.7%, jdk) allocating mostly `byte[]`
   - Called from zip-entry read paths to copy decompressed/raw bytes into request-sized buffers — the cost of nested-jar resource reads.
2. `jdk.internal.misc.Unsafe.allocateUninitializedArray:1375` — 7,545 MB (13.3%, jdk) allocating mostly `byte[]`
   - Backing array allocation for `byte[]` instances; the volume is a consequence of the same lookup churn.
3. `java.lang.StringLatin1.newString:769` — 7,458 MB (13.1%, jdk) allocating mostly `java.lang.String`
4. `org.springframework.boot.loader.net.protocol.jar.JarUrlConnection.open:340` — 4,808 MB (8.4%, framework) allocating mostly `java.net.URL`
5. `jdk.internal.loader.URLClassPath$Loader.findResource:607` — 4,592 MB (8.1%, jdk) allocating mostly `java.net.URL`

## Concurrency & Locks

**Real contention is present**: 1,086 `JavaMonitorEnter` events totalling 39.2 s of wait time, concentrated almost entirely on Spring Boot's nested-jar caches. **`UrlJarFiles$Cache` is by far the most-contended monitor — 770 events / 26.8 s — followed by `UrlNestedJarFile` (241 events / 9.4 s)**, both serializing on classloader resource lookups. Thread parking (40,725 events, 1,249 s of park time) is **100% categorized as `pool_idle_wait`** — Tomcat worker / connection-pool idle threads sitting in `awaitNanos`; this is normal and not a finding. `connection_pool_under_pressure` is false.

### Contended Monitors
1. `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles$Cache` — 770 events, 26836.3 ms total (34.9 ms avg, max 75 ms) at `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles$Cache.get:158`
2. `org.springframework.boot.loader.net.protocol.jar.UrlNestedJarFile` — 241 events, 9357.9 ms total (38.8 ms avg, max 231.2 ms) at `org.springframework.boot.loader.jar.NestedJarFile.hasEntry:251`
3. `java.lang.Object` — 36 events, 1479.6 ms total (41.1 ms avg, max 72.9 ms) at `sun.nio.ch.EPollSelectorImpl.clearInterrupt:262`
4. `java.util.Hashtable` — 24 events, 900 ms total (37.5 ms avg, max 62.5 ms) at `java.util.Hashtable.get:380`
5. `java.util.jar.JarFile` — 4 events, 234.2 ms total (58.6 ms avg, max 62.4 ms) at `java.util.zip.ZipFile.getEntry:337`

### Notable Park Sites
All thread parking matches normal pool-idle patterns (100% by time) — no findings.

## Exception Activity

No exception events captured in this recording.

## I/O Activity

Total I/O blocking time is **3,222.8 ms (2.7% of the recording)**, dominated by **socket writes — 72 events, 3,009 ms** with no significant file I/O. The top endpoints are individual outbound writes to clients on 172.18.0.3 with ephemeral ports (53796, 53068, …) at 60–74 ms each; no single endpoint dominates and no database-port traffic appears (PetClinic with H2 in-memory has no socket DB calls — expected). The slow socket writes look like load-generator clients receiving large HTML responses (Thymeleaf-rendered pages) slowly. Remember that JFR only captures I/O above ~10 ms, so this represents slow operations, not total I/O volume.

### Top I/O Targets
1. 172.18.0.3:53796 — 74.1 ms across 1 op (0 MB, max 74.1 ms) [socket]
   - Slow response write to an HTTP client; likely reflects client receive speed or response size rather than a server bottleneck.
2. 172.18.0.3:53068 — 71.9 ms across 1 op (0 MB, max 71.9 ms) [socket]
   - Same pattern — a single slow client-bound write.
3. 172.18.0.3:60054 — 69.1 ms across 1 op (0 MB, max 69.1 ms) [socket]
4. 172.18.0.3:55144 — 68 ms across 1 op (0 MB, max 68 ms) [socket]
5. 172.18.0.3:46348 — 64.5 ms across 1 op (0 MB, max 64.5 ms) [socket]

## Findings

- **🔴 Nested-jar resource lookup dominates CPU**: `ZipFile$Source.getEntryPos` consumes 58% of all on-CPU Java samples; combined with `URL.<init>`, `URLClassPath`, and Spring Boot `JarFileUrlKey`/`Handler` frames, the nested-jar classloader path is >70% of CPU. **Evidence**: 3,530/6,085 samples in `getEntryPos`; user_code only 1.7% of CPU; framework `org.springframework.boot.loader.*` repeatedly in top callers. **Why it matters**: virtually all CPU budget is paid to classloader plumbing, not application work — directly limits throughput and tail latency.

- **🔴 Allocation rate ~466 MB/s driven by classloader churn**: `byte[]` (44.1%) and `String` (16.9%) from zip-entry reads plus `URL` (16.5%) and `JarFileUrlKey` (8.3%) from `JarUrlConnection.open`/`URLClassPath.findResource` together explain almost half of allocated bytes. **Evidence**: `Arrays.copyOfRange` 16.3 GB (28.7%), `JarUrlConnection.open` 4.8 GB (8.4%), `URLClassPath$Loader.findResource` 4.6 GB (8.1%); user_code only 1.1% of allocations. **Why it matters**: drives 1,095 GCs/min and amplifies the CPU cost above.

- **🔴 Synchronized contention on `UrlJarFiles$Cache`**: 770 contention events (26.8 s total wait) on a single Spring Boot loader cache, plus 241 events / 9.4 s on `UrlNestedJarFile`. **Evidence**: `monitor_contention.total_events`=1,086, top monitor `UrlJarFiles$Cache.get:158` at 34.9 ms avg / 75 ms max. **Why it matters**: serializes request threads through classloader cache operations under concurrent load; max 231 ms wait on `UrlNestedJarFile.hasEntry` is a direct tail-latency contributor.

- **🟡 Metaspace near committed ceiling**: 98.5 MB used of 99.1 MB committed, capped at 200 MB. **Evidence**: `signals.metaspace_near_committed=true`; `MaxMetaspaceSize=200m`. **Why it matters**: continued classloading (e.g., Spring proxies, Thymeleaf templates, hot reload) can trigger metaspace expansion or, at 200 MB, `OutOfMemoryError: Metaspace`.

- **🟡 GC parallelism throttled by CPU limit**: ParallelGC reports `parallel_gc_threads=1`, producing a 229.5 ms ParallelOld pause. **Evidence**: `configuration.parallel_gc_threads=1`, `max_pause_ms=229.51` on a single ParallelOld event. **Why it matters**: with CPU limit=1, compaction cannot parallelize; a single rare full collection becomes a noticeable tail-latency event. A low-pause collector (G1/ZGC) or a higher CPU request would shorten this.

- **🟡 NMT requested but no category data emitted**: JVM args include `-XX:NativeMemoryTracking=summary`, but the recording contains no NMT events, so container fit against the 2Gi limit cannot be evaluated. **Evidence**: `nmt.available=false`; `enable_nmt_recommended=true`. **Why it matters**: heap is small (85 MB peak), but without NMT we cannot rule out native-memory growth pushing total RSS toward the 2Gi pod limit.

- **🔵 Park time is benign idle**: 1,249 s of park time is 100% `pool_idle_wait` (Tomcat workers/condition waits). **Evidence**: `by_category.pool_idle_wait=100%`, `connection_pool_under_pressure=false`. **Why it matters**: no action needed; mentioned to prevent misreading the 40k ThreadPark events as contention.

## Recommendations

1. **(Nested-jar CPU + allocation + contention)** — Stop running the fat jar with `java -jar app.jar`. Either:
   - Extract the layered jar at image-build time and start with the exploded layout: `java -cp app:app/lib/* org.springframework.boot.loader.launch.JarLauncher`, or
   - Use Spring Boot's `tools` launcher (`java -Djarmode=tools -jar app.jar extract` then run from the extracted directory), or
   - Build a CDS/AOT-friendly image (Spring Boot 3.x supports `-XX:ArchiveClassesAtExit` and class-data sharing) — this eliminates the nested-jar lookup path entirely and should remove the `ZipFile.getEntryPos` hotspot, drop allocation rate substantially, and erase the `UrlJarFiles$Cache` contention.

2. **(GC parallelism throttled)** — Either raise the container CPU request/limit above 1 (allowing ParallelGC multiple workers) or switch to a low-pause collector: `-XX:+UseG1GC` (default-friendly at this heap size) or `-XX:+UseZGC` for sub-10ms pauses. Given heap stays under 100 MB, G1 is the simpler choice.

3. **(Metaspace near ceiling)** — Raise `-XX:MaxMetaspaceSize` from 200 MB to ~384 MB and remove/raise `-XX:MetaspaceSize` to give headroom; monitor `jdk.MetaspaceSummary` after deploying recommendation #1, which should also reduce metaspace pressure by avoiding repeated jar/URL handler classloading.

4. **(NMT not emitted)** — Confirm the JFR recording template includes `jdk.NativeMemoryUsage` and `jdk.NativeMemoryUsageTotal` (these are not in the default `profile.jfc`; add them via custom template or `-XX:StartFlightRecording=...,settings=profile,jdk.NativeMemoryUsage#enabled=true,jdk.NativeMemoryUsageTotal#enabled=true`). Re-record to obtain container-fit verdict.

## Analysis Limitations
This build analyzes CPU samples, GC behavior, object allocation, total memory footprint (with NMT for per-category native breakdown), lock contention / thread parking, exception throws (per-class breakdown), file/socket I/O wait, and JVM native-method execution (blocked-in-syscall / JNI). The following are NOT yet covered and would change the picture if data is available:
- Class loading and JIT compilation overhead
- Note: I/O analysis covers only operations exceeding ~10ms; high-frequency fast I/O is aggregated in CPU/allocation profiles instead
