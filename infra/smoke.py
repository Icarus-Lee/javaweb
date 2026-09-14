#!/usr/bin/env python3
# 冒烟测试（infra/smoke.sh 的实现体）：断言全链路 API 与页面可达性。
import json
import urllib.request
import urllib.error
import sys
import time

BASE = {
    "todo":    "http://127.0.0.1:8081/api",
    "chat":    "http://127.0.0.1:8082/api",
    "counter": "http://127.0.0.1:8083/api",
    "train":   "http://127.0.0.1:8084/api",
    "takeout": "http://127.0.0.1:8085/api",
}
NG = "http://127.0.0.1:9090"

passed, failed = [0], [0]
def ok(desc):
    passed[0] += 1
    print("  ✓ " + desc)
def bad(desc):
    failed[0] += 1
    print("  ✗ " + desc)

def req(name, method, path, body=None, token=None, timeout=6):
    """发请求 → (status, json_or_text)。失败返回 (0, 'error')。"""
    url = BASE[name] + path
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            try:
                return resp.status, (json.loads(raw) if raw.strip() else None)
            except json.JSONDecodeError:
                return resp.status, raw
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8", "replace"))
        except Exception:
            return e.code, None
    except Exception as e:
        return -1, str(e)

def expect(desc, name, method, path, want_code, body=None, token=None, want_body=True):
    code, data = req(name, method, path, body, token)
    if code == want_code:
        ok(desc)
        return data
    bad(f"{desc} (code={code}, want={want_code}) {data}")
    return None

print("== 基础设施 ==")
try:
    import socket
    s = socket.create_connection(("127.0.0.1", 9092), 2); s.close()
    ok("kafka 9092 在线")
except OSError:
    bad("kafka 9092 不可达")

print("== demo-todo (8081) ==")
t = expect("todo 新建", "todo", "POST", "/tasks", 200, {"title": "冒烟任务"})
if t:
    expect("todo 完成翻转", "todo", "PUT", f"/tasks/{t['id']}", 200)
    expect("todo 删除", "todo", "DELETE", f"/tasks/{t['id']}", 204)

print("== demo-chat (8082) ==")
m = expect("chat 发消息", "chat", "POST", "/chat/send", 200, {"user": "smoke", "text": "hi"})
if not (m and m.get("user") == "smoke"):
    bad("chat 回读内容")

print("== redis 计数器 (8083) ==")
c = expect("counter 自增", "counter", "GET", "/counter", 200)
if not (c and isinstance(c.get("n"), int)):
    bad("counter 返回 n")

print("== train (8084) ==")
trips = expect("车次列表", "train", "GET", "/trips", 200)
if not (trips and all("stock" in x for x in trips)):
    bad("车次缺余票字段")

uid = "smoke" + str(int(time.time()))
expect("注册", "train", "POST", "/auth/register", 200,
       {"username": uid, "password": "pass123"})
lg = expect("登录+JWT", "train", "POST", "/auth/login", 200,
            {"username": uid, "password": "pass123"})
tok = (lg or {}).get("token", "")
trip_id = trips[0]["id"] if trips else 1
b = expect("下单(UNPAID)", "train", "POST", "/bookings", 200,
           {"tripId": trip_id}, token=tok)
order_no = (b or {}).get("orderNo", "")
time.sleep(1.5)      # 给审计台一点时间
aud = expect("审计(Kafka 消费记录)", "train", "GET", "/bookings/audits", 200,
             None, token=tok)
if aud:
    (ok if len(aud) >= 1 else bad)("Kafka audit 台有记录(%d 条)" % len(aud))
expect("支付", "train", "POST", "/bookings/pay", 200, {"orderNo": order_no}, token=tok)
expect("取消结算余票", "train", "POST", "/bookings/cancel", 200,
       {"orderNo": order_no}, token=tok)

print("== takeaway (8085) ==")
lg2 = expect("外卖登录(alice)", "takeout", "POST", "/auth/login", 200,
             {"username": "alice", "password": "123456"})
tok2 = (lg2 or {}).get("token", "")
menu = req("takeout", "GET", "/menus", None, token=tok2)[1] or []
first = [d for d in menu if d.get("shopId") == 1]
if not first and menu:
    first = [menu[0]]
o = expect("点单创建", "takeout", "POST", "/orders", 200,
           {"shopId": first[0]["shopId"], "dishId": first[0]["id"], "quantity": 1},
           token=tok2) if first else None
onum = (o or {}).get("orderNo", "")
expect("支付", "takeout", "POST", f"/orders/{onum}/pay", 200, token=tok2)
status = ""
for _ in range(8):            # Kafka 异步派单，最多等 8 秒
    time.sleep(1)
    info = req("takeout", "GET", f"/orders/{onum}", None, token=tok2)[1]
    status = (info or {}).get("status", "")
    if status == "DISPATCHED":
        break
(ok if status == "DISPATCHED" else bad)(f"Kafka 异步派单（状态={status}）")

print("== nginx (9090) ==")
def code_of_local(path):
    return code_of(NG + path)

def code_of(url):
    try:
        with urllib.request.urlopen(url, timeout=5) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:
        return -1

(ok if code_of_local("/train-ui/") == 200 else bad)("train 静态站 200")
(ok if code_of_local("/takeout-ui/") == 200 else bad)("takeout 静态站 200")
(ok if code_of_local("/apitrain/trips") == 200 else bad)("反代 /apitrain → 8084")
(ok if code_of_local("/") == 200 else bad)("控制台首页 200")

print()
print(f"smoke: {passed[0]} 通过 / {failed[0]} 失败")
sys.exit(0 if failed[0] == 0 else 1)
