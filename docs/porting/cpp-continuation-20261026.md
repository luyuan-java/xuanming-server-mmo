# 2026-10-26 C++ / Go → Java 续移植复核与实现记录

本批接续 2.10 的已实现原语，处理 4.3b、4.6b、5.6、6.6、7.2d。旧路线图是历史快照，不能据其「待做」直接重写当前实现。
本批最终全量 `clean verify` 已通过：11190 项，0 失败、0 错误、1019 跳过。真依赖集成、真实服务 robot 与双 Unity 尚未验收，详见 §7。

## 1. 基线与边界

| 来源 | 本批复核点 |
|---|---|
| C++ / Go | `275f9c5c00b7799a551a33cef3a17297de036305` |
| Unity | `9ccb99c51579c4028de1487441e75b986f5922a1`，已含 `40e6fdd43` 组队走路跟随 |
| Java 工作基线 | `55bfd8a3818b985d71bf12bd3762660a81f54bc2`，包含完整 2.10；继承主干已有全球 SQL 归属、组队、帮会与匹配 |
| 工作方式 | 独立分支 `port/cpp-continuation-20261026`；保留 2.10 审查分支；实现提交 `8c9a47da`、阶段记录 `86d2b385` 已进入主干，最终构建源码与实现提交一致 |

本批不手改 `game-proto`、`game-table`、配置表、地图或 `contract/SOURCE.properties`。新增与扩展的 proto 仅为 `game-api` 内部协议。
`GetMyTeamRequest.regroup`、43 的目录请求、31 的目录、157 的人数档均已同步。生产匹配已按 SQL 人数分桶，不重新实现旧 Redis 队列。
收口时复核 C++ / Go 新 HEAD `45a808c7f`：相对上述基线仅增加文档与三份匹配 SQL 测试修改，没有本批生产行为变更，因此保留已复核的生产基线。

## 2. 4.3b 组队重聚与可见性

- 组队提交之后异步通知 scene：加入成员可跟随；保留成员、离队成员与被修复的成员刷新投影；显式归队只提示非队长成员。
  通知不是授权凭据，scene 必须强读当前 SQL 队伍与战斗锁。
  scene 通知使用独立有界工作池和预算，定位 / RPC 超时不能耗尽已提交的客户端快照推送；两条通知都不影响原提交回包。
  另按 scene 目录定期回收下线、换实例和撤 RPC 的引用，目录读取失败保留；信号池排空后再停清扫器与 RPC 客户端。
- 每个当前玩家实例合并在途读取；新通知使旧结果失效。回调核对对象、归属 epoch、会话生命周期，不能移动已离开的旧实体。
  队长投影不足时补读队长，再重读发起者，防等待期间转让、离队或战斗锁变化沿用旧决定。
- 同场景水平距离大于 8 米，或双向不可见，调用 2.10 瞬移原语到队长位置和朝向，原因 5；近距互见不瞬移。
  跨场景继续走既有换场路径。冻结、战斗、换场在途等互斥条件保留。
- AOI 仍最多 100 个目标。近距队友可逐出最早进入的普通目标，不能逐出队友；显式发 64，并保持正反向观察索引一致。
  队伍提示只服务于可见性，不用于资产、开战或成员权限判断。
- robot `team --team-regroup-probe` 使用 dev 瞬移布置远距、近距、高度不可见，实际触发源仍为入队或 `regroup` RPC。

## 3. 4.6b 帮会活动终局与恢复

当前 Java 已有灯会、聚餐、试炼及 SQL 幂等发奖；旧路线图的「五个 1006 占位」不再成立。本批补强以下边界：

- 取消只在可证实的非 COMPLETE 终态成立；矛盾绑定不得当成取消成功。
- 战报先证明真实终局、原始 launch / battle 绑定、唯一且完整的队伍 0 名单，以及逃跑 / 阵亡子集；证据不完整保留活动占用。
  只有已证明的终局遇到坏奖励配置才进入 poison。阵亡仍可领奖，逃跑才排除。
- 恢复逐条先延后下次检查再做外部 I/O，一条失败不能持续占住扫描首位。解散按无符号主键顺序清进度，保留永久幂等账本。
- robot `guild` 追加真实 239–243 活动流程：贡献与背包、次数耗尽、拒绝邀请、组队试炼、177→143、直连终局、发奖与清占用。
  缺少配表或活动开关应明确失败，不能静默算通过。

