# Java速通 A07 · Maven 实战：从单模块到多模块

> **本站你走在大厅哪一格**：上篇懂了"注解 + 处理器"，本篇懂 Java 世界的**包管理 + 构建中枢**。
> 你在 C++ 装过依赖、Python 拉 pip 包，本篇让你像看 `requirements.txt` 一样看 `pom.xml`，
> 并亲自走查本项目的真实 pom 树：**1 个 root + 5 个模块，reactor 一键构建 5 个 jar**。

## 名词卡

| 名词 | 人话 |
|---|---|
| POM | Project Object Model。项目档案（依赖、插件、父子结构）——xml 写的 npm package.json |
| 坐标（GAV） | groupId:artifactId:version，Maven 世界的"身份证号" |
| reactor | 多模块仓库里，Maven 会按依赖关系**排序所有子模块**并一次性构建的"目录总指挥" |
| parent（父 POM） | 沉到上层公共配置，子 POM 自动继承 |
| BOM | 依赖版本清单 bill of materials。spring-boot-starter-parent 就是个巨型 BOM |
| 传递依赖 | A 依赖 B，B 又拉 C——C 是 A 的传递依赖 |
| scope | 依赖在什么阶段可用：compile / runtime / test / provided |

## 一、人话：Maven 到底替你干三件事

1. **拉依赖**：pom 里写 GAV 坐标，Maven 去中央仓库下载（类似 npm/pip）。你写过一行、它下载几百个。
2. **yfy统一版本号**：Spring Boot 那一万个 spring-xxx 相互版本必须配套——spring-boot-starter-parent 用一张"官方菜单"（BOM）替你锁定版本，你写依赖时连版本号都可以省。
3. **标准化构建**：`mvn package` 按生命周期 compile → test → package 走一遍，产出 jar。不用自己写 Makefile。

对照 npm：`pom.xml ≈ package.json`、`~/.m2 ≈ node_modules`（但 Maven 是全局缓存）、
`mvn clean ≈ rm -rf dist`、`spring-boot-starter-parent ≈ 有一群官方维护者帮你 pin 死所有版本号`。

## 二、真实代码走查：本项目的 pom 树

### 2.1 root：backend/pom.xml（总指挥）

```xml
<groupId>com.javaweb</groupId>
<artifactId>javaweb-root</artifactId>
<version>1.0.0</version>
<packaging>pom</packaging>          <!-- 本身不是 jar，只是"目录清单+公共配置" -->

<parent>                            <!-- ← 认 Spring Boot 当爹 -->
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.5.4</version>
</parent>

<modules>                           <!-- ← 5 个子模块，reactor 成员 -->
  <module>demo-todo</module>
  <module>demo-chat</module>
  <module>demo-counter</module>
  <module>train</module>
  <module>takeaway</module>
</modules>

<properties>
  <java.version>21</java.version>
</properties>
```

`<parent>` 认 spring-boot-starter-parent 做爹 = 你的 5 个模块从此只写依赖 groupId/artifactId，
**版本号一概省略**——因为爹的 BOM 全锁好了。

实际项目里 root 是 backend/pom.xml（backend/pom.xml:12-17）。
上块我写的是精简示意，注意版号字段别照抄——以真文件为准。

### 2.2 子模块：backend/train/pom.xml

```xml
<parent>
  <groupId>com.javaweb</groupId>
  <artifactId>javaweb-root</artifactId>   <!-- 儿子认儿子对了爹 -->
  <version>1.0.0</version>
</parent>
<artifactId>train</artifactId>            <!-- 只写自己的名字，version 从爹继承 -->
```

依赖列表（train/pom.xml:15-58）毫无版本号——除了 jjwt 三件套（项目显式写了 0.12.6，
因为那不在 Spring Boot BOM 里）。

注意两处 scope：

```xml
<dependency>
  <groupId>com.h2database</groupId><artifactId>h2</artifactId>
  <scope>runtime</scope>       <!-- 只在运行期进 classpath：编译期代码从不 import H2 的类 -->
</dependency>
```

H2 数据库驱动只在运行期由 JDBC 加载，所以 scope=runtime；如果 scope 写 test，
生产跑 jar 时 H2 会缺包直接 `ClassNotFoundException`。

### 2.3 reactor 构建一次成型

```bash
cd backend && mvn -T 4 clean package
```

Maven 干两件事：

1. **拓扑排序**：5 个模块互不依赖，顺序随意；若 A 依赖 B 则 B 必须先构建。
2. **并发执行**：`-T 4` 用 4 线程并行流水。infra/build.sh 里跑的就是这条。

产物一路在 `backend/<module>/target/*.jar`。`spring-boot-maven-plugin`
（train/pom.xml:60-67）负责把普通 jar "膨胀"成**可独立 `java -jar` 运行的胖 jar**——
它把所有 jar 依赖打进去。
所以 start-all.sh 里 `java -jar backend/train/target/train-1.0.0.jar` 直接拉起服务。

## 三、依赖冲突 Top-3 排查（90% 的人栽过）

### 坑 1：版本被覆盖而不自知

