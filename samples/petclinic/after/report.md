# jfrdoc Analysis Report

## Executive Summary
The PetClinic workload runs comfortably within its 2 GiB container (481 MB committed, 76.5% headroom) and GC pressure is well-controlled on G1 (1.04% pause overhead, no anomalies). The notable signals are a 103 MB/s allocation rate driven by Thymeleaf template rendering and a sustained 207/s `java.io.EOFException` rate from the Tomcat NIO read path — the latter is consistent with normal client disconnects under load rather than an application defect.

## Recording Context
- **File**: /Users/ridvanozcan/code/jfrdoc/samples/petclinic/after/petclinic.jfr
- **Duration**: 120.06 s
- **JVM**: OpenJDK 64-Bit Server VM 25.0.3+9-LTS
- **OS**: linux-aarch64
- **Framework**: spring
- **Container limits**: memory=2Gi cpu=1
- **Total events captured**: 112,827

## Memory Footprint
Container fit: **SAFE** — 481 MB committed of 2048 MB limit (76.5% headroom). Dominant category is Java Heap at 157 MB committed (32.6% of total). NMT is enabled and reports 27 categories cleanly. Heap peaked at 149.9 MB used (94.1% of the 157 MB committed, well under the 1 GiB `-Xmx`), metaspace is at 96.8 MB used / 97.6 MB committed (close to its current commit, but reserve is 1.15 GiB so growth is unrestricted), and the code cache is well-provisioned at 240 MB committed with only ~23.6 MB used. Thread footprint is modest (peak 30 threads, ~30 MB stacks).

### Memory Breakdown
- **Java Heap**: 157 MB committed (32.6%, 1024 MB reserved)
- **Tracing**: 90.1 MB committed (18.7%, 90.1 MB reserved)
- **Metaspace**: 83.9 MB committed (17.5%, 128.4 MB reserved)
- **Code**: 48.6 MB committed (10.1%, 259.3 MB reserved)
- **GC**: 38.4 MB committed (8%, 55.4 MB reserved)
- **Symbol**: 19.3 MB committed (4%, 19.3 MB reserved)
- **Class**: 15.5 MB committed (3.2%, 1025.5 MB reserved)
- **Shared class space**: 13.7 MB committed (2.8%, 16 MB reserved)

## Garbage Collection
G1 (G1New + G1Old) ran 173 collections at 86.5 GCs/minute, all "G1 Evacuation Pause" — no full GCs, no humongous allocations, no evacuation failures. Pause overhead is 1.04% of recording duration with p50=3.16 ms, p95=21.5 ms, p99=72 ms, max=72.25 ms. Young pauses average 4.6 ms; the 36 mixed/old G1 pauses average 17.2 ms. GC frequency is on the high side but a direct consequence of the 103 MB/s allocation rate against a 157 MB committed heap.

### GC Anomalies
No anomalies detected.

## CPU Profile
Attribution is healthy (3962 samples, 0% unattributed). The on-CPU mix is JDK 46.1% / framework 47.6% / user_code 6.3% — user code is low because PetClinic delegates almost everything to Spring MVC, Thymeleaf, and Tomcat. The top hotspots are dominated by `java.util.jar.JarFile`/`ZipFile` entry lookups (jar resource scanning) and Thymeleaf template processing, both typical for a Spring Boot fat-jar serving server-rendered pages. Sampling density is ~33 samples/sec — on the low side but acceptable given the short recording.

### Top Hotspots
1. `java.util.jar.JarFile.getEntry:499` — 232 samples (5.9%, jdk) ← called from `java.util.jar.JarFile.getJarEntry`
   Repeated jar entry lookups, almost certainly from `ClassLoader.getResource` traffic against the fat-jar — Spring resource resolution and Thymeleaf template lookups.
2. `java.util.zip.ZipFile$Source.getEntryPos:1873` — 221 samples (5.6%, jdk) ← called from `java.util.zip.ZipFile.getEntry`
   The hash-table probe inside the zip central directory — same root cause as #1, classpath resource resolution.
3. `java.util.jar.JarFile.getJarEntry:460` — 126 samples (3.2%, jdk) ← called from `jdk.internal.loader.URLClassPath$JarLoader.getResource`
   Classpath probing via `URLClassPath` — confirms the hotspot is resource/class lookup, not user logic.
4. `java.util.concurrent.ConcurrentHashMap.get:958` — 76 samples (1.9%, jdk) ← called from `org.apache.catalina.connector.Request.getAttribute`
5. `java.lang.invoke.Invokers$Holder.invokeExact_MT` — 50 samples (1.3%, jdk) ← called from `jdk.internal.reflect.DirectMethodHandleAccessor.invokeImpl`
6. `java.util.HashMap.getNode:586` — 33 samples (0.8%, jdk) ← called from `java.util.HashMap.get`
7. `jdk.internal.util.ArraysSupport.mismatch:555` — 29 samples (0.7%, jdk) ← called from `java.lang.String.startsWith`
8. `java.util.zip.ZipFile$Source.getEntryPos:1860` — 27 samples (0.7%, jdk) ← called from `java.util.zip.ZipFile.getEntry`
9. `org.apache.catalina.connector.CoyoteWriter.write:167` — 27 samples (0.7%, framework) ← called from `org.apache.catalina.connector.CoyoteWriter.write`
10. `java.util.HashMap.getNode:588` — 22 samples (0.6%, jdk) ← called from `java.util.HashMap.get`

