#!/usr/bin/env bash
# Kubernetes dogfood for jfr_container — records a JFR from Spring PetClinic
# running as a Pod under a tight CPU limit (so the kubelet throttles it), pulls
# the recording to the host, and runs jfrdoc's jfr_container tool against it.
#
# Requirements on the host: docker + kind + kubectl + Java 25 (for ./jfrdoc).
# A real cluster is NOT needed — kind runs Kubernetes inside Docker and the
# kubelet sets the same cgroup limits a managed cluster would.
#
# Usage:  samples/petclinic/k8s/run.sh
set -euo pipefail

CLUSTER=jfrdoc
IMAGE=jfrdoc-petclinic:local
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
OUT="$HERE"                       # writes petclinic.jfr + container.json here
JFR="$OUT/petclinic.jfr"

cleanup() { kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "==> Building PetClinic image (eclipse-temurin:25-jdk)…"
docker build -t "$IMAGE" "$ROOT/docker/petclinic"

echo "==> Creating kind cluster '$CLUSTER'…"
kind create cluster --name "$CLUSTER"

echo "==> Loading image into the cluster…"
kind load docker-image "$IMAGE" --name "$CLUSTER"

echo "==> Applying Pod…"
kubectl apply -f "$HERE/petclinic-pod.yaml"
kubectl wait --for=condition=Ready pod/petclinic-jfr --timeout=300s

# JFR = 15s delay + 120s duration; give it margin to finalize on disk.
echo "==> Recording (≈150s)…"
sleep 150

echo "==> Copying recording to host: $JFR"
kubectl cp petclinic-jfr:/jfr/petclinic.jfr "$JFR" -c petclinic

echo "==> Analyzing with jfr_container (declared limits: cpu=500m, mem=2Gi)…"
( cd "$ROOT" && ./jfrdoc debug-tool jfr-container "$JFR" \
    --container-cpu 500m --container-memory 2Gi ) > "$OUT/container.json"

echo "==> Done. Wrote $OUT/container.json"
echo "    (the .jfr is git-ignored; container.json is the committed artifact)"
