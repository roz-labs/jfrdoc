# Case study — jfrdoc against Spring PetClinic

> **The premise.** Run jfrdoc against a stock Spring Boot fat-jar deployment.
> Read the top two recommendations. Apply both as a single bundle. Re-record.
> Show the diff. No cherry-picking — same workload, same container limits,
> same JFR settings.

This page walks through one end-to-end loop:

1. **Before** — what jfrdoc said about a stock `java -jar app.jar` PetClinic
2. **The change** — two bundled fixes (loader + GC/heap), exactly which lines moved, and why each one was picked
3. **After** — the same recording pipeline against the changed deployment
4. **Reproduce** — the two `docker compose` commands that produce both JFRs

---

## 1. Before — `java -jar app.jar`

Full report: [`before/report.md`](before/report.md). Raw tool JSON sits next
to it: [`before/summary.json`](before/summary.json),
[`before/top-methods.json`](before/top-methods.json),
[`before/gc-stats.json`](before/gc-stats.json),
[`before/allocation.json`](before/allocation.json),
[`before/memory.json`](before/memory.json),
[`before/lock-contention.json`](before/lock-contention.json),
[`before/exceptions.json`](before/exceptions.json).

The two headline jfrdoc surfaced:

> 🔴 "~97% of on-CPU samples and ~75% of allocation bytes are JDK/framework
> code inside the `jar:nested:` URL protocol; user code is only 2.5%
> CPU / 1% allocation."
>
> 🔴 "Serial GC on a server workload … 2,283 young collections in 120 s
> (1,140/min) … one SerialOld full collection took 144.88 ms."

Numbers the report called out:

| Signal | Value | Where in the report |
|-|-|-|
| **Loader / CPU** | | |
| User-code CPU share | **2.5%** (JDK 62.9% / framework 34.6%) | `## CPU Profile` |
| Top hotspot | `Arrays.binarySearch0` **6.5%** (called from JAR/classpath lookup) | `### Top Hotspots` #1 |
| `java.net.URL.<init>` (2 sites combined) | **11.2%** of all samples | `### Top Hotspots` #2, #4 |
| `JarFileUrlKey.equalsIgnoringCase` + `Handler.indexOfSeparator` | **6.8%** | `### Top Hotspots` #8, #9 |
| **Allocation / locks** | | |
| Sustained allocation rate | **479.5 MB/s** | `## Allocation Hotspots` |
| Top contended monitor | `UrlNestedJarFile` — **12,151.2 ms** wait (319 events) | `### Contended Monitors` #1 |
| Second contended monitor | `UrlJarFiles$Cache` — **11,723.9 ms** wait (319 events) | `### Contended Monitors` #2 |
| **GC / heap** | | |
| GC collector | **SerialGC** (DefNew young + SerialOld old) | `## Garbage Collection` |
| GCs in 120 s | **2,283** (1,140/min) | `## Garbage Collection` |
| Max GC pause | **144.88 ms** (single SerialOld STW full collection) | `### GC Anomalies` |
| Heap committed | **94.1 MB** of 2 GiB container limit | `## Memory Footprint` |

Two roots, one shared symptom. The top two contended monitors and ~30%+ of
allocated bytes trace back to **Spring Boot's nested-JAR loader**
(`org.springframework.boot.loader.net.protocol.jar.*`) reaching for resources
inside the fat JAR on every request. And because the container is sized to
**1 CPU**, the JVM auto-picks the single-threaded **SerialGC** and refuses to
grow the heap past ~94 MB — so that allocation pressure fires a GC every
~53 ms.

---

## 2. The change — two bundled fixes

Two independent recommendations from the report, applied together as a
single deployment change so the comparison is one before vs one after:

| # | Recommendation | What we did |
|-|-|-|
| 2a | "Repackage the application to avoid the nested-JAR loader at runtime" | Extract the fat JAR at image-build time, run the main class with a plain filesystem classpath |
| 2b | "Add `-XX:+UseG1GC` to JVM args … set `-Xmx` explicitly (e.g. `-Xmx1g`)" | Pass both flags through the JVM command line |