## Allocation Hotspots
Total allocation rate is **103.2 MB/s** (~12.3 GB allocated over the recording). The dominant class is `byte[]` (2.76 GB, 22.4%) followed by `java.lang.String` (1.09 GB, 8.9%) and `java.lang.Object[]` (842 MB, 6.9%). By category, JDK code drives 59% of bytes and Spring/Thymeleaf framework code drives 37% — only 4% is user_code, consistent with a thin-controller PetClinic style. The top site is `HeapByteBuffer.<init>` at 845 MB (likely Tomcat NIO read buffers and Thymeleaf string writers). Attribution quality is excellent (0% unattributed bytes).

### Top Allocators
1. `java.nio.HeapByteBuffer.<init>:75` — 845 MB (6.9%, jdk) allocating mostly `byte[]`
   NIO byte-buffer backing arrays — likely Tomcat request/response read buffers and Thymeleaf output writers; resizing temporary buffers per request is a known pattern.
2. `jdk.internal.misc.Unsafe.allocateUninitializedArray:1396` — 661.9 MB (5.4%, jdk) allocating mostly `byte[]`
   Internal raw array allocations used by `StringBuilder`, NIO, and gzip/serialization paths — symptom of churn in higher-level callers rather than a direct fix target.
3. `java.util.Arrays.copyOfRange:3849` — 540.5 MB (4.4%, jdk) allocating mostly `byte[]`
4. `org.thymeleaf.engine.Model.<init>:76` — 385.4 MB (3.1%, framework) allocating mostly `org.thymeleaf.engine.IEngineTemplateEvent[]`
5. `java.lang.StringLatin1.newString:760` — 357.2 MB (2.9%, jdk) allocating mostly `java.lang.String`

## Concurrency & Locks
Real monitor contention exists but is mild: 89 `JavaMonitorEnter` events totaling 3.88 s of waiting (max 74 ms). The top contended monitor is `java.util.jar.JarFile` at `ZipFile.getEntry:289` (63 events, 2.73 s) — same root cause as the CPU hotspots: concurrent threads serializing on the central directory lock during classpath/resource probing. Thread parking is high in event count (16,294 events / 1136 s) but 100% categorizes as `pool_idle_wait` — these are idle Tomcat/worker threads waiting on `AbstractQueuedSynchronizer$ConditionObject.awaitNanos` for work, which is normal and not contention. No connection-pool saturation.

### Contended Monitors
1. `java.util.jar.JarFile` — 63 events, 2725.4 ms total (43.3 ms avg, max 74.2 ms) at `java.util.zip.ZipFile.getEntry:289`
2. `java.lang.Object` — 14 events, 643.4 ms total (46 ms avg, max 72.7 ms) at `sun.nio.ch.EPollSelectorImpl.clearInterrupt:295`
3. `jdk.internal.loader.URLClassPath` — 11 events, 451.8 ms total (41.1 ms avg, max 55.7 ms) at `jdk.internal.loader.URLClassPath.getLoader:388`
4. `org.apache.coyote.AbstractProtocol$RecycledProcessors` — 1 event, 57 ms total (57 ms avg, max 57 ms) at `org.apache.tomcat.util.collections.SynchronizedStack.push:58`

### Notable Park Sites
All thread parking matches normal pool-idle or scheduled-task patterns — no findings.

## Exception Activity
207.1/s exceptions thrown over 119 s (24,647 events total) — the throw_rate_high signal is set. A single class dominates: `java.io.EOFException` at 96.9% of all throws, all originating from `org.apache.tomcat.util.net.NioEndpoint$NioSocketWrapper.fillReadBuffer:1339`. This is the classic Tomcat NIO behavior when clients (or load-balancer health probes / keep-alive churn) close TCP connections — Tomcat reads `-1` and converts it to an `EOFException` internally. This is framework-side and not an application bug, but the rate is high enough to be worth tuning at the load-balancer / load-generator level. The remaining 3.1% is `CancelledKeyException` from the NIO selector — same root cause (closed channels). `signals.control_flow_smell` is false.

### Top Exception Classes
1. `java.io.EOFException` — 23,894 events (96.9%, 200.8/s), thrown mostly from `org.apache.tomcat.util.net.NioEndpoint$NioSocketWrapper.fillReadBuffer:1339` (framework)
   Tomcat NIO read EOF — normal when clients disconnect mid-keepalive or close abruptly; high rate suggests aggressive load-balancer probing, very short client keep-alive, or a load generator that closes connections per request rather than reusing them.
