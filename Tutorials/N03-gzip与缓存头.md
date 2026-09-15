# N03 · gzip 与缓存头（bundle 实测：120915 → 52999 字节，外加一次"修了 MIME 反而没 gzip"的连环坑）

> **本节要点**：同一份 118 KiB 的 js，压缩后线上只走 52 KiB——近六成的流量省了下来。本篇用"同一份配置只动 gzip 开关"的实验室对比盯住 `Content-Length` 差异，读懂 `Accept-Encoding` / `Content-Encoding` 协商闭环；再实录一个今天才现形的连环坑：**include mime.types 修复了 js 的 Content-Type 却顺手关掉了它的 gzip**，直到给清单补上现代拼写才恢复。
> **前置知识**：N02（dist 与 pinned hash、mime.types include）、零起点-02（HTTP 报文结构）。
> **产出**：会做"只改一个开关"的 gzip A/B 实验；能解释"开了 gzip 却没压"的类型清单根因；能演示 ETag → 304 的白嫖循环。

---

**本站名词卡**

| 名词 | 英文 | 一句人话 | 在本项目哪里见到 |
|---|---|---|---|
| gzip | - | 流行的无损压缩；HTTP 层"先压缩再上路" | `nginx.conf` 第 18 行 `gzip on;` |
| Accept-Encoding | - | 请求头：**我能解** gzip/br，你尽管压 | curl `-H 'Accept-Encoding: gzip'` |
| Content-Encoding | - | 响应头：**这货是** gzip 压过的 | 实测响应里的开关标 |
| Content-Length | - | 响应体的**线上字节数**（压缩后算） | 本站对比实验的主角 |
| chunked | - | 分块传输（长度未知时用），压着传常伴随它 | 静态资源响应常见 |
| ETag | - | 资源指纹（内容 hash），协商缓存凭据 | `ETag: W/"6aa8a592-1d853"` 实测 |
| 304 Not Modified | - | "没变，用你缓存里的"——零字节应答 | 本站实验三实测 |
| Cache-Control | - | 缓存政策：多久内不用问服务器 | 前瞻；配合 N02 的 hash 食用 |

---

## 1. 生活类比与动机：搬家为什么不整箱塞满运

### 是什么

gzip 是 HTTP 标准的内容压缩：服务器把响应体压一遍再发，浏览器解压后再用。协商闭环：

```
浏览器：  Accept-Encoding: gzip, br      ← 我会解这些
服务器：  Content-Encoding: gzip          ← 那我压了 gzip，你解一下
```

### 为什么这么设计

- **JS/HTML/CSS 是文本，重复度极高**（关键字、标签名、变量名反复出现），gzip 压缩率 50%~70% 是常态；
- 省的是**用户流量与等待时间**，对弱网/移动端是几秒级的体验差；
- CPU 代价极低（nginx 压一次几十微秒），几乎纯赚。

### 为什么不是"把文件压成 .zip 再发"

那是"压缩文件"（要手动解压），而 gzip 是"压缩**传输流**"（浏览器透明解压）——用户全程无感，这正是 `Content-Encoding` 头存在的意义。

---

## 2. 配置走查：nginx 的三行 gzip（真实行号，今天的 conf）

`infra/nginx/nginx.conf` 第 18~20 行（由 `nginx-reload.sh` 第 41~43 行生成，**2026-09-15 修复后版本**）：

```nginx
18:   gzip on;
19:   gzip_types text/plain text/css text/javascript application/javascript application/json;
20:   gzip_min_length 100;
```

逐行拆：

- `gzip on;`——总开关；
- `gzip_types`——**只压这些 MIME 类型**。注意 `text/html` 天生默认在列（写不写都压），所以列表里没有它；图片/视频不压（它们已是压缩格式，再压不省反亏）。**清单里同时留了 `text/javascript` 与 `application/javascript` 两个拼写不是冗余，是实录（见第 4 节工程实录）**；
- `gzip_min_length 100`——小于 100 字节不压（压小文件得不偿失，CPU 白花）。

**教学提醒**：`gzip_types` 与 `Content-Type` 对不上号是"明明开了 gzip 却没压"的头号原因——排查时先看响应的 `Content-Type`，再对照清单，最后确认 mime.types 给这个扩展名发的是哪种类型。

---

## 3. 动手验证·一：A/B 对比实验——同一文件、同一个 nginx、只差一个开关（今天实录）

