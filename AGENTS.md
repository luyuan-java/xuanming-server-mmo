# xuanming-server-mmo（Java 版）规范与 AI 协作守则

> 本文件是本仓库对 AI Agent（Claude Code / Codex / Gemini / Cursor / Copilot 等）和人类开发者的**唯一规范总入口**。
> 本仓库是 mmorpg（C++ 节点 + Go 微服务，`https://github.com/luyuan-cpp/xuanming-server-mmo`）的 **Java 版**，两版并行演进。
> `CLAUDE.md` 与 `.github/copilot-instructions.md` 只通过 `@AGENTS.md` 单行导入本文件，不在多处重复维护规则；通用分章细则见 `docs/rules/`。

## 会话启动门禁

0. **新电脑 / 新 AI Agent 环境必先安装必装 Skills**（`mattpocock/skills`、`DietrichGebert/ponytail` 及精选 ≥2 万 Star 代码工程 Skills，共 8 仓 618 项），清单与一键复原脚本见 → [docs/rules/required-skills.md](docs/rules/required-skills.md)
1. 本文件及任务涉及的 `docs/rules/*.md` 分章细则；
2. `PARITY.md`（两版对齐状态）；
3. `docs/design/architecture.md` 与 `tech-stack.md`；
4. `docs/reference/`（从 mmorpg 提取的客户端契约，做客户端可见功能前必读）；
5. `git log -20 --oneline`。

## 规范分章索引（`docs/rules/`）

| 领域 | 核心约束摘要 | 完整规范文档 |
|---|---|---|
| 必装 Skills | 新电脑 / 新 Agent 必装 8 仓 618 个 Skills（含一键脚本与逐项明细） | [docs/rules/required-skills.md](docs/rules/required-skills.md) |
| 工程设计与编码原则 | SOLID、KISS/YAGNI、**系统/模块间 Actor 消息通信（不可变消息、禁过度拆内部函数）**、**23 种设计模式主动采用并说明原因**、**高 Star 开源库（门槛按语言与领域生态浮动）交程序决定并说明原因**、实例与布局分层、线程/连接生命周期 | [docs/rules/engineering-principles.md](docs/rules/engineering-principles.md) |
| 位图（Bit）与删表 ID | 纯布尔完成/领取状态优先 bit 存储、领取归属业务表、删表 ID 策划与程序红线 | [docs/rules/bit-storage-and-table-ids.md](docs/rules/bit-storage-and-table-ids.md) |
| 命名与权限边界 | 命名清晰准确带单位、关联 GitHub 仓库清单、硬禁令与 push 授权边界 | [docs/rules/naming-and-boundaries.md](docs/rules/naming-and-boundaries.md) |
| AI 协作与模型档位 | 构建测试权限、无证据禁称通过、强制高低档模型切换、设计方案强制落盘 `.md` | [docs/rules/ai-workflow.md](docs/rules/ai-workflow.md) |

## 当前开发分支（用户 2026-11-08 要求）

Java 续移植统一在主仓库的 `main` 分支进行，不再为本任务创建并行工作树或临时本地分支。
清理已有工作树之前，先归并未提交改动并保存恢复快照；未执行的候选和历史失败证据不能记为验证通过。

## 通用工程原则（用户补充，完整细则见 `docs/rules/`）

