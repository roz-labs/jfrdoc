# Kubernetes dogfood — `jfr_container`

This is the K8s-equivalent of the repo's `docker-compose.yml` flow, used to
exercise the `jfr_container` tool against a real cgroup the **kubelet** sets up.

## Why this works without a real cluster

From the JVM's point of view, "running on Kubernetes" means running inside a
cgroup with CPU/memory limits. The kubelet translates a Pod's
`resources.requests`/`resources.limits` into the exact cgroup knobs that
`docker run --cpus/--memory` sets — and JFR's `jdk.Container*` events read
straight from that cgroup. So a local [**kind**](https://kind.sigs.k8s.io/)
cluster (Kubernetes-in-Docker) reproduces the full
`kubelet → cgroup → JFR` path. No managed cluster required.

## What the scenario demonstrates

[`petclinic-pod.yaml`](petclinic-pod.yaml) runs Spring PetClinic with a
**deliberately tight `limits.cpu: "500m"`** while a sidecar drives HTTP load.
The CFS quota throttles the app under load, so
`jdk.ContainerCPUThrottling` reports non-zero throttled slices — the headline
signal `jfr_container` is built to surface (CPU throttling is the #1 cause of
silent request tail-latency on Kubernetes, invisible to average-CPU dashboards).

## Run it

Requirements: `docker`, [`kind`](https://kind.sigs.k8s.io/), `kubectl`, and
Java 25 (for `./jfrdoc`).

```bash
samples/petclinic/k8s/run.sh
```

The script builds the PetClinic image, creates a kind cluster, loads the image,
applies the Pod, waits out the 15s-delay + 120s JFR window, copies
`petclinic.jfr` to the host with `kubectl cp`, runs
`./jfrdoc debug-tool jfr-container … --container-cpu 500m --container-memory 2Gi`,
and tears the cluster down.

The `.jfr` is git-ignored (like every recording in this repo); the committed
artifact is `container.json` — the tool output showing the throttling verdict,
CPU utilization vs limit, memory headroom, and the declared-vs-observed limit
cross-check.
