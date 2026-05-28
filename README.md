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

> 🚧 **Day 10.** CPU-hotspot, GC, allocation, total memory footprint (with NMT-aware container-fit verdict), lock contention / thread parking, and per-class exception throws end-to-end against real Spring Boot recordings. I/O wait tool next.

---

## Why?

`jfr print` and JMC give you raw events. Every team then writes the same rollup shell pipelines, eyeballs the same flame graphs, and writes the same Confluence page. jfrdoc skips the boilerplate: a single Claude-driven agent invokes purpose-built JFR tools and emits a structured markdown report — **findings, evidence, recommendations** — the same shape every time.

A real snippet from [`samples/petclinic-report.md`](samples/petclinic-report.md):

```markdown
## CPU Profile
Sampling density is healthy at 61.1 samples/sec across 7,341 attributed
samples (0% unattributed). The category split is 62.9% JDK / 34.3% framework
/ 2.8% user code — extremely skewed away from user code for a Spring Boot
app under load. The top 10 hotspots are all java.net.URL construction,
JarFileUrlKey hashing/equals, and URLClassPath.findResource.

### Top Hotspots
1. java.util.Arrays.binarySearch0:1713  — 417 samples (5.7%, jdk)
2. java.net.URL.<init>:630              — 354 samples (4.8%, jdk)
3. ThreadLocalMap.getEntryAfterMiss:514 — 326 samples (4.4%, jdk)
...

## Findings
- 🔴 Serial GC on a web service with 429 MB/s allocation rate.
  Evidence: young_collector=DefNew, old_collector=SerialOld,
  max_pause_ms=127.28, gcs_per_minute=1018.8, heap peak 96.7% of committed.
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

| | What | Where |
|-|-|-|
| ✅ | **Recording context** — duration, JVM, OS, framework, container limits | `## Recording Context` |
| ✅ | **CPU profile split** — user code vs framework vs JDK (on-CPU Java only; native CPU is out of scope) | `## CPU Profile` |
| ✅ | **Top hotspots** with top callers and Spring/Quarkus-aware attribution | `### Top Hotspots` |
| ✅ | **Findings** — severity-tagged observations with numeric evidence | `## Findings` |
| ✅ | **Recommendations** — actionable fixes tied to each finding | `## Recommendations` |
| ✅ | **GC behavior** — collector config, pause distribution (p50/p95/p99/max), pause overhead, anomalies | `## Garbage Collection` |
| ✅ | **Memory footprint** — heap + metaspace + code cache + thread stacks + per-category NMT, with container-fit verdict | `## Memory Footprint` |
| ✅ | **Allocation hotspots** — rate (MB/s), top classes, top sites with category | `## Allocation Hotspots` |
| ✅ | **Instrumentation-health signals** — `sample_quality` block on CPU + allocation tools surfaces unattributed samples/bytes; flagged as a 🟡 finding when ≥5% so you know when to trust the attribution | `## CPU Profile`, `## Allocation Hotspots` |
| ✅ | **Concurrency & locks** — monitor contention + thread parking with park-site categorization, so 36k benign pool-idle parks aren't mistaken for contention | `## Concurrency & Locks` |
| ✅ | **Exception activity** — per-class throw rate, top throwing sites with framework-aware category, signals (control-flow smell, single-class dominance) so a 259/s EOFException out of Tomcat isn't mistaken for an application bug | `## Exception Activity` |
| 🛠 | I/O wait | _roadmap_ |
| 🛠 | Native-method sampling (`jdk.NativeMethodSample`) — separate tool for blocked/busy-in-native time; keeps the CPU tool's on-CPU Java scope clean | _roadmap_ |
| 🛠 | Virtual-thread sizing, K8s context | _roadmap_ |

The agent's report is explicit about today's scope (CPU + GC + allocation + memory) in its **Analysis Limitations** section, so nothing is hidden.

---

## How it works

