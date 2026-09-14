#!/usr/bin/env bash
# infrastructure common env：所有脚本先 source 这份
set -euo pipefail

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export PATH="$JAVA_HOME/bin:$PATH"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KAFKA="$ROOT/tools/kafka"
KAFKA_DATA="$ROOT/data/kafka/kraft"
REDIS_PORT=6379
KAFKA_PORT=9092

export K_JAVA="$JAVA_HOME"
mkdir -p "$ROOT/data" "$ROOT/logs"