2. `java.nio.channels.CancelledKeyException` — 753 events (3.1%, 6.3/s), thrown mostly from `sun.nio.ch.SelectionKeyImpl.ensureValid:75` (jdk)
   Selector key cancelled after channel close — secondary symptom of the same disconnect pattern as #1; benign.

### Top Throwing Sites
Throwing sites correlate 1:1 with the top classes above.

## Findings
- **🟡 Sustained EOFException rate from Tomcat NIO**: 200.8/s `EOFException` from `NioEndpoint$NioSocketWrapper.fillReadBuffer` indicates very high connection-close churn. **Evidence**: 23,894 EOFException events over 119 s (96.9% of all throws), all from one framework site; `single_class_dominant` and `throw_rate_high` signals set. **Why it matters**: Each throw builds a stack trace (~205 stacks/s) — measurable allocation and CPU overhead, and often a signal that HTTP keep-alive isn't being reused by upstream clients.
- **🟡 JarFile lock contention and classpath-lookup CPU cost**: Top CPU hotspots and the top contended monitor all point to `JarFile`/`ZipFile.getEntry`. **Evidence**: `JarFile.getEntry` 5.9% + `ZipFile$Source.getEntryPos` 5.6% + `JarFile.getJarEntry` 3.2% = ~14.7% of on-CPU samples in jar lookup paths; `JarFile` monitor contended 63 times for 2.73 s. **Why it matters**: Repeated runtime resource/classpath probing in fat-jar deployments is a well-known overhead and contention point.
- **🟡 High allocation rate driving frequent young GCs**: 103.2 MB/s allocation against a 157 MB heap produces 86.5 GCs/min. **Evidence**: byte[] 22.4%, String 8.9%, Object[] 6.9%; `HeapByteBuffer.<init>` 845 MB and `Thymeleaf Model.<init>` 385 MB are top sites; heap reaches 94.1% of committed between collections. **Why it matters**: Pause overhead is currently fine (1.04%, p99=72 ms) but headroom shrinks as load grows; reducing churn or letting the heap grow toward `-Xmx1g` would lower GC frequency.
- **🔵 Generous container headroom**: 481 MB committed of 2048 MB limit. **Evidence**: container_fit=safe, 76.5% headroom, heap `-Xmx1g` well below limit. **Why it matters**: No OOMKill risk; capacity exists to raise `-Xmx` if GC frequency becomes a concern.
- **🔵 Low CPU sampling density**: 33 samples/s over 120 s. **Evidence**: `derived.executionSamplesPerSecond = 33`, 3962 samples total. **Why it matters**: Hotspot percentages are still meaningful but small-share methods (<0.5%) carry more noise.

## Recommendations
1. **Sustained EOFException rate from Tomcat NIO** — Verify upstream HTTP client behavior: check that the load balancer / k8s ingress is sending `Connection: keep-alive` and reusing connections (not closing per request). If using a load generator (wrk/JMeter/Gatling), enable keep-alive. If clients legitimately churn, consider setting `server.tomcat.connection-timeout` and `server.tomcat.keep-alive-timeout` to reduce half-open state, and confirm whether your logging framework is logging these EOFs (silence them at the logger level if so).
2. **JarFile lock contention and classpath-lookup CPU cost** — Two options: (a) deploy as an exploded jar (`spring-boot:run` style or `java -cp BOOT-INF/classes:BOOT-INF/lib/*`) which eliminates the per-lookup zip seek; (b) enable Spring Boot's AOT/native image processing, or at minimum ensure Thymeleaf template caching is on (`spring.thymeleaf.cache=true` in production) so template resources aren't re-resolved per request.
3. **High allocation rate driving frequent young GCs** — The 103 MB/s rate against a 157 MB committed heap is the GC frequency driver. Either (a) raise initial heap with `-Xms512m -Xmx1g` so G1 doesn't recycle the small region set 86 times/minute, or (b) investigate the Thymeleaf `Model.<init>` allocation pattern (385 MB from `IEngineTemplateEvent[]`) — confirm template caching is enabled and review whether any page is rendering large unbounded collections.
4. **Low CPU sampling density** — For follow-up investigations, run JFR with `settings=profile` (already set here) but extend duration beyond 120 s, or set `jdk.ExecutionSample#period=10ms` to densify samples.

## Analysis Limitations
This build analyzes CPU samples, GC behavior, object allocation, total memory footprint (with NMT for per-category native breakdown), lock contention / thread parking, and exception throws (per-class breakdown). The following are NOT yet covered and would change the picture if data is available:
- I/O wait (file, socket) — jdk.FileRead, jdk.FileWrite, jdk.SocketRead, jdk.SocketWrite events not yet analyzed
- Native-method sampling (JNI compute, native I/O syscalls)
- Class loading and JIT compilation overhead
