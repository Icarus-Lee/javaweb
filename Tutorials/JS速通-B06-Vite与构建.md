# JS速通 B06 · Vite 与构建：dev server、HMR、dist 产物与 Nginx 部署

> **本站你走在大厅哪一格**：B 篇收官——前 5 篇你写的 Vue 代码**怎么跑起来的**。
> 本篇拆三件事：**dev server**（`npm run dev` 时背后是什么）、**build**（`npm run build` 出来的 dist 到底变了什么、frontend/train-ui/dist 真实目录）、
> 以及**部署**——本站 nginx-reload.sh 把 dist 挂到 `http://127.0.0.1:9090/train-ui/` 的真实流程。
> 读完你就能解释："开发为什么热腾腾，生产为什么冷冰冰"。

## 问题出发

今天上午刚发生的两个真实事件，正是本篇的两条主线：
① 训练线上敌人的**假热重载**——`nginx-reload.sh` 打印"已热重载"，但 9090 端口实为拒绝，
浏览器一刷新就 `000`，smoke 从 24 全绿跌到 **20 通过 / 4 失败**（实录见本章工程实录节）；
② 明早 bunld 出来的产物 hash 从 `index-De0t7uFW.js` 变成了 `index-DpBn_G5n.js`——因为 api.js 与
vite.config.js 同一晚升过版：**内容变，hash 变**，这正是"内容指纹"的现场示范。
读完全篇，你会回答：dev/HMR/build/nginx 三条路各怎么走、且真事故可以按下复现。

## 名词卡

| 名词 | 人话 |
|---|---|
| Vite（法语"快"） | 前端构建工具：dev 时"**按需、原生 ESM**"供码，build 时用 Rollup 正经打包 |
| dev server | `npm run dev` 起的本地 HTTP 服务：把 src 按请求"现烤"给浏览器 |
| HMR（Hot Module Replacement） | 热模块替换：改一个组件，只把这一"块"打进页面，不整页刷新 |
| bundle 打包 | 把几十个模块合成少量 js/css 文件，缩小+加 hash 文件名 |
| ESM（ES Modules） | JS 官方的模块系统（`import/export`），浏览器原生支持 |
| node_modules | npm 依赖的"全是 JS 的类库摊"（大而分散，dev 用） |
| dist | build 的产物目录："部署专用"的自给自足包 |
| Nginx alias | "URL 前缀 → 磁盘目录"的映射（本站 `/train-ui/ → dist/`） |

## 零点五、从 B05 到 B06：还不是新高原，是同一座山的背面

B01-B05 你写的所有 Vue 代码都活在"Vite dev server 给烤"的世界里；本篇解释这条路的另一端：
- **为什么改代码不刷新**（B03 实验 2 的 HMR）——dev server 的"模块热替换"；
- **部署后它去了哪**——`dist/`（真实目录）与 Nginx 的挂线。

`localStorage`/store 那些"内存态"**不进 build 产物**——build 只是把"你的 code"烘成
"任何浏览器、任何静态服务器都能运行的文件"。所以 B06 的知识是 B01-B05 的"载体知识"。

## 一、人话：dev 与 build 的分岔路

```
开发（npm run dev）                       生产（npm run build + Nginx）
────────────────────                     ─────────────────────────────
浏览器 ←→ Vite dev server (5180)          浏览器 ←→ Nginx (9090) ←→ 静态 dist
          每个请求现烤 src/*.vue                      事先烘焙好的 assets/*.js
          改代码立刻生效（HMR）                        文件名带指纹 hash，可无限缓存
```

**dev 慢不了的原因**：它根本不打包。浏览器请求 `/src/App.vue`，Vite **当场**把 SFC 编译成
"浏览器可执行的 ESM js"（还会顺手做 vue 模板→render 函数编译）直接回给浏览器——
**"要多少编多少，实时编译"**，所以项目大不起来也启动快（冷启是毫秒级，不是 webpack 的分钟级）。

