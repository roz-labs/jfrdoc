# jfrdoc

**AI-powered JFR analyzer that tells you why your Spring Boot / Quarkus pod is OOMKilled — even when heap looks fine.**

> Status: 🚧 Pre-alpha. Day 5 — CPU-hotspot analysis end-to-end against real Spring Boot recordings. GC, allocation, lock-contention, and I/O tools still on the roadmap.

## Install

Prerequisites:

- **Java 25** or newer (uses implicit classes, text blocks, source-file mode)

`lib/zsmith.jar` — the zero-dependency agent framework jfrdoc is built on — is committed in this repo, so you don't need to build it separately. See [`lib/README.md`](lib/README.md) for the bundled version and rebuild instructions.

Make sure the launcher is executable:

```bash
chmod +x jfrdoc
```

No Maven, no Gradle, no npm. Java 25's source-file mode runs the script directly (JEP 458 picks up sibling `.java` files automatically).

## Usage

```bash
./jfrdoc analyze samples/sample.jfr \
    --framework quarkus \
    --container-memory 2Gi \
    --container-cpu 1
```

Produces a markdown report on stdout (zsmith progress chatter goes to stderr,
so `./jfrdoc analyze ... > report.md` gives you a clean file).

Requires `anthropic.api.key` configured in `~/.zsmith/app.properties`:

```
anthropic.api.key=sk-ant-...
anthropic.version=2023-06-01
```

Each analysis costs roughly $0.50–$2.00 in API charges with zsmith's
default model (currently `claude-opus-4-7`).

## Development

Generate a sample `.jfr` recording for local testing:

```bash
./samples/gen-sample.sh
```

Each individual tool can be exercised directly via the `debug-tool`
subcommand — useful for verifying the JSON it returns to the agent
loop without going through the full analysis pipeline:

```bash
./jfrdoc debug-tool jfr-summary samples/test.jfr
./jfrdoc debug-tool jfr-top-methods samples/test.jfr --top-n 10
./jfrdoc debug-tool jfr-top-methods samples/test.jfr --top-n 5 --framework quarkus
```

## What it diagnoses today

- **CPU hotspots** with category breakdown (user code vs framework vs JDK vs native), top callers, and Spring/Quarkus-aware framework attribution
- **Recording context** (duration, JVM info, event distribution) so you know what the recording actually contains before drawing conclusions

The vision is broader (OOMKill root cause, GC pressure, allocation hotspots, CPU throttling, virtual-thread sizing) — see [Roadmap](#roadmap). Today's tooling is CPU-only and the agent's report is explicit about that limitation.

## Example output

[`samples/petclinic-report.md`](samples/petclinic-report.md) is a real end-to-end run against a 60s JFR recording of Spring PetClinic. Supporting raw tool outputs are in [`samples/petclinic-summary.json`](samples/petclinic-summary.json) and [`samples/petclinic-top-methods.json`](samples/petclinic-top-methods.json) — useful both as a demo and as a fixture when iterating on the agent's prompt.

## How it works

jfrdoc reads `.jfr` recordings via `jdk.jfr.consumer.RecordingFile` from the JDK. A single Claude-driven agent built on [zsmith](https://github.com/AdamBien/zsmith) calls two tools — `jfr_summary` (metadata + event distribution) and `jfr_top_methods` (CPU hotspot aggregation with framework-aware categorization) — and synthesizes a markdown report with findings, supporting evidence, and concrete recommendations. As more tools are added (GC, allocation, locks, I/O), the same agent will reach for them.

## Roadmap

- **Done:** CLI scaffold, `jfr_summary` + `jfr_top_methods` tools, agent wiring, first dogfood run on Spring PetClinic
- **Next:** GC / allocation / lock-contention / I/O tools, framework-aware allocation attribution
- **Next month:** K8s-aware diagnostics, multiple `.jfr` support
- **Future:** hosted SaaS with history and trends

## License

MIT — see [LICENSE](LICENSE).
