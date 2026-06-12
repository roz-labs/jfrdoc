# jfrdoc Analysis Report

## Executive Summary
This Spring Boot PetClinic recording shows a severe Spring Boot Loader inefficiency: 38%+ of on-CPU Java time is spent inside `org.springframework.boot.loader` JAR-URL handling (URL parsing, ZipString hashing, `equalsIgnoreCase`, jar-cache lookups), driving a 489 MB/s allocation rate dominated by `byte[]`, `String`, `java.net.URL`, and `JarFileUrlKey` objects. The same code path produces the only real synchronized contention in the recording (826 events, 28.2 s on `UrlJarFiles$Cache` and `UrlNestedJarFile`) and is the root cause of the ~1153 GCs/min frequency. Container memory and GC pauses are healthy; the bottleneck is per-request classpath/resource resolution overhead inside the fat-jar loader.

## Recording Context
- **File**: /Users/ridvanozcan/code/jfrdoc/samples/petclinic-ubi9/before/petclinic.jfr
- **Duration**: 120.9 s
- **JVM**: OpenJDK 64-Bit Server VM 21.0.11+10-LTS
- **OS**: linux-aarch64
- **Framework**: spring
- **Container limits**: memory=2Gi cpu=1
- **Total events captured**: 178,332

## Memory Footprint
Container fit: **SAFE** — 705.5 MB committed of 2048 MB limit (65.6% headroom). Java Heap dominates committed memory (324 MB / 45.9%), well below the 1367 MB Xmx ceiling. Tracing (100.6 MB) is unusually large because the JFR `profile` recording itself is active. Metaspace is close to its committed size (100.8 MB used of 101.7 MB committed, capped at 200 MB by `-XX:MaxMetaspaceSize=200m`) — currently fine but a signal to watch if class-loading grows.

### Memory Breakdown
- **Java Heap**: 324 MB committed (45.9%, 1368 MB reserved)
- **Tracing**: 100.6 MB committed (14.3%, 100.6 MB reserved)
- **Metaspace**: 88.3 MB committed (12.5%, 128.4 MB reserved)
- **Code**: 65 MB committed (9.2%, 248.3 MB reserved)
- **GC**: 49.9 MB committed (7.1%, 53.6 MB reserved)
- **Symbol**: 34.6 MB committed (4.9%, 34.6 MB reserved)
- **Class**: 16 MB committed (2.3%, 162.2 MB reserved)
- **Shared class space**: 12.7 MB committed (1.8%, 16 MB reserved)

## Garbage Collection
ParallelGC (ParallelScavenge / ParallelOld) is in use with a single GC thread, executing **2,325 collections in 120.9 s (1,153.5 GCs/min)** — extraordinarily high frequency driven by the 489 MB/s allocation rate. Pause overhead is 3.51% (p50=1.49 ms, p95=1.93 ms, p99=4.01 ms), so individual pauses stay short, but the cumulative collector activity is wasted CPU. Heap occupancy stays low (max 84.6 MB used of 311.5 MB committed; avg-after-GC 47 MB), confirming the workload is allocation-rate bound rather than retention bound.

### GC Anomalies
- **Long pause >100ms**: 1 occurrence (101.7 ms ParallelOld) — a single old-gen collection during the run; not a pattern but worth noting against any latency SLO.

## CPU Profile
Sampled at 30.1 samples/s (3,644 attributed samples, 100% attribution). The distribution is severely skewed away from a healthy Spring Boot pattern: **66.4% JDK, 30.8% framework, only 2.7% user_code**. Virtually all of the JDK and framework time is concentrated in Spring Boot Loader's URL/JAR handling — `URL.<init>` constructors, `ZipString.hash` via `String.hashCode`, `JarFileUrlKey.equalsIgnoringCase`, and `Handler.indexOfSeparator` together account for over 35% of CPU. User business logic (PetClinic controllers/services) is effectively invisible behind classloader/resource-lookup overhead.

### Top Hotspots
1. `java.lang.String.hashCode:2358` — 366 samples (10%, jdk) ← called from `org.springframework.boot.loader.zip.ZipString.hash`
   - String hashing inside Spring Boot Loader's nested-jar ZIP entry lookup — fired on every classpath/resource probe.
2. `java.net.URL.<init>:654` — 254 samples (7%, jdk) ← called from `java.net.URL.<init>`
   - URL object construction for `jar:nested:` URLs — Spring Boot's executable-jar format rebuilds these on every resource lookup.