mvn 中央仓库里 spring-core 可能被十个 jar 从不同版本拉进来。Maven 的默认策略：**离根越近/路径越浅者胜**。

```bash
cd backend && mvn -pl train dependency:tree | grep -i "spring-core\|h2\|jackson"
mvn -pl train dependency:tree -Dverbose | grep '\(omitted\|conflict\)'
```

看到 `omitted for conflict with 6.1.0` 就是"这个版本被吃掉了"。

### 坑 2：scope 写错导致运行期崩溃

有人把 mysql-connector 写成 `<scope>test</scope>`，本地开发无感，部署 `java -jar` 直接炸 `ClassNotFoundException: com.mysql.cj.jdbc.Driver`。

### 坑 3：传递依赖携带无关 web 服务器

某次要打 CLI 工具却发现来了 tomcat，查找罪魁：

```bash
mvn dependency:tree | grep -B3 tomcat
```

顺手 `<exclusions>` 剔掉，或用 `mvn dependency:analyze` 看有没有 declared-but-unused。

> **三招必背**：`dependency:tree`（看清单）、`-Dverbose`（看冲突被吃没）、`-pl <module>`（只看某子模块）。

### 附：什么是"最近者优先"，拿本项目做沙盘

假设 train pom 里直接声明了 `jjwt-jackson:0.12.6`，而某传递依赖又拉来 `jjwt-api:0.11.0`：

```
train
 ├── jjwt-jackson:0.12.6          ← 路径深度 1（你亲手写的）
 └── some-lib:2.0
      └── jjwt-api:0.11.0         ← 路径深度 2（传递来的）
```

Maven 不会像 pip 那样装两份，也不会报错——它按"**最近者优先**"选 0.12.6，把 0.11.0 标记为
`omitted for conflict with 0.12.6`。这条规则保证了构建可预测，但代价是：**你看到的 jar 不一定是你以为的版本**。
所以任何"本地能跑/线上炸、或者反过来"的玄学问题，第一反应都是打一次 verbose 树看被吃掉的是谁。

### 附：spring-boot-starter-parent 到底"继承"来了什么

打开它自己也是一层层 parent（starter-parent → spring-boot-dependencies）：

| 继承物 | 对你的直觉影响 |
|---|---|
| 一整张 BOM（200+ 个 version 属性） | 你写依赖不用写版本号 |
| `<maven.compiler.release>21</release>` 等 compiler 配置 | A01-A05 里"为什么我啥都没配就编译过了" |
| spring-boot-maven-plugin 的 repackage 配置 | 普通 `mvn package` 出来就是能 `java -jar` 的胖 jar |
| 内置资源过滤 | `application.yml` 里 `${app.port}` 之类的占位符才有机会生效 |
| 测试框架的默认版本对齐 | junit/mockito 版本互相兼容 |

这也解释了 train/pom.xml 为什么只有 68 行：**68 行里大约 40 行是依赖清单，10 行是 plugin 声明**
——其余配置全部从 parent 白嫖。多模块的价值就在这里：这份"白嫖清单"五个模块共用一份。

## 四、动手验证：三步给 train 模块加 MySQL 驱动

**一句话加依赖（在 backend/train/pom.xml 的 `<dependencies>` 末尾插入）**：

```xml
    <dependency>
      <groupId>mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
      <scope>runtime</scope>
    </dependency>
```

版本号也能省——Spring Boot 3.5.4 的 BOM 里就替你锁定好了（可跑下面命令自行确认）。

**验证三连**：

```bash
cd backend
mvn -pl train dependency:tree -Dincludes=:mysql-connector-j
# 预期出现：mysql-connector-j:8.x
mvn -pl train package
unzip -l train/target/train-1.0.0.jar | grep mysql
# 预期：mysql-connector-j-8.x.jar 已在 BOOT-INF/lib 里（胖 jar 把它打包了）

ls backend/train/target/train-1.0.0.jar   # trivial 检查 jar 还在（本机已先行构建过）
```

` BOOT-INF/lib` 就是 spring-boot-maven-plugin 干的文字游戏：把所有普通依赖放进 jar 内 BOOT-INF/lib 目录，
`java -jar` 时自建的 loader 会从那里加载。所以**"fat jar 是能不能独立跑的关键"**。

## 五、生命周期与常用命令：一行命令对照表

Maven 的"生命周期"是一条固定流水线，每个目标（goal）执行时会把它**前面所有阶段**都跑一遍：

```
validate → compile → test → package → verify → install → deploy
```

所以 `mvn package` 隐含 compile + test；`mvn install` = package + 存到本地 `~/.m2` 供他人引用。

| 你想干的事 | 命令 | 本项目实测备注 |
|---|---|---|
| 编译 5 个模块 + 前端 | `bash infra/build.sh` | 内部封装 `mvn -T 4 -DskipTests package` |
| 只重编 train | `mvn -T 4 -pl train -DskipTests package` | 改一行 Java 重打包的最快路径 |
| 清理（废弃 target） | `mvn clean` | 约等于 `rm -rf */target` |
| 下载/梳理依赖树 | `mvn dependency:tree` | 排冲突第一招 |
| 看 spring-core 版本被谁锁的 | `mvn help:effective-pom -pl train` | 打印"继承合并后的最终 pom"，parent 白嫖清单一览无余 |
| 出 5 个 jar 后看真身 | `unzip -l */target/*.jar | grep lib/` | 每个胖 jar 的 BOOT-INF/lib 都有几十个依赖 |

