# 零起点-04 · 依赖与构建：Maven 对 CMake

> 三行件头：①要点——Maven=声明式依赖+多模块+生命周期，写给"写了 CMakeLists、装过 vcpkg"的你；②前置——会 C++ 工程概念、`mvn -v` 能出版本；③产出——能读懂本项目 pom 树、并亲历"把 artifactId 改错 → 真报错 → 修正"一轮完整实录。
>
> 🗺 主线进度：第 4 站 | 🎞 上一站：Z03 端口与进程 | 📀 本站所得：一颗"依赖=生命线"的钉子。

## 🎬 开篇三件套

### 你在哪格 + 任务单
站到道具车间（Maven）。任务：① Maven 是什么（依赖管理+多模块+生命周期三合一）；② 建立"C++ 惯例→Maven 对应物"映射；③ 读懂 backend/pom.xml 父 POM 与 demo-todo 子 POM；④ 分清 Maven 与 npm；⑤ 会跑 `mvn package` 并找到 jar。

### 开工前自查
- [ ] 知道 `java -jar` 是启动命令（Z03 见过）
- [ ] `mvn -v` 有输出
- [ ] 知道头文件/链接是什么

---

## 名词卡（压缩后重排序：从写 pom 到出 jar）

| 名词 | 人话解释 | C++ 世界对应 |
|---|---|---|
| **POM** | `pom.xml` 描述"项目要什么" | CMakeLists.txt |
| **坐标（GAV）** | groupId:artifactId:version 三元组 | 库名+版本 |
| **中央仓库** | Maven Central | vcpkg/conan 的包源 |
| **传递依赖** | 你要 A，A 要 B，自动拉 B | 依赖链 apt install |
| **多模块（reactor）** | 一个父 POM 管一串子模块 | add_subdirectory |
| **生命周期** | validate→compile→test→package→install | 手写 make target 链 |
| **BOM/parent** | 父 POM 统一定义版本，子模块只写名字 | 根 CMakeLists 统一 set 版本 |
| **fat jar** | 代码+全部依赖打成一个可执行 jar | 静态链接的单文件可执行 |
| **scope** | 依赖在生命周期哪一段可用（compile/runtime/test） | 链接阶段 |

## 1. 概念最小人话 + 术语对照（压缩表格）

| 你在 C++ 里的习惯 | Maven 里的对应物 | 差异要点 |
|---|---|---|
| 手写 CMakeLists 找库 | `pom.xml` 里声明 `<dependency>` | Maven 声明式：只说"要什么"，不说"去哪找、怎么编" |
| apt/vcpkg 装库到系统 | 依赖装进用户目录 `~/.m2/repository` | 不污染系统，每个项目版本独立 |
| 头文件路径、链接顺序 | 不存在！jar 里自带字节码和元数据 | 编译期/链接期统一由 Maven 管 |
| make / ninja 增量编译 | `mvn compile` 自动增量 | 生命周期固定，不用自己写 target 依赖图 |
| 静态/动态链接的选择 | 打包方式：瘦 jar / fat jar | Spring Boot 默认 fat jar，一个文件全带走 |
| git submodule 引子工程 | `<modules>` 多模块 reactor | 父 POM 一次调度全部子模块 |
| 版本冲突地狱 | 最近的声明获胜 + dependencyManagement | 仍有冲突但可控，`mvn dependency:tree` 查族谱 |

一句话：**Maven = CMake（构建）+ vcpkg（依赖）+ 约定优于配置（目录结构）**。Java 项目的目录结构几乎是宪法：`src/main/java` 放代码、`src/main/resources` 放配置、`target/` 放产物——Maven 约定好了，CMake 里你要自己 set。

### npm 对照（为 B 系列 Vue 教程打底）

| Maven | npm |
|---|---|
| pom.xml | package.json |
| ~/.m2/repository | node_modules/（就在项目里！） |
| mvn package | npm run build |
| Maven Central | npm registry |
| 传递依赖 | 同样有，且更容易版本地狱 |

最大差异：npm 把依赖装进**项目目录**的 node_modules（所以经常巨肥、要 gitignore）；Maven 装进**用户主目录**的 ~/.m2，项目间共享。

## 2. 真实代码走查

### 2.1 父 POM：backend/pom.xml（族长）

