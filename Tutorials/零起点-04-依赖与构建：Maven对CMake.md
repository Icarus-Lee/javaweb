# 零起点-04 · 依赖与构建：Maven 对 CMake

> C++ 工程里你写过 CMakeLists.txt、装过 vcpkg/conan、纠结过静态库动态库。Java 世界把这些揉成一个工具：Maven。这一站用"C++ 老惯例对照表"讲清它，并走查本项目的多模块骨架。

---

## 🎬 开篇三件套

### 1️⃣ 本站你走在大厅哪一格

影视城的场馆（5 个后端 jar）都不是凭空变出来的——它们在**道具车间**（Maven）里按图纸（pom.xml）组装：从中央仓库领材料（依赖），按工序（生命周期）加工，最后打包成能上场的道具（jar）。本站你站在道具车间门口，看懂图纸。

### 2️⃣ 本站任务单

1. Maven 是什么：依赖管理 + 多模块 + 生命周期三合一；
2. 建立"C++ 工程惯例 → Maven 对应物"的完整映射；
3. 读懂 `backend/pom.xml` 父 POM 与 `demo-todo/pom.xml` 子模块；
4. 分清 Maven 与 npm 的异同；
5. 会跑 `mvn package` 并找到产出的 jar。

### 3️⃣ 开工前自查

- [ ] 知道 `java -jar xxx.jar` 是启动命令（Z03 见过）
- [ ] 本机有 `mvn -v` 能出版本
- [ ] 概念上知道"库/头文件/链接"是什么

---

## 🗂 本站名词卡

| 名词 | 人话解释 | C++ 世界对应 |
|---|---|---|
| **POM** | Project Object Model，`pom.xml` 描述"项目要什么" | CMakeLists.txt |
| **坐标（GAV）** | groupId:artifactId:version 三元组，全世界唯一标识一个库 | 库名 + 版本号 |
| **中央仓库** | Maven Central，公网上的巨大依赖库 | vcpkg/conan 的包源 |
| **传递依赖** | 你要 A，A 要 B，Maven 自动把 B 也拉来 | 手动 apt install 依赖链 |
| **多模块（reactor）** | 一个父 POM 管一串子模块，一条命令全构建 | CMake 的 add_subdirectory |
| **生命周期** | 固定工序：validate→compile→test→package→install | 手写的一串 make target |
| **BOM/parent** | 父 POM 统一定义依赖版本，子模块只写名字 | 根 CMakeLists 统一 set 版本 |
| **fat jar** | 把代码+所有依赖打进一个可执行 jar | 静态链接出的单文件可执行 |
| **npm** | Node.js 的包管理器（前端用），Maven 的 JS 表亲 | conan + CMake 合体 |

---

## 🧠 概念人话：C++ 老惯例对照表

| 你在 C++ 里的习惯 | Maven 里的对应物 | 差异要点 |
|---|---|---|
| 手写 CMakeLists 找库 | `pom.xml` 里声明 `<dependency>` | Maven 声明式：只说"要什么"，不说"去哪找、怎么编" |
| apt/vcpkg 装库到系统 | 依赖装进用户目录 `~/.m2/repository` | 不污染系统，每个项目版本独立 |
| 头文件路径、链接顺序 | 不存在！jar 里自带字节码和元数据 | 没有分 headache：编译期/链接期统一由 Maven 管 |
| make / ninja 增量编译 | `mvn compile` 自动增量 | 生命周期固定，不用自己写 target 依赖图 |
| 静态/动态链接的选择 | 打包方式：瘦 jar / fat jar | Spring Boot 默认 fat jar，一个文件全带走 |
| git submodule 引子工程 | `<modules>` 多模块 reactor | 父 POM 一次调度全部子模块 |
| 版本冲突地狱 | 最近的声明获胜 + dependencyManagement 统一 | 仍有冲突但可控，`mvn dependency:tree` 查族谱 |

**一句话：Maven = CMake（构建）+ vcpkg（依赖）+ 约定优于配置（目录结构）**。Java 项目的目录结构几乎是宪法：`src/main/java` 放代码、`src/main/resources` 放配置、`target/` 放产物——Maven 约定好了，CMake 里你要自己 set。

### npm 对照（为 B 系列 Vue 教程打底）

| Maven | npm |
|---|---|
| pom.xml | package.json |
| ~/.m2/repository | node_modules/（就在项目里！） |
| mvn package | npm run build |
| Maven Central | npm registry |
| 传递依赖 | 同样有，且更容易版本地狱 |

最大差异：npm 把依赖装进**项目目录**的 node_modules（所以经常巨肥、要 gitignore）；Maven 装进**用户主目录**的 ~/.m2，项目间共享。

---

## 🔍 真实代码走查

### 父 POM：backend/pom.xml（31 行）

