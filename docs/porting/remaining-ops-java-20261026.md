# 运维、分布式与强一致能力复核（2026-10-26）

Java 基线 `03a0708d35b1e938d59f51c4e868b45f88ed9da5`；C++ / Go 固定基线 `92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5`。C++ 内容经 `git show` 读取，不读取未提交实现作为移植依据。以下是源码与接线盘点，不是生产验收报告；本轮新增代码尚未编译、运行单测或连接实环境。旧规格的批号用于索引，不代表当前完成状态。

## 1. 逐项盘点与后续批次

| 范围 | 固定 C++ / Go 基线证据 | Java 已有生产实现与证据 | 本次确认的差额、Java 实现方向与依赖 |
| --- | --- | --- | --- |
| 7.1b 整栈部署 | `deploy/debug/compose.game.yml` 有服务、健康依赖、迁移与 Kafka 初始化；`deploy/k8s/manifests/` 已覆盖多服务 | 本轮 `deploy/compose/stack.yaml` 已装配 17 服务 + robot，TiDB 初始化、显式路由世代和 `.github/workflows/integration.yml` 的 stack job 已落；镜像继续使用统一 Dockerfile / build-info | 已做 Compose 解析、模块与配置守卫反例和脚本语法检查，未启动整栈。发行镜像清单、生产 K8s 与 HA 仍待实现/验收；开发单节点配置不证明生产能力。见 `java-stack-20261026.md` 与 `inventory/full-migration-release-20261026.md`。 |
| 7.3 合服 | `tools/merge_zone/merge_run.go`、`scene_hot_state.go`、`scene_registry.go`：持原 run 围栏，先证明源区注册为空，再清位置、场景、频道、节点热键，最后摘 zone 索引；支持 dry-run、分批与续跑 | `ZoneMergeStore` 已有 SQL 全局冻结、128 个源/目标分片围栏、计划摘要、审批、分批迁移、归属/epoch 核验、退役 marker 与独立 release；`MergeZone` 明确不改 Redis。`ZoneMergeSqlAuthorityTiDbTest` 是真实 TiDB 验证入口 | 本轮新增 §4 的 v2 全类型按区目录、永久发布屏障和 `RetireZoneHotState`。代码及测试已写，待统一构建与实库运行。玩家位置、SQL 原件、epoch/seq、版本水位全部保留；没有执行过热退休后的自动重开区能力，不能标整项完全验收。 |
| 7.4 一致性巡检 | `tools/data_consistency_check/main.go`、`go/data_service/internal/routetool/auditor.go`：只读核对权威/投影、反向扫描、差异二读、区分暂态和缺依赖 | Java 已有玩家排障导出/导入 `game-data/.../tools/playerbundle/` 与命名空间/真实实例身份核验；`docs/design/player-bundle-debug.md` 已记录两套真实切片、epoch 与完整 PlayerState 字节往返，不应再列为“首次验收待做” | 缺覆盖全服玩家、帮会、好友、邮件、经济原件及退休区引用的只读巡检 CLI。按 Java SQL 权威表和 Redis 投影构建分页核验；报告 PASS/WARN/BLOCK/NOT_CHECKED，必需连接缺失不能算通过；二读排除并发变化，不自动修复。 |
| 7.4 存盘压测 | `go/db/cmd/data_stress/main.go` 与 `docs/design/data-consistency-stress-testing.md`：顺序/签名、最高已确认进度、积压与崩溃恢复 | Java 玩家存盘通过 `PlayerStore`、owner_epoch 与 final-save 原件；已有 SQL/真实 Kafka/回档往返测试，不采用 Go DBTask 队列作为玩家存储架构 | 缺 Java 持续存盘与故障验证工具。隔离命名空间/合成玩家，经现有存盘接缝或机器人发请求，记录最高确认 seq/epoch/状态摘要；停进程、未知提交、租约切换后验证不回退、不重复经济操作，不能用新请求 ID 掩盖未知提交。依赖巡检及独占实环境。 |
| 7.5 契约 BREAK | `tools/proto_generator/protogen/internal/persistschema/ledger.go` 已做持久字段冻结/退役台账；`docs/design/published-contract-compatibility-20261017.md` 是双发布契约兼容方案，不能把方案当已完成运行时版本入口 | `tools/ContractSync.java` 做受管输入重建/漂移检测，`.github/workflows/contract-drift.yml` 提示变化；当前没有通用客户端 BREAK 审批闸 | 缺消息号、tip、字段 wire/presence、enum 的语义差异闸与明确审批原件。Java 工具在同步前读描述与权威输入，不手改受管产物；不能用持久字段台账代替客户端兼容判据，也不能猜旧客户端的业务 UUID。需先确定发行契约版本及审批格式。 |
| 7.5 导航 | C++ 当前地图导航由原生导航系统提供；以固定源码和受管地图输入为准 | `CurrentMapNavigation` / `SceneNavigation` 已支持当前三图保守栅格导航；SceneWorld 移动裁决、撞墙清速与 133 通知已接通 | “Java 尚无导航”已过时。更广地图/原生多边形寻路与动态网格能力需要另作可达性、碰撞与路径质量对照，不能凭三图移动用例宣称全部导航等价。 |
| 7.6 发布/告警 | `tools/scripts/release_preflight.ps1`、`make_release.ps1`、`lib/release_common.ps1`、`.github/workflows/release.yml` 有秘密/配置静态预检、版本/提交/镜像/制品清单；K8s 中有资产通道、路由、DB 重试、epoch、持久化、玩家收件箱、placement、rank、Redis 告警 | Java 已有 Dockerfile、build-info、指标与 readiness 组件；未发现 Java K8s/Helm、发行工作流或对应告警规则 | 缺 Java 运维 CLI、制品清单、部署与 HA 演练。工具使用 Java 显式配置并调用标准部署接口，不移植 PowerShell/Go 服务架构。先按真实服务矩阵形成整栈，再生成发布门禁、PDB/反亲和/滚动策略及告警，失败演练须核经济和归属不变量。 |
| 审计 Kafka 持久性 | `deploy/k8s/manifests/infra/kafka-topic-init.yaml` 有 topic/分区/保留期与按部署选择 RF1/RF3；不能据此推断所有生产路径已强制 minISR | `AuditPipeline` 已启用 `acks=all` 与幂等 producer；`DataNode` 关闭自动提交；原 audit topic 初始化只核分区和主人配置 | 本轮补显式副本/minISR 生产门禁与真实 metadata 检验，见 §3。既有对局结果 `BattleResultTopics` 已有独立 RF/minISR 门禁，未改其路径。 |

