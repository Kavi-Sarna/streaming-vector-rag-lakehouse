# Flink Kubernetes Operator

The dedup job still runs on the local Compose cluster. This installs the operator on EKS so a later change can run that job there.

The chart is `infra/helm/flink-operator`. It bundles Flink Kubernetes Operator `1.16.1`. cert-manager `v1.21.3` is a pre-install and pre-upgrade hook in the same chart. The namespace hook weight is `-20`. The other cert-manager resources use weight `-10`. Helm finishes each weight before it installs the operator, so the webhook certificate is created only after cert-manager is ready. The cert-manager CRDs live in the chart `crds/` directory, which Helm installs before those hooks.

Bring the demo stack up and approve the deploy release, or install from the laptop after `make kubeconfig`. Helm 4.3.1 is required.

```sh
make flink-operator
kubectl get pods -n flink-operator
kubectl get pods -n cert-manager
```

The operator pod has two containers, the operator and its webhook. The install does not submit `dedup-and-chunk`. `make flink-job` still submits that job to the Compose JobManager.