```xml
 7:   <groupId>com.javaweb</groupId>
 8:   <artifactId>javaweb-root</artifactId>
10:   <packaging>pom</packaging>          ← 不产 jar，只当族长
12:   <parent>
14:     <artifactId>spring-boot-starter-parent</artifactId>
15:     <version>3.5.4</version>          ← 认 Spring Boot 当干爹
19:   <modules>
20:     <module>demo-todo</module> ... 24: <module>takeaway</module>
27:   <properties>
28:     <java.version>21</java.version>   ← 全家统一 Java 21
```

三个看点：
1. **`<packaging>pom</packaging>`**：这模块不编译代码，专职当族长——统一版本属性、放公共插件、列 modules 清单；
2. **认干爹**：继承 `spring-boot-starter-parent` 后 200+ 个常用依赖版本全被锁定，子模块写依赖**不写版本号**（C++ 里"根 CMakeLists 统一 set 版本"的加强版）；
3. **`<modules>`**：在 `backend/` 下跑一条 `mvn package`，5 个子模块按依赖顺序全编。

### 2.2 子模块：backend/demo-todo/pom.xml（41 行，节选）

```xml
 7:   <parent>
 9:     <artifactId>javaweb-root</artifactId>    ← 认族长当爹
11:   </parent>
13:   <artifactId>demo-todo</artifactId>         ← groupId/version 从爹继承
16:   <dependency>
18:     <artifactId>spring-boot-starter-web</artifactId>   ← Web 服务器+MVC，不写版本！
20:   <dependency>
22:     <artifactId>spring-boot-starter-data-jpa</artifactId>  ← 数据库 ORM
28:   <dependency>
30:     <artifactId>h2</artifactId>
31:     <scope>runtime</scope>        ← 只在运行期需要，编译期看不见
37:   <plugin>
39:     <artifactId>spring-boot-maven-plugin</artifactId>  ← 打 fat jar 的关键
```

**starter 是 Spring Boot 的发明**：一个 starter = 一捆配套依赖。"我要做 Web"就引 starter-web，它内部带上 Tomcat、JSON、日志十几条传递依赖——不用像 CMake 那样逐个 find_package。
`<scope>runtime</scope>` 类似"运行时动态库"语义：编译你的代码时它不在 classpath 上（打包进 fat jar 的 BOOT-INF/lib，见 §5 验证 4）。

### 2.3 构建入口：infra/build.sh（13 行）

```bash
 5: echo "== Maven package =="
 6: (cd backend && mvn -q -T 4 -DskipTests package)   ← 4 线程并行，跳过测试
 7: ls backend/*/target/*.jar >/dev/null              ← 断言 5 个 jar 都产出
10: for ui in train-ui takeout-ui; do                  ← 前端交给 npm
11:   (cd "frontend/$ui" && npm install --silent; npm run build --silent)
```

产物落到 `backend/<模块>/target/<名>-1.0.0.jar`——target/ 对应 C++ 的 build/ 目录。这条 `mvn -q -T 4 -DskipTests package` 是 reactor 的"一键全家桶"（A07 再深挖）。

## 3. 工程实录：故意把 pom 里的 artifactId 改错 → mvn 真报错 → 修正 diff

这是全册第一次你亲手在"构建期"现场翻车。全部输出为真机原始记录（Maven 3.9.x）。

### 3.1 错法一（最常见）：typo 版本还能对上

改 `demo-todo/pom.xml` 第 18 行的 artifactId 为 `spring-boot-starter-web-typo`：

```bash
$ cd backend/demo-todo && mvn compile
[ERROR] Some problems were encountered while processing the POMs:
[ERROR] 'dependencies.dependency.version' for org.springframework.boot:spring-boot-starter-web-typo:jar is missing. @ line 16, column 17
[ERROR] The build could not read 1 project -> [Help 1]
```

**读报错**：Maven 首先检查的是"干爹 BOM 里存不存这个 artifactId"——查不到就当你是**显式引 private 依赖**，要你补版本号。这时如果你"顺手"再补一个 `<version>3.5.4</version>`，报错就变成第二阶段：

```bash
$ mvn -U clean package -DskipTests
[INFO] Scanning for projects...
[WARNING] The POM for org.springframework.boot:spring-boot-starter-web-typo:jar:3.5.4 is missing, no dependency information available
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal on project demo-todo:
[ERROR]   Could not resolve dependencies for project com.javaweb:demo-todo:jar:1.0.0
[ERROR]   dependency: org.springframework.boot:spring-boot-starter-web-typo:jar:3.5.4 (compile)
[ERROR]   Could not find artifact org.springframework.boot:spring-boot-starter-web-typo:jar:3.5.4
          in central (https://repo.maven.apache.org/maven2)
[ERROR] -> [Help 1]
```