**build 的原因**：生产要的是"**缓存无关、请求少、体积小**"——预烘焙 + 内容指纹
（filename-hash.js 内容量一变文件名就换）+ gzip + 长缓存。

### 真实三家对照：本站 dev 的 config

frontend/train-ui/vite.config.js（全部 10 行）：

```js
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: '/train-ui/',              // ← 子路径部署：所有产物链接带此前缀（N02 三连修之一）
  plugins: [vue()],                // ← @vitejs/plugin-vue：教 Vite 认得 .vue 文件
  server: {
    port: 5180,                    // dev server 常驻端口
    proxy: {
      '/apitrain': {
        target: 'http://127.0.0.1:8084',
        rewrite: p => p.replace(/^\/apitrain/, '/api'),
      }
    }   // ① 开发代理：把 /apitrain 还原成后端的 /api 转给后端
  },
})
```

**proxy + base 两段是"前后端分离开发/部署的桥梁"**：
- `base: '/train-ui/'`：build 后所有资源 URL 上的公共前缀（这决定 dist 能被挂到 9090 的子路径下）。
- `proxy`：页面里 axios 的 baseURL 是 `/apitrain`（api.js:3，与 Nginx 反代别名同源——dev/prod 双侧零改代）；
  dev server 听到 `/apitrain/...` 就**转手转发给 8084** 且 `rewrite` 把前缀还原成后端认识的 `/api`。
  生成的 nginx.conf 用 `location /apitrain/` 干同一件事（下文）——**一侧换风景，页面代码一个字不改**。

## 二、真实产物走查：frontend/train-ui/dist 列表真联动

本机 build 过的产物（`ls -R frontend/train-ui/dist`，2026-09-15 实测）：

```
frontend/train-ui/dist/
├── assets/
│   └── index-DpBn_G5n.js           ← Vue + App.vue + api.js 三家合成的一坨（120.92 kB，gzip 46.61 kB）
└── index.html                      ← 瘦身后的入口（引用上面的 hash 产物，base 前缀已打进引用）
```

> **hash 变名的现场示范**：昨天的产物叫 `index-De0t7uFW.js`；今早 api.js（baseURL 统一为 `/apitrain`）
> 与 vite.config.js（`base: '/train-ui/'`）升级后同一路径 build 出 `index-DpBn_G5n.js`——
> **JS 字节差了，文件名就换**（无需人工 bump 版本）。这正是下表 hash 一行的意义。

对比源码目录（src/ 一共 3 个文件：App.vue/main.js/api.js；node_modules 里几百的依赖）：