## 2. 分布式与强一致不能仅以类名验收

| 验收视角 | 当前生产接线 | 已有验证入口及仍需证据 |
| --- | --- | --- |
| 多实例调度与领导者围栏 | `WorldChannelControlPlane.start` 创建 coordinator、按 zone 的 `WorldLeaderLock`、独立续租 runner 和目录 publisher；`WorldChannelStore.write` 的 Lua 同时比 leader token 与 planVersion；目录发布也比两者。SceneIdAllocator 失租回调连接 coordinator | `WorldLeaderLockTest`、`WorldPlanRedisIntegrationTest`、planner/rebalance 测试。仍需多副本实环境失租/响应延迟/旧 leader 恢复验证；不能把 Redis HA 切换后的持久水位当作已由这些单测证明。 |
| 节点与玩家持有者 | `SceneNode.start` 领取 NodeIdLease；玩家权威存储写入携 owner_epoch，final-save 使用原准入和终态记录；不同于只靠节点目录 TTL | `NodeIdLeaseTest`、玩家存储/终态恢复测试及本轮排空工作。节点目录空本身不是节点确实停机的证明；退休热清理依赖 §4。 |
| TiDB 强读 | `StrongTiDbConnectionPolicy` 在 Druid 初始化前安装 snapshot 空/staleness=0/lowres=OFF/strict/leader/pessimistic/RC，建表/提供服务前在同一连接核验。`PlayerSqlAuthorityAutoConfiguration`、`GlobalMatchConfiguration`、`ActivityConfiguration` 的生产门禁已接；好友本轮另有专属门禁修正 | `GlobalMatchStoreIntegrationTest`、`BattleCompletionStoreTiDbTest`、`GuildActivityStoreTiDbTest`、`AssetOpSqlProofTiDbTest`、`ZoneMergeSqlAuthorityTiDbTest` 等是实库入口，默认跳过不等于通过。邮件/排行榜本轮发现并补上 prod 禁止 require-tidb=false，见 §3。 |
| Kafka 持久确认和位点 | `AuditPipeline` producer 配置；`DataNode.consumerProperties` 的 `enable.auto.commit=false`；`ConsumerLoop` 先 sink.insert 成功再 commitSync，重试暂停期间仍轮询，被收回分区即废弃批次不提交 | `ConsumerLoopTest` 覆盖落库后提交、暂态失败、毒丸隔离、rebalance 与停止；`KafkaAuditPipelineTest` 覆盖真 broker 生产→消费→落库、重复/坏记录、换消费者与大快照。新门禁测试不替代 RF3 下 broker 故障实测。 |
| 跨区与滚动恢复 | Java 已有 TravelRouter 的 SqlZoneMergeFence 接线、跨区重定向、待落点/新 epoch 接管、主动切线与终态补偿；本轮继续 5.5 排空疏散 | 现有 robot channel/travel 与 SQL 恢复测试应纳入部署验收。C++ `rolling-update-restart-resilience-tests.md` 明注历史实验边界，曾出现 gate 滚动后会话丢失；“连接仍在”不是资产/世界/对局连续性的证明。不能照历史标题宣称 HA 验收已完成。 |