Everything else — **CPU limit (1)**, **memory limit (2 GiB)**, **JFR window
(120 s, `settings=profile`)**, load generator endpoints, and the 150 s curl
loop — is held constant.

### 2a. Exit the nested-JAR loader

> Recommendation #1: "Repackage the application to avoid the nested-JAR
> loader at runtime … Any of these eliminates `JarFileUrlKey` /
> `UrlNestedJarFile` / `URLClassPath` hot paths entirely."

Extract the fat JAR at image-build time and launch the application's main
class directly with a plain filesystem classpath. Dependencies under
`BOOT-INF/lib/*.jar` are then read by the JDK's standard `URLClassLoader`
— no `UrlNestedJarFile`, no `UrlJarFiles$Cache`, no per-request URL parsing.

Before — [`docker/petclinic/Dockerfile`](../../docker/petclinic/Dockerfile):

```dockerfile
WORKDIR /src
RUN git clone --depth 1 https://github.com/spring-projects/spring-petclinic.git . \
 && ./mvnw -q -DskipTests package \
 && cp target/spring-petclinic-*.jar /app.jar
ENTRYPOINT ["java"]
```

After — [`docker/petclinic-optimized/Dockerfile`](../../docker/petclinic-optimized/Dockerfile):

```dockerfile
WORKDIR /src
RUN git clone --depth 1 https://github.com/spring-projects/spring-petclinic.git . \
 && ./mvnw -q -DskipTests package \
 && mkdir -p /app \
 && (cd /app && jar -xf /src/target/spring-petclinic-*.jar)
WORKDIR /app
ENTRYPOINT ["java"]
```

### 2b. Switch off SerialGC, give the heap real room

> Recommendation #2: "Raise the container CPU request/limit to at least 2
> (this alone causes the JVM to auto-select G1), or explicitly add
> `-XX:+UseG1GC` to JVM args. With a 2 GiB container, also set `-Xmx`
> explicitly (e.g. `-Xmx1g`) so the heap can grow beyond 94 MB and reduce
> GC frequency."

We took the explicit-flag path so the container limits stay identical
between runs — only the JVM's own choices change. `-XX:+UseG1GC` replaces
the single-threaded SerialGC; `-Xmx1g` lets the heap grow ~10× beyond
the ~94 MB it stayed pinned at before.

### compose `command:` diff (loader + GC flags together)

Before — [`docker-compose.yml`](../../docker-compose.yml):

```yaml
volumes:
  - ./samples/petclinic/before:/jfr
command:
  - -XX:NativeMemoryTracking=summary
  - -XX:StartFlightRecording=delay=15s,duration=120s,filename=/jfr/petclinic.jfr,settings=profile
  - -jar
  - /app.jar
```

After — [`docker-compose.optimized.yml`](../../docker-compose.optimized.yml):

```yaml
volumes:
  - ./samples/petclinic/after:/jfr            # mount target dir for this run
command:
  - -XX:NativeMemoryTracking=summary
  - -XX:+UseG1GC                # 2b. no more SerialGC default
  - -Xmx1g                      # 2b. heap can grow past ~94 MB
  - -XX:StartFlightRecording=delay=15s,duration=120s,filename=/jfr/petclinic.jfr,settings=profile
  - -cp                                                           # 2a. exploded
  - /app/BOOT-INF/classes:/app/BOOT-INF/lib/*                     # 2a. plain classpath
  - org.springframework.samples.petclinic.PetClinicApplication    # 2a. direct main class
```

---

## 3. After — exploded layout + G1 + `-Xmx1g`

Full report: [`after/report.md`](after/report.md). Raw tool JSON sits next
to it: [`after/summary.json`](after/summary.json),
[`after/top-methods.json`](after/top-methods.json),
[`after/gc-stats.json`](after/gc-stats.json),
[`after/allocation.json`](after/allocation.json),
[`after/memory.json`](after/memory.json),
[`after/lock-contention.json`](after/lock-contention.json),
[`after/exceptions.json`](after/exceptions.json).

