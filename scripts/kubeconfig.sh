#!/usr/bin/env bash
# Grants the current IAM principal cluster admin and writes ~/.kube/config.
# The GitHub role that created the cluster is already an admin. This is the
# laptop user. Authentication mode is API, so a kubeconfig file alone is not enough.
set -euo pipefail

region="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"
name="${EKS_CLUSTER_NAME:-rag-demo}"
policy="arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"

if ! aws eks describe-cluster --name "$name" --region "$region" >/dev/null 2>&1; then
  echo "Cluster ${name} was not found in ${region}. Approve Apply on the demo stack first." >&2
  exit 1
fi

caller="$(aws sts get-caller-identity --query Arn --output text)"
principal="$(python3 - "$caller" <<'PY'
import sys

arn = sys.argv[1]
if arn.startswith("arn:aws:iam::") and (":user/" in arn or ":role/" in arn):
    print(arn)
    raise SystemExit(0)
marker = ":assumed-role/"
if arn.startswith("arn:aws:sts::") and marker in arn:
    account = arn.split(":")[4]
    role = arn.split(marker, 1)[1].split("/", 1)[0]
    print(f"arn:aws:iam::{account}:role/{role}")
    raise SystemExit(0)
raise SystemExit(f"Cannot grant kubectl to {arn}. Use an IAM user or role.")
PY
)"

if ! aws eks describe-access-entry \
  --cluster-name "$name" \
  --principal-arn "$principal" \
  --region "$region" >/dev/null 2>&1; then
  aws eks create-access-entry \
    --cluster-name "$name" \
    --principal-arn "$principal" \
    --region "$region" >/dev/null
fi

associated="$(aws eks list-associated-access-policies \
  --cluster-name "$name" \
  --principal-arn "$principal" \
  --region "$region" \
  --query "associatedAccessPolicies[?policyArn=='${policy}'] | length(@)" \
  --output text)"
if [[ "$associated" == "0" || "$associated" == "None" ]]; then
  aws eks associate-access-policy \
    --cluster-name "$name" \
    --principal-arn "$principal" \
    --policy-arn "$policy" \
    --access-scope type=cluster \
    --region "$region" >/dev/null
fi

aws eks update-kubeconfig --name "$name" --region "$region" >/dev/null

if ! command -v kubectl >/dev/null 2>&1; then
  echo "kubectl is not on PATH. The kubeconfig is written. Install kubectl and run this again." >&2
  exit 1
fi

ready=0
for _ in 1 2 3 4 5 6; do
  if kubectl get ns >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 5
done
if [[ "$ready" -ne 1 ]]; then
  echo "Kubeconfig is written for ${principal}, and kubectl is still unauthorized." >&2
  exit 1
fi

echo "kubectl is signed in to ${name} as ${principal}."
kubectl get ns
if kubectl get namespace rag >/dev/null 2>&1; then
  kubectl -n rag get pods,svc,job
fi
