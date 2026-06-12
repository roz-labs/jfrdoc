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
[`before/exceptions.json`](before/exceptions.json),
[`before/io.json`](before/io.json),
[`before/native-methods.json`](before/native-methods.json).

The two headline jfrdoc surfaced:

> 🔴 "~97% of on-CPU samples and ~75% of allocation bytes are JDK/framework
> code inside the `jar:nested:` URL protocol; user code is only 2.8%
> CPU / 0.9% allocation."
>
> 🔴 "Serial GC on a server workload … 2,321 young collections in 120 s
> (1,159/min) … one SerialOld full collection took 148.91 ms."

Numbers the report called out:

| Signal | Value | Where in the report |
|-|-|-|
| **Loader / CPU** | | |
| User-code CPU share | **2.8%** (JDK 59.9% / framework 37.3%) | `## CPU Profile` |
| Top hotspot | `NestedJarFile.hasEntry` **5.5%** (Spring Boot loader) | `### Top Hotspots` #1 |
| `java.net.URL.<init>` (2 sites combined) | **10.5%** of all samples | `### Top Hotspots` #2, #4 |
| `JarFileUrlKey.equalsIgnoringCase` + `Handler.indexOfSeparator` | **6.5%** | `### Top Hotspots` #8, #9 |
| **Allocation / locks** | | |
| Sustained allocation rate | **495.1 MB/s** | `## Allocation Hotspots` |
| Top contended monitor | `UrlNestedJarFile` — **13,390.1 ms** wait (353 events) | `### Contended Monitors` #1 |
| Second contended monitor | `UrlJarFiles$Cache` — **10,256.1 ms** wait (277 events) | `### Contended Monitors` #2 |
| **GC / heap** | | |
| GC collector | **SerialGC** (DefNew young + SerialOld old) | `## Garbage Collection` |
| GCs in 120 s | **2,321** (1,159/min) | `## Garbage Collection` |
| Max GC pause | **148.91 ms** (single SerialOld STW full collection) | `### GC Anomalies` |
| Heap committed | **94.2 MB** of 2 GiB container limit | `## Memory Footprint` |

Two roots, one shared symptom. The top two contended monitors and ~30%+ of
allocated bytes trace back to **Spring Boot's nested-JAR loader**
(`org.springframework.boot.loader.net.protocol.jar.*`) reaching for resources
inside the fat JAR on every request. And because the container is sized to
**1 CPU**, the JVM auto-picks the single-threaded **SerialGC** and refuses to
grow the heap past ~94 MB — so that allocation pressure fires a GC every
~52 ms.

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

Before — [`before/Dockerfile`](before/Dockerfile):

```dockerfile
WORKDIR /src
RUN git clone --depth 1 https://github.com/spring-projects/spring-petclinic.git . \
 && ./mvnw -q -DskipTests package \
 && cp target/spring-petclinic-*.jar /app.jar
ENTRYPOINT ["java"]
```

After — [`after/Dockerfile`](after/Dockerfile):

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
the single-threaded SerialGC; `-Xmx1g` raises the heap ceiling roughly
~11× above the ~94 MB it stayed pinned at before, and in this recording
G1 settles around ~170 MB at steady state — enough headroom to flatten
the per-collection cadence.

### compose `command:` diff (loader + GC flags together)

Before — [`before/docker-compose.yml`](before/docker-compose.yml):

```yaml
volumes:
  - ./:/jfr
command:
  - -XX:NativeMemoryTracking=summary
  - -XX:StartFlightRecording=delay=15s,duration=120s,filename=/jfr/petclinic.jfr,settings=profile
  - -jar
  - /app.jar
```

After — [`after/docker-compose.yml`](after/docker-compose.yml):

```yaml
volumes:
  - ./:/jfr
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
[`after/exceptions.json`](after/exceptions.json),
[`after/io.json`](after/io.json),
[`after/native-methods.json`](after/native-methods.json).

Same recording window, same container limits, same load mix. What moved:

| Signal | Before | After | Δ |
|-|-|-|-|
| **Loader / CPU** | | | |
| User-code CPU share | 2.8% | **6.7%** | **+3.9 pp (2.4×)** |
| Top hotspot | `NestedJarFile.hasEntry` (Spring Boot loader) | `ZipFile$Source.getEntryPos` (plain JDK URLClassLoader) | nature changed |
| `java.net.URL.<init>` share | 10.5% | **0%** (gone from top 10) | **−10.5 pp** |
| `JarFileUrlKey.equalsIgnoringCase` + `Handler.indexOfSeparator` | 6.5% | **0%** (gone) | **−6.5 pp** |
| **Allocation / locks** | | | |
| Allocation rate | 495.1 MB/s | **86.5 MB/s** | **−83%** |
| `UrlNestedJarFile` monitor wait | 13,390.1 ms (353 events) | **0 ms (monitor no longer exists)** | **−100%** |
| `UrlJarFiles$Cache` monitor wait | 10,256.1 ms (277 events) | **0 ms (monitor no longer exists)** | **−100%** |
| Total monitor-contention wait | 27.5 s (735 events) | **8.3 s (152 events)** | **−70%** |
| **GC / heap** | | | |
| GC collector | SerialGC (DefNew + SerialOld) | **G1 (G1New + G1Old)** | switched |
| GCs in 120 s | 2,321 (1,159/min) | **161 (80.5/min)** | **−93%** |
| Max GC pause | 148.91 ms (SerialOld full GC) | **65.96 ms (G1 old/mixed)** | **−56%** |
| GC anomalies | 1 long pause >100 ms | **0** | cleared |
| Heap committed | 94.2 MB (Serial wouldn't grow) | **171 MB** (G1 with `-Xmx1g` headroom) | +82% |

The two top contended monitors from before — `UrlNestedJarFile` and
`UrlJarFiles$Cache`, together responsible for ~86% of all monitor wait time
in the original recording — **do not appear at all** in the after recording.
They cannot: those classes are loaded only when the JVM is running through
Spring Boot's nested-JAR URL protocol, and the after deployment doesn't use
that protocol. What remains is plain JDK `java.util.jar.JarFile` contention
at 5.78 s (115 events) — standard `URLClassLoader` behavior on the
classpath, still noticeably smaller than the 23.6 s of loader-specific
contention that went away.

The after report's own top finding ([`after/report.md`](after/report.md))
is now no longer about the loader at all — it's a 🟡 note about the
Tomcat NIO `EOFException` rate (187.8/s) coming from clients/load-balancer
not reusing keep-alive connections, which was present in the before
recording too (258.5/s there) but was hidden under the louder loader story.

---

## 4. Reproduce

Each compose file bind-mounts its own target directory, so the JFRs land
straight next to this document — no manual moves.

**Record the `before` JFR** (fat jar):

```bash
docker compose -f samples/petclinic/before/docker-compose.yml up --build --abort-on-container-exit --exit-code-from loadgen
docker compose -f samples/petclinic/before/docker-compose.yml down
# → samples/petclinic/before/petclinic.jfr
```

**Record the `after` JFR** (exploded layout + G1 + `-Xmx1g`):

```bash
docker compose -f samples/petclinic/after/docker-compose.yml up --build --abort-on-container-exit --exit-code-from loadgen
docker compose -f samples/petclinic/after/docker-compose.yml down
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
