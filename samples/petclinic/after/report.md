# jfrdoc Analysis Report

## Executive Summary
PetClinic is running healthy inside its container — total committed memory is 505 MB of the 2048 MB limit (75% headroom) and GC pause overhead is under 1%. The two notable signals are a high exception throw rate (194/s, 96.5% `EOFException` from Tomcat's NIO read path — typical client-disconnect noise under load) and a high allocation rate (86.5 MB/s) dominated by `byte[]`, `String`, and Thymeleaf engine objects on request paths. Heap occupancy peaks at 94% of committed before G1 collects it down to ~48 MB — normal G1 churn, not pressure.

## Recording Context
- **File**: /Users/ridvanozcan/code/jfrdoc/samples/petclinic/after/petclinic.jfr
- **Duration**: 120.0 s
- **JVM**: OpenJDK 64-Bit Server VM 25.0.3+9-LTS
- **OS**: linux-aarch64
- **Framework**: spring
- **Container limits**: memory=2048 MB cpu=1
- **Total events captured**: 108,598

## Memory Footprint
Container fit: **SAFE** — 504.7 MB committed of 2048 MB limit (75.4% headroom, 1543 MB free). Dominant category is Java Heap at 171 MB committed (34% of footprint). NMT is enabled and reports a healthy breakdown: heap is bounded by `-Xmx1g`, metaspace sits at 97.6 MB committed against a peak used of 96.9 MB (signal `metaspace_near_committed` is set, but metaspace remains free to grow), and code cache is well under-utilized (23.7 MB used of 240 MB committed). Thread count is modest (29 peak), and Tracing overhead (95.6 MB, mostly JFR buffers during recording) is the second-largest category.

### Memory Breakdown
- **Java Heap**: 171 MB committed (33.9%, 1024 MB reserved)
- **Tracing**: 95.6 MB committed (18.9%, 95.6 MB reserved)
- **Metaspace**: 84 MB committed (16.6%, 128.4 MB reserved)
- **Code**: 48.7 MB committed (9.7%, 259.3 MB reserved)
- **GC**: 38.7 MB committed (7.7%, 55.4 MB reserved)
- **Symbol**: 19.3 MB committed (3.8%, 19.3 MB reserved)
- **Class**: 15.5 MB committed (3.1%, 1025.5 MB reserved)
- **Shared class space**: 13.7 MB committed (2.7%, 16 MB reserved)

## Garbage Collection
G1GC with dynamic GC threads ran 161 collections (80.5/min) over the recording — 130 young and 31 mixed/old — for a total of 1141 ms of pause time (0.95% overhead). Pause distribution is tight (p50 3.25 ms, p95 22.3 ms, p99 63.6 ms, max 66 ms), with old-generation pauses averaging 19.8 ms vs young at 4 ms. The heap cycled between 45 MB and 146 MB (94% of committed), all driven by ordinary G1 Evacuation Pauses — no anomalies.

### GC Anomalies
No anomalies detected.

## CPU Profile
On-CPU Java time is dominated by **framework (48.4%)** and **JDK (44.9%)** code, with only **6.7% in user code** — typical for a Thymeleaf-rendered Spring MVC app where request handling is mostly view rendering, request routing, and classloader lookups. The biggest single contributors are JDK ZipFile/JarFile entry lookups (~14% combined), which reflects ongoing classpath/resource resolution rather than business logic. Sample density is ~33.5/s (4019 samples over 120s) — adequate for hot-spot identification. Sample quality is perfect (0% unattributed).

### Top Hotspots
1. `java.util.zip.ZipFile$Source.getEntryPos:1873` — 241 samples (6.0%, jdk) ← called from `java.util.zip.ZipFile.getEntry`
   - JAR entry lookups; consistent with Spring's lazy resource resolution on first-request paths under load.
2. `java.util.jar.JarFile.getEntry:499` — 210 samples (5.2%, jdk) ← called from `java.util.jar.JarFile.getJarEntry`
   - Same family — repeated classpath probing for templates/resources. Frequent if templates are not pre-resolved/cached.
3. `java.util.jar.JarFile.getJarEntry:460` — 106 samples (2.6%, jdk) ← called from `jdk.internal.loader.URLClassPath$JarLoader.getResource`
   - URLClassPath resource lookups — bootstrap loader hot path; usually amortized but visible under load.
4. `java.util.concurrent.ConcurrentHashMap.get:958` — 57 samples (1.4%, jdk) ← `jdk.internal.loader.AbstractClassLoaderValue.get`
5. `java.util.HashMap.getNode:579` — 48 samples (1.2%, jdk) ← `java.util.HashMap.get`
6. `org.apache.catalina.connector.RequestFacade.getAttribute:93` — 40 samples (1.0%, framework) ← `jakarta.servlet.ServletRequestWrapper.getAttribute`
7. `java.lang.invoke.Invokers$Holder.invokeExact_MT` — 38 samples (0.9%, jdk) ← `jdk.internal.reflect.DirectMethodHandleAccessor.invokeImpl`
8. `org.apache.catalina.connector.CoyoteWriter.write:167` — 33 samples (0.8%, framework) ← `org.apache.catalina.connector.CoyoteWriter.write`
9. `java.util.concurrent.ConcurrentHashMap.get:952` — 28 samples (0.7%, jdk) ← `org.thymeleaf.cache.StandardCache$CacheDataContainer.get`
10. `java.util.zip.ZipFile$Source.getEntryPos:1860` — 27 samples (0.7%, jdk) ← `java.util.zip.ZipFile.getEntry`

## Native Execution
Native-method samples represent time in JVM native execution (syscalls/JNI), which is mostly **blocked-in-syscall / wait time, not on-CPU work**. The recording is dominated by wait frames (93.9% of native samples), with `sun.nio.ch.Net.accept` (47.4%) from the Tomcat acceptor thread and `sun.nio.ch.EPoll.wait` (46.5%) from the NIO selector event loop — this is normal idle/wait behavior for a server, not a hotspot. There is no genuinely-on-CPU native work flagged (`likely_on_cpu_native_present: false`). The caller frame is more informative than the native method here, and both top callers confirm benign acceptor/event-loop activity.

### Top Native Methods
1. `sun.nio.ch.Net.accept` — 2186 samples (47.4%, jdk) ← caller `sun.nio.ch.ServerSocketChannelImpl.implAccept` (100%)
   - Blocked in syscall waiting for new connections — benign acceptor behavior, not a hotspot.
2. `sun.nio.ch.EPoll.wait` — 2141 samples (46.5%, jdk) ← caller `sun.nio.ch.EPollSelectorImpl.doSelect` (100%)
   - NIO selector parked in epoll_wait waiting for socket events — benign event-loop behavior.
3. `sun.nio.ch.SocketDispatcher.write0` — 106 samples (2.3%, jdk) ← caller `sun.nio.ch.SocketDispatcher.write` (100%)
4. `java.io.UnixFileSystem.getBooleanAttributes0` — 55 samples (1.2%, jdk) ← caller `java.io.UnixFileSystem.hasBooleanAttributes` (100%)
5. `sun.nio.ch.EventFD.set0` — 52 samples (1.1%, jdk) ← caller `sun.nio.ch.EventFD.set` (100%)

## Allocation Hotspots
The application allocates at **86.5 MB/s** (10.4 GB over 120 s), with **JDK code accounting for 58.1% by bytes** and **framework 37.9%** — only 4% from user code. Top allocated class is `byte[]` (23.9% of bytes), driven primarily by `java.nio.HeapByteBuffer.<init>` (820 MB, 7.9%) and `Unsafe.allocateUninitializedArray` (621 MB, 6%) — both consistent with Tomcat NIO request/response buffer creation. Thymeleaf is the dominant framework allocator: `IEngineTemplateEvent[]`, `OpenElementTag`, `ProcessorExecutionVars` together account for ~10% of bytes. Sample quality is excellent (0% unattributed by bytes).

### Top Allocators
1. `java.nio.HeapByteBuffer.<init>:75` — 820.6 MB (7.9%, jdk) allocating mostly `byte[]`
   - NIO heap buffer creation from Tomcat connection handling — could benefit from buffer pooling or larger buffer reuse if confirmed via Tomcat config.
2. `jdk.internal.misc.Unsafe.allocateUninitializedArray:1396` — 621.2 MB (6.0%, jdk) allocating mostly `byte[]`
   - Backing array allocations (often for StringBuilder/StringConcat/IO buffers) — broad source, hard to optimize directly.
3. `java.util.Arrays.copyOfRange:3849` — 459 MB (4.4%, jdk) allocating mostly `byte[]`
4. `org.thymeleaf.engine.Model.<init>:76` — 341.2 MB (3.3%, framework) allocating mostly `org.thymeleaf.engine.IEngineTemplateEvent[]`
5. `java.util.ArrayList.grow:240` — 276.9 MB (2.7%, jdk) allocating mostly `java.lang.Object[]`

## Concurrency & Locks
Real monitor contention is present but modest: 152 `JavaMonitorEnter` events totalling 8.3 s of wait, dominated by `java.util.jar.JarFile` (115 events, 5.78 s — synchronized JAR entry reads on the classloader hot path). Thread parking totals 1,154 s but is 100% categorized as `pool_idle_wait` (worker threads waiting on AQS condition queues — normal idle behavior, not contention). Connection-pool acquire and lock-acquire waits are essentially zero, so there's no signal of saturation on Hikari or application-level locks.

### Contended Monitors
1. `java.util.jar.JarFile` — 115 events, 5784.2 ms total (50.3 ms avg, max 327.4 ms) at `java.util.zip.ZipFile.getEntry:289`
2. `java.lang.Object` — 20 events, 1177.4 ms total (58.9 ms avg, max 331.6 ms) at `sun.nio.ch.EPollSelectorImpl.clearInterrupt:295`
3. `jdk.internal.loader.URLClassPath` — 12 events, 1022.5 ms total (85.2 ms avg, max 481.8 ms) at `jdk.internal.loader.URLClassPath.getLoader:388`
4. `java.util.ArrayDeque` — 3 events, 222.4 ms total (74.1 ms avg, max 80.8 ms) at `sun.nio.ch.SelectorImpl.cancel:255`
5. `java.util.Collections$SynchronizedMap` — 2 events, 87.5 ms total (43.7 ms avg, max 46.6 ms) at `java.util.Collections$SynchronizedMap.get:2989`

### Notable Park Sites
All thread parking matches normal pool-idle patterns — no findings.

## Exception Activity
**194.6 exceptions/s** thrown over 119.6 s (23,276 events total), with **`java.io.EOFException` accounting for 96.5%** (22,453 events). The single dominant throwing site is `org.apache.tomcat.util.net.NioEndpoint$NioSocketWrapper.fillReadBuffer:1339` — Tomcat's NIO reader path observing the client side of the socket closed mid-read. This is **typical client-disconnect / keep-alive teardown noise from a load generator or load balancer**, not an application bug, but the rate is high enough to merit attention (each throw still walks a stack trace). The remaining 3.5% is `CancelledKeyException` from `SelectionKeyImpl.ensureValid` — same NIO selector path during channel close. No user-code exceptions, no `Error` subclasses, and `control_flow_smell` is not set.

### Top Exception Classes
1. `java.io.EOFException` — 22453 events (96.5%, 187.8/s), thrown mostly from `org.apache.tomcat.util.net.NioEndpoint$NioSocketWrapper.fillReadBuffer:1339` (framework)
   - I/O exception from Tomcat NIO read path → almost certainly client disconnects / pipelining / keep-alive timeouts from the load generator. Investigate load-balancer or test-client connection behavior rather than the application.
2. `java.nio.channels.CancelledKeyException` — 823 events (3.5%, 6.9/s), thrown mostly from `sun.nio.ch.SelectionKeyImpl.ensureValid:75` (jdk)
   - NIO selector edge case during channel close — secondary effect of the same disconnect pattern as #1.

### Top Throwing Sites
Throwing sites correlate 1:1 with the top classes above.

## I/O Activity
Total slow-I/O blocking time is **4.7 s over the 120 s recording (4% of wall-clock)**, dominated entirely by socket writes (4.3 s across 588 events) — no file I/O above threshold and no significant socket reads. The traffic is spread across many ephemeral client ports (172.18.0.3:48798, :49554, …), all hitting Tomcat's `NioChannel.write:129`, so no single endpoint dominates — this is response-write latency to the load-generator container, not a downstream-service bottleneck. The slowest single write took 237 ms. Reminder: JFR only captures I/O operations exceeding ~10 ms; faster writes are invisible here and aggregated into CPU/allocation profiles.

### Top I/O Targets
1. 172.18.0.3:48798 — 237.2 ms across 1 op (0 MB, max 237.2 ms) [socket]
   - HTTP response write to client over loopback/bridge network; large variability suggests client-side backpressure rather than network issue.
2. 172.18.0.3:49554 — 217.2 ms across 1 op (0 MB, max 217.2 ms) [socket]
   - Same pattern — ephemeral client port, single slow write.
3. 172.18.0.3:56142 — 141.8 ms across 1 op (0 MB, max 141.8 ms) [socket]
4. 172.18.0.3:50012 — 86.3 ms across 1 op (0 MB, max 86.3 ms) [socket]
5. 172.18.0.3:50683 — 75.0 ms across 1 op (0 MB, max 75 ms) [socket]

## Findings
- **🟡 High exception throw rate from Tomcat NIO read path**: `EOFException` is thrown ~188 times/s (96.5% of 23,276 throws) at `NioSocketWrapper.fillReadBuffer:1339`. **Evidence**: 22,453 events over 119.6 s, single dominant site, `signals.throw_rate_high=true`, `single_class_dominant=true`. **Why it matters**: each throw still walks a stack trace (the rate is high enough to add measurable CPU and allocation pressure), and the volume signals an upstream pattern — likely the load generator closing connections aggressively or a keep-alive mismatch — worth correcting at the client/LB layer rather than in the app.
- **🟡 High allocation rate driven by NIO buffers and Thymeleaf**: 86.5 MB/s sustained allocation, with `HeapByteBuffer.<init>` (820 MB) and `Unsafe.allocateUninitializedArray` (621 MB) as the top two sites and Thymeleaf `Model`/`IEngineTemplateEvent[]` close behind. **Evidence**: `estimated_allocation_rate.mb_per_second=86.5`, top 3 sites are all `byte[]` allocators consuming 18.3% of bytes. **Why it matters**: this drives the 80.5 GCs/min cadence and the 94% heap-of-committed peak. The GC handles it (0.95% overhead), but reducing buffer churn would lower CPU cost and improve tail latency. Many of these allocations are amplified by EOFException stack-trace creation (related to the finding above).
- **🟢 Classloader/JAR lookup overhead on hot path**: `ZipFile.getEntry` and `JarFile.getEntry/getJarEntry` together account for ~14% of on-CPU samples, and `java.util.jar.JarFile` shows synchronized contention (5.78 s total wait, max 327 ms). **Evidence**: top-3 CPU hotspots are all JAR entry lookup; top contended monitor is `JarFile`. **Why it matters**: indicates ongoing resource/classpath resolution rather than fully amortized startup; minor in absolute terms but worth investigating Spring/Thymeleaf resource-resolution caching.
- **🔵 Container fit is healthy**: 504.7 MB committed of 2048 MB limit (75.4% headroom). **Evidence**: `container_fit.verdict=safe`, `dominant_category=Java Heap`. **Why it matters**: no OOMKill risk in current state; the 2 Gi limit is generous for an `-Xmx1g` JVM and could be reduced if cluster-density matters.
- **🔵 GC behavior is healthy**: 0.95% pause overhead, p99 63.6 ms, max 66 ms, no full GCs / no evacuation failures / no humongous allocations. **Evidence**: G1 stats. **Why it matters**: G1 is comfortably keeping up with the 86.5 MB/s allocation rate at the current heap size.

## Recommendations
1. **(High-rate EOFException)** Investigate the client/load-generator and any intermediate proxy: align keep-alive timeouts between client and Tomcat (`server.tomcat.keep-alive-timeout`, `server.tomcat.max-keep-alive-requests`), and confirm the load generator isn't closing sockets immediately after request issuance. If the throws are genuinely unavoidable, consider raising `logging.level.org.apache.tomcat.util.net=WARN` (already default) and confirm no debug logger is forcing stack-trace materialization on every event.
2. **(High allocation rate)** Reduce per-request `byte[]` churn: verify `server.tomcat.max-http-form-post-size` and the Tomcat NIO buffer settings (`socket.appReadBufSize`, `socket.appWriteBufSize`) — if requests are small, oversized buffers are being allocated per connection. Enable Thymeleaf template caching (`spring.thymeleaf.cache=true` in non-dev profiles) to cut `Model`/`IEngineTemplateEvent[]` allocations. Consider raising `-Xmx` to 1536m–2048m given the 2 Gi container limit — at 86.5 MB/s the young gen fills quickly and a larger heap would reduce the 80.5/min GC cadence.
3. **(Classloader/JAR lookup overhead)** Confirm Thymeleaf template caching is enabled (above) and that `spring.web.resources.cache.period` / `cachecontrol` are set so static-resource lookups don't repeatedly probe JARs. If the application is repackaged as a fat JAR, evaluate AOT/CDS (`-XX:SharedArchiveFile`) to reduce classpath-resolution work in steady state.

## Analysis Limitations
This build analyzes CPU samples, GC behavior, object allocation, total memory footprint (with NMT for per-category native breakdown), lock contention / thread parking, exception throws (per-class breakdown), file/socket I/O wait, and JVM native-method execution (blocked-in-syscall / JNI). The following are NOT yet covered and would change the picture if data is available:
- Class loading and JIT compilation overhead
- Note: I/O analysis covers only operations exceeding ~10ms; high-frequency fast I/O is aggregated in CPU/allocation profiles instead
