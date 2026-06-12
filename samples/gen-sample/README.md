Synthetic micro-recording — tiny 8-second allocation/CPU workload, handy for fast local iteration.

## Usage

```bash
./samples/gen-sample/gen-sample.sh   # writes samples/gen-sample/sample.jfr
```

Requires Java 25+ on PATH.

The script `cd`s to its own directory, so `sample.jfr` lands next to it.
JFR files are gitignored; only the script is committed.
