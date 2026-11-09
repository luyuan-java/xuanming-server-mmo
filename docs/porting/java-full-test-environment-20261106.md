# Java 全量回归环境补齐（2026-11-06）

本次在独立 Java 工作树执行，C++ 仓库只读。环境原件放在 `D:/luyuan/tools/review-port-next/java-20261106`，构建结果放在相邻 `full-java-20261026`；临时数据库不与已有业务容器共用。

## 已配置的隔离依赖

| 依赖 | 本机入口 | 用途 |
|---|---|---|
| TiDB `v8.5.8-20261021-849a85c` / TiKV / PD | `127.0.0.1:24000` | 写者资格、行锁及要求 TiDB 的集成回归 |
| Redis 8.10.2 | `127.0.0.1:26379`；配额实例 `13379` | Lua、租约、目录及跨模块集成回归；全量启用登录配额时通用 Redis 参数必须选 13379 |
| Kafka 4.3.1 | `127.0.0.1:29092`、`29093`、`29094` | 三 broker，保留一个 controller；审计与战斗结果真实消息回归 |
| MySQL 8.4.13 | `127.0.0.1:13316` | VERSION 与 performance_schema 两个锁视图已实查；初始化后正常停止，专项阶段再启动 |
| Git Bash | `C:/Program Files/Git/bin/bash.exe` | 本地启动/停止脚本测试；进程 PATH 优先使用此版本 |

Redis/Kafka 的 Compose 项目名为 `game-java-port-20261106`，新的 SQL 项目名为 `game-java-sql-v858-20261106`。所有宿主端口只绑定 loopback，使用独立网络及卷；镜像按本机已有 digest 固定。Redis PING、Kafka API 握手、TiDB VERSION/SELECT 均已实际通过。依赖就绪不是项目测试通过。

