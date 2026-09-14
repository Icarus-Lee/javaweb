#!/usr/bin/env bash
# 一键起全栈：Redis → Kafka → 5 个后端 → Nginx（静态站由 frontend/*/dist 提供）
set -u
cd "$(dirname "$0")"
source ./env.sh
JARSKeeping=""
PIDS=""

echo "== 1) Redis =="

redis-cli -p $REDIS_PORT PING 2>/dev/null || (
  nohup redis-server --port $REDIS_PORT --daemonize no --save '' --appendonly no \
    --logfile "$ROOT/logs/redis.log" >/dev/null 2>&1 &
  echo $! > "$ROOT/logs/redis.pid"
  for i in $(seq 1 20); do redis-cli -p $REDIS_PORT PING >/dev/null 2>&1 && break; sleep 0.25; done
)
echo "redis: $(redis-cli -p $REDIS_PORT PING 2>/dev/null || echo down)"

echo "== 2) Kafka (KRaft 单节点) =="
if ! (echo > /dev/tcp/127.0.0.1/$KAFKA_PORT) 2>/dev/null; then
  # 把数据目录指到项目 data/（可整体删除即重置）
  sed -i "s#^log\.dirs=.*#log.dirs=$KAFKA_DATA#" "$KAFKA/config/server.properties"
  if [ ! -f "$KAFKA_DATA/cluster.meta.properties" ]; then
    rm -rf "$KAFKA_DATA"
    UUID=$(python3 -c 'import uuid;print(uuid.uuid4())')
    "$KAFKA/bin/kafka-storage.sh" format --standalone \
      --cluster-id "$UUID" \
      --config "$KAFKA/config/server.properties" >/dev/null 2>&1
  fi
  nohup "$KAFKA/bin/kafka-server-start.sh" "$KAFKA/config/server.properties" \
    > "$ROOT/logs/kafka.log" 2>&1 &
  echo $! > "$ROOT/logs/kafka.pid"
  for i in $(seq 1 40); do (echo > /dev/tcp/127.0.0.1/9092) 2>/dev/null && break; sleep 1; done
fi
(echo > /dev/tcp/127.0.0.1/9092) 2>/dev/null && echo "kafka: up" || echo "kafka: DOWN"

echo "== 3) 后端 ×5 =="
start_jar() { # name port jarfile
  local name=$1 port=$2 jar=$3
  (echo > /dev/tcp/127.0.0.1/$port) 2>/dev/null && { echo "  $name 已在线（$port）"; return; }
  if [ ! -f "$jar" ]; then echo "  ✗ 缺 jar：$jar（先跑 infra/build.sh）"; return; fi
  nohup "$JAVA_HOME/bin/java" -jar "$jar" > "$ROOT/logs/$name.log" 2>&1 &
  echo $! > "$ROOT/logs/$name.pid"
  for i in $(seq 1 40); do (echo > /dev/tcp/127.0.0.1/$port) 2>/dev/null && break; sleep 0.5; done
  (echo > /dev/tcp/127.0.0.1/$port) 2>/dev/null && echo "  $name: up ($port)" || echo "  ✗ $name 启动失败，看 logs/$name.log"
}
(cd "$ROOT" &&
  source infra/env.sh &&
  start_jar demo-todo    8081 "$ROOT/backend/demo-todo/target/demo-todo-1.0.0.jar"   &&
  start_jar demo-chat    8082 "$ROOT/backend/demo-chat/target/demo-chat-1.0.0.jar"
)
(cd "$ROOT" && source infra/env.sh
  start_jar demo-counter 8083 "$ROOT/backend/demo-counter/target/demo-counter-1.0.0.jar")
(cd "$ROOT" && source infra/env.sh
  start_jar train    8084 "$ROOT/backend/train/target/train-1.0.0.jar")
(cd "$ROOT" && source infra/env.sh
  start_jar takeaway 8085 "$ROOT/backend/takeaway/target/takeaway-1.0.0.jar")

echo "== 4) Nginx（静态站 + 反代）=="
"$ROOT/infra/nginx-reload.sh"

echo
echo "全部就绪（或见上报错）。入口："
echo "  - 开发前端(可选)：cd frontend/train-ui && npm run dev"
echo "  - Nginx 汇总站：http://127.0.0.1:9090 （/train.html 或 / 控制台索引）"
