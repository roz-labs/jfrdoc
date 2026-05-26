# jfrdoc Analysis Report

## Executive Summary
This Spring Boot PetClinic recording shows a JVM dominated by Spring Boot's nested-jar classloader: ~97% of CPU samples sit in JDK/framework code (with `java.net.URL` construction, `URLClassPath.findResource`, and `JarFileUrlKey` hashing topping the list), and the same machinery drives a ~430 MB/s allocation rate. Memory footprint is comfortably inside the 2 GiB limit (428 MB committed, 79% headroom) and GC pause overhead is low (2.74%), but a 127 ms SerialOld pause indicates the Serial collector is in use — unusual for a server workload and worth correcting. Exception-throw rate is also extremely high at 259/s.

## Recording Context
- **File**: /Users/ridvanozcan/code/jfrdoc/samples/petclinic.jfr
- **Duration**: 120.136 s
- **JVM**: OpenJDK 64-Bit Server VM 25.0.3+9-LTS
- **OS**: linux-aarch64
- **Framework**: spring
- **Container limits**: memory=2Gi cpu=1
- **Total events captured**: 197,763

## Memory Footprint
Container fit: **SAFE** — 427.6 MB committed of 2048 MB limit (79.1% headroom). Dominant NMT category is Tracing at 112.1 MB, which is the JFR profile recording itself; Java Heap (97.4 MB committed), Metaspace (84.6 MB), and Code (69.3 MB) follow. Heap peaks at 65.9 MB used against 94.2 MB committed and only 33 threads are active, so there is no memory pressure. Metaspace is committed close to used (98.3 MB committed vs 97.6 MB used) and is unbounded — fine for now but worth observing across longer runs.

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
The JVM is running the **Serial collector** (DefNew young / SerialOld old) — almost certainly an unintended default from a single-CPU container limit. GC frequency is very high at 1018.8/min (2040 collections in 120 s), all from allocation failure, but per-pause times are short (p50 1.33 ms, p99 2.44 ms) so pause overhead is only 2.74%. The standout is a single 127.28 ms SerialOld pause, which is a stop-the-world full collection that would surface as a latency outlier.

### GC Anomalies
- **Long pause ≥100 ms**: 1 occurrence (127.28 ms SerialOld) — a single-threaded full-heap compaction; on a request path this becomes a tail-latency spike.

## CPU Profile
The distribution is extremely skewed: **jdk = 62.9%, framework = 34.3%, user_code = 2.8%, native = 0%**. For a Spring Boot app under load user_code typically sits at 30–60%; here, almost all CPU is being consumed by `java.net.URL` construction, `URLClassPath.findResource`, `ThreadLocalMap.getEntryAfterMiss`, and Spring Boot's `JarFileUrlKey` hashing/equality. This is the classic signature of repeated classpath/resource lookups through the Spring Boot loader's nested-jar URL handler — the application is spending its CPU finding resources, not serving business logic. Sampling density is healthy at 61.1 samples/s.

### Top Hotspots
1. `java.util.Arrays.binarySearch0:1713` — 417 samples (5.7%, jdk) ← called from `java.util.Arrays.binarySearch`
   - Driven by JDK internal lookups (most likely class/resource name binary search inside the nested-jar index).
2. `java.net.URL.<init>:630` — 354 samples (4.8%, jdk) ← called from `java.net.URL.<init>`
   - Repeated URL construction on every resource lookup; combined with ranks 6/10/14 (`URL.<init>` variants), URL construction alone consumes ~13% of CPU.
3. `java.lang.ThreadLocal$ThreadLocalMap.getEntryAfterMiss:514` — 326 samples (4.4%, jdk) ← called from `ThreadLocalMap.getEntry`
   - The "after miss" path means linear probing past a hash collision in a thread-local map — typically a sign of many distinct ThreadLocal keys or short-lived threads.