## 4. 5.6 主动切线

实现按当前 Java 的频道计划与 SQL 交接组织，保留共享客户端契约：

- `WorldChannel.channel_no` 保存稳定线号，与哈希放置 `slot` 分离。同图 ACTIVE 与 DRAINING 共用号池；真正回收后才能复用。
  旧值 0、坏值、重复号由领导者在原有 token + version CAS 内按无符号 scene_id 稳定修复。
- manager 单独按目录发布周期构建 31 的读模型，自动伸缩关闭也发布；发布受领导令牌与计划版本围栏保护，有短 TTL。
  状态优先级为回收中、不可用、爆满、繁忙、流畅。目录用于显示，不能代替准入权威。
  Java 默认每 2 秒发布，有效期为三个发布周期加 5 秒竞选间隔，共 11 秒。读取失败保留旧值直到过期；确认空计划则发布空目录。
- 43 不带开关时不增加 Redis I/O；主世界带开关时异步读取目录；失败、缺失或坏内容仍返回无目录的 31。
  旧玩家、旧会话或换场后的回调丢弃；镜像和副本不查目录。
- 显式主世界手动切线，即使目标在本节点，也先经 manager 只读校验。拒绝不预占、不写冷却；客户端受理后的业务拒绝仍为 23 {3023}。
  通过预检后保留既有软预占；目录仍上报、计划已缺席的世界线拒绝，只有目录明确标识的镜像 / 副本保留私有实例路径。
  自动选图、登录、跟随、疏散、镜像和交接重放保留原路径。
- 冷却按全局 player_id 存 TTL。本地切换成功或跨节点 SQL 交接确定提交后才写；跨节点在 gate 改绑之前完成有界写入。
  写失败不能回滚已成功迁移。不得在 selector 选中时起算。
  SQL 成功后的异步续接必须纳入停服交接计数，先安排必要的 E+1 释放再允许存储池关闭；不能因回调仍在途而误报干净停机。
  排空后另采样仓储累计写失败：释放被拒 / 失败、最终保存未确认 / 冲突均使本次停服保守标为不干净；该计数不是当前队列长度。
  本机冷却写在途还保留按玩家 ID 的短闸，重连重建实体不能立即绕过；旧回调不能清掉新记录。
- 客户端镜像取号、换图选目标的调用失败统一使用既有 3028；dev 建副本管理口仍为原有 1003。

有意差异：Go 的独立线号 HASH 合并进 Java 已有的频道计划；Java 冷却锚点是已确认的切换成功，不是第一跳选路成功。
归属仍由现有 SQL 原始身份与 generation 围栏决定，不用新目录或冷却键替代 SQL。
`robot channel-smoke` 以首次切换固定时间区间验证冷却与拒绝不续期；短冷却、慢到达或观测窗口不足明确输出 `cooldown=skipped`，不会冒充完整验收。

## 5. 6.6 PVE 人数档与节奏

- 生产全球匹配保留现有 SQL 分档；补齐超过配表上限（含 uint32 高半区）的 16002 拒绝、未配置的 16003，以及固定范围的人数档指标。
  0 按配表上限；已持久化原请求的幂等重放优先于新校验。旧 dev / test 兼容队列对不支持的非满员显式档拒绝，不静默放进满员队列。
- 普通 PVE 按队伍 0 的玩家数乘以有效 `Dungeon.monstergroup` 长度，循环原怪组，最多 10 只。
  宠物、另一队玩家不计；活动 kind 非 0 不缩放；空怪组保留原兜底；不增加随机数消费。
- 自动回合间隔为 `max(2000ms, 上一回合行动序列长度 × 250ms)`。手动窗口仍为 6000ms，尚无行动序列的首回合为 2000ms。
- robot `pve-team-size`：五个新号，真实网络探测与同一请求 UUID 的加入 / 查询 / 取消，覆盖 0 默认满员、超限、不同档隔离、取消重排、两人四怪与单人两怪。
  既有 `team`、`guild` 同步补齐全球匹配要求的网络 profile；组队写操作采用请求 UUID，未知结果重试复用原件，取消确认后才创建新请求。
  帮会普通写协议没有 string request_id，继续沿用其已同步契约与试炼持久 launch 原件。