3. `java.lang.String.equalsIgnoreCase:1982` — 222 samples (6.1%, jdk) ← called from `org.springframework.boot.loader.net.protocol.jar.JarFileUrlKey.equalsIgnoringCase`
   - Case-insensitive key comparison in the jar-file URL cache — high cost because the cache is being hit very frequently.
4. `java.net.URL.<init>:778` — 214 samples (5.9%, jdk) ← called from `java.net.URL.<init>`
5. `java.util.Arrays.binarySearch0:1717` — 213 samples (5.8%, jdk) ← called from `java.util.Arrays.binarySearch`
6. `jdk.internal.util.ArraysSupport.mismatch:393` — 191 samples (5.2%, jdk) ← called from `java.lang.String.startsWith`
7. `org.springframework.boot.loader.net.protocol.jar.Handler.indexOfSeparator:174` — 167 samples (4.6%, framework) ← called from `org.springframework.boot.loader.net.protocol.jar.Handler.indexOfSeparator`
8. `sun.net.www.ParseUtil.firstEncodeIndex:93` — 84 samples (2.3%, jdk) ← called from `sun.net.www.ParseUtil.encodePath`
9. `java.net.URL.<init>:801` — 82 samples (2.3%, jdk) ← called from `java.net.URL.<init>`
10. `java.util.HashMap.getNode:587` — 75 samples (2.1%, jdk) ← called from `java.util.HashMap.get`

## Native Execution
Native-method samples reflect time spent in JVM native code (syscalls / JNI), which is overwhelmingly **blocked-in-syscall wait time, not on-CPU work**. The signals confirm `dominated_by_wait_frames=true` with `wait_frame_pct=94.7%` and `likely_on_cpu_native_present=false`. The top two frames (`sun.nio.ch.Net.accept` and `sun.nio.ch.EPoll.wait`) are the Tomcat acceptor thread and the NIO selector event loop sitting idle waiting for connections/events — completely normal server behavior. The caller frame is the diagnostic signal here; nothing in this section indicates a hotspot.

### Top Native Methods
1. `sun.nio.ch.Net.accept` — 2,323 samples (48%, jdk) ← caller `sun.nio.ch.ServerSocketChannelImpl.implAccept` (100%)
   - Blocked in syscall waiting — benign Tomcat acceptor-thread behavior, not a hotspot.
2. `sun.nio.ch.EPoll.wait` — 2,260 samples (46.7%, jdk) ← caller `sun.nio.ch.EPollSelectorImpl.doSelect` (100%)
   - Blocked in syscall waiting — benign NIO event-loop behavior, not a hotspot.
3. `sun.nio.ch.SocketDispatcher.write0` — 103 samples (2.1%, jdk) ← caller `sun.nio.ch.SocketDispatcher.write` (100%)
4. `sun.nio.ch.EventFD.set0` — 63 samples (1.3%, jdk) ← caller `sun.nio.ch.EventFD.set` (100%)
5. `sun.nio.ch.Net.shutdown` — 30 samples (0.6%, jdk) ← caller `sun.nio.ch.SocketChannelImpl.implCloseNonBlockingMode` (100%)

## Allocation Hotspots
Estimated allocation rate is **489.1 MB/s** — extremely high — totaling ~59 GB of heap churn over the recording. The top class is `byte[]` (43.5% of bytes) followed by `java.lang.String` (17.4%) and `java.net.URL` (17.1%). The single largest allocator (`Arrays.copyOfRangeByte`, 28.1% of bytes) plus `Unsafe.allocateUninitializedArray` (13.2%) and `StringLatin1.newString` (13.1%) trace back to Spring Boot Loader's jar/URL processing path. Categories: **JDK 75.3%, framework 23.9%, user_code 0.8%** by bytes — confirming the allocation pressure is virtually all framework plumbing, not application logic. Attribution quality is excellent (0% unattributed bytes).

### Top Allocators
1. `java.util.Arrays.copyOfRangeByte:3863` — 16,611.6 MB (28.1%, jdk) allocating mostly `byte[]`
   - Byte-array slice copies — used heavily by Spring Boot Loader's ZIP/nested-jar entry reading on every classpath probe.
2. `jdk.internal.misc.Unsafe.allocateUninitializedArray:1380` — 7,809.5 MB (13.2%, jdk) allocating mostly `byte[]`
   - Raw byte-array allocation backing buffer/stream operations in the loader and NIO paths.