- **未上线阶段前提**：游戏尚未上线，玩家数据可完全清空；按最标准的方式实现与重构，不保留不必要的历史兼容层。
- **质量与架构要求**：保证**数据强一致性**、命名清晰准确易懂、**可水平扩展**；严格遵守**单一职责（SRP）**等软件开发原则，适合用 23 种设计模式优化的结构主动采用并向程序说明原因。
- **系统 / 模块间消息通信（Actor 隔离）**：跨系统、跨线程、跨进程交互一律通过**不可变消息**（Mailbox / 事件 / RPC / Kafka）通信，各模块独占状态、禁止传递可变对象引用或借全局单例绕过通道；仅限跨模块/跨边界使用，单个模块内部保持正常直接方法调用，严禁把内部普通函数过度拆成消息（详见 [docs/rules/engineering-principles.md](docs/rules/engineering-principles.md)）。
- **位图存储优先**：玩家对表 ID 的纯布尔完成/领取/解锁状态优先用位图存储，严守删表 ID 的策划与程序红线（见 [docs/rules/bit-storage-and-table-ids.md](docs/rules/bit-storage-and-table-ids.md)）。
- **问题当场闭环**：排查出的问题与合理标准直接按最优方案落地，不留“等压测/等以后再修”的债务。
- **分工约定**：只负责业务代码实现时，协议生成、编译与冒烟验证可交由 Codex 执行（见 [docs/rules/ai-workflow.md](docs/rules/ai-workflow.md)）。

## 1. 双版本纪律（强制）

- **所有功能两个版本都要做**。一边完成一个功能，就在 `PARITY.md` 登记一行；交付说明写明另一版状态。
- **两版共享的只有客户端契约**：客户端 ↔ gate 帧格式与握手、`proto/` 里客户端可见的消息、消息号、tip 码、配置表数据、
  客户端访问的 HTTP 接口形状。这些由 `tools/ContractSync.java` 从 mmorpg 同步，**不许手改**：
  - `game-proto/src/main/proto/**`、`game-proto/src/main/resources/contract/**`
  - `game-table/src/main/proto/**`、`game-table/src/main/java/com/game/table/TableConstants.java`
  - `config-data/tables/**`、`config-data/navmesh/current_maps/**`、`contract/SOURCE.properties`
  改契约先改 mmorpg，再运行 `java tools/ContractSync.java --mmorpg <mmorpg 根目录>` 同步，两边同批提交。
  `--check` 只比对不写入（不一致退出码 1）。配置表的 Java 访问代码不再同步，由 `game-table-codegen` 在编译期按权威 schema
  生成（见 `docs/design/config-tables.md`）。
  当前地图允许 `--scope current-maps` 单域同步 BaseScene 与导航，保留其他已接契约；地图独立记录在 `maps.source.commit`，
  不改全局 `mmorpg.commit`。完整检查用 `--mmorpg <全局源> --maps-source <地图源> --check` 从两个提交重建期望，
  禁止拿本仓库现有非地图产物充当完整检查的期望。已有地图覆盖后全量写入必须显式指定 `--maps-source`。
- **服务端内部按 Java 惯用方式实现，不照抄 C++/Go**。Java 版有自己的库表与 Redis 键空间，不与 C++/Go 服务混部。
- **目录、模块、工具都按 Java 标准自行组织，不对应 mmorpg 的目录结构**；Java 代码不得依赖同步来的 proto 所在目录
  （包名按 proto package，路由按服务语义，见 `docs/design/architecture.md` §1）。工具也用 Java 写。

## 2. 选型（强制）

第三方库优先同类头部高 Star 成熟项目（Java 主流通用库参考 GitHub ≥ 2 万 Star、**同类取 Star 最高**；细分/垂直领域按对应语言生态体量浮动，不机械死卡 2 万）；JDK 与 Spring Boot 自带组件视为事实标准。
新增依赖前先查 Star 与维护状态、交由程序决定并说明原因，确认后写进 `docs/design/tech-stack.md`；安全 > Star（例：不用 fastjson 1.x）。

## 3. 编码约定