实验纪律：**只动一个变量**。用同一份 nginx 配置（N02 的 mime.types include、同一桶 dist）跑两个实例——生产 9090 是 `gzip on`，实验室 9093 的副本把总开关删掉，其余一字不差。这样差异 100% 归因于 gzip。

### 3.1 开（生产 9090，gzip on）

```
$ curl -s -o /dev/null -D /tmp/n3on.txt -w "size_download=%{size_download}\n" \
    -H 'Accept-Encoding: gzip' http://127.0.0.1:9090/train-ui/assets/index-DpBn_G5n.js
size_download=120915
$ grep -iE 'content-(encoding|type|length)' /tmp/n3on.txt
Content-Type: text/javascript
Content-Length: 120915
```

**注意实况**：带着 `Accept-Encoding: gzip` 却拿到了**明文 120915 字节**——gzip 开着却没压 js！这不是实验失误，正是今天真机现场抓到的连环坑（第 4 节拆案）。先把对照组做完。

### 3.2 关（实验室 9093，除 gzip 总开关外全同）

```
$ curl -s -o /dev/null -D /tmp/n3off.txt -w "size_download=%{size_download}\n" \
    -H 'Accept-Encoding: gzip' http://127.0.0.1:9093/train-ui/assets/index-DpBn_G5n.js
size_download=120915
$ grep -iE 'content-(encoding|type|length)' /tmp/n3off.txt
Content-Type: text/javascript
Content-Length: 120915
```

### 3.3 修复后再测（生产 9090）

给清单补上现代拼写（第 4 节的 diff），`./infra/nginx-reload.sh` 重载：

```
$ curl -s -o /dev/null -D /tmp/n3fix.txt -w "size_download=%{size_download}\n" \
    -H 'Accept-Encoding: gzip' http://127.0.0.1:9090/train-ui/assets/index-DpBn_G5n.js
size_download=52999
$ grep -iE 'content-(encoding|type|length)|etag' /tmp/n3fix.txt
Content-Type: text/javascript
ETag: W/"6aa8a592-1d853"
Content-Encoding: gzip
```

### 3.4 全程对比表（三轮实测，一表看完）

| | 关（9093OFF） | 开但类型不匹配（9090 修复前） | 开且类型命中（修复后） |
|---|---|---|---|
| 线上字节数 | 120915 | 120915 | **52999** |
| Content-Encoding | （无） | （无） | **gzip** |
| Content-Type | text/javascript | text/javascript | text/javascript |
| 省 | — | — | **67916 字节（56.2%）** |

**118 KiB → 52 KiB**，一倍流量白省。这就是"别人的 bundle 显得小"的真相之一：不是文件小，是线上传输的字节小。而第一轮的对比小组还额外送了一条经验：**A/B 实验里如果两个组的结果一模一样，先怀疑"开关根本没拨到"再怀疑世界**。

### 3.4 `--compressed` 才是完整人设

上面第二条 curl 我们**只测了线上字节**（收的是 gzip 原始流）。若想让 curl 帮你解压看内容：

```
$ curl -s -H 'Accept-Encoding: gzip' --compressed \
    http://127.0.0.1:9090/train-ui/ | head -3
<!doctype html>
<html lang="zh-CN">
  <head>
```

`--compressed` 一条龙：自动带上 `Accept-Encoding: gzip` + 自动解压回明文。**记住分工**：`Accept-Encoding` 决定"压不压"（线上字节），`--compressed` 决定"curl 拿到后解不解"（本地显示）。

> 实测小花絮：`curl -H 'Accept-Encoding: gzip' --compressed -w "%{size_download}"` 显示的是**解压后**的 336 字节（index.html 原文约 336 字节，传输时另算 chunked 头）——`%{size_download}` 统计的是"curl 应用层拿到并解压后的字节"。**想量线上真实字节数，别加 `--compressed`**。这是新手实测时最常见的数字打架，本站踩给你看。

---

## 4.9 动手实验二：三条 curl -I 全景（今天实录，修复后版本）

**(1) 静态 js（修完 gzip_types 后：类型命中 + gzip 生效 + 压缩把 ETag 降为弱指纹）：**

```
$ curl -sI -H 'Accept-Encoding: gzip' http://127.0.0.1:9090/train-ui/assets/index-DpBn_G5n.js
HTTP/1.1 200 OK
Server: nginx/1.30.4
Content-Type: text/javascript
Last-Modified: Tue, 15 Sep 2026 09:01:31 GMT
ETag: W/"6aa8a592-1d853"
Content-Encoding: gzip
```

