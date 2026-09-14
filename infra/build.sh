#!/usr/bin/env bash
# 编译：后端 jar + 前端 dist（二者都成功才叫 build 完成）
set -eu
cd "$(dirname "$0")/.."
source infra/env.sh

echo "== Maven package =="
(cd backend && mvn -q -T 4 -DskipTests package)
ls backend/*/target/*.jar >/dev/null

echo "== Frontend build =="
for ui in train-ui takeout-ui; do
  (cd "frontend/$ui" && [ -d node_modules ] || npm install --silent; npm run build --silent)
done
echo "build OK"
