#!/usr/bin/env bash
# Allows this machine's public IPv4 address to open the Grafana load balancer.
# The rule is a /32. The security group does not allow 0.0.0.0/0.
# Run again to move the rule to the current address. --close removes it.
set -euo pipefail

region="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"
name="${GRAFANA_LB_NAME:-rag-demo-grafana}"
port=80

close=0
if [[ "${1:-}" == "--close" ]]; then
  close=1
elif [[ -n "${1:-}" ]]; then
  echo "Usage: open-grafana.sh [--close]" >&2
  exit 1
fi

sg_id="$(aws ec2 describe-security-groups \
  --region "$region" \
  --filters "Name=group-name,Values=${name}" \
  --query 'SecurityGroups[0].GroupId' \
  --output text)"
if [[ -z "$sg_id" || "$sg_id" == "None" ]]; then
  echo "Security group ${name} was not found in ${region}. Approve Apply on the demo stack first." >&2
  exit 1
fi

rule_ids="$(aws ec2 describe-security-group-rules \
  --region "$region" \
  --filters "Name=group-id,Values=${sg_id}" \
  --query 'SecurityGroupRules[?IsEgress==`false`].SecurityGroupRuleId' \
  --output text)"
if [[ -n "$rule_ids" && "$rule_ids" != "None" ]]; then
  # AWS CLI prints ids separated by tabs.
  read -r -a ids <<< "${rule_ids//$'\t'/ }"
  aws ec2 revoke-security-group-ingress \
    --region "$region" \
    --group-id "$sg_id" \
    --security-group-rule-ids "${ids[@]}"
fi

if [[ "$close" -eq 1 ]]; then
  echo "Closed Grafana. The load balancer has no client rule."
  exit 0
fi

ip="$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')"
cidr="$(python3 - "$ip" <<'PY'
import ipaddress
import sys

raw = sys.argv[1]
try:
    addr = ipaddress.IPv4Address(raw)
except ipaddress.AddressValueError:
    raise SystemExit(f"checkip returned {raw!r}, which is not an IPv4 address.")
if (
    addr.is_unspecified
    or addr.is_private
    or addr.is_loopback
    or addr.is_link_local
    or addr.is_multicast
    or addr.is_reserved
):
    raise SystemExit(
        f"Refusing {addr}. The load balancer needs the public address of this machine."
    )
print(f"{addr}/32")
PY
)"

aws ec2 authorize-security-group-ingress \
  --region "$region" \
  --group-id "$sg_id" \
  --ip-permissions "$(python3 - "$cidr" "$port" <<'PY'
import json
import sys

print(json.dumps([{
    "IpProtocol": "tcp",
    "FromPort": int(sys.argv[2]),
    "ToPort": int(sys.argv[2]),
    "IpRanges": [{"CidrIp": sys.argv[1], "Description": "grafana-operator"}],
}]))
PY
)"

dns="$(aws elbv2 describe-load-balancers \
  --region "$region" \
  --names "$name" \
  --query 'LoadBalancers[0].DNSName' \
  --output text)"
if [[ -z "$dns" || "$dns" == "None" ]]; then
  echo "Allowed ${cidr}. Load balancer ${name} was not found." >&2
  exit 1
fi

echo "Grafana allows ${cidr}."
echo "http://${dns}"