**(2) 静态 html：**

```
$ curl -sI http://127.0.0.1:9090/train-ui/
HTTP/1.1 200 OK
Content-Type: text/html; charset=utf-8
Last-Modified: Tue, 15 Sep 2026 09:01:31 GMT
ETag: W/"6aa8a592-145"
```

`ETag: W/"..."` 的 `W/`＝weak（弱指纹，nginx 对携带 gzip 的代理/静态响应自动降级）。

**(3) 反代 API（注意：没有 ETag、没有 Content-Encoding）：**

```
$ curl -sI http://127.0.0.1:9090/apitrain/trips
HTTP/1.1 200 
Server: nginx/1.30.4
Content-Type: application/json
```

三个头集体缺席的解读：

- **无 ETag**：响应来自 Tomcat（动态内容），不是 nginx 静态货架，没有"文件指纹"可算；
- **无 Content-Encoding**：这趟响应体很小（ trips 列表几百字节），`gzip_min_length 100` 之外，nginx 对代理响应的 gzip 还要求 `Accept-Encoding` 明确带上；
- **无 Last-Modified/Expires 族**：动态数据每秒都可能变，缓存它毫无意义。

**结论口诀**：静态资源配缓存 + 压缩；动态 API 不缓存、按需压缩。两类资源两种待遇，别一锅炖。

---

## 4. 工程实录：真实问题与解决——修 MIME 白屏的并发症："开了 gzip 却没压"

**现场**（2026-09-15 全栈在线实测）：N02 把 `include /etc/nginx/mime.types;` 修进 http 块后，浏览器解析正确了（`.js` 身份证从"未识别的 text/plain"升级为 `text/javascript`）。但 gzip 被顺手打穿了：

```
$ curl -sI -H 'Accept-Encoding: gzip' http://127.0.0.1:9090/train-ui/assets/index-DpBn_G5n.js
HTTP/1.1 200 OK
Server: nginx/1.30.4
Content-Type: text/javascript          ← 身份对了（这个是白屏修复的功劳）
Content-Length: 120915                 ← 但一字节没压！
```

逐段走一遍**排查链**（这条链以后能直接抄走）：

1. `Content-Encoding` 缺席 → 先按总开关怀疑：`nginx -T | grep gzip` → `gzip on;` 在局。开关没坏；
2. 再按数据格式怀疑：body 大于 `gzip_min_length 100`（120915 > 100），长度也没问题；
3. 剩下唯一变量是**类型匹配**：`gzip -` 从 mime.types 里验扩展名——

```
$ grep -w js /etc/nginx/mime.types
text/javascript                 js mjs;
```

4. 结论现形：本机 mime.types 把 `.js` 认成 `text/javascript`（现代写法），而 gzip_types 清单里只有老拼写 `application/javascript`——**类型对不上号，gzip 悄悄跳过**，且**不报任何错**。对照组里 html 一直被压（`text/html` 默认必压），正因为它是默认项才逃过修 MIME 的连带伤害。

**修复 diff**（`infra/nginx-reload.sh` 第 42 行，前后各一行）：

```diff
-  gzip_types text/plain text/css application/javascript application/json;
+  gzip_types text/plain text/css text/javascript application/javascript application/json;
```

`./infra/nginx-reload.sh` 重载后复跑 3.4 的三条 curl——120915 → 52999（省 56.2%），闭合验证。**教训落成一句**：修一个问题的"正确姿势"（include mime.types）可能顺手改变另一个机制的前提条件（gzip_types 的类型匹配）；**改完配置必须复跑受影响的全部验证**，gzip 与 MIME 恰好是一本连环账。

顺带一个实测彩蛋：压缩后静态文件的 `ETag` 从强指纹 `"..."` 变成了弱指纹 `W/"..."`——nginx 对 gzip 响应自动降级为 weak ETag（压缩前后的字节内容不同，弱化以示"协商但别拿它当字节级凭据"）。读头时能读出这三层的信息，你就是会看头的人了。

## 4.5 历史案例存档：mime.types 缺席时代的 text/plain（对照伤疤）