实环境还应逐项注入：停一个调度副本、旧租约恢复、延迟回包、TiDB leader 切换/超时、Kafka ISR 不足/消费者 rebalance、Redis 重启或主从切换、跨区源节点释放前后退出、排空时长耗尽。每项留原请求、SQL 原件、epoch/version、位点、客户端终局与最终状态摘要；同一未知操作必须重试原 ID。

## 3. 本轮已落盘的生产门禁（待构建验证）

`game-audit` 新增 `AuditTopicDurability`，复用 TopicAdmin / KafkaTopicAdmin：所有分区的不同 broker 副本数取最小值；元数据缺失或 RF 不足拒绝，不从创建参数推断成功，不自动改分区或做副本重分配。OWN 先核 RF 再提高 minISR，读回确认并保留更强已有值；VERIFY_ONLY 不创建/不修改；CREATE_AND_VERIFY 不改既有配置。显式强模式同时要求 `unclean.leader.election.enable=false`。

`AuditProperties.validateFor(RunMode)` 显式校验：prod 的启用 scene 审计，以及始终工作的 data 消费者，要求 RF≥3、minISR≥2。`enabled=false` 只关 scene producer，不能绕过 data 门禁。原单 broker 默认仅在明确 dev/test 时可装配；缺省或写错运行模式按 prod 拒绝弱配置。SceneNodeConfiguration/DataConfiguration 已接真实工厂，SceneNode/DataNode 传递 minISR topic 规格。

部署需显式设置 `GAME_AUDIT_REPLICATION_FACTOR=3`、`GAME_AUDIT_MIN_IN_SYNC_REPLICAS=2`，并保证现有 topic 的**每个**分区至少三个 broker 副本。首次迁移建议先由 data 的 OWN 初始化完成 minISR，再启动生产 scene；既有 RF1 topic 必须由运维迁移或升代次，代码不会自动降级。单 broker 本机路径显式设置 `GAME_RUN_MODE=dev` 或 `test`；默认 prod 不再默默沿用 RF1。

MailConfiguration / RankConfiguration 的连接策略工厂经 Spring Environment 读取 `game.run-mode` 与各自 `game.mail.require-tidb` / `game.rank.require-tidb`；prod、缺省和未知模式拒绝 false，dev/test 可显式关闭。默认仍为 true。未更改业务存储、事务或受管客户端协议。