初次使用的 TiDB 8.5.2 不含 Java SQL 预算所需的 `tidb_dml_max_execution_time`，真实回归明确失败。因此只更换本次隔离 SQL 夹具，旧三容器正常停止、卷和失败原件保留；未修改共享中间件或生产部署。新 TiDB 镜像为 `pingcap/tidb@sha256:3378c71dc8b3dd39742dc0ba546c80880a5d8b0da67a9f9d116cfc33505505c3`，镜像 `-V` 与 SQL `VERSION()` 均确认提交 `849a85c3795c3472b46f59a8f2917b880d7ee923`，对应[官方事务 DML 超时实现](https://github.com/pingcap/tidb/commit/849a85c3795c3472b46f59a8f2917b880d7ee923)。实际查询确认变量存在，并确认死锁历史容量 128、重试死锁采集已开启。未移除或降级 Java 的超时与死锁断言。

Windows 写者资格夹具使用已安装的 `C:/Users/luyua/.mmorpg/sql-boot-local/private_control_file.exe`，SHA-256 为 `5e38b54f2960324dfc5bc6191e483322a543e35831341478454430b302c0d131`，与现有工具原件相同。设置 `MMORPG_SQL_PRIVATE_FILE_HELPER` 与对应摘要，不跳过 Java 的整路径 ACL 和摘要检查。私有回执目录已通过该助手的 `validate-directory`。未编译、替换或修改 C++ / Go 工具。

## 运行与证据边界

33 模块普通 `clean verify` 保留全部默认用例，另启用真实 Redis/Kafka、固定六地图和专用 `game.it.authority.jdbc`。SQL 用例的历史参数存在混用：`game.it.mysql` 可被一部分 TiDB 专项借用，另一部分却断言实际 MySQL 方言，不能把所有用例连到一个后端后宣称两种数据库都验证。专项按实际依赖分开执行，物理身份及锁资格摘要随对应隔离实例设置。

保留 JVM 768 MiB、2 个处理器、单 fork，以及提交余量启动 12 GiB / 运行 8 GiB 的既有约束。仅在没有其他重型构建且受监测 IDE 身份空闲时运行；源码在启动前及进程家族退出后核验冻结摘要。

最近已完成的 `clean-verify-20261106-native-java-two` 退出 1：导航 95 项通过、战斗引擎 2390 项中 1 项既有跳过、旧信封兼容 5 项通过；discovery 2 项和 gateway-store 5 项因未设置私有助手失败，robot 1 项因闸门清单漏登记 equipment 失败。依赖失败使后续模块未执行，不能算全量通过。助手环境与 robot 清单现已修复，待新的完整运行结果确认。

其后 `clean-verify-20261106-full-env-two` 实际执行 4148 项，5 失败、2 错误、11 跳过；149 个旧跳过中 134 个 Redis、1 个 Kafka、3 个 Bash 用例已实际通过。错误定位到旧 TiDB 缺 DML 变量，以及 robot 清单更新后尚未同步的一条精确期望；均已有对应修正。

`clean-verify-20261106-v858-one` 因 Windows 进程采样耗时 8.65 秒超过原 4 秒观察间隔而中断；该次不是通过，也不是测试断言失败。独立核实其进程家族全部退出后，监督器 v4 只将最大采样间隔调整为 10 秒，保留所有 CPU、内存、进程身份与源码冻结要求；143 条监督器边界回归通过。

`clean-verify-20261106-v858-two` 正常结束并确认源码摘要一致、家族完全退出，执行 9300 项，19 失败、0 错误、446 条条件跳过。另有 login/scene 测试编译失败，gateway/data 被 reactor 跳过。失败包含 gateway-store 的 4 个 JDBC 超时夹具问题、match 的 14 个目录/观战/枚举旧测试问题、battle 的 1 个 Kafka 副本环境问题。普通全量并未开启全部 SQL 条件用例，不能把 446 条跳过计作通过。

Kafka 的全球战斗结果链要求至少 3 个 broker、RF 3、minISR 2；原单 broker 环境不能满足。补环境时保留现存 node 1 及其日志，增加两个 broker-only 节点，单 controller 不代表控制面高可用测试。旧兼容 RF 1 与全球 RF 3 的用例还需使用不同 topic 代次，不能改写或删除旧 topic 来掩盖不兼容。修复及补环境后继续验证，此处尚无全绿声明。

`baseline-repair-20261106-four` 是修复专项及此前未执行模块的完整测试，正常退出并核验源码一致，310 份 XML 与日志合计一致：3243 项、63 失败、16 错误、25 条条件跳过。Gateway pending-delete 4 项真实 TiDB、Match 修复 5 类、BattleConfiguration 31 项、Gateway 模块 114 项均通过；职业技能领域 11 项与分发 4 项、回档 RestoreBuilder 17 项也通过。失败分组为 Scene 35 个旧预期/夹具问题，Data 28 失败和 10 错误（H2 数据库时钟整数乘法溢出），以及 Login 6 个 Redis 端口约束错误。该专项不是全量成功。

登录 `PasswordFailureQuotaIntegrationTest` 读的是通用 `game.it.redis`，且要求本机隔离 13379；此前环境清单仅登记了帮会的独立配额参数，漏掉这一约束。13379 实例已独立启动且 PING 通过。后续全量与登录补验将通用地址设为 13379，不修改或绕过该测试的隔离校验。

`scene-data-repair-20261106-two` 已正常退出 0，源码冻结、根进程退出及最终进程快照均核验通过。18 个测试类合计 389 项，0 失败、0 错误、0 跳过，JUnit XML 与 Maven 汇总一致。覆盖登录真实 Redis 配额、场景技能初始存档/属性/战斗期限与闸门，以及 H2 巡检数据库毫秒时钟。H2 的兼容处理仅在测试夹具替换时钟表达式；生产 MySQL/TiDB SQL 未修改。此结果是修复专项通过，后续全量与各后端专项仍须独立验证。

`full-tidb-20261106-three` 被宿主重启中断，不能认定正常退出或完整回归。Windows 系统事件记录 09:44:37（America/New_York）的重启请求与 09:45:11 的新启动时间；原 Maven、Surefire 和监督进程均已消失，监督器没有最终 result。已有 281 份完成的 XML 共 4630 项、0 断言失败、4 错误、1 跳过，原 XML/txt/dump 与日志已复制到 `full-tidb-20261106-three-interrupted-evidence`。这些只是中断前的完成报告，不覆盖未跑或尚未结束的测试。玩家存储 211 项虽然断言全过，共用 fork 仍触发累计 1800 秒超时；下一轮改为每类独立 JVM、仍串行单 fork，保留 1800 秒限制及全部资源监督。

本轮发现好友/全球匹配原有的固定初始化语句附带 `innodb_lock_wait_timeout=3`，统一物理连接保护层却没有登记该精确语句，导致好友真实连接初始化失败。Java 现补充这一固定策略，并要求 TiDB 初始化后读回恰一行、非 NULL、值为 3；不放开任意会话 SET，也不向 MySQL/H2 发送 TiDB 策略。新增 4 个针对性测试已通过。聊天的 3 个错误另属启动参数：`game.it.chat.tidb` 要求裸 `127.0.0.1:24000`，现已修正，不改测试约束。

重启恢复后重新采集全部 2958 个旧输入，摘要与本轮冻结原件一致，并记录没有旧 Java 进程残留；没有伪造原监督器的最终退出结果。随后仅应用上述 2 个 Java 修复文件，重新冻结 2959 个输入。TiDB/PD/TiKV 按已核验完整容器 ID、原镜像与资源限额恢复，保留原卷，实际重新确认 VERSION、DML 预算变量和死锁历史设置。其他依赖按后续专项需要恢复，不使用已有业务容器替代。

重启后私有文件助手曾发生 Java CreateProcess 错误 5，导致 SQL 修复专项前两次实际运行失败；另一次启动因其他 Go 构建占用而中止，未开始 Maven。这些原件均保留。没有改助手文件、ACL 或安全软件设置。独立受限 Java 诊断对目录校验、创建、读取各执行 3 次（共 9 次、每次不重试），全部成功；随后 `sql-initialization-repair-20261106-four` 退出 0，5 个测试类合计 19 项、0 失败、0 错误、1 个 MySQL 条件跳过。新连接保护 4 项、读回保护 3 项、好友真实 TiDB 3 项和聊天真实 TiDB 3 项均通过，JUnit 与日志一致，源码冻结和进程退出核验通过。该结果证明当前专项调用链可用，不能据此断言先前访问拒绝的永久根因已消除；MySQL 分支仍由独立后端专项补验。

Redis 13379 与 Kafka 三个原 broker 已按原容器 ID、镜像与限额恢复，未重建旧日志。恢复后 Redis PING、三个未 fenced 的 broker API 与原 Kafka cluster ID 均已实查；controller 仍只有一个，不计为控制面 HA 验证。

全量重启过程中，其他任务的 Go 构建/真实 SQL 测试与本机内存压力曾同时出现。本任务正常停止尚未使用的 TiDB/Kafka，保留卷和全部原件，待重型进程退出后恢复；未停止其他任务。`full-tidb-20261106-four` 在启动观察第 41 秒发现新的 `go.exe`，启动记录明确 `launched=false`，没有 Maven 或测试结果。`full-tidb-20261106-five` 则完成两个观察窗口，于 10:52:02（America/New_York）实际启动 Maven，使用每类独立 JVM、单 fork、2959 个冻结输入；此处登记为运行中，最终结论以完整退出证据及自动归档的 XML 为准。

`full-tidb-20261106-five` 仍运行中。11:20:58 已完成玩家存储 SQL 104 项（1198.145 秒），11:29:21 已完成交易排空 SQL 19 项（501.554 秒），11:30:03 已完成交易资格 2 项（41.750 秒）；三组均为 0 失败、0 错误、0 跳过。这些时间与数量来自本轮生成的独立 JUnit XML，只代表已完成部分，不代替全量退出结果。新增功能仍在仓外候选目录，未改变本轮 2959 项冻结输入。

测试结束后正常停止本项目拥有的临时服务，保留诊断原件及卷；不停止或清理其他任务的容器。