```xml
 7:   <groupId>com.javaweb</groupId>
 8:   <artifactId>javaweb-root</artifactId>
 9:   <version>1.0.0</version>
10:   <packaging>pom</packaging>          ← 父 POM 自己不产 jar，只当"族长"

12:   <parent>
13:     <groupId>org.springframework.boot</groupId>
14:     <artifactId>spring-boot-starter-parent</artifactId>
15:     <version>3.5.4</version>          ← 认 Spring Boot 当干爹
16:     <relativePath/>
17:   </parent>

19:   <modules>                            ← 多模块清单（reactor）
20:     <module>demo-todo</module>
21:     <module>demo-chat</module>
22:     <module>demo-counter</module>
23:     <module>train</module>
24:     <module>takeaway</module>
25:   </modules>

27:   <properties>
28:     <java.version>21</java.version>    ← 全家统一 Java 21
29:     <maven.compiler.release>21</maven.compiler.release>
30:   </properties>
```

三个看点：

1. **`<packaging>pom</packaging>`**：这个模块不编译代码，专职当族长。
2. **认干爹**：继承 `spring-boot-starter-parent`，于是 200 多个常用依赖的版本号全被它锁定——子模块写依赖**不用写版本号**，这就是 C++ 里"根 CMakeLists 统一 set 版本"的加强版。
3. **`<modules>`**：在 `backend/` 下跑一条 `mvn package`，5 个子模块按依赖顺序全编。

### 子模块：backend/demo-todo/pom.xml（41 行）

```xml
 7:   <parent>
 9:     <artifactId>javaweb-root</artifactId>   ← 认族长当爹
11:   </parent>
13:   <artifactId>demo-todo</artifactId>        ← 自己不用再写 groupId/version，继承的

16:   <dependency>
18:     <artifactId>spring-boot-starter-web</artifactId>   ← Web 服务器+MVC，不写版本！
19:   </dependency>
20:   <dependency>
22:     <artifactId>spring-boot-starter-data-jpa</artifactId>  ← 数据库 ORM
23:   </dependency>
24:   <dependency>
26:     <artifactId>spring-boot-starter-validation</artifactId> ← 参数校验
27:   </dependency>
28:   <dependency>
30:     <artifactId>h2</artifactId>
31:     <scope>runtime</scope>        ← 只在运行时需要，编译期看不见
32:   </dependency>

37:   <plugin>
39:     <artifactId>spring-boot-maven-plugin</artifactId>  ← 打 fat jar 的关键
40:   </plugin>
```

**starter 是 Spring Boot 的发明**：一个 starter = 一捆配套依赖。"我要做 Web"就引 `starter-web`，它内部带上 Tomcat、JSON 序列化、日志等十几个传递依赖——你不用再像 CMake 那样逐个 find_package。

`<scope>runtime</scope>` 类似 C++ 里"只在链接运行时需要"的动态库：编译你的代码时它不在 classpath 上。

### 构建入口：infra/build.sh（13 行）

```bash
 5: echo "== Maven package =="
 6: (cd backend && mvn -q -T 4 -DskipTests package)   ← 4 线程并行，跳过测试
 7: ls backend/*/target/*.jar >/dev/null              ← 断言 5 个 jar 都产出
10: for ui in train-ui takeout-ui; do                  ← 前端交给 npm
11:   (cd "frontend/$ui" && npm install --silent; npm run build --silent)
```

产物落在 `backend/<模块>/target/<名>-1.0.0.jar`——对应 C++ 的 `build/` 目录。

---

## 📋 附：Maven 常用命令速查（对照 make 习惯）

| 命令 | 干什么 | make 习惯对照 |
|---|---|---|
| `mvn compile` | 只编译主代码 | make（增量） |
| `mvn test` | 编译并跑测试 | make test |
| `mvn package` | 编译+测试+打包到 target/ | make all |
| `mvn install` | package + 装进 ~/.m2 | make install |
| `mvn clean` | 删 target/ | make clean |
| `mvn clean package` | 推倒重来再打包 | make clean all |
| `mvn -T 4 package` | 4 线程并行构建 | make -j4 |
| `mvn -pl demo-todo package` | 只构建指定模块 | make target 指定文件 |
| `mvn dependency:tree` | 打印依赖族谱 | 无（vcpkg 无此利器） |
| `mvn -DskipTests package` | 跳过测试 | make 时绕过 test target |

> 记忆钩子：Maven 的命令都是**生命周期阶段名**或**插件目标**（`插件:目标`）。你敲的每个词都是流水线上真实存在的工位——这也是"约定优于配置"的一部分：命令不用注册，生来就有。

---

## 动手验证

### 实验 1：看依赖族谱

```bash
$ cd ~/Projects/javaweb/backend/demo-todo
$ mvn dependency:tree | head -20
com.javaweb:demo-todo:jar 1.0.0
├─ org.springframework.boot:spring-boot-starter-web:jar:3.5.4
│  ├─ org.springframework.boot:spring-boot-starter:jar:3.5.4
│  │  ├─ org.springframework.boot:spring-boot-autoconfigure:jar:3.5.4
│  │  ├─ org.springframework:spring-core:jar:6.2.x
│  ...
```

一个 starter 展开是一棵几十节点的树——这就是"传递依赖"，C++ 里你得手动装的那串 apt 包。

### 实验 2：只编译不打包

