# jfrdoc Analysis Report

## Executive Summary
Note: execution-sample density is low (26.5 samples/s) — CPU attribution percentages should be read as directional, not precise. The application is dominated by Spring Boot loader (nested-JAR) overhead: ~36% of on-CPU Java time and the top monitor-contention hotspots all live in `org.springframework.boot.loader.net.protocol.jar.*`, while allocation is enormous (≈472 MB/s, with `byte[]` + `URL` + `JarFileUrlKey` together ~69% of bytes) driving 146 GC/min — much of it stemming from the same nested-JAR resource lookup path. Heap, GC pauses, and container-fit are all healthy; the inefficiency is wasted CPU and allocation on classloader/resource lookups, not memory pressure.

## Recording Context
- **File**: /Users/ridvanozcan/code/jfrdoc/samples/petclinic-ubi9/after/petclinic.jfr
- **Duration**: 120.86 s
- **JVM**: OpenJDK 64-Bit Server VM 21.0.11+10-LTS
- **OS**: linux-aarch64
- **Framework**: spring
- **Container limits**: memory=2Gi cpu=1
- **Total events captured**: 168,518

## Memory Footprint

Container fit: SAFE — 665.1 MB committed of 2048 MB limit (67.5% headroom). NMT is enabled and confirms Java Heap (342 MB committed, peak used 266 MB) is the dominant category at 51.4%, with Metaspace at 13.3%. Metaspace is committed near its used watermark (101.6 MB committed vs 100.8 MB used against a 200 MB max) — the `metaspace_near_committed` signal — but it is well under the configured cap. Thread count is modest at 31 peak; code cache has plenty of room (42.7/240 MB used).

### Memory Breakdown
- **Java Heap**: 342.0 MB committed (51.4%, 1368.0 MB reserved)
- **Metaspace**: 88.2 MB committed (13.3%, 128.4 MB reserved)
- **Tracing**: 57.5 MB committed (8.6%, 57.5 MB reserved)
- **Code**: 51.4 MB committed (7.7%, 246.3 MB reserved)
- **GC**: 43.2 MB committed (6.5%, 63.2 MB reserved)
- **Symbol**: 34.6 MB committed (5.2%, 34.6 MB reserved)
- **Class**: 15.8 MB committed (2.4%, 162.0 MB reserved)
- **Shared class space**: 12.7 MB committed (1.9%, 16.0 MB reserved)

## Garbage Collection

G1GC (G1New young + G1Old mixed) ran 294 collections in 120.9 s — **146 GC/min**, a very high frequency reflecting the heavy allocation rate. Total pause overhead is benign at **1.39%** (p50=2.3 ms, p95=20.2 ms, p99=73.0 ms, max=75.5 ms — under the 200 ms target). All collections are normal G1 Evacuation Pauses; no Full GC, no humongous allocations, no evacuation failures. Heap peaks at 266.3 MB (70.6% of committed 342 MB) and drops back to ~39 MB after collection — clean steady-state with no leak signal.

### GC Anomalies
No anomalies detected.

## CPU Profile

On-CPU Java time is dominated by JDK code (61.3%) and Spring framework code (36.0%), with user code at just **2.7%** — extremely unusual for a typical PetClinic-under-load profile and a strong signal that the application is spending most of its CPU in *infrastructure* paths rather than business logic. The top hotspots cluster tightly around `java.net.URL` construction/parsing, `URLClassPath.getLoader`, and Spring Boot's nested-JAR handlers (`Handler.indexOfSeparator`, `UrlJarFiles.getCached`, `Canonicalizer.canonicalizeAfter`, `JarFileUrlKey.equalsIgnoringCase`) — i.e., the executable-JAR loader resolving resources repeatedly at runtime.

### Top Hotspots
1. `java.lang.String.equalsIgnoreCase:1982` — 178 samples (5.6%, jdk) ← called from `org.springframework.boot.loader.net.protocol.jar.JarFileUrlKey.equalsIgnoringCase`
   - Driven 100% by Spring Boot loader comparing nested-JAR URL keys — repeated resource lookups against the JAR cache.
2. `java.lang.ThreadLocal$ThreadLocalMap.getEntry:504` — 172 samples (5.4%, jdk) ← called from `java.lang.ThreadLocal.get`
   - Generic ThreadLocal access; likely from request-scoped context (Tomcat / Spring) on every request.
