#!/usr/bin/env bash
# 一键全停：nginx → 后端 → kafka → redis
set -u
cd "$(dirname "$0")"
source ./env.sh

echo "== 后端 =="
for name in demo-todo demo-chat demo-counter train takeaway; do
  if [ -f "$ROOT/logs/$name.pid" ]; then
    pid=$(cat "$ROOT/logs/$name.pid")
    kill "$pid" 2>/dev/null && echo "  $name 已停" || true
    rm -f "$ROOT/logs/$name.pid"
  fi
done

echo "== nginx =="
[ -f "$ROOT/infra/nginx/run/nginx.pid" ] && \
  nginx -s quit -c "$ROOT/infra/nginx/nginx.conf" 2>/dev/null && echo "nginx 已停" || true

echo "== kafka =="
"$KAFKA/bin/kafka-server-stop.sh" >/dev/null 2>&1
sleep 2
echo "== redis =="
redis-cli -p $REDIS_PORT shutdown nosave 2>/dev/null | true
echo "全部停止完成"