新增测试：`AuditDurabilityTest`（创建竞态、每 topic 最小 RF、metadata 缺失、OWN/只读差异、保持更强 minISR、坏配置修复与读回、unclean、broker 不可达），`KafkaTopicAdminMetadataTest`（真适配器聚合全部分区并按 broker ID 去重），`AuditPropertiesTest`（真实配置绑定），`SceneAuditPolicyTest` / `DataAuditPolicyTest`（真实工厂拒绝弱生产配置），`MailProductionPolicyTest` / `RankProductionPolicyTest`。均待统一构建；未删改旧断言。

内部资产审计枚举增加 `TX_EQUIP_ENHANCE=27`、`TX_EQUIP_INLAY=28`、`TX_EQUIP_GEM_REMOVE=29`，与固定 C++ 的 `proto/common/rollback/transaction_log.proto` 对应；scene 资产流水映射由装备批次提供。这是 Java 自有内部协议，不是手改客户端受管契约。

## 4. 7.3 永久退休与热状态工具（已落盘，待构建/实库验证）

`ZoneMergeStore.withRetirementProof` 持有同一 SQL 事务的 route_meta 与源/目标 128 个分片锁，限时 30 秒：只接受正向合服 state=5（finish 完成、尚未 release），核 run/hash/generation、zone_merged_into 精确原件、源区 manual_status=2、迁后条目、全服 owner_released 与 final-save。回调失败保留原冻结；已有 release、反向撤销、marker 或世代改变均拒绝。Redis 成功但 SQL 提交结果未知时仍保留永久屏障，续跑原 run，绝不自动解冻。

`NodeDirectory` 对所有非零区类型统一写 `world:{z:<zone>}:nodes:<type>` 哈希与 `node-expiry:<type>` ZSET，Redis TIME 决定可见性。`node-seen:<type>` 永久记录迁移水位与 epoch/instance：低代次、同代异实例拒绝；正常摘除必须匹配当前对象最后确认发布的完整 protobuf，旧实例迟到 stop 不会删新原件。scene 单飞发布循环、gate 固定周期任务串行化同实例同代快照，不能另加并行写者。内部 gate/scene 消息和生产发布均带 lease_epoch；epoch=0 只兼容旧数据/测试，不能覆写已有正代水位。新增 `listAsync` 严格解析，坏原件或故障异常完成；同步 list 保留跳过单条坏原件及抛原 RuntimeException 的约定。

永久 `world:{z:<zone>}:retired` 原件包含 run/hash/generation/source/target 与外部部署证据 SHA-256，无 TTL、不删除。节点发布、world leader 竞选/续期、计划写入、频道目录发布和预占都在同槽 Lua 中检查 EXISTS；退休后计划/频道/节点读面返回空。`world:zones` 只保留索引，读时强读过滤退休区，不跨槽 SREM 后冒充原子清理。旧 RMapCache 仅对未退休且未迁移过的 node 回退；迁移后过期也不再暴露旧值。global zone 0 仍走旧目录、不能用于退休工具。

迁移要求：**本轮 v2 默认写入；旧读方看不到新条目，不能混合启动旧消费者。** 可先发布一个只升级读方的过渡版本，再启用 v2 写者，或者维护停机后一起升级全部读写进程；本轮没有提供自动双写开关。执行退休前全部旧二进制必须退出。工具核对外部已审清单的命名空间、源区、发布 commit、协议 2、全部 node types 和 `old-writers-stopped=true`，摘要精确绑定原字节；这是明确的部署审批证据，不是代码自动证明旧进程已停止。Redis 另核全部旧目录为空、源区 node-id 租约确实不存在、v2 到期索引无活节点，任何查询失败拒绝。原子 BEGIN 再核类型集合/活节点、leader token 与 planVersion，封住预检后恢复发布的竞态。节点租约本身保留；若此后新进程拿到租约，其目录发布仍被永久屏障拒绝，SQL 原冻结保护玩家写入。

`RetiredZoneRedis` 先设永久屏障，再按白名单清理 `ch/desired/cooldown/directory:<config>/resv:<scene>/nodes:<type>/node-expiry:<type>`。每次最多删 256 个键，SCAN 最多 20,000 个候选，所有操作受 SQL 预算约束；屏障后重扫，报告 candidates/deleted/remaining/version。清理 Lua 再比原件、工具 token 和版本，失锁或变版不删；工具锁 30 秒且仅按本 token 释放。原件、目录格式/类型证据、node-seen、world:zones、ver、全部 node-id/epoch、玩家位置与 seq/owner_epoch、SQL 原件和其它区都保留。玩家位置在 Java 携 epoch/seq 墓碑，不能照 Go DEL；清理未证明的玩家位置不是本工具范围。