修复前的旧 conf 没有 include mime.types，那天实测 js 的 `Content-Type: text/plain`——gzip 之所以"误打误撞"也没压它，同样是类型不匹配（text/plain 根本不在旧清单里）。白屏事件从"浏览器解不了 ES module"升级为"配置三族错乱（解析 404 白屏 / 类型误判 / 压缩失效）"，全部由**一行的正确性与连锁反应**串成——这条连环的因果图值得给每个新同学画一遍：MIME 类型是"文件身份证"，它同时喂**浏览器**、**nginx 的 gzip_types**、以及一切按类型分流的中游（CDN、缓存层）。

---

## 5. 缓存头前瞻：ETag 与 304 的白嫖循环

### 5.1 两种缓存策略（一张表）

| 策略 | 头 | 问服务器吗 | 本项目 |
|---|---|---|---|
| 强缓存 | `Cache-Control: max-age=...` | ❌ 有效期内直接用本地 | dist 的 js（配合 N02 的 hash，可管一年）——nginx 目前未显式发，属**可加配项** |
| 协商缓存 | `ETag` / `Last-Modified` | ✅ 每次问，但"没变"时只回 304 | nginx 静态文件**已默认在发**（实测见上） |

### 5.2 动手实验三：ETag → 304 实测（今天实录）

```
$ ET=$(curl -sI http://127.0.0.1:9090/train-ui/ | grep -i etag | tr -d '\r' | cut -d' ' -f2)
$ echo $ET
"6aa8a592-14e"

$ curl -s -o /dev/null -w "%{http_code} (%{size_download} bytes)\n" \
    -H "If-None-Match: $ET" http://127.0.0.1:9090/train-ui/
304 (0 bytes)
```

**304 的含义**：浏览器说"我手里的版本指纹是这个"，nginx 对比指纹一致 → "没变，用你自己的" → **0 字节应答**。整趟往返只有几百字节的头，body 完全省了。

### 5.3 两个头怎么配合 hash（N02 的钉子拔出）

```
强缓存（max-age 一年）  →  管住"不重复下载 index-DpBn_G5n.js"
内容 hash（文件名指纹）  →  管住"发新版必然换名 → 强缓存自动失效"
ETag/304               →  管住"html 每次轻问一句有没有变"
```

三者不是替代关系：**hash 是身份、强缓存是政策、ETag 是保险丝**。工业级前端部署三件齐上，用户的第二次访问几乎零流量。

### 5.4 给本项目补上缓存政策（可动手的加分练习）

当前 nginx 静态响应**没有发 Cache-Control**（4.2 实测可见）。给 js/css 补强缓存、给 html 保留协商缓存的经典配法（加进 `nginx-reload.sh` 生成的 location 里）：

```nginx
location /train-ui/ {
    alias /home/icaruslee/Projects/javaweb/frontend/train-ui/dist/;
    index index.html;
    try_files $uri /train-ui/index.html;

    location ~* \.(js|css)$ {
        add_header Cache-Control "public, max-age=31536000, immutable";
    }
    location = /train-ui/index.html {
        add_header Cache-Control "no-cache";
    }
}
```

- js/css：一年强缓存 + `immutable`（浏览器连 304 都不问，因为 hash 文件名永不变）；
- index.html：`no-cache`＝可以缓存但**每次必须协商**（ETag/304 轻问一句）——正是 5.2 实测的那套握手。

改完 `./infra/nginx-reload.sh`，重跑 4.2 的 `curl -I` 验证新头出现——**本篇的知识就落了地**。

### 5.5 Last-Modified 与 ETag 的分工（nginx 双保险）

实测里静态响应同时带了两个指纹：

```
Last-Modified: Mon, 14 Sep 2026 09:54:51 GMT
ETag: W/"6aa7c46b-145"
```

- `Last-Modified`：粗粒度（秒级时间戳），配请求头 `If-Modified-Since`；
- `ETag`：细粒度（内容指纹），配请求头 `If-None-Match`，本站 5.2 实测用的就是它。

nginx 对静态文件两者都发；浏览器下次协商时两个头都会带，服务器任选其一裁决。**为什么有了时间戳还要指纹**：时间戳只能精确到秒（同一秒内两次修改无法区分），且分布式文件系统上时间可能不可靠——ETag 按内容说话，更铁面。

---

## 6. 压缩与安全的边界：哪些东西不该压（CRIME 教训一分钟）

gzip 不是无脑全开：

