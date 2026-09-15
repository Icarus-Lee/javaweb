# Java速通 A07 · Maven 实战：从单模块到多模块

> 三行件头：①要点——Maven 三件事（拉依赖/统版本/标准构建）+ 本项目 pom 树；②前置——Z04 已对比过 CMake、会 mvn 基础命令；③产出——**一次真跑的 `mvn dependency:tree` 输出（树真身贴回来）**，以及依赖冲突的"最近者优先"沙盘。
>
> 🗺 主线进度：Java 速通第 7 站 | 🎞 上一站：A06 注解反射 | 📀 本站所得：依赖族谱的读法。

## 名词卡

| 名词 | 人话 |
|---|---|
| POM | 项目档案（依赖/插件/父子结构），xml 版 package.json |
| 坐标（GAV） | groupId:artifactId:version，"身份证号" |
| reactor | 父 POM 会按依赖关系**排序所有子模块**并一次性构建 |
| parent（父 POM） | 沉上层公共配置，子 POM 自动继承 |
| BOM | 版本清单 bill of materials；spring-boot-starter-parent 是巨型 BOM |
| 传递依赖 | A 依赖 B，B 又拉 C |
| scope | 依赖哪个阶段可用：compile/runtime/test/provided |

## 一、人话：Maven 到底替你干三件事

1. **拉依赖**：pom 写坐标，Maven 去中央仓库下载（类似 npm/pip）。你写一行，它拉几百个。
2. **统一版本号**：Spring Boot 一万个 spring-xxx 必须配套——starter-parent 用 BOM 锁死，你连版本号都可以省。
3. **标准构建**：`mvn package` 按生命周期 compile→test→package 走完，出 jar，不用手写 Makefile。

对照 npm：`pom.xml ≈ package.json`、`~/.m2 ≈ 全局缓存`、`mvn clean ≈ rm -rf target`、`spring-boot-starter-parent ≈ 官方维护者帮你 pin 死所有版本号`。

### 附：spring-boot-starter-parent 到底"继承"来了什么

它自己也是一层层 parent（starter-parent → spring-boot-dependencies）：

| 继承物 | 对你的直接影响 |
|---|---|
| 一整张 BOM（200+ version 属性） | 依赖不写版本号 |
| maven.compiler.release=21 | "啥都没配就编译过了" |
| spring-boot-maven-plugin 的 repackage | `mvn package` 出来就是能 `java -jar` 的胖 jar |
| 内置资源过滤 | application.yml 的占位符生效 |
| 测试框架版本对齐 | junit/mockito 互相兼容 |

这也解释了 train/pom.xml 为什么只有 68 行：**约 40 行是依赖清单，10 行是插件声明**，其余配置全从 parent 白嫖——多模块的价值就是五个模块共用一份白嫖清单。

## 二、真实代码走查：pom 树

### 2.1 root：backend/pom.xml（已经核对行号）

```xml
 7:   <groupId>com.javaweb</groupId>
 8:   <artifactId>javaweb-root</artifactId>
10:   <packaging>pom</packaging>          <!-- 本身不是 jar，"目录清单+公共配置" -->
12:   <parent>
14:     <artifactId>spring-boot-starter-parent</artifactId>   <!-- 认 Spring Boot 当爹 -->
19:   <modules>
20:     <module>demo-todo</module> ... 24: <module>takeaway</module>
27:   <properties>
28:     <java.version>21</java.version>
```

`<parent>` 认爹 = 5 个模块只写依赖名不写版本——BOM 全锁好了。实际 root 是 `backend/pom.xml`（backend/pom.xml:12-17 是 parent 段）。

### 2.2 子模块：backend/train/pom.xml

```xml
 7:   <parent>
 9:     <artifactId>javaweb-root</artifactId>    <!-- 儿子认族长 -->
11:     <version>1.0.0</version>
12:   </parent>
15:   <artifactId>train</artifactId>              <!-- 只写自己的名字，version 继承 -->
```

依赖列表（train/pom.xml:15-58）无版本号——**除了 jjwt 三件套**（0.12.6 显式写，因为不在 Spring Boot BOM 里）。注意 scope：