Same recording window, same container limits, same load mix. What moved:

| Signal | Before | After | Δ |
|-|-|-|-|
| **Loader / CPU** | | | |
| User-code CPU share | 2.5% | **6.3%** | **+3.8 pp (2.5×)** |
| Top hotspot | `Arrays.binarySearch0` (Spring Boot loader JAR lookup) | `JarFile.getEntry` (plain JDK URLClassLoader) | nature changed |
| `java.net.URL.<init>` share | 11.2% | **0%** (gone from top 10) | **−11.2 pp** |
| `JarFileUrlKey.equalsIgnoringCase` + `Handler.indexOfSeparator` | 6.8% | **0%** (gone) | **−6.8 pp** |
| **Allocation / locks** | | | |
| Allocation rate | 479.5 MB/s | **103.2 MB/s** | **−78%** |
| `UrlNestedJarFile` monitor wait | 12,151.2 ms (319 events) | **0 ms (monitor no longer exists)** | **−100%** |
| `UrlJarFiles$Cache` monitor wait | 11,723.9 ms (319 events) | **0 ms (monitor no longer exists)** | **−100%** |
| Total monitor-contention wait | 27.2 s (734 events) | **3.88 s (89 events)** | **−86%** |
| **GC / heap** | | | |
| GC collector | SerialGC (DefNew + SerialOld) | **G1 (G1New + G1Old)** | switched |
| GCs in 120 s | 2,283 (1,140/min) | **173 (86.5/min)** | **−92%** |
| Max GC pause | 144.88 ms (SerialOld full GC) | **72.25 ms (G1 mixed pause)** | **−50%** |
| GC anomalies | 1 long pause >100 ms | **0** | cleared |
| Heap committed | 94.1 MB (Serial wouldn't grow) | **157 MB** (G1 with `-Xmx1g` headroom) | +67% |

The two top contended monitors from before — `UrlNestedJarFile` and
`UrlJarFiles$Cache`, together responsible for ~88% of all monitor wait time
in the original recording — **do not appear at all** in the after recording.
They cannot: those classes are loaded only when the JVM is running through
Spring Boot's nested-JAR URL protocol, and the after deployment doesn't use
that protocol. What remains is plain JDK `java.util.jar.JarFile` contention
at 2.73 s (63 events) — standard URLClassLoader behavior, an order of
magnitude smaller.

The after report's own top finding ([`after/report.md`](after/report.md))
is now no longer about the loader at all — it's a 🟡 note about the
Tomcat NIO `EOFException` rate (200.8/s) coming from clients/load-balancer
not reusing keep-alive connections, which was present in the before
recording too but was hidden under the louder loader story.

---

## 4. Reproduce

Each compose file bind-mounts its own target directory, so the JFRs land
straight next to this document — no manual moves.

**Record the `before` JFR** (fat jar):

```bash
docker compose up --build --abort-on-container-exit --exit-code-from loadgen
docker compose down
# → samples/petclinic/before/petclinic.jfr
```

**Record the `after` JFR** (exploded layout + G1 + `-Xmx1g`):

```bash
docker compose -f docker-compose.optimized.yml up --build \
  --abort-on-container-exit --exit-code-from loadgen
docker compose -f docker-compose.optimized.yml down
# → samples/petclinic/after/petclinic.jfr
```

**Run jfrdoc against either recording** (Java 25 + your Anthropic key):

```bash
./jfrdoc analyze samples/petclinic/before/petclinic.jfr \
    --framework spring --container-cpu 1 --container-memory 2Gi \
    > samples/petclinic/before/report.md

./jfrdoc analyze samples/petclinic/after/petclinic.jfr \
    --framework spring --container-cpu 1 --container-memory 2Gi \
    > samples/petclinic/after/report.md
```

Same container limits, same JFR window, same load mix — only the launch
strategy and JVM flags change.