3. `java.lang.StringLatin1.newString:756` — 7,739 MB (13.1%, jdk) allocating mostly `java.lang.String`
4. `jdk.internal.loader.URLClassPath$Loader.findResource:604` — 5,159.9 MB (8.7%, jdk) allocating mostly `java.net.URL`
5. `org.springframework.boot.loader.net.protocol.jar.JarUrlConnection.open:340` — 4,965.7 MB (8.4%, framework) allocating mostly `java.net.URL`

## Concurrency & Locks
**Real monitor contention is present**: 826 `JavaMonitorEnter` events totaling 28.2 s of wait time, with the top contended monitor being `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles$Cache` (457 events, 14.3 s total, max 73.8 ms) — same Spring Boot Loader path that dominates CPU and allocation. The second contended monitor (`UrlNestedJarFile`, 295 events, 11.3 s) reinforces the pattern. Thread parking totals 1,724,952 ms but is overwhelmingly classified as `pool_idle_wait` (79.8%) and `condition_wait` (20.2%) — these are idle Tomcat worker threads and Hikari connection-pool idle waits, **not** contention. Connection-pool pressure signal is false.

### Contended Monitors
1. `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles$Cache` — 457 events, 14,280.2 ms total (31.2 ms avg, max 73.8 ms) at `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles$Cache.get:158`
2. `org.springframework.boot.loader.net.protocol.jar.UrlNestedJarFile` — 295 events, 11,341.6 ms total (38.4 ms avg, max 162 ms) at `org.springframework.boot.loader.jar.NestedJarFile.hasEntry:251`
3. `java.util.Hashtable` — 43 events, 1,552.6 ms total (36.1 ms avg, max 65.8 ms) at `java.util.Hashtable.get:380`
4. `java.lang.Object` — 19 events, 631.7 ms total (33.2 ms avg, max 61 ms) at `sun.nio.ch.EPollSelectorImpl.clearInterrupt:268`
5. `jdk.internal.loader.URLClassPath` — 10 events, 328 ms total (32.8 ms avg, max 57.5 ms) at `jdk.internal.loader.URLClassPath.getLoader:424`

### Notable Park Sites
All thread parking matches normal pool-idle or condition-wait patterns (79.8% pool_idle_wait, 20.2% condition_wait, only 1 lock_acquire_wait event) — no findings.

## Exception Activity
No exception events captured in this recording.

## I/O Activity
Total I/O blocking time is 3,771.7 ms across the run (3.1% of recording), 100% socket (no file I/O above threshold). The 93 slow socket writes (3,659 ms total) all hit endpoints on `172.18.0.3` from `sun.nio.ch.SocketChannelImpl.write` — i.e., the load generator / client side, not a database or downstream service (no database ports observed). Each endpoint shows a single ~60-70 ms write event, consistent with response-buffer flushes to a slow/throttled client, not application I/O latency. Note: JFR only records I/O above ~10ms, so fast operations are invisible — this section shows bottlenecks, not total I/O volume.

### Top I/O Targets
1. 172.18.0.3:57072 — 71.2 ms across 1 op (0 MB, max 71.2 ms) [socket]
   - Client-side socket write — likely client-side slowness or TCP backpressure on response flush, not an application issue.
2. 172.18.0.3:56274 — 69.3 ms across 1 op (0 MB, max 69.3 ms) [socket]
   - Same pattern as above — single slow response write to a client endpoint.
3. 172.18.0.3:57016 — 69.1 ms across 1 op (0 MB, max 69.1 ms) [socket]
4. 172.18.0.3:57548 — 65.7 ms across 1 op (0 MB, max 65.7 ms) [socket]
5. 172.18.0.3:50482 — 65.1 ms across 1 op (0 MB, max 65.1 ms) [socket]

