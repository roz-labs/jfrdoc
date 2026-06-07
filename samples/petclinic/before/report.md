# jfrdoc Analysis Report

## Executive Summary
This Spring Boot PetClinic recording is dominated by a pathological Spring Boot nested-jar URL resolution path: ~95% of on-CPU Java time is in `org.springframework.boot.loader` jar/URL handling and JDK URL/string code, and ~25% of total allocation bytes are `byte[]` from `Arrays.copyOfRange` driven by jar reads. Additional concerns: a serial garbage collector (`DefNew`/`SerialOld`) running 1,159 GCs/min with a 148 ms SerialOld pause, an extremely high `EOFException` throw rate (258/s) from Tomcat client disconnects, and real synchronized contention on the boot loader's `UrlJarFiles$Cache` and `UrlNestedJarFile` monitors.

## Recording Context
- **File**: /Users/ridvanozcan/code/jfrdoc/samples/petclinic/before/petclinic.jfr
- **Duration**: 120.143 s
- **JVM**: OpenJDK 64-Bit Server VM 25.0.3+9-LTS
- **OS**: Linux aarch64
- **Framework**: spring
- **Container limits**: memory=2Gi cpu=1
- **Total events captured**: 206,845

## Memory Footprint
Container fit: **SAFE** — 436.4 MB committed of 2048 MB limit (78.7% headroom). NMT is enabled, and the dominant native category is `Tracing` (JFR's own buffers at 105.1 MB / 24.1%), followed by `Java Heap` (97.4 MB) and `Metaspace` (84.5 MB). Heap is small (94.2 MB committed, 65.7 MB peak) but reaches 95.6% of committed during operation, which combined with the SerialGC explains the GC churn below. Metaspace is committed close to used (98.3 MB committed / 97.5 MB used) and marked as `metaspace_near_committed` — likely to grow.

### Memory Breakdown
- **Tracing**: 105.1 MB committed (24.1%, 105.1 MB reserved)
- **Java Heap**: 97.4 MB committed (22.3%, 512 MB reserved)
- **Metaspace**: 84.5 MB committed (19.4%, 128.4 MB reserved)
- **Code**: 68.7 MB committed (15.7%, 271.6 MB reserved)
- **Symbol**: 19.4 MB committed (4.4%, 19.4 MB reserved)
- **Arena Chunk**: 16.6 MB committed (3.8%)
- **Class**: 16 MB committed (3.7%, 1025.9 MB reserved)
- **Shared class space**: 13.7 MB committed (3.1%)

## Garbage Collection
GC is configured as **SerialGC** (`DefNew` young + `SerialOld` old) — single-threaded, stop-the-world, and an unusual choice for a server workload. The application underwent **2,321 collections in 120 s (1,159 GCs/min)**, with 2.89% pause overhead, p99 = 2.41 ms, but **one SerialOld full collection took 148.91 ms**. The driver is a tiny heap (94.2 MB committed) churning at ~495 MB/s allocation rate, leaving little room before each `Allocation Failure`.

### GC Anomalies
- **Long pauses over 100 ms**: 1 (the SerialOld pause at 148.91 ms — a tail SLO risk under load)

## CPU Profile
Attribution is clean (0% unattributed). The CPU profile is overwhelmingly **JDK code (59.9%) plus framework code (37.3%), with only 2.8% in user code** — a strong signal that this workload is NOT doing meaningful business logic on-CPU but is instead burning cycles in classloader / URL / jar plumbing. The top frames are all Spring Boot's loader (`NestedJarFile.hasEntry`, `Handler.indexOfSeparator`, `UrlJarFiles.getCached`) and the JDK URL/string machinery they call (`URL.<init>`, `StringLatin1.hashCode`, `ParseUtil.encodePath`, `Arrays.binarySearch`). At 61.7 samples/s over 120 s, sampling density is adequate.

### Top Hotspots
1. `org.springframework.boot.loader.jar.NestedJarFile.hasEntry:244` — 407 samples (5.5%, framework) ← called from `org.springframework.boot.loader.net.protocol.jar.JarUrlConnection.hasEntry`
   - Spring Boot's executable-jar loader is repeatedly checking entries inside the nested jar on every resource lookup — classic fat-jar overhead.
2. `java.net.URL.<init>:630` — 406 samples (5.5%, jdk) ← called from `java.net.URL.<init>`
   - Massive volume of `URL` object construction, almost certainly downstream of classloader resource resolution (matches the URL-allocation hotspot below).
3. `java.lang.StringLatin1.hashCode:195` — 388 samples (5.2%, jdk) ← called from `java.lang.String.hashCode`
   - String hashing is being driven by URL/jar key lookups in the loader's caches.
4. `java.net.URL.<init>:741` — 371 samples (5.0%, jdk) ← `java.net.URL.<init>`
5. `java.util.Arrays.binarySearch0:1713` — 338 samples (4.6%, jdk) ← `java.util.Arrays.binarySearch`
6. `jdk.internal.util.ArraysSupport.mismatch:555` — 294 samples (4.0%, jdk) ← `java.lang.String.startsWith`
7. `java.util.HashMap.getNode:588` — 273 samples (3.7%, jdk) ← `java.util.HashMap.get`
8. `java.lang.String.equalsIgnoreCase:2056` — 249 samples (3.4%, jdk) ← `JarFileUrlKey.equalsIgnoringCase`
9. `org.springframework.boot.loader.net.protocol.jar.Handler.indexOfSeparator:174` — 230 samples (3.1%, framework)
10. `sun.net.www.ParseUtil.firstEncodeIndex:95` — 217 samples (2.9%, jdk) ← `ParseUtil.encodePath`

## Native Execution
These samples represent time in JVM native execution (syscalls/JNI) and are mostly BLOCKED-IN-NATIVE / WAIT time, not on-CPU work. The signal shows `dominated_by_wait_frames=true` with **94.4% in wait frames**, and `likely_on_cpu_native_present=false` — there is no real on-CPU native compute. The two top frames (`Net.accept` 47.7% from the Tomcat acceptor and `EPoll.wait` 46.7% from the NIO selector) are exactly the benign acceptor-thread and event-loop idle waits expected on any server. The caller frame is more informative than the native method itself.

### Top Native Methods
1. `sun.nio.ch.Net.accept` — 2253 samples (47.7%, jdk) ← caller `sun.nio.ch.ServerSocketChannelImpl.implAccept` (100%)
   - Blocked in syscall waiting — benign Tomcat acceptor-thread behavior, not a hotspot.
2. `sun.nio.ch.EPoll.wait` — 2204 samples (46.7%, jdk) ← caller `sun.nio.ch.EPollSelectorImpl.doSelect` (100%)
   - NIO selector event loop blocked on `epoll_wait` — normal idle/wait, not a hotspot.
3. `sun.nio.ch.SocketDispatcher.write0` — 114 samples (2.4%, jdk) ← `SocketDispatcher.write` (100%)
4. `sun.nio.ch.EventFD.set0` — 60 samples (1.3%, jdk) ← `EventFD.set` (100%)
5. `sun.nio.ch.Net.shutdown` — 24 samples (0.5%, jdk) ← `SocketChannelImpl.implCloseNonBlockingMode` (100%)

## Allocation Hotspots
Allocation rate is **495.1 MB/s** — extraordinarily high for a PetClinic-class app and the root cause of the 1,159 GCs/min. By bytes, **74.4% of allocation is in JDK code, 24.7% in framework, and only 0.9% in user code** — again, plumbing not business logic. The top allocated class is **`byte[]` (42.7%, 25.4 GB est.)**, followed by `java.net.URL` (17.6%) and `String` (16.7%); together these three make up 77% of all bytes allocated. The single largest site is `java.util.Arrays.copyOfRange` (26.9% of bytes), with Spring Boot's `JarUrlConnection.open` and `UrlJarFiles$Cache` putIfAbsent/get sitting at #5–#7 — direct evidence that the nested-jar resource lookup path is the allocation driver.

### Top Allocators
1. `java.util.Arrays.copyOfRange:3849` — 16002.5 MB (26.9%, jdk) allocating mostly `byte[]`
   - Bulk byte-array copies — almost certainly classpath / jar entry reads being copied out of buffers, driven by the Spring Boot loader's repeated nested-jar lookups.
2. `jdk.internal.misc.Unsafe.allocateUninitializedArray:1396` — 7994.0 MB (13.4%, jdk) allocating mostly `byte[]`
   - Underlying array allocation for IO buffers and string/byte storage along the same jar-read path.
3. `java.lang.StringLatin1.newString:760` — 7628.3 MB (12.8%, jdk) allocating mostly `java.lang.String`
4. `jdk.internal.loader.URLClassPath$Loader.findResource:514` — 5269.2 MB (8.9%, jdk) allocating mostly `java.net.URL`
5. `org.springframework.boot.loader.net.protocol.jar.JarUrlConnection.open:340` — 5199.2 MB (8.7%, framework) allocating mostly `java.net.URL`

## Concurrency & Locks
Real monitor contention IS present: **735 `JavaMonitorEnter` events totaling 27.5 s of wait time** (avg 37.9 ms, max 152.9 ms). The contention is concentrated on the Spring Boot loader's jar caches — `UrlNestedJarFile` (13.4 s) and `UrlJarFiles$Cache` (10.3 s) — the same code path that dominates CPU and allocation. The 1.2M ms of `ThreadPark` time is 100% `pool_idle_wait` (idle Tomcat/HikariCP threads waiting on `awaitNanos` / `ConditionNode.block`), which is benign and expected. Connection-pool pressure is NOT detected.

### Contended Monitors
1. `org.springframework.boot.loader.net.protocol.jar.UrlNestedJarFile` — 353 events, 13390.1 ms total (37.9 ms avg, max 76.3 ms) at `org.springframework.boot.loader.jar.NestedJarFile.hasEntry:251`
2. `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles$Cache` — 277 events, 10256.1 ms total (37.0 ms avg, max 152.9 ms) at `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles$Cache.get:158`
3. `java.util.Hashtable` — 48 events, 1680.2 ms total (35.0 ms avg, max 65.0 ms) at `java.util.Hashtable.get:382`
4. `java.lang.Object` — 25 events, 986.4 ms total (39.5 ms avg, max 66.1 ms) at `sun.nio.ch.EPollSelectorImpl.clearInterrupt:295`
5. `jdk.internal.loader.URLClassPath` — 20 events, 729.9 ms total (36.5 ms avg, max 61.3 ms) at `jdk.internal.loader.URLClassPath.getLoader:388`

### Notable Park Sites
All thread parking matches normal pool-idle patterns — no findings.

## Exception Activity
Throw rate is **267.1/s exceptions over 119.8 s (31,998 events total)** — well above normal. **`java.io.EOFException` dominates at 96.8% (258.5/s)**, thrown 100% from `org.apache.tomcat.util.net.NioEndpoint$NioSocketWrapper.fillReadBuffer:1339` (framework). This is Tomcat NIO observing client-side TCP closes mid-read — typical of a load generator that opens connections, sends one request, and tears down (no keep-alive), or aggressive client timeouts. It is not an application bug, but the rate is high enough to be expensive: each throw fills in a stack trace and allocates (matches the 658.9 MB `EOFException` allocation seen above). Secondary class `CancelledKeyException` (3.2%, 8.5/s) from `SelectionKeyImpl.ensureValid` is the same client-disconnect race observed on the selector side.

### Top Exception Classes
1. `java.io.EOFException` — 30973 events (96.8%, 258.5/s), thrown mostly from `org.apache.tomcat.util.net.NioEndpoint$NioSocketWrapper.fillReadBuffer:1339` (framework)
   - I/O exception from Tomcat NIO read on a client-closed connection — usually benign client disconnect, but at 258/s the stack-trace-fill cost and `byte[]` allocation pressure are real. Investigate the load generator / load balancer keepalive behavior.
2. `java.nio.channels.CancelledKeyException` — 1015 events (3.2%, 8.5/s), thrown mostly from `sun.nio.ch.SelectionKeyImpl.ensureValid:75` (jdk)
   - Same client-disconnect race observed on the selector side; correlates with the EOFException flood.
3. `java.lang.ClassNotFoundException` — 10 events (0.0%, 0.1/s), thrown mostly from `jdk.internal.loader.BuiltinClassLoader.loadClass:580` (jdk)
   - Sample: "io.micrometer.core.instrument.Tags$1" — benign classpath probing, negligible volume.

### Top Throwing Sites
Throwing sites correlate 1:1 with the top classes above.

## I/O Activity
Total slow-I/O blocking time is **4607.9 ms over 119.8 s (3.8% of recording)**, all socket I/O (no file I/O — expected for in-memory H2 / no disk-heavy workload). 553 socket writes account for 3846.4 ms and 176 reads for 761.5 ms. No single endpoint dominates — traffic is spread across many ephemeral client ports on 172.18.0.3 (a single load-generator host) hitting Tomcat (`NioChannel.read` / `NioChannel.write`); the slowest single op is 68.4 ms. Reminder: JFR only records I/O above ~10 ms, so this shows bottlenecks, not total volume.

### Top I/O Targets
1. 172.18.0.3:48946 — 68.4 ms across 1 op (0 MB, max 68.4 ms) [socket]
   - Single slow read from a load-generator client connection — no DB latency signal here (PetClinic is using embedded H2).
2. 172.18.0.3:49264 — 68.2 ms across 1 op (0 MB, max 68.2 ms) [socket]
   - Slow write back to a client; likely a response-flush stall, not a downstream-service problem.
3. 172.18.0.3:53154 — 67.5 ms across 1 op (0 MB, max 67.5 ms) [socket]
4. 172.18.0.3:40010 — 67.0 ms across 1 op (0 MB, max 67.0 ms) [socket]
5. 172.18.0.3:44010 — 66.0 ms across 1 op (0 MB, max 66.0 ms) [socket]

## Findings
- **🔴 Spring Boot nested-jar loader is the dominant CPU + allocation + contention hotspot**: ~25% of on-CPU samples are in `org.springframework.boot.loader.*` framework frames (plus most of the 59.9% JDK time is downstream URL/String/HashMap work serving the loader), the top allocation site is `Arrays.copyOfRange` for `byte[]` (16,002 MB / 26.9%) with `JarUrlConnection.open` and `UrlJarFiles$Cache` allocations sitting at #5/#6/#7, and the two most-contended monitors are `UrlNestedJarFile` and `UrlJarFiles$Cache` (23.6 s combined wait). **Evidence**: top-1 CPU method `NestedJarFile.hasEntry` 5.5%; allocation rate 495.1 MB/s with 42.7% as `byte[]` and 17.6% as `java.net.URL`; `UrlNestedJarFile` monitor 353 events / 13.4 s. **Why it matters**: under load this loader path runs on every classpath resource resolution and serializes requests through synchronized blocks, capping throughput and inflating GC pressure.
- **🔴 SerialGC on a server workload**: the JVM is using `DefNew` + `SerialOld` collectors with a 94.2 MB committed heap, producing 1,159 GCs/minute and one 148.91 ms SerialOld pause. **Evidence**: `young_collector=DefNew`, `old_collector=SerialOld`, total_gcs=2321 in 120 s, max_pause_ms=148.91. **Why it matters**: SerialGC is single-threaded and stop-the-world; any old-gen collection blocks the whole application, and the small heap forces extremely frequent young collections. G1 (the default in current JDKs) would handle this workload far better.
- **🟡 Very high EOFException throw rate driven by Tomcat client disconnects**: 30,973 `EOFException` events at 258.5/s, 100% from `NioEndpoint$NioSocketWrapper.fillReadBuffer`. **Evidence**: `dominant_class_pct=96.8`, `throws_per_second=258.5`, exception-class allocation 658.9 MB. **Why it matters**: each throw fills a stack trace and allocates — it's not an application bug, but at this rate it is a meaningful drag on CPU and allocator. Tune load-generator keep-alive / connection reuse and/or Tomcat's connection-close handling.
- **🟡 Metaspace committed near used and unbounded**: `metaspace_near_committed=true` and `metaspace_unbounded=true` (used 97.5 MB / committed 98.3 MB). **Evidence**: NMT Metaspace 84.5 MB committed; signals from jfr_memory. **Why it matters**: no `MaxMetaspaceSize` cap means a class-loader leak or extensive lambda/proxy generation could grow unbounded, eventually contributing to OOMKill on the pod.
- **🟢 Real but secondary monitor contention on `java.util.Hashtable` and JDK `URLClassPath`**: 48 + 20 events, ~2.4 s combined. **Evidence**: monitors #3 and #5 in contention table. **Why it matters**: these are downstream of the same boot-loader cascade — fixing the loader path will mitigate them automatically.

## Recommendations
1. **Address the Spring Boot nested-jar loader hotspot** (finding: nested-jar loader hotspot). The fastest, most reliable fix is to repackage the application so classes are not served from a nested jar at runtime: either deploy with `spring-boot-maven-plugin`'s `<layout>ZIP</layout>` + `LaunchScript` extraction, use the `extractedJar`/`tools.extract` mode (Spring Boot 3.2+ `java -Djarmode=tools -jar app.jar extract`), or build a regular (non-fat) container image with `BOOT-INF/classes` and `BOOT-INF/lib` laid out as plain directories on the classpath (the standard Cloud Native Buildpacks layout). Any of these eliminates the `UrlJarFiles$Cache` / `NestedJarFile.hasEntry` synchronized lookups entirely.
2. **Switch from SerialGC to G1GC and increase heap** (finding: SerialGC on a server workload). Remove any `-XX:+UseSerialGC` flag and add `-XX:+UseG1GC -Xms512m -Xmx1g` (the container has 2 GB and only 78.7% headroom needs are tiny). G1 will parallelize collection, avoid stop-the-world full GCs at this workload size, and the larger heap will reduce GC frequency from ~1,159/min to a handful per minute.
3. **Cap metaspace to prevent runaway growth** (finding: metaspace unbounded). Add `-XX:MaxMetaspaceSize=256m` so a class-loader / dynamic-proxy leak surfaces as a clear `OutOfMemoryError: Metaspace` instead of silently expanding into RSS and risking pod OOMKill.
4. **Investigate the EOFException flood** (finding: high EOFException rate). Confirm the load generator uses HTTP keep-alive and reuses connections (e.g., wrk's `-c` connections, k6 `discardResponseBodies` + connection reuse). If a load balancer is in front, ensure idle-timeout > Tomcat's `connectionTimeout`. This is configuration on the client/LB side, not in PetClinic itself.

## Analysis Limitations
This build analyzes CPU samples, GC behavior, object allocation, total memory footprint (with NMT for per-category native breakdown), lock contention / thread parking, exception throws (per-class breakdown), file/socket I/O wait, and JVM native-method execution (blocked-in-syscall / JNI). The following are NOT yet covered and would change the picture if data is available:
- Class loading and JIT compilation overhead
- Note: I/O analysis covers only operations exceeding ~10ms; high-frequency fast I/O is aggregated in CPU/allocation profiles instead