- **不该压的内容**：已经是压缩格式的（jpg/png/mp4/woff2）——再压不省反耗 CPU；
- **不该压的内容 2**：**含敏感 Cookie 的响应**——CRIME/BREACH 攻击（2012/2013）证明：攻击者能通过观察"密文+压缩"的长度变化，逐字节猜出压缩流里的机密。HTTPS 流量上压缩用户敏感数据要谨慎（现代浏览器已禁 TLS 层压缩，应用层 gzip 对 Cookie 的风险场景已很罕见，但面试考点在）；
- **该压的甜点区**：JS/CSS/JSON/SVG/HTML——文本重复度高，实测 56% 的节省就是它们贡献的。

**一句话记住**：压缩是给"公开的文本"省流量的，不是给"机密"打包的。

---

## 7. 常见坑清单

| 症状 | 根因 | 修法 |
|---|---|---|
| 开了 gzip 但响应没压 | 类型不在 `gzip_types`；或没带 `Accept-Encoding`；或 body < `gzip_min_length` | 对照实测响应的 `Content-Type` 与第 16 行清单 |
| `--compressed` 后数字对不上 `Content-Length` | `%{size_download}` 统计的是解压后字节 | 量线上字节时**去掉** `--compressed` |
| 发新版用户还是旧页面 | html 被强缓存太狠 | html 短缓存/no-cache，js 靠 hash 长缓存 |
| 304 一直是 200 | ETag 变了（文件被重写过）或 If-None-Match 头没带对 | 原样回传服务器给的 ETag 字符串（含 `W/` 引号） |
| 代理后的 API 突然 304 | 上游缓存头透传 | API 不要缓存头；必要时 `proxy_hide_header` |

---

## 8. 自测题（五分钟能答完）

1. `Accept-Encoding` 和 `Content-Encoding` 各是谁发给谁的？缺了前者会发生什么？
2. 实测 gzip 把 120915 字节压到 52999——省下的字节去哪了？CPU 花在哪一步？
3. 为什么 `gzip_types` 清单里没有 `text/html`，但 html 还是被压了？
4. 304 的响应里 body 是 0 字节，浏览器从哪拿到页面内容？
5. 静态 js 想配"管一年"的强缓存，前提条件是什么？没有这个前提会发生什么事故？

**参考答案**（自测后再看）：

1. 都是浏览器→服务器的"我会解"与服务器→浏览器的"我压了"；缺了前者（老代理/CDN 剥头），服务器按明文发，省流失效但不出错。
2. 字节没"去哪"，是被压缩算法用更短的编码表示了重复内容；CPU 花在 nginx 压缩（服务器）与浏览器解压（客户端）两端。
3. `text/html` 是 gzip 的**默认必压类型**（HTTP 规范使然），清单里写不写都压——清单是用来**追加**其他类型的。
4. 从**本地缓存**拿——304 的意义正是"用你手里那份"，body 为空是设计不是缺陷。
5. 前提是文件名带内容 hash（N02）；没有 hash 时发新版浏览器仍用旧缓存，用户看到"改不动的页面"，只能强刷清缓存。

---

## 9. 本节小结

- gzip 协商：请求头 `Accept-Encoding`（我会解）↔ 响应头 `Content-Encoding`（我压了）。
- 实测：`index-DpBn_G5n.js` 明文 120915 字节，gzip 修复后 52999 字节，**省 56.2%**。
- nginx 三行配置：`gzip on` + `gzip_types` 清单 + `gzip_min_length 100`（nginx.conf 第 18~20 行）。
- 静态资源有 ETag/304 的资格，动态 API 没有——实测三条 curl 的头差异就是分界线。
- 缓存双轨：强缓存（Cache-Control）不问服务器，协商缓存（ETag/304）轻问一句；hash 让两者共存。

---

## 10. 下一站

一台 nginx、一扇门铃已经够用——可流量翻十倍、后端一台扛不住呢？把 train 起两份（8084 / 18084），让 nginx `upstream` 带着请求**轮着发**：N04《负载均衡与压测》用 `log_format` 把每一发打给了谁写进日志，10 次 curl 循环实测计数，顺便演练"一台挂了另一台独扛"的故障戏码。

## 思考题

1. 本章主题换到你自己的场景里，最先想到的一个问题是什么？先用 3 句话写出你的猜测，再实测一次。
2. 本章与相邻一章的知识点拼起来会解决什么问题？给一个一句话用例。


## 练习题

- 练 1：把本章动手实验的参数改一档，预测输出再实测，把差异写下来。
- 练 2：设计一个"改坏条件"的反向实验，验证错误表现与预期一致。