## Findings
- **🔴 Spring Boot Loader jar-URL handling dominates the application**: Resource/classpath resolution inside `org.springframework.boot.loader` consumes the vast majority of CPU, allocation, and lock-contention budget. **Evidence**: top 10 CPU hotspots are all URL parsing / ZipString hashing / `JarFileUrlKey` comparisons (>35% of CPU), 8.4%+ of allocations come from `JarUrlConnection.open` and `UrlJarFiles$Cache` (>4.8 GB allocated each), `UrlJarFiles$Cache` is the #1 contended monitor (457 events, 14.3 s total wait). **Why it matters**: user code is only 2.7% of CPU and 0.8% of allocations — the application is spending nearly all its compute on filesystem-style lookups inside the fat-jar, which limits throughput and inflates response latency under load.
- **🔴 Extreme allocation rate driving GC churn**: ~489 MB/s of allocations, 75% from JDK code paths invoked by the loader. **Evidence**: 2,325 GCs in 120.9 s (1,153/min), 16.6 GB allocated via `Arrays.copyOfRangeByte` alone, 10.3 GB total `String` allocation, heap-max-used only 84.6 MB (allocation-rate bound, not retention bound). **Why it matters**: every allocated byte must be scanned by the collector; at this rate the JVM is wasting ~3.5% of recording time in GC pauses on a single CPU and burning core capacity on collector work that could serve requests.
- **🟡 Single-threaded ParallelGC on a 1-CPU container**: `parallel_gc_threads=1` confirms only one collector thread, with one ParallelOld pause of 101.7 ms recorded. **Evidence**: `XX:+UseParallelGC` configured, `parallel_gc_threads=1`, max pause 101.7 ms (ParallelOld), p99 4.01 ms. **Why it matters**: ParallelGC is a throughput-oriented stop-the-world collector; combined with the 489 MB/s allocation rate, even a small old-gen collection blocks the entire JVM for >100 ms. A low-pause collector would be safer here for tail latency.
- **🟡 Metaspace committed within 1 MB of its committed ceiling**: 100.8 MB used / 101.7 MB committed against a 200 MB max. **Evidence**: `metaspace_near_committed=true` signal, `-XX:MaxMetaspaceSize=200m`. **Why it matters**: not currently failing, but if dynamic class generation (proxies, expression compilation) continues to grow, metaspace will need to expand toward the hard cap.
- **🔵 Slow socket writes are client-side, not downstream**: All 10 slowest endpoints are ephemeral high-numbered ports on the load generator (172.18.0.3). **Evidence**: 93 socket writes totaling 3.66 s, no database ports, each endpoint a single slow write. **Why it matters**: context for I/O readers — there is no slow downstream service or database call here; the 3.1% I/O blocking time is client backpressure on response flush, expected under load testing.

## Recommendations
1. **Eliminate the Spring Boot Loader hot path** (addresses *Spring Boot Loader jar-URL handling dominates* and *Extreme allocation rate*): Repackage the application using `spring-boot-maven-plugin`'s exploded layout or build a "thin" image where dependencies are extracted to the filesystem (`java -Djarmode=tools extract` then run with `-cp` instead of `-jar app.jar`). This bypasses the nested-jar URL handler entirely and is the single highest-leverage change — it will simultaneously reduce CPU, allocation, GC frequency, and monitor contention. Alternatively, build a native image with Spring Boot's AOT/GraalVM support if the deployment model permits.
2. **Switch to a low-pause collector** (addresses *Single-threaded ParallelGC on a 1-CPU container*): Replace `-XX:+UseParallelGC` with `-XX:+UseG1GC` (default in JDK 21) or `-XX:+UseZGC` for the same heap size. G1 is a better fit for a 1.3 GB heap on a 1-CPU container, will smooth out the 101 ms outlier, and removes the need to tune `MinHeapFreeRatio`/`MaxHeapFreeRatio`/`GCTimeRatio`/`AdaptiveSizePolicyWeight` for ParallelGC.
3. **Monitor metaspace growth** (addresses *Metaspace committed within 1 MB of its committed ceiling*): If recommendation 1 is not adopted, raise `-XX:MaxMetaspaceSize` to 256m or remove the cap, and add metaspace usage to dashboards. With the loader path fixed, metaspace growth typically stabilizes; without that fix, watch for `OutOfMemoryError: Metaspace` under sustained class generation.

## Analysis Limitations
This build analyzes CPU samples, GC behavior, object allocation, total memory footprint (with NMT for per-category native breakdown), lock contention / thread parking, exception throws (per-class breakdown), file/socket I/O wait, and JVM native-method execution (blocked-in-syscall / JNI). The following are NOT yet covered and would change the picture if data is available:
- Class loading and JIT compilation overhead
- Note: I/O analysis covers only operations exceeding ~10ms; high-frequency fast I/O is aggregated in CPU/allocation profiles instead
