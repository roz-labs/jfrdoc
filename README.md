<h1 align="center">jfrdoc</h1>

<p align="center"><strong>One command. One <code>.jfr</code> file. A real performance report.</strong></p>

<p align="center">
Hand jfrdoc a JVM Flight Recorder recording and it tells you where your<br>
Spring Boot or Quarkus app is burning CPU, allocating memory, pausing for GC,<br>
contending for locks, or throwing exceptions — with framework-aware<br>
categorization, top callers, and concrete fixes you can ship today.
</p>

<p align="center">
  <img alt="Status"  src="https://img.shields.io/badge/status-pre--alpha-orange">
  <img alt="Java"    src="https://img.shields.io/badge/Java-25%2B-blue">
  <img alt="Agent"   src="https://img.shields.io/badge/agent-zsmith-7e57c2">
  <img alt="Model"   src="https://img.shields.io/badge/model-claude--opus--4--7-cc785c">
  <img alt="License" src="https://img.shields.io/badge/license-MIT-green">
</p>

> 🚧 **Day 10.** CPU-hotspot, GC, allocation, total memory footprint (with NMT-aware container-fit verdict), lock contention / thread parking, per-class exception throws, file/socket I/O wait, and native-method sampling (with wait-vs-CPU disambiguation) end-to-end against real Spring Boot recordings.

---

## Why?

`jfr print` and JMC give you raw events. Every team then writes the same rollup shell pipelines, eyeballs the same flame graphs, and writes the same Confluence page. jfrdoc skips the boilerplate: a single Claude-driven agent invokes purpose-built JFR tools and emits a structured markdown report — **findings, evidence, recommendations** — the same shape every time.

A real snippet from [`samples/petclinic/before/report.md`](samples/petclinic/before/report.md):

```markdown
## CPU Profile
Sampling density is healthy at ~57 samples/sec across 6,834 fully-attributed
samples. The category split is 62.9% JDK / 34.6% framework / 2.5% user code
— extremely skewed away from user code for a Spring Boot app under load.
The top 10 hotspots are all java.net.URL construction, JarFileUrlKey
hashing/equals, and the Spring Boot nested-JAR handler.

### Top Hotspots
1. java.util.Arrays.binarySearch0:1713  — 442 samples (6.5%, jdk)
2. java.net.URL.<init>:630              — 410 samples (6.0%, jdk)
3. ThreadLocalMap.getEntryAfterMiss:514 — 380 samples (5.6%, jdk)
...

## Findings
- 🔴 Serial GC on a web service with 479.5 MB/s allocation rate.
  Evidence: young_collector=DefNew, old_collector=SerialOld,
  max_pause_ms=144.88, gcs_per_minute=1140, heap peak 95% of committed.
  Why it matters: SerialOld pauses are STW and unpredictable; under load
  this produces visible request tail-latency spikes and offers no
  concurrency for old-gen collection.
```

---

## Quickstart

