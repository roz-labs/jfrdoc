#!/usr/bin/env bash
# Generates samples/sample.jfr by running a small allocation/CPU workload
# under -XX:StartFlightRecording. Requires Java 25+ on PATH.
set -euo pipefail

cd "$(dirname "$0")"

WORKDIR=$(mktemp -d)
WORKLOAD="$WORKDIR/JfrLoad.java"
trap 'rm -rf "$WORKDIR"' EXIT

cat > "$WORKLOAD" <<'EOF'
void main() throws Exception {
    System.out.println("Generating load for JFR recording...");
    var deadline = System.currentTimeMillis() + 6_000;
    var garbage = new java.util.ArrayList<byte[]>();
    long churn = 0;
    while (System.currentTimeMillis() < deadline) {
        garbage.add(new byte[64 * 1024]);
        churn++;
        if (garbage.size() > 256) garbage.clear();
    }
    System.out.println("Load complete (" + churn + " allocations).");
}
EOF

java -XX:StartFlightRecording=duration=8s,filename=sample.jfr,settings=profile \
     --source 25 "$WORKLOAD"

echo "Wrote $(pwd)/sample.jfr"
