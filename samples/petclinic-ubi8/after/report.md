# jfrdoc Analysis Report

## Executive Summary
This Spring Boot Petclinic recording is dominated by JAR/URL resource lookups: **60.4% of all on-CPU Java samples** are in `StringLatin1.hashCode` driven by `java.net.URL` hashing during nested-JAR resource resolution, and the same code path is the #1 source of monitor contention (`UrlJarFiles$Cache` — 1066 contention events, ~40 s total wait). Allocation pressure is extreme at **457 MB/s**, with `byte[]`, `java.lang.String`, and `java.net.URL` accounting for ~79% of bytes — again driven by Spring Boot's nested-JAR loader. GC handles the churn safely (0.98% pause overhead, max 95 ms) and there is no slow database or downstream-service I/O.

## Recording Context
- **File**: /Users/ridvanozcan/code/jfrdoc/samples/petclinic-ubi8/after/petclinic.jfr
- **Duration**: 122.4 s
- **JVM**: OpenJDK 64-Bit Server VM 17.0.19+10-LTS
- **OS**: linux-aarch64
- **Framework**: spring
- **Container limits**: memory=2048 MB cpu=1
- **Total events captured**: 230,243

## Memory Footprint

Limited memory analysis: NMT was requested via `-XX:NativeMemoryTracking=summary` in JVM args, but per-category native memory data is not present in this recording. Heap, metaspace, code cache, and thread count are visible; total JVM footprint and container fit are not computable here.

Heap is healthy: 342 MB committed, peak used 317.5 MB (92.8% of committed) against a 1367 MB max — plenty of room to grow. Metaspace is near its committed size (98.4 MB used of 99.1 MB committed, capped at 200 MB) which is normal steady-state for Spring Boot. Code cache is large (240 MB committed) but only ~37 MB used. Thread count is modest at 31 peak.

### Memory Breakdown
- **Heap**: 342 MB committed, 317.5 MB peak used
- **Metaspace**: 99.1 MB committed, 98.4 MB used
- **Code Cache**: 240 MB committed, ~37.2 MB used
- **Thread stacks (estimated)**: ~31 MB across 31 threads (using 1024 KB default; actual may differ)
- **Other native memory**: unknown (NMT data not captured in recording)

▶ NMT was requested in JVM args but no NMT events appear in the file — verify the JFR template (`profile`) is collecting `jdk.NativeMemoryUsage`/`jdk.NativeMemoryUsageTotal` events, or capture an `nmt summary` jcmd snapshot alongside JFR for full container-fit analysis.

## Garbage Collection

G1 (G1New young + G1Old) ran 277 collections in 122 s (135.8/min) with **0.98% pause overhead** — well within tolerance. Pause distribution is healthy at p50 = 2.5 ms, p95 = 5.8 ms, p99 = 63 ms, max = 94.7 ms; all pauses stayed under the 200 ms MaxGCPauseMillis target. Every collection was a G1 Evacuation Pause (no mixed/old or full GC), with one preventive collection. The high frequency reflects the very high allocation rate (see below), not a GC misconfiguration.

### GC Anomalies
No anomalies detected.

## CPU Profile

On-CPU Java time is overwhelmingly in the JDK (82.9%) with framework code at 15.9% and user code at just 1.1% — extremely unusual for a request-serving Spring Boot app, where 30–60% user_code is typical. The signature is unmistakable: 60.4% of all samples are in `StringLatin1.hashCode` called from `String.hashCode`, and most of the remaining hot frames (`java.net.URL.<init>`, `URLStreamHandler.parseURL`, `ZipContent.getFirstLookupIndex`, `Handler.indexOfSeparator`) belong to Spring Boot's nested-JAR URL handler. This means the application is spending the bulk of its CPU resolving and hashing JAR-relative URLs for classloader/resource lookups, not running business logic. Sample quality is perfect (100% attributed).

### Top Hotspots
1. `java.lang.StringLatin1.hashCode:196` — 4179 samples (60.4%, jdk) ← called from `java.lang.String.hashCode`
   - Hashing String keys, dominantly for `JarFileUrlKey` and URL lookups in the nested-JAR loader cache.
2. `java.net.URL.<init>:569` — 151 samples (2.2%, jdk) ← called from `java.net.URL.<init>`
   - URL parsing/construction during repeated resource resolution — each constructor triggers protocol/handler validation.
3. `org.springframework.boot.loader.zip.ZipContent.getFirstLookupIndex:283` — 143 samples (2.1%, framework) ← called from `org.springframework.boot.loader.zip.ZipContent.hasEntry`
   - Nested JAR entry lookup — Spring Boot fat-JAR loader walking inner-JAR indexes on each `hasEntry` call.
