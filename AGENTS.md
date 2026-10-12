# xuanming-server-mmo（Java 版）规范与 AI 协作守则

> 本仓库是 mmorpg（C++ 节点 + Go 微服务，`github.com/luyuan-cpp/xuanming-server-mmo`）的 **Java 版**，两版并行演进。
> `CLAUDE.md` 只通过 `@AGENTS.md` 导入本文件。通用工程原则沿用 mmorpg `AGENTS.md` §11（正确性 / 数据一致性优先、
> SRP、KISS、YAGNI、显式依赖、面向接口测试等），本文件只写 Java 版特有的规则。

## 会话启动门禁

1. 本文件；2. `PARITY.md`（两版对齐状态）；3. `docs/design/architecture.md` 与 `tech-stack.md`；
4. `docs/reference/`（从 mmorpg 提取的客户端契约，做客户端可见功能前必读）；5. `git log -20 --oneline`。

## 1. 双版本纪律（强制）

- **所有功能两个版本都要做**。一边完成一个功能，就在 `PARITY.md` 登记一行；交付说明写明另一版状态。
- **两版共享的只有客户端契约**：客户端 ↔ gate 帧格式与握手、`proto/` 里客户端可见的消息、消息号、tip 码、配置表数据、
  客户端访问的 HTTP 接口形状。这些由 `tools/ContractSync.java` 从 mmorpg 同步，**不许手改**：
  - `game-proto/src/main/proto/**`、`game-proto/src/main/resources/contract/**`
  - `game-table/src/main/proto/**`、`game-table/src/main/java/com/game/table/TableConstants.java`
  - `config-data/tables/**`、`contract/SOURCE.properties`
  改契约先改 mmorpg，再运行 `java tools/ContractSync.java --mmorpg <mmorpg 根目录>` 同步，两边同批提交。
  `--check` 只比对不写入（不一致退出码 1）。配置表的 Java 访问代码不再同步，由 `game-table-codegen` 在编译期按权威 schema
  生成（见 `docs/design/config-tables.md`）。
- **服务端内部按 Java 惯用方式实现，不照抄 C++/Go**。Java 版有自己的库表与 Redis 键空间，不与 C++/Go 服务混部。
- **目录、模块、工具都按 Java 标准自行组织，不对应 mmorpg 的目录结构**；Java 代码不得依赖同步来的 proto 所在目录
  （包名按 proto package，路由按服务语义，见 `docs/design/architecture.md` §1）。工具也用 Java 写。

## 2. 选型（强制）

第三方库优先 GitHub ≥ 2 万 star，**同类取 star 最高**；JDK 与 Spring Boot 自带组件视为事实标准。
新增依赖前先查 star，写进 `docs/design/tech-stack.md`；安全 > star（例：不用 fastjson 1.x）。

## 3. 编码约定

- 中文注释、中文 commit message、中文文档；Java 21；包名 `com.game.<模块>`。
- **部署命名空间** `<ns>` = `game.namespace`（环境变量 `GAME_NAMESPACE`，缺省 `game`；`com.game.common.GameNamespace`，architecture.md §6.2）：
  共享中间件里的名字都带它，缺省时与以前逐字节相同。
  - 所有 Redis 键与频道经 `com.game.discovery.RedisKeys` 生成（前缀 `<ns>:`，缺省 `game:`）；Kafka 的 topic / 消费组 / client.id / transactional.id
    一律经 `com.game.audit.KafkaNames` 生成（前缀 `<ns>-`）；MySQL 缺省库名 = `<ns>`（yaml 的 JDBC URL 写 `${game.namespace:game}`）。
    新增共享中间件里的名字必须经这三个出处（game-robot 的 `NamespaceLiteralGuardTest` 守着）；配置键 `game.`、环境变量 `GAME_`、包名、指标名不跟命名空间走。
  - 命名空间进程内定格、不可再改：`main()` 在 `SpringApplication.run` 之前、EnvironmentPostProcessor 在定格之前，都不得触碰名字类（RedisKeys、KafkaNames、
    AuditTopics……）；不起 Spring 的入口在生成第一个名字之前显式 `GameNamespace.set`。命名空间只经环境变量注入，不写进 yaml。
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

**部署命名空间与测试**：测试 JVM 的 `game.namespace` 由根 pom 的 surefire 钉成 `game`；非缺省命名空间的用例打 `@Tag("namespace")`，只在六个模块各自的
`namespace` 执行（另一个 fork，钉成 `nstest`）里跑，IDE 里单跑要加 `-Dgame.namespace=nstest`。**跑测试不要给 mvn 传 `-Dgame.namespace`**
（surefire 3.2.5 让命令行的 -D 覆盖 POM 钉的值，两类用例都会在 `@BeforeAll` 失败并说明原因）。surefire 必须 fork，不得配 `forkCount=0`（定格的全局状态会在
Maven JVM 里跨模块共享）。

**同步契约之后必须 `clean install`**：protoc 插件是增量生成，删掉或改名的 proto 类型留下的旧生成类不会被清理，
会让本应失败的引用继续编译通过（2026-09-29 实例：旧包名下残留的生成类掩盖了一处未迁移的 import）。
没有运行证据时不得声称「编译通过」「测试通过」。

## 5. 禁止事项

- ❌ `git tag`（由用户执行）。`git push`：用户 2026-10-02 授权，**每完成一个功能就提交并推送到 `origin/main`**
  （提交前构建与相关测试必须通过、PARITY.md 已登记）；不许 force push、不许改写已推送的历史。
- ❌ 手改同步产物（§1 列出的路径）。
- ❌ 把 `player_id` 等高基数值当指标 label。
- ❌ 在公开仓库里放个人 / 面试内容（本仓库公开且挂在简历上）。