## 6. 7.2d 回档验证边界

- 保留期拒绝后的钳位重查使用 cutoff + 900000ms，「不可证明」判据一致；溢出拒绝。
- 清理终态操作前推进无 TTL、只增的 Redis 水位；推进失败不删终态。已有水位即使低值 / 等值推进也清掉误设 TTL。
- 帮会查询下界为当前保留期与水位 + 1 小时的较大者；水位读失败返回不可用，不能因调大保留期放行已删除历史。
- 启动校验 recheck-budget ≥ check-budget。写事务 COMMIT 结果未知的玩家也纳入独立写后复查；中断 / 心跳丢失停止新增写，但不跳过已写集合复查。
- 保留 RESTORED 的已提交明细与 `post_write_unverified`，不得被外围异常覆盖成普通存档错误。

运维约束：备份、迁移 Redis 时必须保留终态清理水位。合服工具当前不自动清 Redis，新目录键随区退役清理；线号随原有计划清理，玩家冷却自行过期。

## 7. 验证记录

首次全量 `mvn -o -B clean verify` 用时 2 分 20 秒，协议、表、网络、common、battle-engine、pbmysql、api 通过；在 discovery 的 RedisKeysGoldenTest 停止：新增三个键方法后，总数期望仍为 79，实际 82（样本已包含新键）。该模块 303 项，1 失败、0 错误、117 跳过；后续模块尚未执行。日志保存在本机审查证据目录，不提交生成物。各新增单元、SQL 故障注入和真 Redis / MySQL / TiDB 用例的最终结果须以最终日志为准。
真依赖用例默认跳过不能计为已验收，robot 真服务与双 Unity 结果单列。2.10 的既有 11018 项通过证据只覆盖本批之前的基线。

第二次全量 `mvn -o -B -fae verify` 用时 13 分 45 秒，11190 项、7 失败、0 错误、1019 跳过。失败分别为固定标签集合、新增匹配 tip 表与标点、归队结果期望、机器人帮助清单、帮会方法对应消息号，以及一处真实回归：选目标后的战斗检查错误阻止了同节点换场。已恢复原有本节点放行 / 跨节点拦截行为，原战斗互斥用例未改；归队用例保留锁、ACK 和读取序列断言，并补位置、互见与唯一原因 5 瞬移断言。其余修正精确契约期望或帮助文本，不删除断言。失败 XML、全日志与计数已保留。

最终 `mvn -o -B -fae clean verify` 于 2026-10-26 09:18:23 EDT 成功，耗时 14 分 54 秒，31 个 reactor 项目全部成功。855 份测试报告共 11190 项：0 失败、0 错误、1019 跳过，实际执行 10171 项。关键模块：scene 1808、scene-manager 296、team 387、guild 689、match 1888、battle-engine 2261、battle 574、data 397、robot 685 项，均无失败或错误。构建前后 2655 个源码、pom 与配置文件的 SHA-256 完全一致；构建期间只发生文档提交，未改变验证输入。

本机审查证据保存在 `D:\luyuan\tools\review-port-next\evidence`：`clean-verify-final.log`、`clean-verify-final-counts.json`、`clean-verify-final-input-check.json`、`clean-verify-final-reports.zip`。前两次失败证据仍保留。没有启动真实服务或 Unity，未启用真 Redis / MySQL / TiDB 用例；跳过项不能记为验收通过。

主要回归入口：

| 范围 | 自动测试入口 | 另需实环境验证 |
|---|---|---|
| 组队 | `TeamPushesTest`、`SceneTeamEndpointSweeperTest`、`TeamFollowServiceTest`、`TeamViewIndexTest`、`TeamViewWorldTest` | `robot team --team-regroup-probe`、双 Unity 移动与归队 |
| 帮会 | `GuildTrialCancellationTest`、`GuildTrialTerminalTest`、`GuildActivityRuntimeTest` | `GuildActivityStoreTiDbTest`、`robot guild` 活动段 |
| 切线 | `ChannelNumbersTest`、`ClientChannelPickTest`、`ChannelDirectoryPublisherTest`、`SharedPlayerChannelsTest`、`ChannelSwitchWorldTest` | `ChannelSwitchRedisIntegrationTest`、双线 `robot channel-smoke` |
| PVE | `GlobalJoinSizeTest`、`PveTeamScalingTest`、`RoundTimerTest`、`PveTeamSizeChecksTest`、`PveNetworkProbeTest` | 全球匹配与真实 TCP battle 下 `robot pve-team-size` |
| 回档 | `GuildDivergenceGateTest`、`RollbackCommitUncertaintySqlTest`、`JdbcGuildAssetStoreUnitTest`、`GuildInternalQueriesTest` | `TerminalCleanupWatermarkRedisIntegrationTest`、真实 SQL 清理与回档演练 |