**离线/加速技巧**：`-o`（offline，已下载离线跑）、`-DskipTests`（编译时不跑测试）、
`-q`（安静模式只报错）。infra/build.sh 就是 `mvn -q -T 4 -DskipTests package` 全套用上。

### 多模块为什么值得：单模块 vs 多模块对比

| 维度 | 单模块（一个大 pom） | 多模块（本项目） |
|---|---|---|
| 一个仓库装 3 个 demo + 2 个大案例 | 所有代码挤一个 jar，`java -jar` 起来带全部无关代码 | 每个服务一个 jar，端口 8081-8085 各跑各的 |
| 版本/Java 版本统一 | 各自维护五份 settings 容易漂移 | root 的 properties 一处改五处生效 |
| 按需部署 | 无 | backend/train/target/train-1.0.0.jar 只装 train |
| 改 demo-chat 只重编谁 | 全仓库 | `mvn -pl demo-chat` |

也就是说，多模块不是炫技，而是"**一个仓库像一个组织，reactor 像大楼的电梯调度**"：
你按一下 3 层（train），电梯只带你去 train，不会顺路把外卖（takeaway）也撞一遍。

### 和 npm 的全家桶对照表（记得住这页就够了）

| Maven | npm | 说明 |
|---|---|---|
| `pom.xml` | `package.json` | 项目档案 |
| `~/.m2/repository` | 全局 npm cache / node_modules | 依赖落地处（Maven 全局共享，npm 每项目装） |
| `mvn package` | `npm run build` | 构建 |
| BOM / parent | `overrides` / lockfile | 锁版本手段 |
| reactor / modules | workspace packages | monorepo 多包构建 |

## 思考题

1. 把 `<parent>` 从 spring-boot-starter-parent 换成自写的 parent，会导致哪些版本问题？至少说出两类。
2. `<packaging>pom</packaging>` 的模块被执行 `mvn package` 时会产什么？为什么 modules 里的 5 个不写成 packaging=pom？
3. `-pl train` 与 `-am`（also-make，把依赖它的兄弟也构建）有啥区别？什么场景两个都得加？

## 练习题

1. 在 backend 目录跑 `mvn dependency:tree -pl train | grep -i "h2"`，记录 H2 由哪几个 artifact 引入（大概 2~3 条 path），并解释为什么"compile 的引用方"和"runtime 的 H2"位置不同。
2. 给 takeaway 也加一条 mysql-connector-j，验证两个模块都打出了含 mysql 的胖 jar。
3. 改 backend/pom.xml 把 `<module>demo-todo</module>` 临时注释，重新 `mvn -q package`，
   记录输出数字变化（构建了几个 jar）并解释 reactor 是"漏斗队列"还是"并行大汇聚"。

## 参考答案

**练 1**：H2 只有一条路径：spring-boot-starter-parent → spring-boot-dependencies（BOM）→ spring-boot-dependencies（BOM）直接管理 h2，节点旁边常标注 (runtime)。阅读要点：parent 的 BOM 管"版本号"，管不了"scope"——scope 仍以你 pom 里写的 runtime 为准；编译期拿不到它（代码不该 import H2 类），package 时进 jar 的 BOOT-INF/lib。对照同名 compile 依赖的差别：scope 让"进不进编译 classpath / 进不进胖 jar"分层。

**练 2**（同 A07 第四节"给 train 加 mysql"四步，把 -pl train 换成 -pl takeaway 即可，产物 takeaway-1.0.0.jar 在 `takeaway/target/takeaway-1.0.0.jar`，unzip -l 同样能看到 BOOT-INF/lib/mysql-connector-j-*.jar）。

**练 3**：注释 demo-todo 后 reactor 成员变 4，`mvn package` 只构建 4 个 jar，demo-todo/target 下保留旧 jar 不再更新。reactor 是**拓扑序队列**：按依赖图谱排好序再逐个（可并行）跑——不是"漏斗"也不是"汇聚"，是"调度器"。

## 本节小结
- Maven 三件事：拉依赖（GAV 坐标）、锁版本（parent/BOM/版本一致性）、标准化构建（生命周期与插件）。
- 本项目 root（packaging=pom + parent=spring-boot-starter-parent + modules×5）→ 子模块只写自家依赖与版本声明可省。
- backend/build.sh 一次 `-T 4` 并行构建 5 个 jar + 2 个前端 dist：调的就是 reactor。
- 排冲突三招：`dependency:tree` / `-Dverbose` / `-pl`；scope 写错最阴——test 不进 jar、runtime 不给编译用。

## 下一站

下一站 A08 并发基础：线程池 / `Runnable` / `Atomic*`。在本项目的 demo-chat 里你已经有一段"多客户端并发"的真实代码在等你——`CopyOnWriteArrayList` 为何比 synchronized 更适合 SSE 广播（demo-chat/ChatController）。
