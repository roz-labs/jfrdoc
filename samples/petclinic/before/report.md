# jfrdoc Analysis Report

## Executive Summary
This Spring Boot PetClinic recording shows a JVM running on the **Serial GC collector** with allocation pressure of ~480 MB/s dominated by Spring Boot's nested-JAR URL loader machinery, and a 250 exceptions/sec throw rate driven almost entirely by client disconnects on the Tomcat NIO connector. Container memory fit is SAFE (430 MB of 2048 MB committed), but the JVM is spending most CPU and allocation on `java.net.URL` construction and JAR resource resolution inside Spring Boot's loader — not on user business logic (only 2.5% user code).

## Recording Context
- **File**: /Users/ridvanozcan/code/jfrdoc/samples/petclinic/before/petclinic.jfr
- **Duration**: 120.134 s
- **JVM**: OpenJDK 64-Bit Server VM 25.0.3+9-LTS
- **OS**: linux-aarch64
- **Framework**: spring
- **Container limits**: memory=2Gi cpu=1
- **Total events captured**: 206,227

## Memory Footprint

Container fit: **SAFE** — 430.2 MB committed of 2048 MB limit (79% headroom). NMT is enabled and provides a full per-category breakdown. The dominant category is "Tracing" at 112.5 MB (26.2%), which is the JFR/JDK FlightRecorder buffer overhead itself during this `profile`-settings recording — operationally invisible outside the recording window.

Heap is committed at 94.1 MB with peak usage 65.9 MB, metaspace is 98.3 MB committed against 97.6 MB used (very tight, flagged by `metaspace_near_committed`), and the code cache holds 240 MB committed with only ~39.8 MB used. Thread count peaked at 33 with ~33 MB of estimated stack space.

### Memory Breakdown
- **Tracing**: 112.5 MB committed (26.2%, 112.5 MB reserved)
- **Java Heap**: 97.4 MB committed (22.6%, 512 MB reserved)
- **Metaspace**: 84.6 MB committed (19.7%, 128.4 MB reserved)
- **Code**: 70.1 MB committed (16.3%, 272.3 MB reserved)
- **Symbol**: 19.4 MB committed (4.5%, 19.4 MB reserved)
- **Class**: 16 MB committed (3.7%, 1025.9 MB reserved)
- **Shared class space**: 13.7 MB committed (3.2%, 16 MB reserved)
- **Native Memory Tracking**: 10.8 MB committed (2.5%, 10.8 MB reserved)

## Garbage Collection

The JVM is running **Serial GC** (DefNew young + SerialOld old), which is single-threaded and inappropriate for a multi-vCPU service workload — 2,283 young collections in 120 s (1,140/min) with 2.71% pause overhead, p99 = 2.2 ms, but one SerialOld full collection took 144.88 ms (the only event over 100 ms). Heap peak occupancy was 65.9 MB against 94.1 MB committed (95% of committed at peak), suggesting the heap is being driven close to its committed size repeatedly.

### GC Anomalies
- One long pause of 144.88 ms (the single SerialOld full GC) — at the upper edge of typical web request SLOs.

## CPU Profile
The sample distribution is highly unusual for a Spring Boot web app: **only 2.5% user_code, 34.6% framework, and 62.9% JDK** (attributed against 6,834 fully-attributed samples — sample quality is perfect). The dominant CPU consumers are `java.net.URL.<init>` (~14% across multiple line entries), `Arrays.binarySearch0`, `ThreadLocal$ThreadLocalMap.getEntryAfterMiss`, and Spring Boot's nested-JAR URL handler (`org.springframework.boot.loader.net.protocol.jar.*`). This is the classic signature of an executable Spring Boot fat JAR repeatedly resolving classpath resources via the nested-JAR URL protocol at runtime.

### Top Hotspots
1. `java.util.Arrays.binarySearch0:1713` — 442 samples (6.5%, jdk) ← called from `java.util.Arrays.binarySearch`
   - Likely binary search inside JAR entry tables in Spring Boot's loader.
2. `java.net.URL.<init>:630` — 410 samples (6.0%, jdk) ← called from `java.net.URL.<init>`
   - URL parsing for nested-JAR resource resolution; expensive due to the custom `jar:nested:` protocol.
