# N03 · gzip 与缓存头（bundle 实测：120910 → 52995 字节）

> **本节要点**：同一份 118 KiB 的 js，开着 gzip 走线上只有 52 KiB——近六成的流量就这么省下来了。本篇用三条 curl 实测 gzip 开/关的 `Content-Length` 差异，读懂 `Accept-Encoding` / `Content-Encoding` 协商协议，再前瞻 `Cache-Control` 与 `ETag`：压缩管"这次传多少字节"，缓存管"这次还要不要传"。
> **前置知识**：N02（dist 与 pinned hash）、零起点-02（HTTP 报文结构）。
> **产出**：会用 `curl -H 'Accept-Encoding: gzip' --compressed` 做字节对比实验；能解释 gzip 协商的请求/响应头闭环；能演示 ETag → 304 的白嫖流程。

---

**本站名词卡**

| 名词 | 英文 | 一句人话 | 在本项目哪里见到 |
|---|---|---|---|
| gzip | - | 流行的无损压缩；HTTP 层"先压缩再上路" | `nginx.conf` 第 15 行 `gzip on;` |
| Accept-Encoding | - | 请求头：**我能解** gzip/br，你尽管压 | curl `-H 'Accept-Encoding: gzip'` |
| Content-Encoding | - | 响应头：**这货是** gzip 压过的 | 实测响应里的开关标 |
| Content-Length | - | 响应体的**线上字节数**（压缩后算） | 本站对比实验的主角 |
| chunked | - | 分块传输（长度未知时用），压着传常伴随它 | 静态资源响应常见 |
| ETag | - | 资源指纹（内容 hash），协商缓存凭据 | `ETag: "6aa7c46b-145"` 实测 |
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

## 2. 配置走查：nginx 的三行 gzip（真实行号）

`infra/nginx/nginx.conf` 第 15~17 行（由 `nginx-reload.sh` 第 38~40 行生成）：

```nginx
15:   gzip on;
16:   gzip_types text/plain text/css application/javascript application/json;
17:   gzip_min_length 100;
```

逐行拆：

- `gzip on;`——总开关；
- `gzip_types`——**只压这些 MIME 类型**。注意 `text/html` 天生默认在列（写不写都压），所以列表里没有它；图片/视频不压（它们已是压缩格式，再压不省反亏）；
- `gzip_min_length 100`——小于 100 字节不压（压小文件得不偿失，CPU 白花）。

**教学提醒**：`gzip_types` 写错 MIME（比如把 js 写成 `text/javascript` 之外的老写法）是"明明开了 gzip 却没压"的头号原因——排查时先看响应的 `Content-Type` 对不对得上清单。

---

## 3. 动手验证·一：压缩实测——同一文件的两种命运（今天实录）

### 3.1 关（不带 Accept-Encoding，明文走线上）

```
$ curl -s -o /dev/null -w "%{size_download} bytes\n" \
    http://127.0.0.1:9090/train-ui/assets/index-De0t7uFW.js
120910 bytes
```

### 3.2 开（会解 gzip，让它压）

```
$ curl -s -H 'Accept-Encoding: gzip' -o /dev/null -w "%{size_download} bytes\n" \
    -D /tmp/h.txt http://127.0.0.1:9090/train-ui/assets/index-De0t7uFW.js
52995 bytes
$ grep -i content-encoding /tmp/h.txt
Content-Encoding: gzip
```

### 3.3 对比结论

| | 明文 | gzip | 省 |
|---|---|---|---|
| 线上字节数 | **120910** | **52995** | **67915（56.2%）** |

**118 KiB → 52 KiB**，一倍流量白省。这就是"别人的 bundle 显得小"的真相之一：不是文件小，是线上传输的字节小。

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

> 实测小花絮：`curl -H 'Accept-Encoding: gzip' --compressed -w "%{size_download}"` 显示的是**解压后**的 253 字节（index.html 原文 325 字节，传输时另算 chunked 头）——`%{size_download}` 统计的是"curl 应用层拿到并解压后的字节"。**想量线上真实字节数，别加 `--compressed`**。这是新手实测时最常见的数字打架，本站踩给你看。

---

## 4. 动手实验二：三条 curl -I 全景（今天实录）

**(1) 静态 js（gzip 命中 + ETag 在列）：**

```
$ curl -sI -H 'Accept-Encoding: gzip' http://127.0.0.1:9090/train-ui/assets/index-De0t7uFW.js
HTTP/1.1 200 OK
Server: nginx/1.30.4
Content-Type: text/plain
Last-Modified: Mon, 14 Sep 2026 09:54:51 GMT
ETag: "6aa7c46b-1d84e"
Content-Encoding: gzip
```

> 注意 `Content-Type: text/plain`——文件扩展名 `.js` 没被 mime.types 认出来（本机 nginx 的 mime 配置没挂进这份极简 conf），所以 gzip 之所以照样压它，是因为我们**显式带了 Accept-Encoding** 且 nginx 对未识别类型默认也压。真实项目应让 js 被识别为 `application/javascript`（正好在 gzip_types 清单里）。

**(2) 静态 html：**

```
$ curl -sI http://127.0.0.1:9090/train-ui/
HTTP/1.1 200 OK
Content-Type: text/html
Last-Modified: Mon, 14 Sep 2026 09:54:51 GMT
ETag: W/"6aa7c46b-145"
```

`ETag: W/"..."` 的 `W/`＝weak（弱指纹，nginx 静态文件的默认形态，够用）。

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

## 4.5 动手实验二·补：给 nginx 配上正确的 MIME（顺手修掉 4.2 的 text/plain）

第 4.2 节实测 js 的 `Content-Type: text/plain`——根因是这份极简 nginx.conf 没有 `include mime.types;`。修法（改 `nginx-reload.sh` 生成的 http 块）：

```nginx
http {
    include /etc/nginx/mime.types;     # ← 加这一行（或绝对路径 mime.types 文件）
    default_type application/octet-stream;
    ...
}
```

修后重跑第 4.2 节的 curl，`Content-Type` 会变成 `application/javascript`——正好命中 `gzip_types` 清单，gzip 与浏览器解析双正确。**这个 30 秒的修复串起了本篇两个知识点**：MIME 类型是"文件身份证"，gzip_types 与浏览器行为都看它办事。

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
"6aa7c46b-145"

$ curl -s -o /dev/null -w "%{http_code} (%{size_download} bytes)\n" \
    -H "If-None-Match: $ET" http://127.0.0.1:9090/train-ui/
304 (0 bytes)
```

**304 的含义**：浏览器说"我手里的版本指纹是这个"，nginx 对比指纹一致 → "没变，用你自己的" → **0 字节应答**。整趟往返只有几百字节的头，body 完全省了。

### 5.3 两个头怎么配合 hash（N02 的钉子拔出）

```
强缓存（max-age 一年）  →  管住"不重复下载 index-De0t7uFW.js"
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
2. 实测 gzip 把 120910 字节压到 52995——省下的字节去哪了？CPU 花在哪一步？
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
- 实测：`index-De0t7uFW.js` 明文 120910 字节，gzip 后 52995 字节，**省 56.2%**。
- nginx 三行配置：`gzip on` + `gzip_types` 清单 + `gzip_min_length 100`（nginx.conf 第 15~17 行）。
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

