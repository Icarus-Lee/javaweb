#!/usr/bin/env bash
# 冒烟测试入口（断言实现在 smoke.py）
cd "$(dirname "$0")"
python3 smoke.py