3. `java.lang.ThreadLocal$ThreadLocalMap.getEntryAfterMiss:514` — 380 samples (5.6%, jdk) ← called from `java.lang.ThreadLocal.getEntry`
   - ThreadLocal map collision/probing; suggests many ThreadLocals or many threads accessing them.
4. `java.net.URL.<init>:741` — 355 samples (5.2%, jdk) ← called from `java.net.URL.<init>`
5. `java.util.HashMap.getNode:588` — 298 samples (4.4%, jdk) ← called from `java.util.HashMap.get`
6. `java.lang.StringLatin1.hashCode:195` — 297 samples (4.3%, jdk) ← called from `java.lang.String.hashCode`
7. `jdk.internal.util.ArraysSupport.mismatch:555` — 265 samples (3.9%, jdk) ← called from `java.lang.String.startsWith`
8. `java.lang.String.equalsIgnoreCase:2056` — 245 samples (3.6%, jdk) ← called from `org.springframework.boot.loader.net.protocol.jar.JarFileUrlKey.equalsIgnoringCase`
9. `org.springframework.boot.loader.net.protocol.jar.Handler.indexOfSeparator:174` — 218 samples (3.2%, framework)
10. `sun.net.www.ParseUtil.firstEncodeIndex:95` — 165 samples (2.4%, jdk) ← called from `sun.net.www.ParseUtil.encodePath`

## Allocation Hotspots

Allocation rate is **479.5 MB/s** (~57.6 GB over 120 s) — very high. The top allocated class is `byte[]` at 43.6% of bytes, followed by `java.net.URL` (17.2%) and `java.lang.String` (17.1%); these three together account for ~78% of all allocation. The largest allocation site is `java.util.Arrays.copyOfRange` (28.1% of bytes), and a major framework-attributed site is `org.springframework.boot.loader.net.protocol.jar.JarUrlConnection.open` (8.8%). JDK-attributed allocations are 74.7% of bytes; framework 24.3%; user_code only 1.0% — confirming the loader/URL machinery is the actual workload here, not Petclinic business logic. Sample quality is excellent (0.1% unattributed samples, ~0% of bytes).

### Top Allocators
1. `java.util.Arrays.copyOfRange:3849` — 16,208.2 MB (28.1%, jdk) allocating mostly `byte[]`
   - Backs `ZipFile`/`InflaterInputStream` reads inside the nested-JAR loader — every cached JAR entry retrieval re-copies bytes.
2. `jdk.internal.misc.Unsafe.allocateUninitializedArray:1396` — 7,685.2 MB (13.3%, jdk) allocating mostly `byte[]`
   - Raw byte[] backing for IO buffers and Strings; counterpart to copyOfRange above.
3. `java.lang.StringLatin1.newString:760` — 7,601.8 MB (13.2%, jdk) allocating mostly `java.lang.String`
4. `org.springframework.boot.loader.net.protocol.jar.JarUrlConnection.open:340` — 5,094.7 MB (8.8%, framework) allocating mostly `java.net.URL`
5. `jdk.internal.loader.URLClassPath$Loader.findResource:514` — 4,813.9 MB (8.4%, jdk) allocating mostly `java.net.URL`

## Concurrency & Locks

Real lock contention is present: **734 monitor-contention events totaling 27.2 s of wait time**, with the top two monitors both inside Spring Boot's loader cache — `UrlNestedJarFile` (12.15 s across 319 events) and `UrlJarFiles$Cache` (11.72 s across 319 events). This is a direct consequence of the high-rate JAR resource lookups identified in CPU/allocation. Thread parking totals 39,903 events / 1,322.7 s, but the lock_contention tool classifies 100% of park time as `pool_idle_wait` (benign worker threads waiting on `AbstractQueuedSynchronizer$ConditionObject.awaitNanos`) — these are normal Tomcat/scheduler idle waits, not contention.