```bash
$ cd backend && mvn -q compile && ls demo-todo/target/classes/com/javaweb/todo
controller/  model/  repo/  TodoApp.class ...
```

`.class` 文件对应 C++ 的 `.o`：Java 编译产物是字节码，由 JVM 解释/JIT 执行。

### 实验 3：亲眼看一次"缺依赖"的报错

把 demo-todo/pom.xml 里 `spring-boot-starter-web` 那段临时注释掉再编译：

```bash
$ mvn -q compile
.../TaskController.java:[3] 程序包 org.springframework.web.bind.annotation 不存在
```

对照 C++ 的体验：这相当于删掉 include 路径后的 `fatal error: xxx.h: No such file`——但修复方式不同：C++ 要装库/配路径，Maven 把那几行注释还原即可，依赖自动从 ~/.m2（或中央仓库）回来。**"声明即拥有"是依赖管理器给 Java 程序员最大的红利。**（做完记得还原。）

### 实验 4：fat jar 里有什么

```bash
$ unzip -l demo-todo/target/demo-todo-1.0.0.jar | head -8
  BOOT-INF/                        ← 你的代码和依赖都塞在这
  BOOT-INF/classes/                ← 本模块的 .class
  BOOT-INF/lib/                    ← 全部依赖 jar（几十个）
  org/springframework/boot/loader/ ← 启动器
$ ls -lh demo-todo/target/demo-todo-1.0.0.jar
-rw-r--r-- 24M ...                  ← 一个文件 = 静态链接的味道
```

所以 Z03 里 `java -jar demo-todo-1.0.0.jar` 一条命令就能跑：所有依赖都随身携带，目标机器只要装了 Java 21。

---

## 思考题

1. 为什么子模块的 `<dependency>` 不用写版本号？如果两个子模块想要不同版本的同一个库，怎么办？
2. `<packaging>pom</packaging>` 的模块里没有一行 Java 代码，它的存在意义是什么？
3. fat jar 与 C++ 静态链接的相似与不同？
4. `mvn package` 和 `mvn install` 差在哪一步？

## 练习题

**练习 1**：用 `mvn dependency:tree` 统计 demo-todo 一共传递依赖多少个 jar（提示：数 `lib/` 下更省事）。

**练习 2**：给 demo-todo/pom.xml 临时加一个依赖 `com.fasterxml.jackson.core:jackson-databind`（不写版本号），`mvn compile` 应该照样成功——解释为什么没写版本也能编译。

**练习 3**：改 demo-todo 源码里的任意一行注释，只重编这个模块（`mvn -q package -pl demo-todo`），对比全量构建时间。

### 完整参考答案

**思考题 1**：版本由干爹 `spring-boot-starent-parent` 的 dependencyManagement 锁定。要不同版本：在自己模块的 `<dependency>` 里显式写 `<version>` 覆盖，或用 `<properties>` 改属性——Maven 的版本仲裁是"离根越近、声明越具体者胜"。

**思考题 2**：当"族长"——统一版本属性、放公共插件配置、列出 modules 让一条命令构建全家。相当于根 CMakeLists.txt。

**思考题 3**：相似：都是"一个产物自带全部依赖，部署简单"。不同：fat jar 里依赖仍是独立 jar（类加载器按需读），不是二进制级熔接；且 JVM 跨平台，fat jar 不分操作系统，C++ 静态链接产物绑定平台。

**思考题 4**：install = package + 把产出的 jar 装进本地仓库 `~/.m2`，供**本机其他项目**当依赖引用。package 只产出文件不动仓库。类比：package 是"编译出 .a"，install 是"再装进 /usr/lib"。

**练习 1 参考**：`ls target/../demo-todo/target/demo-todo-1.0.0.jar` 解开数 `BOOT-INF/lib` 条目，约 40+ 个 jar；`unzip -l demo-todo/target/demo-todo-1.0.0.jar | grep -c BOOT-INF/lib`。

**练习 2 参考**：jackson-databind 是 starter-web 的传递依赖，干爹已锁版本（3.5.4 对应 2.x），声明它只是"显式确认"既有版本，所以不用写 version。

**练习 3 参考**：`-pl demo-todo`（project list）只构建指定模块，配合增量编译只重编改动的类，时间从几十秒降到几秒。C++ 里等价于只 make 一个 target。

---

## 本节小结
- Maven = 构建工具 + 包管理器 + 约定目录，三者合一；对 C++ 程序员是 CMake+vcpkg 的合体。
- 父 POM（packaging=pom）管版本和模块清单；starter 一捆打包常见依赖；子模块极薄。
- `mvn package` 产出 fat jar：一个文件自带全部依赖，`java -jar` 直接跑。
- npm 是它的前端表亲：package.json↔pom.xml，node_modules↔~/.m2（位置不同）。

## 下一站

[Java速通-A01-类与对象：从C++视角.md](Java速通-A01-类与对象：从C++视角.md)——道具车间的图纸看懂了，现在学画图纸的语言本体：Java 的类、对象、构造器，以及它和你写了多年的 C++ 在内存模型上的根本分歧。