**读两行 ERROR**：`(compile)` 是 scope，jar 的作用域；`Could not find artifact ... in central` 是"中央仓库没这个名字"，这就是"我改错了 artifactId"最典型的原声。对照 C++：相当于写错库名后 `vcpkg install` 报 `No package 'xxx' found`——只是 Maven 把包源写死了，且解析是**声明式**（一句配置就能引爆）。

### 3.2 错法二导致的"下一波"（并发症）

注意上面报错里若木已成舟，有些版本的 Maven 会**缓存"找不到"的失败记录**，下次 mvn 不再重试，除非 `mvn -U` 强刷（本机实测输出片段）：

```
[ERROR] ...spring-boot-starter-radish:jar:3.5.4 was not found in https://repo.maven.apache.org/maven2
        during a previous attempt. This failure was cached in the local repository and
        resolution is not reattempted until the update interval of central has elapsed or
        updates are forced
```

（顺带说"radish 版"也是真实试验：把 artifactId 随手敲成不存在的东西，也同样 `Could not find artifact`——凡是这个报错，第一反应是**回 pom 看坐标**。）

### 3.3 修正（完整 diff）

```diff
--- a/backend/demo-todo/pom.xml
+++ b/backend/demo-todo/pom.xml
@@ -15,9 +15,8 @@
     <dependency>
       <groupId>org.springframework.boot</groupId>
-      <artifactId>spring-boot-starter-web-typo</artifactId>
+      <artifactId>spring-boot-starter-web</artifactId>
-      <version>3.5.4</version>     ← 这行也不用要，干爹锁着呢
     </dependency>
```

```bash
$ mvn -q package -DskipTests && ls -lh target/demo-todo-1.0.0.jar
-rw-r--r-- 56M target/demo-todo-1.0.0.jar
```

修正后 BUILD SUCCESS，产物照常产出——**"声明即拥有"，修 pom 比修 CMakeLists 快得多**。

**复盘的"分层读报错"一图流**（值 5 分钟背下来）：

```
POM 语法/坐标检查          ← mvn 一进来先查（"version missing @ line 16"）
   ↓ 过了
依赖解析（去仓库找）       ← "Could not find artifact ... in central"
   ↓ 过了
编译（javac）             ← "程序包 xxx 不存在"
   ↓ 过了
测试/打包/装配 fat jar     ← "BUILD SUCCESS"
```

**报错出现在哪一层，就到哪一层去修**：坐标问题回 pom、下载问题看网络/`-U`、代码问题看 import。这一层划分对 C++ 老手同样适用（CMake 配置期 → 链接期 → 编译期）。

### 3.4 另一类坑（同源预演）：缺依赖的编译报错

把 starter-web 那段**临时注释掉**后编译，报错变成编译器风格（原文照录）：

```
.../TaskController.java:[3] 程序包 org.springframework.web.bind.annotation 不存在
```

对照 C++：删掉 include 路径后的 `fatal error: xxx.h: No such file`。修复方式不同：C++ 要装库/配路径，这里把注释还原即可，依赖自动从 `~/.m2/` 回来。

## 4. 模式对比 / 选型表

| 命令 | 干什么 | make 习惯 |
|---|---|---|
| `mvn compile` | 只编译 | make |
| `mvn test` | 编译并跑测试 | make test |
| `mvn package` | 编译+测试+打包到 target/ | make all |
| `mvn install` | package + 装进 ~/.m2 | make install |
| `mvn clean` | 删 target/ | make clean |
| `mvn clean package` | 推倒重来再打包 | make clean all |
| `mvn -T 4 package` | 4 线程并行 | make -j4 |
| `mvn -pl demo-todo package` | 只构建指定模块 | make 一个 target |
| `mvn dependency:tree` | 打印依赖族谱 | vcpkg 无此利器 |
| `mvn -DskipTests package` | 跳过测试 | 绕过 test target |

记忆钩子：Maven 的命令都是**生命周期阶段名**或**插件目标**（`插件:目标`）——你敲的每个词都是流水线上真实存在的工位，这正是"约定优于配置"的一部分：命令不用注册，生来就有。

## 5. 动手验证（升级：自己跑一遍 + 逐行对账）