- 中文注释、中文 commit message、中文文档；Java 21；包名 `com.game.<模块>`。
- **部署命名空间** `<ns>` = `game.namespace`（环境变量 `GAME_NAMESPACE`，缺省 `game`；architecture.md §6.2）：共享中间件里的名字都带它，缺省时与以前逐字节相同。
  它是**注入的**，没有进程级全局值（同 Spring Session 的 `spring.session.redis.namespace`）：
  - `<ns>` 由不可变值类型 `com.game.common.GameNamespace` 表示（`[a-z][a-z0-9]{0,27}`、不是 mysql / sys / public，构造即校验）；Spring 进程里由 game-discovery 的
    `GameNamespaceProperties`（`@ConfigurationProperties` + `@Validated`）绑定，非法值由 Spring Boot 自带的绑定校验报告拒启。不写自定义 EnvironmentPostProcessor / FailureAnalyzer。
  - Redis 键与频道一律经注入的 `com.game.discovery.RedisKeys` 实例生成（前缀 `<ns>:`）；Kafka 的 topic / 消费组 / client.id（将来的 transactional.id）一律经注入的
    `com.game.common.KafkaNames` 实例生成（前缀 `<ns>-`；`AuditTopics` / `BattleResultTopics` 由它构造）。`GameNamespace`、`RedisKeys`、`KafkaNames` 三者都是
    game-discovery 的 `GameNamespaceAutoConfiguration` 注册的 bean，使用方经构造器 / bean 方法参数拿到；`RedisKeys` / `KafkaNames` 按 Spring Boot 惯例
    `@ConditionalOnMissingBean`（使用方自己定义了就让路），`GameNamespace` 不让路（它就是绑定校验后的配置值，唯一来源）。
  - MySQL 库名 = `<ns>`（yaml 的 JDBC URL 写 `${game.namespace:game}`，部署只覆盖 host / port）；连库的服务进程（`NamespaceDatabaseVerifier`）与命令行工具都核对 `DATABASE()`。
    服务进程的应急开关 `game.mysql.expect-database` 同样是 `@ConfigurationProperties` + `@Validated`（`GameMysqlProperties`），不在代码里现取 Environment。
  - 不起 Spring 的命令行工具（AuditFallbackReplay、PasswordAdmin、assetopfix、RetireZoneHotState、玩家排障导出 / 导入 PlayerBundleExport / PlayerBundleImport）一律经 game-discovery 的 `GameNamespaceBinder` 取命名空间：与服务进程同一个
    `GameNamespaceProperties`、同一套宽松绑定（`-Dgame.namespace` 优先于环境变量 `GAME_NAMESPACE`，assetopfix 再到它的 `-f` yaml），再自己 `new` 名字类。
    部署推荐经环境变量 `GAME_NAMESPACE` 给值、不写进 yaml；工具与服务进程拿同一个 `GAME_NAMESPACE`（或 `-Dgame.namespace`）即同值。
  - main 代码里不得静态持有命名空间或名字类实例，也不得在装配类（`GameNamespaceAutoConfiguration`）、`GameNamespaceBinder` 与四个命令行工具入口（AuditFallbackReplay、
    assetopfix、RetireZoneHotState、PlayerBundleExport——玩家排障导入共用它的那一处构造）之外 `new RedisKeys` / `new KafkaNames` / `GameNamespace.of`；不得写死 `game:` 键或未登记的 `"game-…"` 名字（game-robot 的 `NamespaceLiteralGuardTest`
    守着）。配置键 `game.`、环境变量 `GAME_`、包名、指标名不跟命名空间走。
- **线程所有权**：scene 的场景状态只在场景逻辑线程读写；gate 的会话状态只在该连接的 EventLoop 上读写；
  阻塞 I/O（MySQL / Redis）不得在 Netty I/O 线程或场景逻辑线程上执行，结果投递回所属线程。
- 连接与回调生命周期（同 mmorpg §11.7 的精神）：长期持有的 Netty `Channel` 引用在关闭后必须清掉；
  回调里先检查对象 / 连接是否仍有效。
- 秘密（gate 令牌密钥、开发口令、数据库密码）只从环境变量注入，不写进仓库。
- 玩家资产与归属路径默认 fail-closed；写回玩家数据必须带 `owner_epoch` 围栏。

## 4. 构建与测试

Claude 在本仓库**可以自行编译和跑测试**（用户 2026-09-29 授权，仅限本仓库；mmorpg 仍按其 §10.1）。