### Contended Monitors
1. `org.springframework.boot.loader.net.protocol.jar.UrlNestedJarFile` — 319 events, 12151.2 ms total (38.1 ms avg, max 75.6 ms) at `org.springframework.boot.loader.jar.NestedJarFile.hasEntry:251`
2. `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles$Cache` — 319 events, 11723.9 ms total (36.8 ms avg, max 73 ms) at `org.springframework.boot.loader.net.protocol.jar.UrlJarFiles$Cache.get:158`
3. `java.util.Hashtable` — 45 events, 1353.5 ms total (30.1 ms avg, max 60.5 ms) at `java.util.Hashtable.get:382`
4. `java.lang.Object` — 26 events, 1137.3 ms total (43.7 ms avg, max 75.9 ms) at `sun.nio.ch.EPollSelectorImpl.clearInterrupt:295`
5. `jdk.internal.loader.URLClassPath` — 19 events, 646.8 ms total (34 ms avg, max 51.1 ms) at `jdk.internal.loader.URLClassPath.getLoader:388`

### Notable Park Sites
All thread parking matches normal pool-idle patterns — no findings.

## Exception Activity

**250.1 exceptions/sec over 119.8 s (29,957 total)** — `throw_rate_high` signal is set. The distribution is heavily skewed: **`java.io.EOFException` is 97% of all throws** (29,055 events), all originating from `org.apache.tomcat.util.net.NioEndpoint$NioSocketWrapper.fillReadBuffer:1339`. This is Tomcat's NIO connector signaling client-side disconnects (FIN received before a full request) — almost certainly the load generator closing keep-alive connections aggressively rather than an application bug. The remaining 3% is `java.nio.channels.CancelledKeyException` from the NIO selector (also normal network teardown). No user-code exceptions, no Error subclasses, no control-flow smell.

### Top Exception Classes
1. `java.io.EOFException` — 29,055 events (97%, 242.5/s), thrown mostly from `org.apache.tomcat.util.net.NioEndpoint$NioSocketWrapper.fillReadBuffer:1339` (framework)
   - Tomcat NIO read encountering closed sockets — typical client/load-balancer disconnect behavior, not an application bug, but the rate is high enough to investigate keepalive/pipelining settings on the client side.
2. `java.nio.channels.CancelledKeyException` — 889 events (3%, 7.4/s), thrown mostly from `sun.nio.ch.SelectionKeyImpl.ensureValid:75` (jdk)
   - Selector key cancellation races on socket close; companion symptom to the EOFException pattern above.
3. `java.lang.ClassNotFoundException` — 13 events (0%, 0.1/s), thrown mostly from `jdk.internal.loader.BuiltinClassLoader.loadClass:580` (jdk). Sample: "org.apache.catalina.webresources.WarResourceSet"

### Top Throwing Sites
Throwing sites correlate 1:1 with the top classes above.