3. `java.net.URL.<init>:654` — 139 samples (4.3%, jdk) ← called from `java.net.URL.<init>`
   - URL object construction on the request path — pairs with the allocation profile showing 9.5 GB of `java.net.URL` allocated.
4. `java.lang.StringLatin1.toLowerCase:428` — 123 samples (3.8%, jdk) ← called from `java.lang.String.toLowerCase`
5. `java.util.Arrays.binarySearch0:1717` — 122 samples (3.8%, jdk) ← called from `java.util.Arrays.binarySearch`
6. `org.springframework.boot.loader.net.protocol.jar.Handler.indexOfSeparator:174` — 94 samples (2.9%, framework) ← called from itself
7. `jdk.internal.loader.URLClassPath.getLoader:432` — 77 samples (2.4%, jdk) ← called from `URLClassPath.findResource`
8. `java.net.URLStreamHandler.parseURL:326` — 67 samples (2.1%, jdk) ← called from `java.net.URL.<init>`
9. `java.net.URL.<init>:778` — 66 samples (2.1%, jdk) ← called from `java.net.URL.<init>`
10. `sun.net.www.ParseUtil.firstEncodeIndex:93` — 64 samples (2.0%, jdk) ← called from `sun.net.www.ParseUtil.encodePath`

## Native Execution

These samples reflect time spent in JVM native execution (syscalls / JNI), which is mostly **blocked-in-native wait time**, not on-CPU work. The signals confirm this: **94.4%** of native samples are wait frames, and `likely_on_cpu_native_present` is false. The top two methods — `sun.nio.ch.Net.accept` (48.3%, called by `ServerSocketChannelImpl.implAccept`) and `sun.nio.ch.EPoll.wait` (46.1%, called by `EPollSelectorImpl.doSelect`) — are the Tomcat acceptor thread and the NIO selector loop idly waiting for connections / events. This is normal, healthy server behavior, not a hotspot. The caller frame is more informative than the native method itself.

### Top Native Methods
1. `sun.nio.ch.Net.accept` — 2265 samples (48.3%, jdk) ← caller `sun.nio.ch.ServerSocketChannelImpl.implAccept` (100%)
   - Blocked in syscall waiting — benign Tomcat acceptor behavior, not a hotspot.
2. `sun.nio.ch.EPoll.wait` — 2163 samples (46.1%, jdk) ← caller `sun.nio.ch.EPollSelectorImpl.doSelect` (100%)
   - Blocked in epoll_wait — benign NIO event-loop idle, not a hotspot.
3. `sun.nio.ch.SocketDispatcher.write0` — 108 samples (2.3%, jdk) ← caller `sun.nio.ch.SocketDispatcher.write` (100%)
4. `sun.nio.ch.EventFD.set0` — 75 samples (1.6%, jdk) ← caller `sun.nio.ch.EventFD.set` (100%)
5. `sun.nio.ch.Net.shutdown` — 20 samples (0.4%, jdk) ← caller `sun.nio.ch.SocketChannelImpl.implCloseNonBlockingMode` (100%)

## Allocation Hotspots

Allocation rate is very high: **~472.5 MB/s** (≈56.8 GB total over 120 s). The top allocated classes — `byte[]` (43.9%), `java.lang.String` (17.4%), `java.net.URL` (16.7%), and `org.springframework.boot.loader.net.protocol.jar.JarFileUrlKey` (8.4%) — together account for **~86%** of bytes, and most of them flow from the Spring Boot nested-JAR loader (URL construction, JarFileUrlKey caching, classpath resource resolution). JDK code accounts for 75.8% of allocated bytes by category and framework for 23.5%; user code is just 0.7%. This is the same nested-JAR resource-lookup pattern visible in the CPU profile, expressed as allocation pressure that drives the high GC frequency.

### Top Allocators
1. `java.util.Arrays.copyOfRangeByte:3863` — 15,936 MB (28.1%, jdk) allocating mostly `byte[]`
   - Massive `byte[]` copying — typical of repeated stream/reader operations; almost certainly invoked from nested-JAR resource reads given the surrounding hotspots.
2. `jdk.internal.misc.Unsafe.allocateUninitializedArray:1380` — 7,950 MB (14.0%, jdk) allocating mostly `byte[]`
   - Backing array allocations for HeapByteBuffer / String construction — paired with the `String.newString` site below.