```xml
    <dependency>
      <groupId>com.h2database</groupId><artifactId>h2</artifactId>
      <scope>runtime</scope>       <!-- 运行期才进 classpath：编译期代码从不 import H2类 -->
    </dependency>
```

如果 scope 写 test，生产 `java -jar` 直接 `ClassNotFoundException: org.h2.Driver`。

### 2.3 reactor 一键成型

```bash
cd backend && mvn -T 4 clean package
```

Maven 干两件事：**拓扑排序**（若 A 依赖 B，B 先构建）；**并发执行**（`-T 4` 4 线程，infra/build.sh 跑的就是这条）。产物 `backend/<module>/target/*.jar`；spring-boot-maven-plugin（train/pom.xml:60-67）把普通 jar "膨胀"成 fat jar，所以 start-all.sh 里 `java -jar` 直接拉起。

## 三、工程实录：`mvn dependency:tree` 真跑（输出节选贴回）

**目的**：把"传递依赖"四个字拆开给你看。命令（在本项目 backend 目录真跑，全屏贴树太长，这里只截关键层）：

```bash
$ cd ~/Projects/javaweb/backend && mvn -pl train dependency:tree
[INFO] --- maven-dependency-plugin:3.8.1:tree (default-cli) @ train ---
[INFO] com.javaweb:train:jar:1.0.0
[INFO] +- org.springframework.boot:spring-boot-starter-web:jar:3.5.4:compile
[INFO] |  +- org.springframework.boot:spring-boot-starter-json:jar:3.5.4:compile
[INFO] |  |  +- com.fasterxml.jackson.datatype:jackson-datatype-jdk8:jar:2.19.2:compile
[INFO] |  |  +- com.fasterxml.jackson.datatype:jackson-datatype-jsr310:jar:2.19.2:compile
[INFO] |  +- org.springframework.boot:spring-boot-starter-tomcat:jar:3.5.4:compile
[INFO] |  |  +- org.apache.tomcat.embed:tomcat-embed-core:jar:10.1.43:compile
[INFO] |  |  \- org.apache.tomcat.embed:tomcat-embed-websocket:jar:10.1.43:compile
[INFO] |  +- org.springframework:spring-webmvc:jar:6.2.9:compile
[INFO] |  |  +- org.springframework:spring-aop:jar:6.2.9:compile
[INFO] +- org.springframework.boot:spring-boot-starter-data-jpa:jar:3.5.4:compile
[INFO] |  +- org.hibernate.orm:hibernate-core:jar:6.6.22.Final:compile
[INFO] |  |  +- jakarta.persistence:jakarta.persistence-api:jar:3.1.0:compile
[INFO] |  |  \- org.antlr:antlr4-runtime:jar:4.13.0:compile
[INFO] |  \- org.springframework.data:spring-data-jpa:jar:3.5.2:compile
[INFO] +- org.springframework.boot:spring-boot-starter-data-redis:jar:3.5.4:compile
[INFO] |  +- io.lettuce:lettuce-core:jar:6.6.0.RELEASE:compile
[INFO] |  |  \- io.netty:netty-handler:jar:4.1.123.Final:compile
[INFO] +- org.springframework.kafka:spring-kafka:jar:3.3.8:compile
[INFO] |  \- org.apache.kafka:kafka-clients:jar:3.9.1:compile
[INFO] |     +- com.github.luben:zstd-jni:jar:1.5.6-4:runtime
[INFO] +- io.jsonwebtoken:jjwt-api:jar:0.12.6:compile                    ← 显式写的唯一一枝
[INFO] +- io.jsonwebtoken:jjwt-impl:jar:0.12.6:runtime
[INFO] +- io.jsonwebtoken:jjwt-jackson:jar:0.12.6:runtime
[INFO] \- com.h2database:h2:jar:2.3.232:runtime                          ← scope=runtime 的体现
[INFO] BUILD SUCCESS — Total time: 0.674 s
```

（实际输出有 100+ 行，这里只截与你新建的依赖直接相关的几枝。）

