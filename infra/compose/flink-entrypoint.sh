#!/usr/bin/env bash
# Iceberg records writer metrics through Flink's Dropwizard wrapper. That jar
# ships outside /opt/flink/lib, and Flink loads org.apache.flink classes from lib.
set -euo pipefail

lib=/opt/flink/lib
dropwizard="$lib/flink-metrics-dropwizard-1.20.5.jar"
metrics="$lib/metrics-core-3.2.6.jar"
if [[ ! -f "$dropwizard" ]]; then
  curl -fsSL -o "$dropwizard" \
    https://repo1.maven.org/maven2/org/apache/flink/flink-metrics-dropwizard/1.20.5/flink-metrics-dropwizard-1.20.5.jar
fi
if [[ ! -f "$metrics" ]]; then
  curl -fsSL -o "$metrics" \
    https://repo1.maven.org/maven2/io/dropwizard/metrics/metrics-core/3.2.6/metrics-core-3.2.6.jar
fi

exec /docker-entrypoint.sh "$@"