3. `java.lang.StringLatin1.newString:756` — 7,580 MB (13.3%, jdk) allocating mostly `java.lang.String`
4. `jdk.internal.loader.URLClassPath$Loader.findResource:604` — 4,973 MB (8.8%, jdk) allocating mostly `java.net.URL`
5. `org.springframework.boot.loader.net.protocol.jar.JarUrlConnection.open:340` — 4,532 MB (8.0%, framework) allocating mostly `java.net.URL`

## Concurrency & Locks

There is **real monitor contention**: 637 `JavaMonitorEnter` events totalling **25.2 s** of blocked wall time, and the top two contended monitors are both in the Spring Boot loader — `UrlNestedJarFile` (316 events, 12.5 s, avg 39.5 ms, max 80.6 ms at `NestedJarFile.hasEntry`) and `UrlJarFiles$Cache` (248 events, 9.7 s at `Cache.get`). These are the exact same code paths driving the CPU and allocation hotspots — concurrent threads serializing on the nested-JAR cache. Thread parking totals 1.63 M ms across 36 k events but is overwhelmingly normal pool-idle waiting (78.3% `pool_idle_wait`, 21.7% `condition_wait`); connection-pool pressure is not flagged. Treat the large ThreadPark counts as benign idle workers.

### Contended Monitors
1. `org.springframework.boot.loader.net.protocol.jar.UrlNestedJarFile` — 316 events, 12,480.1 ms total (39.5 ms avg, max 80.6 ms) at `org.springframework.boot.loader.jar.NestedJarFile.hasEntry:251`
2. `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles$Cache` — 248 events, 9,728.4 ms total (39.2 ms avg, max 76.3 ms) at `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles$Cache.get:158`
3. `java.lang.Object` — 30 events, 1,398.0 ms total (46.6 ms avg, max 73.7 ms) at `sun.nio.ch.EPollSelectorImpl.clearInterrupt:268`
4. `java.util.Hashtable` — 22 events, 947.1 ms total (43.1 ms avg, max 71.1 ms) at `java.util.Hashtable.get:380`
5. `jdk.internal.loader.URLClassPath` — 12 events, 500.9 ms total (41.7 ms avg, max 63.7 ms) at `jdk.internal.loader.URLClassPath.getLoader:424`

### Notable Park Sites
All thread parking matches normal pool-idle or condition-wait patterns — no findings.

## Exception Activity

No exception events captured in this recording.

## I/O Activity

Total slow-I/O blocking is modest: **3.31 s** (2.8% of recording), entirely socket-based (file I/O is zero). It is spread across many ephemeral client endpoints on `172.18.0.3` (the load generator) with no single-endpoint dominance — typical of a load test where each HTTP write hits the slow-I/O threshold once. The slowest single op is 74.5 ms on a socket write. There is no database endpoint visible (consistent with an embedded H2 / in-memory profile), and no repeated-file-access pattern. JFR only records I/O > ~10 ms, so this captures only slow tail operations, not total I/O volume.

### Top I/O Targets
1. 172.18.0.3:49190 — 74.5 ms across 1 ops (0 MB, max 74.5 ms) [socket]
   - Single slow socket write to the test client; not a backend dependency. Likely network/TCP-buffer back-pressure on the load generator side.
2. 172.18.0.3:37690 — 70.2 ms across 1 ops (0 MB, max 70.2 ms) [socket]
   - Single slow socket read from the test client — same pattern, opposite direction.
3. 172.18.0.3:59676 — 70.0 ms across 1 ops (0 MB, max 70.0 ms) [socket]
4. 172.18.0.3:53158 — 69.4 ms across 1 ops (0 MB, max 69.4 ms) [socket]
5. 172.18.0.3:33946 — 68.8 ms across 1 ops (0 MB, max 68.8 ms) [socket]