```bash
./mvnw -B -DskipTests install            # 全量构建
./mvnw -B test                           # 单元测试
./mvnw -B -pl game-gate -am test         # 单模块（连同依赖模块）
./mvnw -B test -Dgame.it.redis=redis://127.0.0.1:6379   # 连真 Redis 的集成测试（默认跳过）
./mvnw -B test -Dgame.it.mysql=jdbc:mysql://127.0.0.1:3306  # PlayerStoreSqlTest 改连真 MySQL（缺省 H2；口令取 GAME_MYSQL_PASSWORD）
```

网络受限的机器用进程级镜像配置：`./mvnw -s <本机 settings.xml> ...`（settings 不进仓库）。

**部署命名空间与测试**：测试直接构造实例（每个测试类一行 `new RedisKeys(GameNamespace.of("game"))` / `new KafkaNames(...)`，非缺省命名空间就另构造一个，
同一个 JVM 里可以并存），Spring 上下文测试用属性 `game.namespace=…`（`ApplicationContextRunner` 加 `GameNamespaceAutoConfiguration`）；不用系统属性钉值，也不分 surefire 执行。
测试与运行环境隔离：Spring 上下文用例读真实的环境变量与系统属性（宽松绑定），所以根 pom 的 surefire 用 `excludedEnvironmentVariables` 挡掉
`GAME_NAMESPACE`、`GAME_MYSQL_EXPECT_DATABASE`（及 `GAME_MYSQL_EXPECTDATABASE`）、`GAME_NACOS_ADDR`——切片调试时 shell 里 export 过它们照样可以跑 `./mvnw test`。
`-D` 给 mvn 的系统属性 surefire 会原样传进测试进程、排除不了：**不要给 mvn 传 `-Dgame.namespace`**；IDE 里跑测试时，运行配置也不要带这几个变量。
断言「没配」的用例自己把系统属性与环境变量属性源换成空的，起 `SpringApplication` 的用例经命令行参数给值（优先级最高，`.properties(…)` 给的是最低的 default properties）。

**同步契约之后必须 `clean install`**：protoc 插件是增量生成，删掉或改名的 proto 类型留下的旧生成类不会被清理，
会让本应失败的引用继续编译通过（2026-09-29 实例：旧包名下残留的生成类掩盖了一处未迁移的 import）。
没有运行证据时不得声称「编译通过」「测试通过」。

## 5. 禁止事项

- ❌ `git tag`（由用户执行）。`git push`：用户 2026-10-02 授权，**每完成一个功能就提交并推送到 `origin/main`**
  （提交前构建与相关测试必须通过、PARITY.md 已登记）；不许 force push、不许改写已推送的历史。
- ❌ 手改同步产物（§1 列出的路径）。
- ❌ 把 `player_id` 等高基数值当指标 label。
- ❌ 在公开仓库里放个人 / 面试内容（本仓库公开且挂在简历上）。

## 6. 模型档位与设计落盘规定（强制，负责人 2026-11-20 定，2026-11-21 补充）

- **写/修改代码，以及需求分析、设计分析、思想分析、漏洞分析、步骤分析、执行步骤，必须使用各软件当时的最高档模型**，不得在低档模型上执行：Claude Code 用 `Fable 5.2 Max`，Gemini 用 `Gemini 4 Neon Extra`，Codex 用 `GPT 6.2 Astral Ultra`。
- **设计方案强制落盘 `.md`**：以后编写设计方案时，Claude Code（及其他 AI Agent）写好设计后必须将**需求分析、设计分析、思想分析、漏洞分析、步骤分析、执行步骤**及完整方案保存到 `docs/design/<主题>.md`（或既有对应设计文档），不得只留在对话中。
- **仅编译、同步契约、跑测试与冒烟使用低档模型**：Gemini 用 `Carbon Medium`，Codex 用 `6.1 Sol Ultra`，Claude Code 用 `Opus 5 High`；遇到需要改代码或需做上述分析/步骤设计的报错先保留原文停下，切回最高档模型再处理。