CLI 为 `com.game.data.tools.RetireZoneHotState`，用构建后的 game-data jar 按现有 PropertiesLauncher 的 loader.main 调用：`plan|apply --run <UUID> --hash <计划SHA256> --generation <UUID> --deployment <外部properties路径> --deployment-sha256 <已审文件SHA256> [--expect-database <真实业务库>]`。连接显式读取 `GAME_ZONE_MERGE_JDBC`、`GAME_MYSQL_USER`、`GAME_MYSQL_PASSWORD`、`GAME_RETIRE_REDIS_ADDRESS` 与可选 `GAME_REDIS_PASSWORD`，命名空间同 GameNamespaceBinder。Redis DB 复用服务的 `RedisProperties` 标准绑定：默认 12，`GAME_REDIS_DATABASE` 可覆盖，`-Dgame.redis.database` 优先，负数/非整数拒绝；没有工具独立的 DB 参数。强制真 TiDB 强读与 DATABASE 核对，Redis 地址为明确的 `redis[s]://host:port`、重试 0、单次超时 2 秒；当前 CLI 连接单节点/代理地址，不宣称已完成 Redis Cluster 拓扑运行验收。执行顺序是 MergeZone finish → 本工具 plan → apply，remaining>0 时同参数逐批续跑 → 独立 MergeZone release；release 不删除退休屏障。

外部 properties 只有以下六项（值按真实发布填写）：

```properties
namespace=game
source-zone=1
release-commit=<已升级二进制的40位commit>
directory-protocol=2
node-types=gate,scene
old-writers-stopped=true
```

新增测试：`RetirementDeploymentTest`（证据摘要、必需字段、白名单与原件绑定）、`NodeDirectoryContractTest`（严格异步/同步异常/同槽）、`ZoneDirectoryRedisIntegrationTest`（v1→v2、过期不回退、旧实例删除、精确大整数代次、全类型退休与 zone0）、`RetiredZoneRedisIntegrationTest`（只读 plan、永久拒写、活节点/租约拒绝、分批/重跑、BEGIN 前复活、CLEAN 失 token/变版）、`ZoneMergeSqlAuthorityTiDbTest` 的退休原件/回调失败/与 release 互斥用例。均未运行；实 Redis/TiDB 条件用例被默认跳过不能算已验收。

重开区边界：C++ 固定基线 `main.go:229` 有 `unmerge`，`scene_hot_state.go` 按“源区永久下线”清热键；本次有限读取未找到与 Java 永久 retired 等价的恢复屏障协议，不能据此宣称所有撤销能力对齐。Java 已有 SQL 原件反向合服，但**执行本工具后，反向 SQL 完成也不会自动重新开放原区**；需要另批显式恢复协议，绝不通过 DEL retired 回滚。Redis 丢失持久状态的灾难恢复同样必须先保留 SQL 冻结并核原件，不能把当前单元测试说成 HA 持久性证明。

## 5. 实施顺序和工作区新增候选

优先顺序：审计/强读生产门禁 → 5.5 排空与节点租约证明 → 7.3 发布退休屏障/热清理 → 只读巡检/存盘故障工具 → 整栈 Compose 与 CI → Java 发布预检/K8s/告警/HA 实验。契约 BREAK 闸可独立并行；导航完整差额需专项证据。每批同时记录功能测试和真实部署运行证据，生产外部依赖缺失不可用 skip 冒充验收通过。

2026-10-26 C++ 工作区只记录状态候选：`.github/workflows/cpp-build-ci.yml`、`docs/design/agones-scene-node-high-density.md`、`tools/scripts/verify_full_chain.ps1`、第三方 SDK/CMake/项目生成器及其测试正在被其它任务修改，另有 third_party 子模块脏状态。它们尚未纳入固定基线结论；待对方形成稳定提交后重新以 commit 复核，不复制当前未稳定文件。