## Findings
- **🔴 Serial GC on a server workload**: The JVM is configured with `DefNew` + `SerialOld`, single-threaded collectors unsuited to a multi-vCPU Spring Boot service. **Evidence**: jfr_gc_stats.configuration shows young=DefNew, old=SerialOld; 1 SerialOld full GC of 144.88 ms; 2,283 young collections in 120 s. **Why it matters**: Serial GC stops all application threads during every collection and scales poorly under load; under heavier traffic the SerialOld pause class will blow past request SLOs.
- **🔴 Spring Boot nested-JAR loader is the dominant runtime workload**: ~97% of on-CPU samples and ~75% of allocation bytes are JDK/framework code inside the `jar:nested:` URL protocol; user code is only 2.5% CPU / 1% allocation. **Evidence**: CPU top-10 dominated by `java.net.URL.<init>` (multiple lines), `Arrays.binarySearch0`, `JarFileUrlKey.equalsIgnoringCase`, `Handler.indexOfSeparator`; allocation sites `JarUrlConnection.open` (5.1 GB) and `URLClassPath$Loader.findResource` (4.8 GB); contended monitors `UrlNestedJarFile` and `UrlJarFiles$Cache` (319 events each, ~12 s wait each). **Why it matters**: The application is paying repeated runtime cost to resolve resources from inside the executable fat JAR — packaging strategy is bleeding through into hot-path performance.
- **🟡 Very high allocation rate (479.5 MB/s)**: Driven by transient `byte[]`/`String`/`java.net.URL` allocations from the loader path. **Evidence**: jfr_allocation.estimated_allocation_rate.mb_per_second=479.5; top class byte[] = 25 GB / 43.6% of bytes; `Arrays.copyOfRange` allocating 16.2 GB. **Why it matters**: High allocation rate drives GC frequency (1,140 GCs/min) and pushes Serial GC harder; reducing allocations here directly reduces GC pressure.
- **🟡 Monitor contention on Spring Boot loader caches**: 734 contention events, ~27 s aggregate wait time, concentrated on `UrlNestedJarFile` and `UrlJarFiles$Cache`. **Evidence**: jfr_lock_contention.monitor_contention.total_events=734; top two monitors 319 events / ~12 s wait each. **Why it matters**: Under higher concurrency these synchronized caches will serialize request threads doing classpath/resource lookups, capping throughput.
- **🟡 Metaspace near its committed size**: 97.6 MB used vs 98.3 MB committed. **Evidence**: jfr_memory.signals.metaspace_near_committed=true; metaspace.used_mb=97.6, committed_mb=98.3; metaspace_unbounded=true. **Why it matters**: Metaspace will expand on the next class definition, triggering a Metaspace-cause GC (one already occurred during this recording).
- **🟡 High Tomcat client-disconnect rate**: 242.5 EOFException/s from Tomcat's NIO read path. **Evidence**: jfr_exceptions.top_exception_classes[0]: 29,055 events, 97% share, top_throwing_site=NioEndpoint$NioSocketWrapper.fillReadBuffer. **Why it matters**: Not an application bug, but the rate suggests load generator / load balancer / client keep-alive misconfiguration; each EOF also allocates an exception object with full stack trace (449 MB total for EOFException allocations).
- **🔵 JFR Tracing buffers are the largest committed memory category**: 112.5 MB / 26.2% of committed JVM memory is the JFR `profile`-settings recording buffer itself. **Evidence**: jfr_memory.native_memory_by_category[0]: category=Tracing, committed_mb=112.5. **Why it matters**: This is recording overhead, not steady-state — disregard when sizing production pods, but be aware `settings=profile` is heavy.

## Recommendations
1. **Switch off Serial GC** (Finding: Serial GC on a server workload). Add `-XX:+UseG1GC` (or `-XX:+UseZGC` for low-pause requirements) to the JVM args. The current `-XX:+UseSerialGC` is likely an unintended default on a small container; G1 is the right baseline for a 1-CPU, 2 GB Spring Boot pod and eliminates the SerialOld full-pause risk.
2. **Repackage the application to avoid the nested-JAR loader** (Findings: Spring Boot nested-JAR loader is the dominant runtime workload; Monitor contention on Spring Boot loader caches; High allocation rate). Build the Spring Boot app with the **layered JAR + extracted layout** (`java -Djarmode=tools -jar app.jar extract`) or use a **buildpack/CNB image** that explodes the JAR into an exploded directory. Running from an exploded directory bypasses `org.springframework.boot.loader.*` entirely and eliminates the URL-construction and JAR-cache contention from the hot path.
3. **Raise the metaspace floor** (Finding: Metaspace near its committed size). Add `-XX:MetaspaceSize=128m` (and optionally `-XX:MaxMetaspaceSize=256m` for a safety ceiling) to avoid Metadata-GC-Threshold collections triggering on the first class-loading burst.
4. **Investigate the client-side disconnect pattern** (Finding: High Tomcat client-disconnect rate). Verify the load generator's keep-alive / connection-reuse settings and the load balancer's idle timeout vs Tomcat's `connectionTimeout`/`keepAliveTimeout`. No application change needed; this is a client/LB tuning issue.
5. **Drop JFR settings from `profile` to `default` outside of focused investigations** (Finding: JFR Tracing buffers are the largest committed memory category). For continuous production recording, use `settings=default` (~1% overhead vs ~2% for profile and a fraction of the buffer footprint).

## Analysis Limitations
This build analyzes CPU samples, GC behavior, object allocation, total memory footprint (with NMT for per-category native breakdown), lock contention / thread parking, and exception throws (per-class breakdown). The following are NOT yet covered and would change the picture if data is available:
- I/O wait (file, socket) — jdk.FileRead, jdk.FileWrite, jdk.SocketRead, jdk.SocketWrite events not yet analyzed
- Native-method sampling (JNI compute, native I/O syscalls)
- Class loading and JIT compilation overhead