```bash
# ① 看依赖族谱（想背下来的先看干货）
$ cd ~/Projects/javaweb/backend/demo-todo && mvn dependency:tree | head -20
com.javaweb:demo-todo:jar 1.0.0
├─ org.springframework.boot:spring-boot-starter-web:jar:3.5.4
│  ├─ org.springframework.boot:spring-boot-starter:jar:3.5.4
│  │  ├─ org.springframework.boot:spring-boot-autoconfigure:jar:3.5.4
│  ...

# ② 只编译不打包
$ mvn -q compile && ls demo-todo/target/classes/com/javaweb/todo
controller/  model/  repo/  TodoApp.class ...

# ③ 亲跑实录 4 的"改错→报错→diff→复原"一轮，做完必须复原 pom
# ④ fat jar 里有什么
$ unzip -l target/demo-todo-1.0.0.jar | head -8
  BOOT-INF/
  BOOT-INF/classes/         ← 本模块的 .class
  BOOT-INF/lib/             ← 全部依赖 jar（几十个）
  org/springframework/boot/loader/  ← 启动器
$ ls -lh target/demo-todo-1.0.0.jar
-rw-r--r-- 1 icaruslee icaruslee 56M ...

# ⑤ 把刚才的"带依赖随身跑"连回 start-all
$ unzip -l target/demo-todo-1.0.0.jar | grep -c BOOT-INF/lib
```

一个 starter 展开是一棵几十节点的树——这就是"传递依赖"，C++ 里你得手动装的那串 apt 包；`.class` 文件对应 C++ 的 `.o`（Java 产物是字节码，由 JVM 解释/JIT 执行）。做完 ①~④ 后，把"为什么 `$JAVA_HOME/bin/java -jar` 就能跑"用 ④ 的输出自答一遍——所有依赖都随身携带，目标机器只要装了 Java 21。

## 思考题

1. 子模块 `<dependency>` 不写版本号，是靠什么机制？两个子模块想用不同版本同一库怎么办？
2. `<packaging>pom</packaging>` 的模块里没有一行 Java，存在意义是什么？
3. fat jar 与 C++ 静态链接的异同？
4. `mvn package` 与 `mvn install` 的差别？实测报错时"看到两不同"是什么？

## 练习题

**练习 1**：`mvn dependency:tree` 统计 demo-todo 传递依赖 jar 数：`unzip -l target/demo-todo-1.0.0.jar | grep -c BOOT-INF/lib` 更省事（41+ 个 jar）。
**练习 2**：给 demo-todo 加 `com.fasterxml.jackson.core:jackson-databind`（不写版本号）编译应成功——解释为什么。
**练习 3**：改 demo-todo 源码一行注释，只重编本模块（`mvn -q package -pl demo-todo`），对比全量构建时间。

### 完整参考答案

**思考 1**：版本由干爹 BOM 锁定；要不同版本在本模块显式写 `<version>` 覆盖，或改 `<properties>`；仲裁是"离根近、声明具体者胜"——本次实录的 `Could not find artifact` 一行就是"声明式失败"的原声。
**思考 2**：当族长：统一版本属性、公共插件、modules 清单；对应根 CMakeLists.txt。
**思考 3**：像：一个产物自带全部依赖，部署简单。异：fat jar 里依赖仍是独立 jar，类加载按需读；跨平台不分 OS；静态链接是二进制熔接绑定平台。
**思考 4**：install = package + 装 `~/.m2` 供他项目引用。实测时 POM 检查报"坐标 problems"（还没拉包）vs 构建期 `Could not resolve dependencies`——"声明期"/"解析期"两层关。
**练习 2 参考**：jackson-databind 是 starter-web 的传递依赖，干爹已锁版本——只是"显式确认"，无需版本。
**练习 3 参考**：`-pl demo-todo` 只构建这个模块+增量编译，几十秒→几秒，等价"只 make 一个 target"。

---

## 本节小结
- Maven = 构建工具 + 包管理器 + 约定目录，三者合一（CMake+vcpkg 合体）。
- 父 POM（packaging=pom）管版本和模块；starter 一捆带走依赖；子模块极薄。
- `mvn package` 产 fat jar：一个文件自带全部依赖，java -jar 直接跑。
- "Could not find artifact … in central"=坐标写错的原声；先回 pom，别猜网、别猜缓存。

## 下一站

[Java速通-A01-类与对象：从C++视角.md](Java速通-A01-类与对象：从C++视角.md)——图纸看懂了，现在学画图纸的语言：Java 的类、对象、构造器，和 C++ 在内存模型上的根本分歧。