## Findings
- **🔴 Spring Boot nested-JAR loader is the dominant performance cost**: The executable-JAR `org.springframework.boot.loader.*` codepath drives ~36% of on-CPU Java time, the top two contended monitors (22.2 s combined wait), and large chunks of allocation (`URL` 16.7%, `JarFileUrlKey` 8.4%). **Evidence**: 178 samples on `JarFileUrlKey.equalsIgnoringCase`, 316 contention events / 12.5 s on `UrlNestedJarFile`, 248 / 9.7 s on `UrlJarFiles$Cache.get`, 4.5 GB allocated from `JarUrlConnection.open`. **Why it matters**: This is pure infrastructure overhead — under load, threads serialize on the JAR cache while CPU burns on URL parsing instead of business logic. User code is only 2.7% of CPU.
- **🔴 Very high allocation rate driven by infrastructure paths**: ~472 MB/s sustained allocation, ~86% in just four classes (`byte[]`, `String`, `URL`, `JarFileUrlKey`), forcing G1 to run 146 GC/min. **Evidence**: total estimated 56.8 GB allocated over 120 s; top allocator `Arrays.copyOfRangeByte` at 15.9 GB; `URLClassPath$Loader.findResource` at 5.0 GB. **Why it matters**: Even though pauses are short (p99=73 ms, overhead 1.39%), the constant churn limits throughput headroom and is mostly avoidable — it traces back to the same nested-JAR resource lookups, not request handling.
- **🟡 Low on-CPU user_code ratio (2.7%)**: For a Spring Boot app under HTTP load, healthy user_code share is typically 30–60%. **Evidence**: jfr_top_methods categories — jdk 61.3%, framework 36.0%, user_code 2.7%. **Why it matters**: Confirms that throughput is bottlenecked on framework infrastructure, not application logic — fixing the loader pressure should yield substantial gains.
- **🟢 Metaspace committed near used watermark**: 101.6 MB committed against 100.8 MB used, with a 200 MB cap. **Evidence**: `metaspace_near_committed` signal true; class space 13.4/13.8 MB. **Why it matters**: Headroom exists (still under the cap), but watch for `MaxMetaspaceSize=200m` getting close if more classes load post-warmup.
- **🔵 GC and container fit are healthy**: 1.39% pause overhead, no Full GCs, 665 MB committed vs 2048 MB limit (67.5% headroom). **Evidence**: 294 G1 Evacuation Pauses, max 75.5 ms; NMT confirms Java Heap at 51.4% of committed total. **Why it matters**: No memory or pause-time risk; tuning effort should target allocation reduction, not heap sizing.
- **🔵 Low execution-sample density**: 26.5 samples/s. **Evidence**: 3,200 execution samples over 120 s. **Why it matters**: CPU percentages are directional; the strong corroboration from allocation and lock-contention data makes the top findings robust regardless.

## Recommendations
1. **Address Spring Boot nested-JAR loader pressure (Findings #1, #2, #3)** — The single highest-impact change. Options, in order of preference:
   - Run the app as **exploded layout** instead of an executable JAR: `java -cp 'BOOT-INF/classes:BOOT-INF/lib/*' org.springframework.boot.loader.launch.JarLauncher` or simply extract the JAR at image build time and run with a classic classpath. This eliminates the `UrlNestedJarFile` / `UrlJarFiles$Cache` codepath entirely.
   - Alternatively, build a **layered/CDS-enabled image** (`spring-boot-maven-plugin` layered jars + `-XX:SharedArchiveFile`) so resource lookups warm into a cached form.
   - Either change should remove the top 5 contention sites, drop allocation by a large fraction, and raise user_code's CPU share materially.
2. **Verify with a follow-up recording (Finding #6)** — After applying #1, capture another 2-minute JFR under the same load and confirm: `UrlNestedJarFile` contention gone, allocation rate dropped, user_code CPU share risen. Consider also raising the execution sample rate (e.g., `jdk.ExecutionSample#period=10ms`) for sharper attribution.
3. **Keep an eye on metaspace (Finding #4)** — No immediate action; if post-warmup metaspace approaches 180 MB, raise `-XX:MaxMetaspaceSize` to 256 MB. Plenty of container headroom to do so.

## Analysis Limitations
This build analyzes CPU samples, GC behavior, object allocation, total memory footprint (with NMT for per-category native breakdown), lock contention / thread parking, exception throws (per-class breakdown), file/socket I/O wait, and JVM native-method execution (blocked-in-syscall / JNI). The following are NOT yet covered and would change the picture if data is available:
- Class loading and JIT compilation overhead
- Note: I/O analysis covers only operations exceeding ~10ms; high-frequency fast I/O is aggregated in CPU/allocation profiles instead