4. `java.util.HashMap.getNode:579` — 124 samples (1.8%, jdk) ← called from `java.util.HashMap.get`
5. `java.net.URL.<init>:635` — 108 samples (1.6%, jdk) ← called from `java.net.URL.<init>`
6. `java.lang.String.equalsIgnoreCase:1968` — 106 samples (1.5%, jdk) ← called from `org.springframework.boot.loader.net.protocol.jar.JarFileUrlKey.equalsIgnoringCase`
7. `java.util.Arrays.binarySearch0:1716` — 99 samples (1.4%, jdk) ← called from `java.util.Arrays.binarySearch`
8. `java.net.URLStreamHandler.setURL:514` — 81 samples (1.2%, jdk) ← called from `java.net.URLStreamHandler.parseURL`
9. `java.net.URL.<init>:680` — 52 samples (0.8%, jdk) ← called from `java.net.URL.<init>`
10. `java.net.URL.<init>:703` — 47 samples (0.7%, jdk) ← called from `java.net.URL.<init>`

## Native Execution

The 4739 native-method samples represent time in JVM native execution (syscalls / JNI), which is overwhelmingly **blocked-in-syscall wait time, not on-CPU work**. Signals confirm: 94.2% of native samples are in known wait frames, and `likely_on_cpu_native_present` is false. The top two — `sun.nio.ch.Net.accept` (48%) from the Tomcat acceptor thread and `sun.nio.ch.EPoll.wait` (46.2%) from the NIO selector — are completely normal idle server behavior: an acceptor blocked waiting for the next connection and an event loop blocked waiting for socket readiness. This is NOT a performance problem and should not be added to the CPU profile numbers. The caller frame is the diagnostic signal here, not the native method.

### Top Native Methods
1. `sun.nio.ch.Net.accept` — 2274 samples (48%, jdk) ← caller `sun.nio.ch.ServerSocketChannelImpl.implAccept` (100%)
   - Blocked in syscall waiting for next TCP connection — benign Tomcat acceptor behavior, not a hotspot.
2. `sun.nio.ch.EPoll.wait` — 2188 samples (46.2%, jdk) ← caller `sun.nio.ch.EPollSelectorImpl.doSelect` (100%)
   - Blocked in epoll waiting for socket events — benign NIO event-loop behavior, not a hotspot.
3. `sun.nio.ch.FileDispatcherImpl.write0` — 116 samples (2.4%, jdk) ← caller `sun.nio.ch.SocketDispatcher.write` (100%)
4. `sun.nio.ch.EventFD.set0` — 71 samples (1.5%, jdk) ← caller `sun.nio.ch.EventFD.set` (100%)
5. `sun.nio.ch.Net.shutdown` — 38 samples (0.8%, jdk) ← caller `sun.nio.ch.SocketChannelImpl.implCloseNonBlockingMode` (100%)

## Allocation Hotspots

Allocation rate is **457.4 MB/s** — very high — totalling ~55.6 GB allocated over the 122 s recording. The top three allocated classes are `byte[]` (43.3%), `java.lang.String` (17.9%), and `java.net.URL` (17.6%); together with `JarFileUrlKey` (8.2%) they account for 87% of bytes. Allocation is 75.2% JDK-categorised and 24% framework — only 0.8% in user code. The dominant allocation sites (`Arrays.copyOfRange`, `StringLatin1.newString`, `Unsafe.allocateUninitializedArray`, `JarUrlConnection.open`, `URLClassPath$Loader.findResource`) match the CPU profile exactly: this churn is being driven by Spring Boot's nested-JAR URL handler resolving resources repeatedly. Sample quality is excellent (99.8% attributed by samples, 100% by bytes).

### Top Allocators
1. `java.util.Arrays.copyOfRange:3822` — 15698.8 MB (28%, jdk) allocating mostly `byte[]`
   - Buffer slicing inside the JAR/ZIP read path — every resource lookup copies bytes out of the nested-JAR backing store.
2. `java.lang.StringLatin1.newString:769` — 7872.6 MB (14.1%, jdk) allocating mostly `java.lang.String`
   - String construction from byte ranges — directly downstream of the URL-parsing hot path.
3. `jdk.internal.misc.Unsafe.allocateUninitializedArray:1375` — 7503.5 MB (13.4%, jdk) allocating mostly `byte[]`
4. `org.springframework.boot.loader.net.protocol.jar.JarUrlConnection.open:340` — 5084.8 MB (9.1%, framework) allocating mostly `java.net.URL`
5. `jdk.internal.loader.URLClassPath$Loader.findResource:607` — 4763.8 MB (8.5%, jdk) allocating mostly `java.net.URL`