## 8. 后续库存

本批不把整个移植路线图视为已完成。4.1c、装备 0.4 / 2.11 / 6.7、5.5、7.1b、7.2c、7.4–7.6 等必须按最新两边源码重新盘点。
其中合服、全球匹配和全球帮会已在本批基线存在大量后续实现，旧文档的「待做」不能作为新开工依据。

等待构建期间的有限只读盘点（不代表本批已实现或重跑验收）：

| 项目 | 当前证据与实际剩余范围 |
|---|---|
| 0.4 / 2.11 / 6.7 | 相关契约已同步：45 张表、pending 为空，装备属性 / 强化 / 宝石表、246/247/249–252 与 28000–28021 文案已在；Unity 已有实际操作 UI。Java 的容器、装备槽和整实例托管基础已有，但 BagItemState/运行时实例仍无 equip，穿脱回 1006，面板不填 bonus/combat，战斗不消费 combat。建议下一独立批做装备一期与战斗闭环，强化 / 镶嵌另批；不能再等待已经完成的导表，也不能重写现有 SQL 资产底座。 |
| 4.1c | `RecommendStore` 仍先排除再排名，随机兜底共同好友数固定 0；Go 已有 W=1024 排名窗口与真实共同好友数。是适合下一小批的真实差距，须保留 Java 原账号 / revision 校验并补 MySQL/TiDB 方言与真库验证。 |
| 5.5 | `SceneWorld` 排空只选本节点目标，`SceneNode.stop` 尚无整节点有预算疏散；失联宽限与频道清理、SQL epoch 交接已存在。下一批应复用原 SQL 交接，补跨节点排空、退避和生命周期收敛；自动疏散不套手动切线冷却。 |
| 5.4 | 226 状态机、交出与 travel robot 已有，当前主要剩双 zone 真服务验收，不从零重写；本次只读盘点未代替完整审计。 |
| 7.1b | `deploy/compose` 只有基础设施，`.github/workflows/integration.yml` 明记整栈 job 待补；整栈编排仍缺。 |
| 7.2c | Java `RecallAdminController` 执行回 501，`RecallPlanner` 只有计划；Go 同样没有回收执行能力，不能当作已有成熟代码直接移植。 |
| 7.3 | `ZoneMergeStore`、`SqlZoneMergeFence` 和真实 home/from/to 围栏已接，旧「恒放行」描述过时。退役区 Redis 热状态维护仍缺：清频道、desired、cooldown、预占、leader 与 zones 成员，但保留 plan version 水位；新线号随频道记录删除，目录可自然过期。 |
| 7.4 | `PlayerBundleExport/Import` 已实现，`docs/design/player-bundle-debug.md` 已记录真实库、Redis、整包迁移与重登 SHA 验收，不能列为待首次外测。本轮未重跑该证据。全域一致性巡检与持续存盘压测仍未找到 Java 对等入口；Go 的 DBTask Kafka 注入器不适用于 Java 直接 SQL 架构。 |
| 7.5 | `CurrentMapNavigation` 已支持当前三图保守栅格；`ContractSync` 与漂移 CI 已有。跨版本 BREAK 审批闸仍缺，通用 Detour 范围应按最新地图需求重定。 |
| 7.6 | K8s、发布前核验与部署告警清单仍缺。`BattleResultTopics` 已有 RF3/minISR2 强门禁，但 `AuditTopicInitializer` 仍只检查分区；不可笼统说 Kafka 多副本能力全部待做。game-data 已有消费组与作业 CAS，多副本清理与滚动发布仍需专批验收。 |