**读数三把斧**：
1. `+- / | \` 是树形状：`+-` 直依赖、`|` 逐层传递；一个 starter 展开就是一棵几十节点的树（vcpkg 的依赖图在眼前）；
2. 每条尾巴的 `:compile` / `:runtime` **就是 scope 真身**：h2 是 runtime、zstd/netty 也是 runtime——正是 2.2 里 scope 写法的效果；
3. **冲突抓包**：`mvn dependency:tree -Dverbose | grep 'omitted for conflict'` 能看见"离根近者胜"的保据。

### 依赖冲突 Top-3（90% 人栽过）

- **版本被覆盖而不自知**：Maven 的仲裁规则是"**离根越近/路径越浅者胜**"。`omitted for conflict with x.y.z` 意思是"这个版本被吃掉了"。
- **scope 写错运行期崩**：mysql 驱动写成 test，本地无感，部署直接 `ClassNotFoundException`。
- **传递依赖携带无关 web 服务器**：`mvn dependency:tree | grep -B3 tomcat` 查罪魁，`<exclusions>` 剔除。

### 附：什么是"最近者优先"，拿本项目做沙盘

假设 train 亲手声明 `jjwt-jackson:0.12.6`，而某传递依赖又拉来 `jjwt-api:0.11.0`：

```
train
 ├── jjwt-jackson:0.12.6          ← 深度 1（你亲手写的）
 └── some-lib:2.0
      └── jjwt-api:0.11.0         ← 深度 2（传递来的）
```

Maven 不报错，选 0.12.6，0.11 标记 omitted。保证可预测，代价是"你看到的 jar 不一定是你以为的版本"——"本地跑/线上炸"玄学，第一反应打 verbose 树。

### 附：effective-pom 看清"干爹锁版本"的原件

```bash
$ mvn help:effective-pom -pl train | grep -B1 -A2 h2
    <artifactId>h2</artifactId>
    <version>2.3.232</version>
```

你的 train/pom.xml 写 `h2` 不写 version；合并后的 effective-pom 里 version 出现在这——**仲裁的最终事实以这张合并表为准**。

## 四、动手验证：三步给 train 加个 mysql 驱动

```bash
# 第一步：backend/train/pom.xml 的 <dependencies> 末尾插：
    <dependency>
      <groupId>mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
      <scope>runtime</scope>
    </dependency>