jfrdoc reads `.jfr` files via `jdk.jfr.consumer.RecordingFile` from the JDK — **no java agent, no runtime overhead in your app**. A single Claude-driven agent built on [zsmith](https://github.com/AdamBien/zsmith) calls seven tools:

| Tool | Purpose |
|-|-|
| `jfr_summary` | High-level metadata, JVM info, event distribution |
| `jfr_top_methods` | CPU hotspot aggregation with framework-aware categorization |
| `jfr_gc_stats` | GC collector config, pause distribution, heap occupancy, anomalies |
| `jfr_allocation` | Allocation rate (MB/s), top allocated classes, top allocation sites with category |
| `jfr_memory` | Total JVM memory footprint (heap, metaspace, code cache, threads, NMT) with container-fit verdict |
| `jfr_lock_contention` | Monitor contention + thread parking with heuristic category hints (pool-idle vs connection-pool vs lock-acquire) |
| `jfr_exceptions` | Per-class exception breakdown — throw rate, top classes, top throwing sites with category, control-flow-smell signal |

…then synthesizes a markdown report with findings, evidence, and recommendations. As more tools land (I/O), the same agent reaches for them.

---

## Sample report

[`samples/petclinic-report.md`](samples/petclinic-report.md) — end-to-end run against a 120-second JFR of [Spring PetClinic](https://github.com/spring-projects/spring-petclinic) 4.0.0-SNAPSHOT under HTTP load.
Raw tool outputs (handy as prompt-tuning fixtures): [`petclinic-summary.json`](samples/petclinic-summary.json), [`petclinic-top-methods.json`](samples/petclinic-top-methods.json), [`petclinic-gc-stats.json`](samples/petclinic-gc-stats.json), [`petclinic-allocation.json`](samples/petclinic-allocation.json), [`petclinic-memory.json`](samples/petclinic-memory.json), [`petclinic-lock-contention.json`](samples/petclinic-lock-contention.json), [`petclinic-exceptions.json`](samples/petclinic-exceptions.json).

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
```

> 💡 **Recommended JVM flags for richest analysis.** For full memory analysis (per-category native memory and the `container_fit` verdict), enable Native Memory Tracking when generating the JFR:
> ```
> -XX:NativeMemoryTracking=summary
> ```
> This adds ~1% memory overhead and is safe in production. NMT events fire roughly once per minute, so capture **at least 90–120 s** for reliable per-category data; shorter recordings may show NMT as unavailable even when the flag is set.

### Refresh the dogfood snapshot

Two independent steps. Step 1 records a fresh JFR in a container (no host JDK / Maven needed). Step 2 runs jfrdoc against the recording.

**Step 1 — record `samples/petclinic.jfr`** (needs `docker` + `docker compose`):

```bash
docker compose up --build --abort-on-container-exit --exit-code-from loadgen
docker compose down
```

`docker-compose.yml` runs Spring PetClinic under cgroup limits of 1 CPU / 2 GB and a 120 s JFR window, while an `alpine/curl` sidecar drives load against `/owners`, `/vets.html`, `/owners/{id}/edit`, etc. The recording is bind-mounted to `./samples/petclinic.jfr` so it appears on the host the moment the JVM finalizes it. Change the limits, JFR window, or load endpoints by editing `docker-compose.yml` directly — there are no env-var indirections.

**Step 2 — analyze with jfrdoc** (needs Java 25 + your Anthropic key):

```bash
./jfrdoc analyze samples/petclinic.jfr \
    --framework spring --container-cpu 1 --container-memory 2Gi \
    > samples/petclinic-report.md

./jfrdoc debug-tool jfr-summary     samples/petclinic.jfr > samples/petclinic-summary.json
./jfrdoc debug-tool jfr-top-methods samples/petclinic.jfr --framework spring --top-n 20 \
    > samples/petclinic-top-methods.json
./jfrdoc debug-tool jfr-gc-stats samples/petclinic.jfr > samples/petclinic-gc-stats.json
./jfrdoc debug-tool jfr-allocation samples/petclinic.jfr --framework spring --top-n 20 \
    > samples/petclinic-allocation.json
./jfrdoc debug-tool jfr-memory samples/petclinic.jfr --container-memory 2Gi \
    > samples/petclinic-memory.json
./jfrdoc debug-tool jfr-lock-contention samples/petclinic.jfr --top-n 10 \
  > samples/petclinic-lock-contention.json
./jfrdoc debug-tool jfr-exceptions samples/petclinic.jfr --framework spring --top-n 15 \
  > samples/petclinic-exceptions.json
```

**Why containers, not host `java -jar`?** The JVM honors cgroup limits (`-XX:UseContainerSupport` is default on JDK 25), so GC heuristics, ForkJoin pool size, and `Runtime.availableProcessors()` all reflect the constrained environment — which is exactly what jfrdoc's `--container-cpu` / `--container-memory` flags claim in the report header. As a bonus, the build is reproducible across macOS / Linux / Windows because both build and run happen inside `eclipse-temurin:25-jdk`, so host Maven version, BSD vs GNU coreutils quirks, and corporate TLS-MITM proxies stop mattering for the recording step.

---

## Roadmap

- ✅ **Done** — CLI scaffold, `jfr_summary` + `jfr_top_methods` + `jfr_gc_stats` + `jfr_allocation` + `jfr_memory` + `jfr_lock_contention` + `jfr_exceptions` tools, agent wiring, first dogfood on Spring PetClinic
- 🛠 **Next** — I/O wait tool, native-method sampling tool (separate from `jfr_top_methods` to keep on-CPU Java scope clean)
- 📦 **Next month** — K8s-aware diagnostics, multi-`.jfr` correlation
- ☁ **Future** — hosted SaaS with history and trends

---

## License

MIT — see [LICENSE](LICENSE).