4. `jdk.internal.loader.URLClassPath$Loader.findResource:534` — 308 samples (4.2%, jdk) ← called from `URLClassPath.findResource`
5. `java.util.Objects.hashCode:97` — 288 samples (3.9%, jdk) ← called from `org.springframework.boot.loader.net.protocol.jar.JarFileUrlKey.hashCode`
6. `java.net.URL.<init>:741` — 285 samples (3.9%, jdk) ← called from `java.net.URL.<init>`
7. `java.lang.String.equalsIgnoreCase:2056` — 234 samples (3.2%, jdk) ← called from `JarFileUrlKey.equalsIgnoringCase`
8. `jdk.internal.util.ArraysSupport.mismatch:555` — 228 samples (3.1%, jdk) ← called from `java.lang.String.startsWith`
9. `sun.net.www.ParseUtil.firstEncodeIndex:95` — 212 samples (2.9%, jdk) ← called from `sun.net.www.ParseUtil.encodePath`
10. `java.net.URL.<init>:764` — 208 samples (2.8%, jdk) ← called from `java.net.URL.<init>`

## Allocation Hotspots
Estimated allocation rate is **429.5 MB/s** (51.6 GB across 120 s) — very high, and explains the constant young-GC churn. `byte[]` dominates at 43.9% of bytes (22.6 GB), followed by `java.lang.String` (17.8%) and `java.net.URL` (16.9%). By category, **jdk = 75.3%, framework = 23.8%, user_code = 0.9%** — the same Spring Boot nested-jar resource loading drives most of it: `JarUrlConnection.open`, `UrlJarFiles$Cache.putIfAbsent/get`, and `URLClassPath$Loader.findResource` together produce roughly 30% of all bytes allocated.

### Top Allocators
1. `java.util.Arrays.copyOfRange:3849` — 14275.0 MB (27.7%, jdk) allocating mostly `byte[]`
   - Bulk byte-array slicing — typically inside zip/jar entry reading or HTTP buffer handling; ties directly to the classloader workload.
2. `jdk.internal.misc.Unsafe.allocateUninitializedArray:1396` — 7223.4 MB (14.0%, jdk) allocating mostly `byte[]`
   - The backing primitive for `new byte[]` from things like `ByteBuffer`/`InputStream.readAllBytes`; combined with #1, raw `byte[]` allocation alone is ~42% of all bytes.
3. `java.lang.StringLatin1.newString:760` — 7017.3 MB (13.6%, jdk) allocating mostly `java.lang.String`
4. `jdk.internal.loader.URLClassPath$Loader.findResource:514` — 4400.1 MB (8.5%, jdk) allocating mostly `java.net.URL`
5. `org.springframework.boot.loader.net.protocol.jar.JarUrlConnection.open:340` — 4308.2 MB (8.4%, framework) allocating mostly `java.net.URL`