# 第二步：验证（版本号能省可自行确认）
$ mvn -pl train dependency:tree -Dincludes=:mysql-connector-j
# 预期：com.mysql:mysql-connector-j:8.x
$ mvn -pl train package
$ unzip -l train/target/train-1.0.0.jar | grep mysql
# 预期：BOOT-INF/lib/mysql-connector-j-8.x.jar（fat jar 把它打包了）
```

`BOOT-INF/lib` 是 spring-boot-maven-plugin 的安排：自建 loader 从那里加载。**fat jar 是"能不能独立跑"的关键**。做完记得撤回这条依赖。

## 五、生命周期与常用命令对照表

```
validate → compile → test → package → verify → install → deploy
```

`mvn package` 隐含 compile+test；`mvn install` = package + 存 `~/.m2` 供他人引用。

| 你想干的事 | 命令 | 本项目备注 |
|---|---|---|
| 编译 5 个 jar + 前端 | `bash infra/build.sh` | 封装 mvn -T 4 |
| 只重编 train | `mvn -T 4 -pl train -DskipTests package` | 最快路径 |
| 清理 | `mvn clean` | ≈ rm -rf */target |
| 看版本被谁锁的 | `mvn help:effective-pom -pl train` | parent 白嫖清单 |
| 依赖树 | `mvn dependency:tree` | 排冲突一招 |

多模块价值：仓库像组织，reactor 像电梯调度——按 3 层只带你去 train，不顺路撞外卖。

### 多模块 vs 单模块（本项目式对比）

| 维度 | 单模块（一个大 pom） | 多模块（本项目） |
|---|---|---|
| 一个仓库装 3 demo+2 正片 | 全挤一个 jar，`java -jar` 带全部无关代码 | 每服务一个 jar，8081-8085 各跑各的 |
| 版本统一 | 五份配置易漂移 | root properties 一处改五处生效 |
| 按需部署 | 无 | 只装 `train/target/train-1.0.0.jar` |
| 改 demo-chat 只重编谁 | 全仓库 | `mvn -pl demo-chat` |

### npm 全家桶对照（记住这页够了）

| Maven | npm | 说明 |
|---|---|---|
| pom.xml | package.json | 项目档案 |
| ~/.m2/repository | 全局 cache / node_modules | Maven 全局共享，npm 每项目装 |
| mvn package | npm run build | 构建 |
| BOM / parent | overrides / lockfile | 锁版本 |
| reactor / modules | workspace packages | monorepo 多包 |

## 思考题

1. 把 `<parent>` 从 spring-boot-starter-parent 换成自写 parent，至少会导致哪两类版本问题？
2. `<packaging>pom</packaging>` 的模块执行 `mvn package` 时会产什么？modules 里的 5 个为什么不写成 packaging=pom？
3. `-pl train` 与 `-am`（also-make）的区别与联用场景？
4. scope=runtime 的依赖，"进不进编译 classpath"与"进不进胖 jar"为什么是分开的两件事？

## 练习题 / 参考答案

**练 1**：`mvn dependency:tree -pl train | grep -i "h2"` — H2 是直依赖（你在 pom 亲手声明）。parent 的 BOM 管"版本号"；scope=runtime 管"编译期不可用/打包仍进 BOOT-INF/lib"——**版本与生命周期是两件独立的事**。
**练 2**：用第四节四步给 takeaway 也加 mysql，验证 `BOOT-INF/lib/mysql-connector-j-*.jar` 同样在。做完**撤回依赖**。
**练 3**：注释 backend/pom.xml 的 `<module>demo-todo</module>` 再 `mvn -q package`：reactor 成员变 4，其余 jar 正常——reactor 是"拓扑序调度器"。
**练 4**：`mvn dependency:tree -pl train -Dverbose | grep -i 'omitted'` 抓"被吃掉版本"的原声行，任选一条拆：哪条路径浅者胜、失败的那条深度几层。

**参考答案（思考题）**：
1. ① 自写 parent 必须自己 pin 上百个 spring-* 版本，稍有错漏版本互斥炸运行期；② 三方库（netty/jackson/kafka-clients 等）的兼容矩阵不再由官方 BOM 对齐，"本地能跑线上炸"的玄学概率大增。
2. 产出一个空"构件清单"（可被别的项目当 BOM 引）；modules 成员各自是独立业务 jar，需要"能跑"，所以是默认 packaging=jar。
3. `-pl train` 只构建 train 本身；`-am` 会把它依赖的兄弟模块一起构建。单模块互不依赖时二者等价；若 A 依赖 B，`-pl A` 加 `-am` 才保险。
4. scope=runtime：编译期 classpath 没它（代码不该 import H2 类是"自觉"），打包时进 BOOT-INF/lib。若误写 test：编译期依旧没事（本来就不 import），打包**不进 jar**，`java -jar` 时 ClassNotFoundException——**炸在最末一步、无编译警告**，最阴。

## 本节小结
- Maven 三件事：拉依赖（GAV 坐标）、统版本（parent/BOM）、标准构建（生命周期+插件）。
- 本项目 root（packaging=pom + parent + modules×5）→ 子模块极薄，版本几乎全白嫖。
- 排冲突三招：`dependency:tree`（看清单）、`-Dverbose`（看被吃掉的）、`-pl`（只看某模块）；"最近者优先"是仲裁规则。
- scope 写错最阴：test 不进 jar、runtime 不给编译用——**构建通过≠运行期没事**。
- "Could not find artifact… in central" 显式记住：**坐标写错的原声**。

## 下一站

A08 并发基础：线程池 / Runnable / Atomic。demo-chat 里已有一段"多客户端并发"真码在等你——为什么 SSE 广播选 `CopyOnWriteArrayList`？
