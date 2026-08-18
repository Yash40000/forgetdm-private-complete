# ForgeTDM on OpenShift / Kubernetes (RFP §3.1.2)

Deploys the masking + delivery engine as OpenShift container pods that **scale dynamically** to
handle peak volumes, on bare-metal Hyper-Converged Infrastructure (HCI). The app is 12-factor
(all config via env), exposes Spring Boot health probes on a separate management port, shuts down
gracefully, and emits Prometheus metrics — so it drops into a cluster cleanly.

## Contents

| File | Purpose |
|---|---|
| `Dockerfile` | Multi-stage build → slim JRE 21 image, non-root, arbitrary-UID (OpenShift) friendly |
| `configmap.yaml` | Non-secret config (DB URL, ports, staging encryption, Vault, CDC) |
| `secret.example.yaml` | Template for DB creds, Vault token, fallback masking secret |
| `pvc.yaml` | Shared (RWX) staging pool + file-vault volume |
| `deployment.yaml` | Pods with startup/liveness/readiness probes, security context, volumes |
| `service.yaml` | ClusterIP for app (8088) + management (8090) |
| `route.yaml` | OpenShift Route, TLS edge, HTTPS redirect |
| `hpa.yaml` | HorizontalPodAutoscaler — CPU/memory driven autoscaling (2→10) |
| `kustomization.yaml` | `oc apply -k` bundle |

## Build & push the image

```bash
# From the repo root:
oc new-project forgetdm
oc create imagestream forgetdm
# Build in-cluster (BuildConfig) or locally then push to the internal registry:
podman build -t forgetdm:1.0.0 -f deploy/openshift/Dockerfile .
podman tag forgetdm:1.0.0 default-route-openshift-image-registry.apps.<cluster>/forgetdm/forgetdm:1.0.0
podman push default-route-openshift-image-registry.apps.<cluster>/forgetdm/forgetdm:1.0.0
```

## Deploy

```bash
# 1) Secrets (never commit real values):
oc create secret generic forgetdm-secrets \
  --from-literal=FORGETDM_DB_USER=forgetdm \
  --from-literal=FORGETDM_DB_PASS='***' \
  --from-literal=FORGETDM_VAULT_TOKEN='***'

# 2) Everything else:
oc apply -k deploy/openshift/

# 3) Enable metrics-driven autoscaling (needs metrics-server / OpenShift monitoring):
oc get hpa forgetdm -w
```

Validate manifests without a cluster: `oc apply -k deploy/openshift/ --dry-run=server` (or
`kubectl kustomize deploy/openshift/ | kubeconform -`).

## How the RFP requirements map

- **Native OpenShift pods.** Standard Deployment + Route; runs as an arbitrary UID in group 0
  (no `runAsUser` pinned, data dirs group-writable) so it complies with the default `restricted-v2`
  SCC — no privileged access required.
- **Dynamic scaling to peak volume.** The `HorizontalPodAutoscaler` spins up parallel processing
  pods on CPU/memory pressure (2→10) and scales back when idle. Provisioning/masking work is pulled
  per-pod, so more pods = more parallel throughput.
- **Bare-metal HCI.** The staging pool + file vault sit on a `ReadWriteMany` PVC (CephFS/NFS/ODF on
  the HCI), so every replica shares one content-addressed pool and sees the same snapshots. Set
  `storageClassName` in `pvc.yaml` to your HCI shared-filesystem class.
- **Zero-trust staging.** `FORGETDM_STAGING_ENCRYPT=true` encrypts pool payloads at rest with the
  Vault-held key (RFP §3.1.2) — even the shared PVC holds only ciphertext.
- **Key custody.** `FORGETDM_VAULT_*` sources the masking key from HashiCorp Vault (RFP §3.2.3);
  `FORGETDM_VAULT_FAIL_CLOSED=true` refuses to start if Vault is unreachable.
- **Continuous CDC.** `FORGETDM_CDC_CONTINUOUS_ENABLED=true` keeps capture slots current across the
  fleet.

## Operational notes

- **Health:** liveness/readiness are served on the management port (8090) at
  `/actuator/health/liveness` and `/actuator/health/readiness`; a generous `startupProbe` covers the
  ~30–45s Flyway/JPA boot.
- **DB migrations:** Flyway takes a lock, so multiple replicas starting together migrate safely
  (one runs, the rest wait).
- **Shared pool concurrency:** the pool is content-addressed with atomic writes, so concurrent pods
  are safe; for a single-replica install `ReadWriteOnce` is fine and you can drop the HPA `minReplicas`
  to 1.
- **Not included (deliberately):** an in-cluster Postgres and Vault — point `FORGETDM_DB_URL` and
  `FORGETDM_VAULT_ADDRESS` at your managed instances (or add their own manifests/operators).
