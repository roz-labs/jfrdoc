#!/usr/bin/env bash
# Generates samples/petclinic.jfr by cloning Spring PetClinic, building it,
# running it under a JFR profile recording, and driving HTTP load against it.
# Used to refresh the dogfood artifacts (samples/petclinic-report.md,
# samples/petclinic-summary.json, samples/petclinic-top-methods.json).
#
# Requires Java 25+ on PATH. Maven and curl will be invoked via the project's
# ./mvnw wrapper. If a system JDK <25 is needed to satisfy the Spring Boot
# Maven plugin's TLS truststore, set BUILD_JAVA_HOME to that JDK; the run
# itself always uses the JDK on PATH (which must be 25+).
set -euo pipefail

cd "$(dirname "$0")"
SAMPLES_DIR="$(pwd)"
WORKDIR="${PETCLINIC_WORKDIR:-$(mktemp -d)}"
KEEP_WORKDIR="${KEEP_WORKDIR:-0}"
trap '[ "$KEEP_WORKDIR" = "1" ] || rm -rf "$WORKDIR"' EXIT

JFR_DELAY="${JFR_DELAY:-15s}"
JFR_DURATION="${JFR_DURATION:-60s}"
LOAD_SECONDS="${LOAD_SECONDS:-80}"
PORT="${PETCLINIC_PORT:-8080}"

echo "[gen-petclinic] workdir=$WORKDIR"
echo "[gen-petclinic] java: $(java -version 2>&1 | head -1)"

if [ ! -d "$WORKDIR/spring-petclinic" ]; then
    git clone --depth 1 https://github.com/spring-projects/spring-petclinic.git \
        "$WORKDIR/spring-petclinic"
fi

cd "$WORKDIR/spring-petclinic"
if [ ! -f target/spring-petclinic-*.jar ]; then
    if [ -n "${BUILD_JAVA_HOME:-}" ]; then
        JAVA_HOME="$BUILD_JAVA_HOME" ./mvnw -q -DskipTests package
    else
        ./mvnw -q -DskipTests package
    fi
fi
APP_JAR=$(ls target/spring-petclinic-*.jar | head -1)

JFR_OUT="$WORKDIR/petclinic.jfr"
APP_LOG="$WORKDIR/petclinic.log"
echo "[gen-petclinic] starting petclinic: jfr delay=$JFR_DELAY duration=$JFR_DURATION"
java -XX:StartFlightRecording=delay=$JFR_DELAY,duration=$JFR_DURATION,filename=$JFR_OUT,settings=profile \
     -jar "$APP_JAR" > "$APP_LOG" 2>&1 &
APP_PID=$!
trap '[ "$KEEP_WORKDIR" = "1" ] || rm -rf "$WORKDIR"; kill '"$APP_PID"' 2>/dev/null || true' EXIT

# Wait for HTTP readiness
echo -n "[gen-petclinic] waiting for http://localhost:$PORT "
for _ in $(seq 1 60); do
    if curl -sf "http://localhost:$PORT/" >/dev/null 2>&1; then echo "up"; break; fi
    echo -n .; sleep 2
done

# Drive load throughout the JFR window (delay + duration + a little slack)
endpoints=(/ /owners "/owners?lastName=" "/owners?lastName=Davis" /owners/1 /owners/1/edit /owners/2/pets/new /vets.html /oups /owners/find)
deadline=$(( $(date +%s) + LOAD_SECONDS ))
echo "[gen-petclinic] driving load for ${LOAD_SECONDS}s"
i=0
while [ "$(date +%s)" -lt "$deadline" ]; do
    for ep in "${endpoints[@]}"; do
        curl -s -o /dev/null "http://localhost:$PORT$ep" &
        i=$((i + 1))
        if [ $((i % 64)) -eq 0 ]; then wait; fi
    done
done
wait
echo "[gen-petclinic] issued $i requests"

# Wait for the JFR window to close before shutting down
echo "[gen-petclinic] waiting for JFR file to settle"
for _ in $(seq 1 60); do
    [ -f "$JFR_OUT" ] || { sleep 2; continue; }
    a=$(stat -c%s "$JFR_OUT"); sleep 5; b=$(stat -c%s "$JFR_OUT")
    if [ "$a" = "$b" ] && [ "$a" -gt 1000000 ]; then break; fi
done

kill "$APP_PID" 2>/dev/null || true
wait "$APP_PID" 2>/dev/null || true

cp "$JFR_OUT" "$SAMPLES_DIR/petclinic.jfr"
echo "[gen-petclinic] wrote $SAMPLES_DIR/petclinic.jfr ($(stat -c%s "$SAMPLES_DIR/petclinic.jfr") bytes)"

# Now run the full analyze pipeline and refresh sample artifacts. Redirect
# only stdout so zsmith's stderr chatter does not contaminate the report.
cd "$SAMPLES_DIR/.."
echo "[gen-petclinic] running ./jfrdoc analyze"
./jfrdoc analyze "$SAMPLES_DIR/petclinic.jfr" \
    --framework spring \
    --container-memory 2Gi \
    --container-cpu 1 \
    > "$SAMPLES_DIR/petclinic-report.md"

./jfrdoc debug-tool jfr-summary "$SAMPLES_DIR/petclinic.jfr" \
    > "$SAMPLES_DIR/petclinic-summary.json"

./jfrdoc debug-tool jfr-top-methods "$SAMPLES_DIR/petclinic.jfr" \
    --framework spring --top-n 20 \
    > "$SAMPLES_DIR/petclinic-top-methods.json"

echo "[gen-petclinic] refreshed:"
echo "  $SAMPLES_DIR/petclinic-report.md"
echo "  $SAMPLES_DIR/petclinic-summary.json"
echo "  $SAMPLES_DIR/petclinic-top-methods.json"