## Concurrency & Locks

Real synchronized contention IS present: **1066 JavaMonitorEnter events totalling 39.6 s of wait time**, dominated by `UrlJarFiles$Cache` (680 events, 24 s, top site `UrlJarFiles$Cache.get:158`) and `UrlNestedJarFile` (296 events, 11.7 s) — i.e., threads serializing on the Spring Boot nested-JAR cache. This is the same code path as the CPU and allocation hotspots and confirms multiple request threads are concurrently driving classloader/resource lookups through a contended `synchronized` cache. No connection-pool pressure, and the very large ThreadPark counts (34,792 events, 1.18 M ms) are 100% in `pool_idle_wait` — normal Tomcat / executor idle behavior, NOT contention.

### Contended Monitors
1. `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles$Cache` — 680 events, 24031 ms total (35.3 ms avg, max 78.5 ms) at `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles$Cache.get:158`
2. `org.springframework.boot.loader.net.protocol.jar.UrlNestedJarFile` — 296 events, 11657 ms total (39.4 ms avg, max 78.7 ms) at `org.springframework.boot.loader.jar.NestedJarFile.hasEntry:251`
3. `java.util.Hashtable` — 45 events, 1775 ms total (39.4 ms avg, max 67.6 ms) at `java.util.Hashtable.get:380`
4. `java.lang.Object` — 27 events, 1212 ms total (44.9 ms avg, max 73.3 ms) at `sun.nio.ch.EPollSelectorImpl.clearInterrupt:262`
5. `jdk.internal.loader.URLClassPath` — 14 events, 698.8 ms total (49.9 ms avg, max 68.1 ms) at `jdk.internal.loader.URLClassPath.getLoader:429`

### Notable Park Sites
1. `java.util.concurrent.locks.LockSupport.park:211` — 16 events, 642.4 ms parked (lock_acquire_wait)
   Caller: `java.util.concurrent.locks.AbstractQueuedSynchronizer.acquire:715`
   - Real lock contention via AQS — small in absolute time but consistent with the monitor contention above; investigate the lock at this site for granularity.

## Exception Activity

No exception events captured in this recording.

## I/O Activity

Total slow-I/O blocking time is just 4.4 s over 122 s of recording (3.9%), and all of it is socket writes/reads to client IP `172.18.0.3` on many ephemeral ports (each event a one-shot ~65 ms response write from `SocketChannelImpl.write`). This is the load generator on the other side of the connection — not a database or downstream service. There is no file I/O above threshold and no single endpoint dominates (each request is a different ephemeral port). Reminder: JFR only captures I/O operations exceeding ~10 ms; fast I/O is invisible — this section shows there is **no slow downstream/DB bottleneck**, not that the app does no I/O.

### Top I/O Targets
1. 172.18.0.3:37168 — 68.7 ms across 1 op (0 MB, max 68.7 ms) [socket]
   - Single slow HTTP response write to the load generator — likely tail-latency event correlated with a GC pause or contention spike.
2. 172.18.0.3:47652 — 68.6 ms across 1 op (0 MB, max 68.6 ms) [socket]
   - Same pattern — these are individual slow response writes, not a repeated endpoint problem.
3. 172.18.0.3:46744 — 68.3 ms across 1 op (0 MB, max 68.3 ms) [socket]
4. 172.18.0.3:45718 — 67.9 ms across 1 op (0 MB, max 67.9 ms) [socket]
5. 172.18.0.3:37316 — 67.1 ms across 1 op (0 MB, max 67.1 ms) [socket]

## Findings

