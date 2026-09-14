#!/usr/bin/env bash
# 生成/更新 infra/nginx/nginx.conf 并（重新）加载本地 nginx —— 端口 9090
set -u
cd "$(dirname "$0")"
source ./env.sh

NG="$ROOT/infra/nginx"
RUN="$NG/run"
mkdir -p "$RUN/logs" "$RUN/tmp"

# 前端构建产物缺失则现编一次（首次需要网络下载 npm 依赖）
for ui in train-ui takeout-ui; do
  if [ ! -f "$ROOT/frontend/$ui/dist/index.html" ]; then
    echo "构建前端 $ui ..."
    (cd "$ROOT/frontend/$ui" && npm install --silent && npm run build --silent) \
      || echo "  ⚠ $ui 前端构建失败（先忽略，API 仍可用）"
  fi
done

TRAIN_DS="$ROOT/frontend/train-ui/dist"
TAKEOUT_DS="$ROOT/frontend/takeout-ui/dist"

cat > "$NG/nginx.conf" <<EOF
worker_processes 1;
pid "$RUN/nginx.pid";
error_log "$RUN/logs/error.log" warn;

events { worker_connections 128; }

http {
  include /etc/nginx/mime.types;   # 关键：让 .js 是 text/javascript、.css 是 text/css（否则 ES 模块会被浏览器拒执行 → 白屏）
  default_type application/octet-stream;
  charset utf-8;
  access_log "$RUN/logs/access.log";
  client_body_temp_path "$RUN/tmp/client_body";
  proxy_temp_path "$RUN/tmp/proxy";
  fastcgi_temp_path "$RUN/tmp/fastcgi";
  uwsgi_temp_path "$RUN/tmp/uwsgi";
  scgi_temp_path "$RUN/tmp/scgi";

  gzip on;
  gzip_types text/plain text/css application/javascript application/json;
  gzip_min_length 100;

  server {
    listen 9090;
    server_name _;

    # 静态站：/train-ui/、/takeout-ui/ 各挂一个 Vue dist
    location /train-ui/ {
      alias $TRAIN_DS/;
      index index.html;
      try_files \$uri /train-ui/index.html;
    }
    location /takeout-ui/ {
      alias $TAKEOUT_DS/;
      index index.html;
      try_files \$uri /takeout-ui/index.html;
    }

    # 反代：页面里 axios 的 baseURL=/apitrain 与 /apitakeout
    location /apitrain/ {
      proxy_pass http://127.0.0.1:8084/api/;
      proxy_set_header Host \$host;
    }
    location /apitakeout/ {
      proxy_pass http://127.0.0.1:8085/api/;
      proxy_set_header Host \$host;
    }

    # 控制台首页：预写一个小 index.html
    location = / {
      root $RUN;
      try_files /index.html =404;
    }
  }
}
EOF

cat > "$RUN/index.html" <<'HTML'
<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"></head>
<body style="font-family:sans-serif"><h2>javaweb 控制台</h2>
<p><a href=/train-ui/> &#128646; 火车购票小站</a></p>
<p><a href=/takeout-ui/> &#127976; 外卖小站</a></p>
<p>API: /apitrain/* → train:8084; /apitakeout/* → takeaway:8085</p>
</body></html>
HTML
mkdir -p "$TRAIN_DS" "$TAKEOUT_DS"
if nginx -t -c "$NG/nginx.conf" 2>/dev/null; then
  # 已在跑？则热重载；否则冷启
  if [ -f "$RUN/nginx.pid" ] && kill -0 "$(cat "$RUN/nginx.pid")" 2>/dev/null; then
    nginx -s reload -c "$NG/nginx.conf" >/dev/null 2>&1 && echo "nginx: 已热重载"
  else
  nginx -c "$NG/nginx.conf" >/dev/null 2>&1
  echo "nginx: up (9090)"
  fi
else
  echo "nginx: 配置错误 → $(nginx -t -c "$NG/nginx.conf" 2>&1)"
  exit 1
fi