| 变化点 | 源（src+node_modules） | 产物（dist） |
|---|---|---|
| 文件数 | 几百个模块 | **2 个文件** |
| 体积 | node_modules 几十 MB | assets/*.js 通常 <100KB 量级（minify + tree-shake） |
| axios/pinia/vue 三家 | node_modules 里几千个文件 | 被"摇"走没用的分支后只剩用到的部分 |
| 文件名 | 语义化（App.vue） | `index-De0t7uFW.js`（内容 hash——改一个字，文件名就换新） |
| 形态 | `.vue` 不可被浏览器直接吃 | 全 js/css，浏览器原生可加载 |

> `index.html` 里那行 `<script type="module" src="/src/main.js">`（开发用的真身），
> build 后被改写成指向 hash 产物——所以 dist 目录**自洽可独立部署**：把整个 dist 搬
> 到任何静态服务器即可跑（不需要 node/npm）。

### 动手验证：一键看 build 差异（不用改任何代码）

```bash
cd frontend/train-ui
npm run build                        # 产物打进 dist/
ls -lh dist/assets/                  # 记录体积
# 顺手 diff 一下 index.html 开发版与产物版：
grep "script" dist/index.html        # 产物引用带 hash；dev 版引用 /src/main.js
```

**预期形态**：

```
-rw-r--r-- 1 user user 121K dist/assets/index-*.js   ← minified 主包（文件名 hash，内容变化即换名）
```

（实际数字是 120.92 kB / gzip 46.61 kB——当你读到这里时 hash 与体量都可能又变了，那**正常**；
`hash` 指纹功能和"改动换名"是长缓存策略的基础——**产物一改、名全换、缓存必失效**。）

## 二点五、为什么产物这么小：minify + tree-shake + hash 三件套

`index-DpBn_G5n.js`（120.92 kB / gzip 46.61 kB）管"Vue 整个框架 + 你的三份源码"，背后三板斧：

| 技术 | 干什么 | 人话对照 |
|---|---|---|
| minify | 压缩空白/短化内部名/删注释 | `-O2` + strip，JS 版 |
| tree-shake | 把**没用到的**导出整段削掉 | 链接器 dead-strip（`-ffunction-sections`那套思想的 JS 版） |
| 内容 hash 文件名 | 文件内容变名就变 | "指纹 jar"：内容对得上缓存就永不重下 |

**tree-shake 的生效前提**：模块必须可静态分析（ESM 的 import/export 是编译期"看得见"的）。
`require()` 那种动态老形态装不下分析，摇不动——这也是现代前端坚持 ESM 的硬理由。
Java 的对照：Maven 打包粒度是 jar，没有"按单个函数摇掉无用代码"的概念；而 JS 的依赖能沿"单个导出函数"粒度摇出来——产物因此能压得很贴身。

## 三点七、工程实录：踩坑与修复（真机实录）

### 实录 1（背景，一笔带过）：MIME 白屏——N02 主线的"文件级修复"

今天早上的第一个坑是 `.js` 被 Nginx 挡成 `text/plain` 后浏览器拒执行 ES 模块 → **子路径白屏**。
**修复一行**：nginx.conf 里 `include /etc/nginx/mime.types;`——完整复现、排查、验证与截图在
**N02("前后端解耦：静态站建立") 第 7.5 章**，本篇不重复，只提醒：**静态资源部署事故几乎都是"浏览器收到了什么"的问题**，看 Network 面板 Response Headers 即见底。

### 实录 2（本篇主菜）：陈旧 pid 引发的"假热重载"——脚本嘴上绿灯，9090 静默拒绝

**问题**：今早 `bash infra/start-all.sh` 一切"正常打印"，但任何 `/apitrain/*` 都 `Connection refused`：
脚本第 4 步在说"nginx: 已热重载"，而 smoke 的 4 条 nginx 断言全败（smoke 尾打 `20 通过 / 4 失败`）。

**现场复现**（分两步，全部真机 2026-09-15 12:51 实录）：

```bash
# 步骤 A：真杀掉 nginx master，但把 pid 文件指向一个"活着"的其他进程（模拟 pid 被复用）
RPID=$(cat logs/redis.pid); echo "$RPID" > infra/nginx/run/nginx.pid
ss -tln | grep -c 9090                     # → 0 ：nginx 实际没监听
bash infra/nginx-reload.sh                 # → 打印：nginx: 已热重载     ← ⚠️ 假话

curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:9090/apitrain/trips
# 000                        ← 连接拒绝，浏览器就是白屏
python3 infra/smoke.py | tail -1
# smoke: 20 通过 / 4 失败     ← smoke 里 nginx 四断言，故精确掉 4 条
```

**排查路径**（三步看穿）：

```bash
# ① 证据一：pid 文件里的 pid，是谁？
ps -p $(cat infra/nginx/run/nginx.pid) -o pid,comm
#    → redis/其他进程：kill -0 判活成功，脚本误以为"nginx 还在跑"

# ② 证据二：直接手跑 reload，去掉脚本里的 >/dev/null 2>&1（真相没人看）：
nginx -s reload -c /home/icaruslee/Projects/javaweb/infra/nginx/nginx.conf 2>&1
# 2026/09/15 12:51:43 [emerg] ... : open() "...": failed ...  ← 退出码 1
#    名面上 master 已死，reload 变"无人听海"，但 stderr 早被脚本吞了 → 假话无人报错

# ③ 结论：kill -0 判活 + 吞错 reload，两个"省心写法"叠加出静默故障。
```

**修复与二次验证**：

```bash
rm -f infra/nginx/run/nginx.pid        # 假 pid 摘掉（防 pid 复用；或读 ps 真核对再删）
nginx -c /home/icaruslee/Projects/javaweb/infra/nginx/nginx.conf   # 冷启
ss -tln | grep 8090/9090               # → 9090 回来
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:9090/apitrain/trips  # → 200
python3 infra/smoke.py | tail -1       # → smoke: 24 通过 / 0 失败
```

（全部命令真机跑过，24/24 复绿。）

**一行级改进建议**（脚本加固，留给读者动手）：

```bash
# 原：nginx -s reload -c "$NG/nginx.conf" >/dev/null 2>&1 && echo "nginx: 已热重载"
# 提议：不再吞错；且必要时先验证 master 存在：
if ! nginx -t -c "$NG/nginx.conf" 2>/dev/null; then exit 1; fi
# reload 后立刻 curl 探一次：失败自动降级冷启 → 假"已热重载"不可能再出场
```

**教学点**：
- `>/dev/null 2>&1` 把 reload 的报错全文吞掉（`[emerg]` 只进脚本耳朵再被扔）；"吞错 + 进程判活"
  两种省心写法叠加才是本次静默故障的完整成因——对比：`start_jar()` 用**端口探测**就诚实得多（S01 实录）。
- smoke 的价值再次应验：**脚本说"Yeah"与系统说"Yeah"可以是两种声音**，而 smoke（具体的 curl 断言）
  只信 000/200。

### 实录 3：hash 变名的"现场记录"（观察记录，一类问题=1）

见本章第二节"hash 变名的现场示范"框注——`De0t7uFW → DpBn_G5n` 的改名完全自动。
**动手自查**（不抹机重证）：`sha1sum dist/assets/index-*.js` 与文件名 hash 前段逐次对上——
产物**内容演进史可完全从文件名读出**，这就是"部署可回滚"的物理基础：旧 hash 的文件还在缓存/归档里，一指过去就回去了。

## 三、部署走查：本站 nginx-reload.sh 的真实流程

本站的静态部署脚本：infra/nginx-reload.sh（摘要、减到核心 12 行）：

```bash
for ui in train-ui takeout-ui; do
  if [ ! -f "$ROOT/frontend/$ui/dist/index.html" ]; then   # ① dist 缺就现 build 一次
    (cd "$ROOT/frontend/$ui" && npm install --silent && npm run build --silent)
  fi
done

TRAIN_DS="$ROOT/frontend/train-ui/dist"
cat > "$NG/nginx.conf" <<EOF            # ② 关键 run 路径后写 nginx 配置
...
location /train-ui/ {
  alias $TRAIN_DS/;                     # ③ URL 目录挂 dist
  index index.html;
  try_files \$uri /train-ui/index.html; # ④ 命中不了就回 index.html（SPA 路由后备）
}
location /apitrain/ {
  proxy_pass http://127.0.0.1:8084/api/; # ⑤ API 反代给后端
}
EOF

if [ -f "$RUN/nginx.pid" ] && kill -0 "$(cat "$RUN/nginx.pid")"; then
  nginx -s reload -c "$NG/nginx.conf"      # ⑥ 已在跑 → 热重载
else
  nginx -c "$NG/nginx.conf"                # ⑦ 否则冷启
fi
```

**逐行解读**：

- **alias + try_files** 是"SPA 部署"的黄金二件：静态文件有真身就给真身；404 时**回 index.html**（因为 Vue Router 的 /xxx 路由并不对应磁盘文件）。
- **reload vs 冷启**：nginx 加载新配置**不中断服务**（老连接服务完成后再切）——这就是 start-all.sh 里"重跑脚本却永远不重启前端站"的秘密。
- 本站端口全貌：**5180 dev（Vite）** ⇄ 开发自用；**8084-8085 API（Java）**；**9090 Nginx**（正式门口）。

### 真实流程对照：build.sh→start-all.sh→smoke.py 的全链序

```bash
bash infra/build.sh        # mvn package（5 jar）＋ npm run build（2 dist）
bash infra/start-all.sh    # Redis/Kafka/后端×5/ + "nginx-reload.sh" 收尾
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:9090/train-ui/   # 预期 200
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:9090/apitrain/trips  # 预期 200
```

这三行正是 infra/smoke.py 里**四处 nginx 断言**（smoke.py:141-144）的命令级拆解：
`train 静态站 200 / takeout 静态站 200 / 反代 /apitrain 200 / 控制台首页 200`。

## 四点五、命令速查（本站日常六条）

| 命令 | 干什么 | 记法 |
|---|---|---|
| `npm run dev` | 起 dev server（5180，HMR 全开） | "生火" |
| `npm run build` | 产出 dist（Rollup 打包） | "烘焙装箱" |
| `bash infra/build.sh` | Maven 5 jar + 前端 2 dist 一次起 | "全厂打包" |
| `bash infra/nginx-reload.sh` | dist 挂到 9090 + 反代配置 + 热重载 | "上架挂线" |
| `curl -w "%{http_code}" http://127.0.0.1:9090/train-ui/` | 快极验产 | "门铃一按" |
| `rm -rf dist && bash infra/nginx-reload.sh` | 验"dist 缺自动重建" | "拆楼再上楼" |

## 四、动手验证：三连实验（本篇亮点）

### 实验 1：HMR 的"局部更新"到底多快

```bash
cd frontend/train-ui && npm run dev &   # dev 起来
# 另窗口改 UI 文案
sed -i 's/我的订单/我的订单（HMR 改过）/g' src/App.vue
# 浏览器**不刷新**：标题秒变，Console 有 [vite] hmr update: /src/App.vue
```

### 实验 2：build 产物"无 node 也活"

```bash
npm run build
python3 -m http.server 8899 --directory dist &   # 任何静态服务器，包括无 node 环境
# 浏览器开 http://127.0.0.1:8899 —— 站活着（API 会 404，页面壳完整）
```

这证明 dist 是**自给自足**的——"JS 世界的 jar"。区别在：jar 还需要 JVM，而 dist 只需一个"静态文件服务器"（nginx/python 都行）。

### 实验 3：dev 与 build 双端接口对拿了同一套代码

- dev：`vite.config.js` 里 `proxy /apitrain → 8084（重写为 /api）`，页面里 axios `baseURL: '/apitrain'` —— **零改代**运行。
- 生产：**没有 Vite 了**！由 nginx `location /apitrain/` 反代（注意前缀变了 `/apitrain` → `/api/`）。
  但前端 axios baseURL 用的是 `/apitrain`？——**对**（dev 代理/prod 反代都认这个别名，`rewrite` 把它还原成后端的 `/api`），所以 nginx-reload.sh 的反代把
  `/apitrain/` 前缀剥掉再转给 8084 的 `/api/`，"两边桥接"由 nginx 负责（nginx.conf:~50-54）。
- 验证：`curl http://127.0.0.1:9090/apitrain/trips`（200 JSON）；
  `curl http://127.0.0.1:5180/api/trips`（dev 模式同样 200）——**接口地址对页面**完全无感。

## 思考题

1. 为什么 dev 模式**不打包**也能跑（浏览器 import /src/App.vue 时是谁在回话）？而 build 后必须打包？（提示：dev 是"按需现烤"，浏览器能 native import 不了 .vue 这种非标准模块——Vite's 插件层是不是已经把 .vue 烤成 js？）
2. `try_files $uri /train-ui/index.html` 为什么是 SPA 必备？删掉它会坏什么场景（路由刷新 404 之类）？
3. nginx `nginx -s reload` 与 `kill` 后重启的差别：重新在访问高峰做构建+reload 为什么**无感**（master/worker 架构一句话）？

## 练习题

1. 跑通"三连实验"全部三步，并在 `http://127.0.0.1:9090/train-ui/` 与 `http://127.0.0.1:5180/` 两个端口对比访问同一功能（Network 面板里记录两边请求 URL 的差异）。
2. 修改 train-ui 的 vite.config.js：把 dev 端口 5180 换 5200、并把 proxy 换一个目标端口——重跑 dev 验证生效（这是"配置型可配"的直接练习）。
3. 给 takeout-ui 也跑一遍 build 与 Nginx 挂载（nginx-reload.sh 已替你理好），再用 `curl` 记录 200/请求处理差；再删除 dist 后重跑 nginx-reload.sh，观察它**自动重建**的能力。

## 参考答案

**练 1**（两端口真实对照样本）：

| 面 | dev (5180) | build 后 (9090) |
|---|---|---|
| html 来处 | Vite 内存的"实时模板" | 磁盘 dist/index.html |
| js 请求 | 一堆 `/src/*.vue` 烤好的 ESM | 一个 `assets/index-hash.js` |
| API | `/api/trips`（Vite proxy →8084） | `/apitrain/trips`（nginx 反代→8084/api/） |
| 改源码 | HMR 立刻生效 | 不动（除非重建） |

**练 2**：

```js
server: {
  port: 5200,                                   // 端口可换
  proxy: { '/apitrain': { target: 'http://127.0.0.1:8084', rewrite: p => p.replace(/^\/apitrain/, '/api') } }  // 指向不变也一样能跑
}
```

`npm run dev` 后输出 `Local: http://localhost:5200/`；点接口 Network 里仍是 `/api/trips`——**proxy 只是 dev 期便利，不进产物**。

**练 3**：`cd frontend/takeout-ui && npm run build` → `bash infra/nginx-reload.sh`
→ `curl -o /dev/null -w '%{http_code}\n' http://127.0.0.1:9090/takeout-ui/`（200）。
脚本开头"dist 缺就现 build"的 if 分支正是自愈逻辑——全删后跑一次 nginx-reload.sh，
Console 能看到 "构建前端 takeout-ui ..."，构建完 nginx reload，站点重新上线。

## 本节小结
- **dev（Vite）**：按需现烤+HMR，快源于"不打包"；`vite.config.js` 的 proxy 让前后端各自独立.run。
- **build（Rollup）**：合成 1 个 hash 主包 + 瘦 index.html；tree-shake+minify 后浏览器只吃 2 个文件。
- **部署（Nginx）**：`alias` 挂 dist、`try_files` 兜 SPA 路由、`proxy_pass` 反代 API——**同一页面代码两侧跑通**（5180 与 9090 只是两套"桥"）。
- 本站的真实全链：`build.sh → start-all.sh（含 nginx-reload.sh）→ smoke.py 四处 nginx 断言`——**代码改哪段，从 dev 到 prod 都知道去哪个文件看**。
- "dist 是 JS 世界的 jar"——理解了这比喻，B 篇六步和你写 Maven 的 A07 就在**同一条思维线**上闭环了。
- 工程链上别记混的两个端口：**5180 dev（Vite）**，开发自用不对外；**9090 Nginx**，正式门口——排错第一步永远是"我现在连的是哪一侧"。

## 下一站

B 篇六步（B01-B06）至此全通。下一站进入主轴 S 系教程：Spring Boot 内核 14 篇（S01-IoC//DI、S02-MVC 路由与参数、S05-REST、S06-JPA……），从本站 train/takeaway 的真代码里逐件拆解。本站的另两条终篇线（N 系 Nginx 部署、W 系全链对账）也会把今天学的"dist 部署"与"nginx 反代"推到可对账的深处。