## Findings
- **🔴 Spring Boot nested-jar loader saturates CPU and allocations**: ~30%+ of CPU and ~30% of allocated bytes are consumed by `JarFileUrlKey` hashing/equality, `URL.<init>`, `URLClassPath.findResource`, and `UrlJarFiles$Cache` lookups. **Evidence**: Hotspots ranks 1–10 are all classloader / URL machinery (CPU); allocation sites #4 (`URLClassPath$Loader.findResource` 4.4 GB) and #5 (`JarUrlConnection.open` 4.3 GB) plus #6/#7 (`UrlJarFiles$Cache` ~4.2 GB combined) account for ~25% of total bytes. User code is only 2.8% of CPU and 0.9% of bytes. **Why it matters**: At 429.5 MB/s allocation and >1000 GCs/min, the JVM is spending most of its budget on classpath resource resolution instead of business logic — directly limiting throughput on a 1-CPU container.
- **🟡 Serial GC on a server workload, with a 127 ms full-GC outlier**: Configuration reports young=`DefNew`, old=`SerialOld`, and one 127.28 ms SerialOld pause occurred. **Evidence**: `configuration.young_collector="DefNew"`, `anomalies.long_pauses_over_100ms=1`, `by_name.SerialOld.max_pause_ms=127.28`. **Why it matters**: Serial GC is single-threaded and selected by default when the JVM sees ≤1 available processor; on Kubernetes with `cpu=1` this is the likely cause. A 127 ms stop-the-world pause is a visible tail-latency event for HTTP requests.
- **🟡 Very high Java exception throw rate (258.9/s)**: 31,099 exceptions thrown in 120 s. **Evidence**: `derived.javaExceptionThrowPerSecond=258.9`; allocation table shows 333.4 MB of `java.io.EOFException` allocated from `NioEndpoint$NioSocketWrapper.fillReadBuffer`. **Why it matters**: Exceptions as control flow allocate, fill stack traces, and pollute GC. The EOFException source strongly suggests client disconnect handling in Tomcat, which is expected at low volume but becomes a hotspot under load.
- **🟢 Metaspace committed is near its used size**: 98.3 MB committed vs 97.6 MB used. **Evidence**: `metaspace.committed_mb=98.3`, `used_mb=97.6`, `signals.metaspace_near_committed=true`. **Why it matters**: Further class loading will trigger metaspace expansion and possibly a metadata-cause GC (one already observed). Headroom exists in reserved (128.4 MB), so this is not urgent.
- **🔵 JFR profile recording is the largest single native memory category**: 112.1 MB committed (26.2% of total). **Evidence**: `native_memory_by_category[0]={category:"Tracing", committed_mb:112.1}`. **Why it matters**: This is the cost of `settings=profile` for this recording; it disappears once JFR stops. Useful to know when interpreting absolute footprint numbers.

## Recommendations
1. **Address "Spring Boot nested-jar loader saturates CPU and allocations"** — rebuild the image as an *exploded* (unpacked) Spring Boot layout instead of a fat jar. Use Spring Boot's `org.springframework.boot.loader.tools.JarLauncher` extraction or, preferably, build with `spring-boot-maven-plugin` `<layered>` + `java -Djarmode=tools extract` and launch the extracted main class directly. This eliminates the `jar:nested:` URL handler from the hot path. Buildpacks/Paketo and `bootBuildImage` do this by default — confirm the deployed image is using one of these. Expect a large reduction in `byte[]`/`URL` allocation and GC frequency.
2. **Address "Serial GC on a server workload"** — explicitly select G1 by adding `-XX:+UseG1GC` to JVM args, or raise the container CPU request to ≥2 so the JVM auto-selects G1/Parallel. On 1 CPU with G1 you still get concurrent-style behavior and avoid 100+ ms SerialOld pauses. Also consider sizing heap explicitly with `-XX:MaxRAMPercentage=50` since current committed heap (94 MB) is far below the 2 GiB container limit.
3. **Address "Very high Java exception throw rate"** — investigate the Tomcat `NioEndpoint.fillReadBuffer` EOFException source: ensure the upstream LB / ingress is not aggressively closing keep-alive connections, and check whether any application code uses exceptions for control flow (e.g., `NumberFormatException`, `NoSuchElementException` in request parsing). Enabling `-XX:+OmitStackTraceInFastThrow` is already default; the durable fix is reducing the throw rate at the source.
4. **Address "Metaspace committed is near its used size"** — set an explicit cap such as `-XX:MaxMetaspaceSize=256m` to fail fast on classloader leaks and to make the working set predictable; current reserved (128.4 MB) gives room to grow without imminent issue.

## Analysis Limitations
This build analyzes CPU samples, GC behavior, object allocation, and total memory footprint (with NMT for per-category native breakdown). The following are NOT yet covered and would change the picture if data is available:
- Lock contention and thread blocking (36,745 `ThreadPark` events were captured but not analyzed in detail)
- I/O wait (file, socket)
- Class loading and JIT compilation overhead
- Exception throw analysis (raw counts visible in summary but no per-class breakdown yet)