**Prerequisites:** [Java 25+](https://jdk.java.net/) (jfrdoc uses JEP 458 source-file mode and implicit classes — no build tool required).

```bash
git clone https://github.com/roz-labs/jfrdoc.git
cd jfrdoc
chmod +x jfrdoc

# Configure your Anthropic API key
mkdir -p ~/.zsmith && cat > ~/.zsmith/app.properties <<'EOF'
anthropic.api.key=sk-ant-...
anthropic.version=2023-06-01
EOF

# Analyze a JFR recording
./jfrdoc analyze recording.jfr \
    --framework spring \
    --container-memory 2Gi \
    --container-cpu 1 \
    > report.md
```

> ⚠️ **Redirect only stdout** (`> report.md`). zsmith's progress chatter goes to stderr — merging the streams with `2>&1` duplicates the report inside the file.

Each analysis costs roughly **$0.40** in Anthropic API charges with `claude-opus-4-7`.

No Maven, no Gradle, no npm. `lib/zsmith.jar` (the zero-dependency agent framework jfrdoc is built on) is committed in this repo, so the launcher runs out of the box. See [`lib/README.md`](lib/README.md) for version and rebuild instructions.

---

## What you get today

| | What                                                                                                                                                                                                                              | Where |
|-|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-|
| ✅ | **Recording context** — duration, JVM, OS, framework, container limits                                                                                                                                                            | `## Recording Context` |
| ✅ | **CPU profile split** — user code vs framework vs JDK (on-CPU Java only; native CPU is out of scope)                                                                                                                              | `## CPU Profile` |
| ✅ | **Top hotspots** with top callers and Spring/Quarkus-aware attribution                                                                                                                                                            | `### Top Hotspots` |
| ✅ | **Findings** — severity-tagged observations with numeric evidence                                                                                                                                                                 | `## Findings` |
| ✅ | **Recommendations** — actionable fixes tied to each finding                                                                                                                                                                       | `## Recommendations` |
| ✅ | **GC behavior** — collector config, pause distribution (p50/p95/p99/max), pause overhead, anomalies                                                                                                                               | `## Garbage Collection` |
| ✅ | **Memory footprint** — heap + metaspace + code cache + thread stacks + per-category NMT, with container-fit verdict                                                                                                               | `## Memory Footprint` |
| ✅ | **Allocation hotspots** — rate (MB/s), top classes, top sites with category                                                                                                                                                       | `## Allocation Hotspots` |
| ✅ | **Instrumentation-health signals** — `sample_quality` block on CPU + allocation tools surfaces unattributed samples/bytes; flagged as a 🟡 finding when ≥5% so you know when to trust the attribution                             | `## CPU Profile`, `## Allocation Hotspots` |
| ✅ | **Concurrency & locks** — monitor contention + thread parking with park-site categorization, so 36k benign pool-idle parks aren't mistaken for contention                                                                         | `## Concurrency & Locks` |
| ✅ | **Exception activity** — per-class throw rate, top throwing sites with framework-aware category, signals (control-flow smell, single-class dominance) so a 259/s EOFException out of Tomcat isn't mistaken for an application bug | `## Exception Activity` |
| ✅ | **I/O activity** — file and socket blocking time above the JFR ~10ms threshold, per-file and per-endpoint, with DB-port awareness and an explicit "absence ≠ no I/O" caveat                                                       | `## I/O Activity` |
| ✅ | **Native-method sampling** (`jdk.NativeMethodSample`) — separate tool for blocked/busy-in-native time with wait-vs-CPU signals; keeps the CPU tool's on-CPU Java scope clean so `Net.accept` / `EPoll.wait` aren't mistaken for hotspots | `## Native Execution` |
| 🛠 | Virtual-thread sizing, K8s context                                                                                                                                                                                                | _roadmap_ |

The agent's report is explicit about today's scope (CPU + GC + allocation + memory + locks + exceptions + I/O) in its **Analysis Limitations** section, so nothing is hidden.

---

## How it works

jfrdoc reads `.jfr` files via `jdk.jfr.consumer.RecordingFile` from the JDK — **no java agent, no runtime overhead in your app**. A single Claude-driven agent built on [zsmith](https://github.com/AdamBien/zsmith) calls eight tools:

| Tool | Purpose |
|-|-|
| `jfr_summary` | High-level metadata, JVM info, event distribution |
| `jfr_top_methods` | CPU hotspot aggregation with framework-aware categorization |
| `jfr_gc_stats` | GC collector config, pause distribution, heap occupancy, anomalies |
| `jfr_allocation` | Allocation rate (MB/s), top allocated classes, top allocation sites with category |
| `jfr_memory` | Total JVM memory footprint (heap, metaspace, code cache, threads, NMT) with container-fit verdict |
| `jfr_lock_contention` | Monitor contention + thread parking with heuristic category hints (pool-idle vs connection-pool vs lock-acquire) |
| `jfr_exceptions` | Per-class exception breakdown — throw rate, top classes, top throwing sites with category, control-flow-smell signal |
| `jfr_io` | File and socket I/O wait — top files / endpoints by blocking time above the JFR ~10ms threshold, DB-port-aware signals, explicit threshold caveat |
| `jfr_native_methods` | JVM native execution (syscalls / JNI) — top native methods with caller, wait-vs-CPU signal block, separate from CPU profile (mostly blocked-in-syscall wait, not on-CPU work) |

…then synthesizes a markdown report with findings, evidence, and recommendations. As more tools land, the same agent reaches for them.

---

## Sample report

[`samples/petclinic/before/report.md`](samples/petclinic/before/report.md) — end-to-end run against a 120-second JFR of [Spring PetClinic](https://github.com/spring-projects/spring-petclinic) 4.0.0-SNAPSHOT under HTTP load.
Raw tool outputs (handy as prompt-tuning fixtures): [`summary.json`](samples/petclinic/before/summary.json), [`top-methods.json`](samples/petclinic/before/top-methods.json), [`gc-stats.json`](samples/petclinic/before/gc-stats.json), [`allocation.json`](samples/petclinic/before/allocation.json), [`memory.json`](samples/petclinic/before/memory.json), [`lock-contention.json`](samples/petclinic/before/lock-contention.json), [`exceptions.json`](samples/petclinic/before/exceptions.json), [`io.json`](samples/petclinic/before/io.json).

### Case study — apply the top recommendations, re-record, diff the numbers

[`samples/petclinic/case-study.md`](samples/petclinic/case-study.md) walks through one end-to-end loop: take the two 🔴 findings from the PetClinic report (Spring Boot's nested-JAR loader dominating CPU/allocation/locks, and SerialGC firing 2,283 times in 120 s on a 94 MB heap), apply both of jfrdoc's recommended fixes — exploded JAR layout + `-XX:+UseG1GC -Xmx1g` — as a single bundled deployment change, and re-record under the same container limits and load to see what actually moves. Headline deltas: user-code CPU share **2.5% → 6.3%** (2.5×), allocation rate **479.5 → 103.2 MB/s** (−78%), GC count **2,283 → 173** (−92%), and both Spring Boot loader monitors that ate 23.9 s of wall time disappear entirely. The "after" Dockerfile lives at [`docker/petclinic-optimized/Dockerfile`](docker/petclinic-optimized/Dockerfile) and the matching compose file at [`docker-compose.optimized.yml`](docker-compose.optimized.yml); the after report sits at [`samples/petclinic/after/report.md`](samples/petclinic/after/report.md) so both runs are reproducible side-by-side.

---

## Development

Generate a tiny `.jfr` for fast local iteration (8 s of allocation churn):

```bash
./samples/gen-sample.sh        # writes samples/sample.jfr
```

Exercise each tool directly without the agent loop — useful for verifying the JSON the agent sees:

```bash
./jfrdoc debug-tool jfr-summary         samples/sample.jfr
./jfrdoc debug-tool jfr-top-methods     samples/sample.jfr --top-n 10
./jfrdoc debug-tool jfr-top-methods     samples/sample.jfr --top-n 5 --framework quarkus
./jfrdoc debug-tool jfr-gc-stats        samples/sample.jfr
./jfrdoc debug-tool jfr-allocation      samples/sample.jfr --framework spring --top-n 10
./jfrdoc debug-tool jfr-memory          samples/sample.jfr --container-memory 2Gi
./jfrdoc debug-tool jfr-lock-contention samples/sample.jfr --top-n 10
./jfrdoc debug-tool jfr-exceptions      samples/sample.jfr --framework spring --top-n 10
./jfrdoc debug-tool jfr-io              samples/sample.jfr --top-n 10
./jfrdoc debug-tool jfr-native-methods  samples/petclinic/after/petclinic.jfr --framework spring --top-n 10
```

> 💡 **Recommended JVM flags for richest analysis.** For full memory analysis (per-category native memory and the `container_fit` verdict), enable Native Memory Tracking when generating the JFR:
> ```
> -XX:NativeMemoryTracking=summary
> ```
> This adds ~1% memory overhead and is safe in production. NMT events fire roughly once per minute, so capture **at least 90–120 s** for reliable per-category data; shorter recordings may show NMT as unavailable even when the flag is set.

### Refresh the dogfood snapshot

Two independent steps. Step 1 records a fresh JFR in a container (no host JDK / Maven needed). Step 2 runs jfrdoc against the recording. The case study has both halves — the `before` (fat-jar) and `after` (exploded + G1 + `-Xmx1g`) runs — but the snippets below show the `before` half; for the `after` half, swap `docker-compose.yml` for `docker-compose.optimized.yml` and `before/` for `after/` everywhere.

**Step 1 — record `samples/petclinic/before/petclinic.jfr`** (needs `docker` + `docker compose`):

```bash
docker compose up --build --abort-on-container-exit --exit-code-from loadgen
docker compose down
```

`docker-compose.yml` runs Spring PetClinic under cgroup limits of 1 CPU / 2 GB and a 120 s JFR window, while an `alpine/curl` sidecar drives load against `/owners`, `/vets.html`, `/owners/{id}/edit`, etc. The recording is bind-mounted to `./samples/petclinic/before/petclinic.jfr` so it appears on the host the moment the JVM finalizes it. Change the limits, JFR window, or load endpoints by editing `docker-compose.yml` directly — there are no env-var indirections.

**Step 2 — analyze with jfrdoc** (needs Java 25 + your Anthropic key):

```bash
JFR=samples/petclinic/before/petclinic.jfr
OUT=samples/petclinic/before

./jfrdoc analyze "$JFR" \
    --framework spring --container-cpu 1 --container-memory 2Gi \
    > "$OUT/report.md"

./jfrdoc debug-tool jfr-summary         "$JFR" > "$OUT/summary.json"
./jfrdoc debug-tool jfr-top-methods     "$JFR" --framework spring --top-n 20 > "$OUT/top-methods.json"
./jfrdoc debug-tool jfr-gc-stats        "$JFR" > "$OUT/gc-stats.json"
./jfrdoc debug-tool jfr-allocation      "$JFR" --framework spring --top-n 20 > "$OUT/allocation.json"
./jfrdoc debug-tool jfr-memory          "$JFR" --container-memory 2Gi > "$OUT/memory.json"
./jfrdoc debug-tool jfr-lock-contention "$JFR" --top-n 10 > "$OUT/lock-contention.json"
./jfrdoc debug-tool jfr-exceptions      "$JFR" --framework spring --top-n 15 > "$OUT/exceptions.json"
./jfrdoc debug-tool jfr-io              "$JFR" --top-n 10 > "$OUT/io.json"
./jfrdoc debug-tool jfr-native-methods  "$JFR" --framework spring --top-n 20 > "$OUT/native-methods.json"
```

**Why containers, not host `java -jar`?** The JVM honors cgroup limits (`-XX:UseContainerSupport` is default on JDK 25), so GC heuristics, ForkJoin pool size, and `Runtime.availableProcessors()` all reflect the constrained environment — which is exactly what jfrdoc's `--container-cpu` / `--container-memory` flags claim in the report header. As a bonus, the build is reproducible across macOS / Linux / Windows because both build and run happen inside `eclipse-temurin:25-jdk`, so host Maven version, BSD vs GNU coreutils quirks, and corporate TLS-MITM proxies stop mattering for the recording step.

---

## Roadmap

- ✅ **Done** — CLI scaffold, `jfr_summary` + `jfr_top_methods` + `jfr_gc_stats` + `jfr_allocation` + `jfr_memory` + `jfr_lock_contention` + `jfr_exceptions` + `jfr_io` + `jfr_native_methods` tools, agent wiring, first dogfood on Spring PetClinic
- 📦 **Next month** — K8s-aware diagnostics, multi-`.jfr` correlation
- ☁ **Future** — hosted SaaS with history and trends

---

## License

MIT — see [LICENSE](LICENSE).