- **🔴 Spring Boot nested-JAR URL handler is the dominant hot path**: 60.4% of on-CPU samples sit in `StringLatin1.hashCode` from `String.hashCode`, and the top monitor (`UrlJarFiles$Cache`, 680 events / 24 s wait) plus the top allocators (`JarUrlConnection.open`, `URLClassPath$Loader.findResource`, `Arrays.copyOfRange` slicing JAR bytes) all live in the same code path. **Evidence**: top method 4179 samples (60.4%); JDK category dominates CPU at 82.9%, user_code only 1.1%; monitor contention 1066 events totalling 39.6 s on `UrlJarFiles$Cache` and `UrlNestedJarFile`. **Why it matters**: the application is spending the majority of its CPU and serializing on a `synchronized` cache for classloader/resource lookups instead of running business logic, capping throughput per CPU on this 1-CPU container.
- **🔴 Very high allocation rate (~457 MB/s)**: Driven almost entirely by the nested-JAR/URL path — `byte[]` 43.3%, `String` 17.9%, `java.net.URL` 17.6%, `JarFileUrlKey` 8.2%. **Evidence**: 55.6 GB allocated in 122 s; `Arrays.copyOfRange` alone 15.7 GB (28%); `URLClassPath$Loader.findResource` 4.76 GB. **Why it matters**: this allocation churn is what's driving 277 G1 young collections (135/min); eliminating it would simultaneously reduce CPU time, GC pressure, and the lock contention above.
- **🟡 Metaspace near its committed size**: 98.4 MB used of 99.1 MB committed (max 200 MB). **Evidence**: `metaspace_near_committed = true`. **Why it matters**: expected for Spring Boot steady-state, but worth confirming `-XX:MaxMetaspaceSize=200m` has enough headroom for any dynamic class generation (proxies, compiled SpEL/Thymeleaf) under production load.
- **🟡 NMT data missing from recording despite being enabled in JVM args**: `-XX:NativeMemoryTracking=summary` is set, but no NMT events appear in the file. **Evidence**: `nmt.available = false`; `categories_count = 0`. **Why it matters**: total JVM committed memory and container-fit verdict against the 2 GiB limit cannot be evaluated.
- **🔵 GC behavior is healthy**: 0.98% pause overhead, p99 = 63 ms, max = 94.7 ms, no full GCs or evacuation failures. **Evidence**: `pause_overhead_pct = 0.98`; `anomalies` all zero. **Why it matters**: G1 is absorbing the allocation churn well — the allocation problem is a CPU/throughput problem, not yet a pause-time problem.
- **🔵 No slow downstream or database I/O**: only 4.4 s of slow socket I/O total, all client-side response writes to a single load-generator IP. **Evidence**: `total_io_blocking_time_ms = 4438.6`; no database ports (5432/3306/etc.) seen. **Why it matters**: confirms the bottleneck is in-JVM work (JAR resolution), not external dependencies.

## Recommendations

1. **(Nested-JAR hot path)** Address the Spring Boot fat-JAR URL/resource resolution overhead. Concrete options, highest impact first:
   - **Switch the container image to use exploded layout** instead of the fat JAR — run with `java -cp 'BOOT-INF/lib/*:BOOT-INF/classes' org.springframework.boot.loader.launch.JarLauncher` or `org.springframework.boot.loader.launch.PropertiesLauncher` over an extracted directory (`java -Djarmode=tools -jar app.jar extract`). This eliminates the entire nested-JAR `URL` resolution path that dominates CPU, allocation, and contention.
   - **Build a CDS/AppCDS archive** so classloader lookups for already-loaded classes don't hit the JAR cache: `-XX:ArchiveClassesAtExit=/app/app.jsa` once, then `-XX:SharedArchiveFile=/app/app.jsa` at runtime.
   - **Verify production code paths are not calling `ClassLoader.getResource(s)` / `ResourcePatternResolver` per-request** — if a user-code path is repeatedly resolving classpath resources (e.g., a custom Thymeleaf template resolver or config lookup), cache the resolved result.
2. **(High allocation rate)** Reductions from #1 will cascade into allocation reductions since `JarUrlConnection.open`, `URLClassPath$Loader.findResource`, and `Arrays.copyOfRange` (28% of all bytes) are the same code path. After #1, re-profile and confirm allocation rate drops materially before pursuing further allocation-site optimizations.
3. **(Metaspace headroom)** Monitor metaspace under sustained production load; if `used_mb` continues to approach `MaxMetaspaceSize=200m`, raise to 256–384 MB and re-check.
4. **(NMT data missing)** Confirm the JFR `profile` template includes `jdk.NativeMemoryUsage` / `jdk.NativeMemoryUsageTotal` events (enable explicitly in a custom `.jfc` if needed), or run `jcmd <pid> VM.native_memory summary` alongside JFR capture to obtain a full per-category breakdown and a real container-fit verdict against the 2 GiB limit.

## Analysis Limitations
This build analyzes CPU samples, GC behavior, object allocation, total memory footprint (with NMT for per-category native breakdown), lock contention / thread parking, exception throws (per-class breakdown), file/socket I/O wait, and JVM native-method execution (blocked-in-syscall / JNI). The following are NOT yet covered and would change the picture if data is available:
- Class loading and JIT compilation overhead
- Note: I/O analysis covers only operations exceeding ~10ms; high-frequency fast I/O is aggregated in CPU/allocation profiles instead
