# 合服（批次 7.3）移植统一规格：围栏、归属改写、跨系统步骤、预检 / 审计 / 验证 / 撤销、合服后提示

> **基线**：mmorpg `26ceb70ca`（`D:\work\mmorpg` 稀疏克隆，与 `contract/SOURCE.properties` 的 `mmorpg.commit` 相同）。本稿用到的目录全部已检出、没有缺失：
> `tools/merge_zone/**`（约 18k 行，含测试）、`tools/data_consistency_check/`、`go/{data_service,scene_manager,login,guild,trade,friend,chat,match}/**`、`proto/**`、
> `docs/ops/merge-zone-runbook.md`、`docs/design/**`、`java/gateway_node/**`、`PROGRESS.md`、`robot/*.go`。
> - 组队代码在 `go/match/internal/team/`（`go/team/` 下只有 `generated/`）。基线没有独立的排行服务，全仓带 zone 维度的排行只有帮会榜。
> - 只读的 Unity 客户端是另一个稀疏克隆 `D:\work\mmorpg-client`（`a8577c7`），只核对了 `Assets/Scripts`。
>
> **路径约定**
> - 不带目录的 `*.go` 都在 `tools/merge_zone/`。`runbook` = `docs/ops/merge-zone-runbook.md`；`placement.md` = `docs/design/player-storage-placement.md`。
> - `router.go` = `go/data_service/internal/routing/router.go`；`home_zone.go`(SM) = `go/scene_manager/internal/logic/home_zone.go`；
>   `entergamelogic.go` / `createplayerlogic.go` 在 `go/login/internal/logic/clientplayerlogin/`；`config.go`(login) = `go/login/internal/config/config.go`；
>   `homezone.go` = `go/login/internal/logic/pkg/homezone/homezone.go`；`merge_fence.go`(guild)、`guild_logic.go`、`economy_logic.go`、`guild_manage_logic.go` 在 `go/guild/internal/logic/`；
>   `guild_repo.go` 在 `go/guild/internal/data/`；`guild.go` = `go/guild/guild.go`；`chat_logic.go` 在 `go/chat/internal/logic/`；`queue.go`(match) 在 `go/match/internal/logic/`；
>   `agones_binding.go` / `scene_atomic.go` / `enterscenelogic.go` 在 `go/scene_manager/internal/logic/`。
> - `GameClient.cs` = `mmorpg-client/Assets/Scripts/Game/GameClient.cs`。
> - Java 路径相对 `D:\work\xuanming-server-mmo`；只写类名时可在仓库里唯一定位。
>
> **Java 侧**：HEAD `9fde7d8`（规格文档提交；5.2 在 `6b28e9d`、7.1a / 7.2a 在 `37dd8dc` 都已提交）加工作区（5.3 副本、6.2 battle 节点、gate 限流迁入 xm-net 等未提交改动），
> 行号按工作区计。**5.4（`zone-travel-spec.md`）与 7.2b（`data-ops-spec.md`）尚未落地**（工作区里没有 `com.game.discovery.zone` 包、没有 `SelectTravelTargetRequest`、
> 没有作业执行器），本稿把它们的规格当权威设计，依赖它们的类 / 方法标「(5.4)」「(7.2b)」。
>
> **本稿的来历**：第三版。以上一版统一稿（随 `9fde7d8` 提交：基线主流程、跨系统步骤、Java 映射三份分区稿合并，并经完整性评审 C1–C31）为底，合入本轮两份分区稿——
> 「跨系统步骤稿」（基线帮会 / 聚宝斋 / 排行 / 场景热状态 / 好友·聊天·组队、键空间、失败与续跑）与「Java 映射稿」（按当前工作区逐条重核，Δ1–Δ12）。
> **本轮的基线主流程分区稿缺失（未交付）**，§1 沿用上一版内容，关键行号按 `26ceb70ca` 抽查重核。分歧都回到代码核对，裁决见 §0.7（历次）与 §0.8（本轮）。本稿只读代码，没有改任何源文件。
>
> **契约影响**：7.3 **不改任何客户端契约**，不需要跑 ContractSync。用到的客户端可见面全部已在同步产物里：26 `EnterGameResponse` 字段 3 `post_merge_notice_ts` / 字段 4 `force_rename_required`
> （`xm-proto/src/main/proto/proto/login/login.proto:88-120`）、124 RedirectToGate、tip 2020 `kLoginDataSerializeFailed`（`xm-table/src/main/proto/tip/login_error_tip.proto:52`）、
> 3023 `kEnterSceneFailed`（`scene_error_tip.proto:58`）、3027 `kZoneTravelTargetBusy`（`:66`）、14013 `kGuildZoneMerging`（`guild_error_tip.proto:38`；文案 `config-data/tables/tip_text.json:37`）、
> 4020 `kTeamCrossZoneDenied`（`team_error_tip.proto:52`，robot 断言用）。新增的 `zone_merge.proto`、`SelectTravelTargetRequest.home_zone_id` 都是 xm-api 内部 proto。

---

## 0 概览与范围

### 0.1 盘点 id 与本批产出

| 盘点 id | 盘点出处 | 基线位置 | Java 7.3 产出 |
|---|---|---|---|
| merge-fence-and-remap | `inventory/data.md:275-285` | `fence.go`、`merged_into.go`、`manifest.go`、`merge_run.go` 步骤 5、`router.go` RemapHomeZoneForMerge | 围栏写侧与读侧（F1 / F2 / F4 / F5）接真实现；按清单分批改 `player.zone_id`；合走记录落 MySQL |
| merge-player-data | `data.md:287-297` | `player_rows.go`、`pin_placement.go`、`player_blob_migrate.go`、能力标记 | **不适用**（单库，玩家行不随归属移动，§3） |
| merge-cross-system-steps | `data.md:299-309` | `guild_step.go`、`trade_step.go`、`scene_hot_state.go` | 帮会 / 聚宝斋由属主改写（Dubbo 参与方）、帮会区榜从 MySQL 重建、源区频道计划由领导者退役（§4） |
| merge-preflight-audit-unmerge | `data.md:311-320` | `preflight.go`、`audit_*.go`、`unmerge.go` | 归属静止预检、计划（dry-run）、合服后验证、按清单撤销（§5） |
| post-merge-notice | `data.md:323-334` | `post_merge_stamp.go`、`consumePostMergeFlags` | 26 字段 3 来自 `player.merge_notice_ms`；字段 4 恒 false（§6） |
| merge-zone-core | `inventory/tools.md:326-336` | 合服工具主流程 | xm-data 运维作业 `ZONE_MERGE` + 只用 JDK 的 CLI `tools/MergeZone.java`（§7） |
| merge-zone-audit-unmerge | `tools.md:338-348` | audit / unmerge / backfill / capability-check | 验证、撤销作业；backfill、capability-check 不适用 |
| post-merge-login-signals | `tools.md:362-372`；`login.md:245-251` | 同 post-merge-notice | 同上 |
| storage-placement-relocate | `tools.md:350-360` | `relocate*.go`、`placement_*.go`、`storage_audit.go` | 不适用（盘点已判） |
| 5.4 钩子 | `zone-travel-spec.md:446-470` | `router.go:73-109`、`home_zone.go`(SM)`:173-200`、`homezone.go:245-275` | `ZoneMergeFence` 换成 Redis 实现；`RedirectOnEnter`（缺省关）；`SelectTravelTargetRequest.home_zone_id`（F4） |
| 帮会钩子 | `guild-spec.md:1716` D4；`MergeFence.java:18-38` | `merge_fence.go`(guild) | `MergeFence.NONE` 换成适配器，14013 开始出现 |

### 0.2 结论（一页纸）

1. **基线是一个停服维护窗口里跑的 Go 命令行工具**（`main.go:3-53`），步骤顺序本身就是正确性的一部分；四条规矩是「先证明再写、先立围栏再收集、清单先于写、玩家 id 只收集一次」
   （`merge_run.go:5-15`）。它通过了 83 个集成测试（`PROGRESS.md:6003-6004`），但**从没在真实集群上演练过**（`PROGRESS.md:6030`），也**没有任何合服 robot**。
2. **基线的跨系统写只有三处，全部由工具直接写别的服务的库与缓存**：步骤 3 帮会 `guild.zone_id` + `guild:v2` 缓存失效（`merge_run.go:480-523`）、步骤 3b `trade_listing.market_zone`
   （`:525-557`）、步骤 4 区榜 ZSET 合并（`:559-583`）；另有步骤 6 清场景热状态（**缺省关**，`main.go:305-306`，但 runbook 的 T-0 命令带了开关，`runbook:564`）与步骤 7 提示标记。
   好友、聊天、组队、匹配一个字节都不写（组队只做阻断式预检 P7）。顺序是「全部 zone 列先改、最后才翻归属」，撤销完全相反（`unmerge.go:9-22`）。
3. **Java 的合服只剩「改三列、刷新派生状态、留一次性提示」**：`player.zone_id`、`guild.zone_id`、`trade_listing.market_zone`。玩家行在单库 `xm_java` 里不用搬，
   基线大约一半的代码（pin / copy、blob、能力标记、回填、搬库、Kafka 积压门禁）都不适用（§3、§11 D2）。
4. **形态：xm-data 的运维作业**（`OPS_JOB_ZONE_MERGE = 7`、`OPS_JOB_ZONE_UNMERGE = 8`，追加在 `ops_tables.proto:22-36` 的 1–6 之后），复用 7.2b 的作业框架
   （单飞 `ops_active`、心跳与清扫、幂等键、事件审计、`xm.data.ops.enabled` 写闸），另配只用 JDK 的薄壳 `tools/MergeZone.java`。**不新开 `xm-ops` 进程**（推翻 `inventory/tools.md:332`）。
5. **清单在 MySQL，第一笔写之前落库**：pbmysql 表 `zone_merge_run`（一次合服）+ `zone_merge_item`（每个玩家 / 帮会 / 商品一行），同时承担基线清单文件（`manifest.go:3-26`）
   与 `merge:merged_into`（`merged_into.go:3-20`）的职责。玩家每批（1000 人）的改归属与清单进度**在同一个事务里**提交。
6. **跨服务的写一律走属主**（同 7.2 Q15，`data-ops-spec.md:1109`）：xm-api 新增 Dubbo 接口 `ZoneMergeParticipantService`，xm-guild、xm-trade 各自实现。帮会按主键逐条改写
   （属主自己的数据源：锁等待 1 s、RC，`xm-guild/src/main/resources/application.yaml:31`）、**严格**失效快照缓存，再用现成的 `GuildRanks.rebuild` 从 MySQL 重建区榜；
   聚宝斋按主键逐条改 `market_zone`。玩家行经共享库 `PlayerStore` 由 xm-data 改。源区频道计划由 scene-manager 的**领导者**用围栏 Lua 退役，xm-data 只把 src 移出 `xm:world:zones`。
7. **围栏**：键 `xm:{merge}:fence:<zone>`（Hash，记 run / op / src / dst / 操作人 / 时刻），src、dst 两把**在一段 Lua 里同时立**；读者只看键在不在、读失败按封锁；
   参与方核对 `run` 字段。run 处于写阶段期间由 `ZoneMergeFenceKeeper` 每 10 s 续期 / 补回，半合服状态不会因 TTL 过期而失去保护（修基线 `merge_run.go:1309-1368` 的「中止后只剩 TTL」）。
8. **合服不夺权**：`zone_id` 不在任何 `owner_epoch` 围栏 SQL 里（`PlayerMapper.java:53-138`），迟到的带围栏写回与改归属互不覆盖。判据是**归属静止**
   （清单 = src 区玩家 `owner_released = 1` 或租约早于 now − 余量）加清单玩家无在线目录 / 位置记录 / 活队伍，每批改写在同一事务里 `FOR UPDATE` 复核；dst 区玩家持有归属只报 warn。
9. **合服提示**：`player.merge_notice_ms` 与 `zone_id` 在**同一条 UPDATE** 里写；login 在夺权成功的那一刻用条件 UPDATE 原子消费（只出现一次、进场被拒不吞提示）。撤销在同一条语句里还原成改写前的值。
10. **撤销**：只在「合服后没人进过游戏」时允许——清单玩家的 `owner_epoch` 不得比改归属时大，清单帮会不得有合服后入帮的成员；不提供强制选项（修基线 B4）。
11. **客户端可见**：窗口内建角 14 {2020}、进游戏 26 {3023}（同步口径，5.4 已定）、跨区传送 23 {3027}、帮会写 14013；合服后角色列表、帮会、区榜、聚宝斋、好友视图都按目标区，
    两区玩家组队不再出现 4020；源区在区服列表里是 CLOSED；26 字段 3 只出现一次，字段 4 恒 false。当前 Unity 客户端不读字段 3 / 4（`GameClient.cs:795-805`），但**已实现 124**（`:1037-1066`）。

### 0.3 不做 / 不适用

| 基线项 | 处置 | 依据 |
|---|---|---|
| pin / copy 玩家行、data Redis blob、共享缓存失效、go/db 能力标记、`-backfill-home-zone`、relocate / pin-placement / storage-audit | 不适用 | 单库、`zone_id NOT NULL` 建角时写入（`xm-player-schema.sql:14`）；Java 没有玩家数据缓存层（`data-ops-spec.md:578`） |
| 基线 blob 里的 `PlayerMergeStateComp.post_merge_notice_seen_ms` | 不移植 | 只在注释里被提到、没有读写者（`proto/common/component/player_comp.proto:110-142`；`mysql_database_table.proto:121-125`；`post_merge_stamp.go:27-40`）。它随同步产物进了 `xm-proto`，**Java 代码不得引用**；Java 的玩家状态是自有 proto `xm.storage.PlayerState` |
| P3 Kafka 积压、P4 重试 / 处理中 / 死信队列 | 不适用，改为归属静止判据 | Java 写回同步且带 `owner_epoch` 围栏（`architecture.md:743` 起「玩家数据归属协议」） |
| data_service `RemapHomeZoneForMerge`（内部消息 129） | 不移植 | 全量 SCAN、不认清单、不 CAS（`router.go:474-530`），与工具的 A2 口径矛盾；工具本身也不调它（自己做逐键 CAS，`merge_run.go:911-951`）（§10.4 B2） |
| force_rename 打标与改名 RPC | 不做，字段 4 恒 false | 名字全服唯一（`xm-player-schema.sql:31`），基线同样不启用（`post_merge_stamp.go:69-77`；`merge_run.go:636` 传 nil；`runbook:861`） |
| 清理玩家位置等场景热状态（基线步骤 6） | 不做 | `xm:location` / `xm:presence` 都带 TTL（`PlayerLocationDirectory.java:46-49`、`PlayerPresenceDirectory.java:39`），由预检兜住；5.3 的副本是「节点自有 + 目录登记」（`dungeon-mirror-spec.md` §6 方案 C），没有实例键；Java 唯一会永久残留的是频道计划（§4.6） |
| 删除源区登录排队 / 限流键 | 不做 | src 置为 CLOSED 后准入先拒（`AssignGateService.java:205-229`），这些键不可达（YAGNI） |
| 组队记录里的 zone 改写、管理员解散队伍 | 不做，活队伍阻断合服 | 同基线 P7（`preflight.go:270-350`）；队伍空闲 24 h 自动过期（`team-spec.md:313`） |
| 好友 / 好友申请 / 帮会成员的审计计数 | 不做 | 基线只出 info 行（`audit_resources.go:488-592`），没有判据价值（D17） |

### 0.4 拆批与依赖

| 子批 | 内容 | 前置 |
|---|---|---|
| **7.3a** 读侧与提示 | `RedisKeys.mergeFence`、`RedisZoneMergeFence` 与写侧 Lua 工具类 `ZoneMergeFences`（xm-discovery）；F1 / F2 / F4 / F5 接真实现（帮会 `MergeFence` 适配器；F4 的 `home_zone_id` 由 xm-scene 填）；`player.merge_notice_ms` 迁移与 login 消费；`RedirectOnEnter` 开关；`WorldChannelStore.unregisterZone` / `retireZone` 与 `WorldChannelCoordinator` 的退役分支；xm-data 的 `fence-drill`（dev / test）；指标 | 5.4 |
| **7.3b** 合服 | xm-api 参与方接口与 proto；xm-guild / xm-trade 提供方；xm-data 合服作业、计划、验证、续跑 / 补扫、`abandon`、清单两表、围栏续期器（含终态清理）、`ZoneAdminController` 防护、7.2 受理的 `zone_merging` 检查；`PbMysql.insertAll`；zone 列登记表守护测试；CLI（含 `--self-test`）；robot `merge-fence-drill` / `merge-prepare` / `merge-check`；`tools/local/merge-smoke.sh` | 7.2b、7.3a |
| **7.3c** 撤销 | 撤销作业、参与方 UNMERGE 预检；robot `merge-unmerge-check`、撤销拒绝分支 | 7.3b |

其他依赖：4.4 / 4.5 帮会、4.7 聚宝斋、4.3 组队、5.1 频道计划（都已交付）；5.2 交出（归属三列语义，已提交）。7.3b 依赖 7.2b 的作业执行器、`ops_active` 单飞、清扫器、
xm-data 自己的 `PlayerStore` bean（现状 `DataApplication.java:14` 排除了 `PlayerStoreAutoConfiguration`）与 guild 的 Dubbo 消费端（`data-ops-spec.md:78`）。
每个子批各自提交、推送、登记 PARITY（AGENTS.md §5）；mmorpg 侧标「已有」。

### 0.5 PARITY 与文档登记

- **PARITY 新增行**：
  - 「合服（围栏、主流程、帮会 / 聚宝斋 / 区榜 / 频道计划、计划 / 验证 / 撤销 / 补扫）」，写明 §11 的 D1–D22；mmorpg 列写 `tools/merge_zone/**`。
  - 「合服后一次性提示（26 字段 3 / 4）」，写明 D11。
  - 「进游戏按归属区重定向（RedirectOnEnter，缺省关）」。
- **PARITY 更新行**：5.4 的「合服围栏」行由「检查点恒放行」改为「已接 Redis 实现」；帮会核心行（`PARITY.md:105`）的 D4 改为「已接线，14013 开始出现」；
  聚宝斋只读面行（`:108`）补「合服改写已对齐；4.8 写侧必须接 F 检查点并扩展参与方」；场景实例与主世界频道行（`:107`）补「退役 zone 由合服作业移出集合、领导者清计划」。
- **文档**：`architecture.md` 新增「合服（批次 7.3）」小节；§7 写明「`zone_id` / `merge_notice_ms` 只有建角 INSERT 与合服 / 撤销作业写」「归属静止判据」；§4.17 帮会合服闸已生效
  （`:528`「合服闸门（4.4 恒放行）」随之改写）；§10（`:953-956`）删去「合服」；§11 补指标。`MergeFence.java:18` 的注释（引旧行号 `architecture.md:617-620`）与 `guild-spec.md:1716` D4 随 7.3a 改写。
  `db-migrations.md` 在 M8（`:204`）之后登记 `merge_notice_ms`（与 7.2b 的 `idx_player_zone` 同批时合成一条，迁移号取当时的下一个空号，§7.8）与两张 pbmysql 表；
  `tech-stack.md` 不新增第三方依赖，只登记「xm-pbmysql 新增批量插入」与「xm-data 引入 trade 的 Dubbo 消费端」；新增运维手册 `docs/design/zone-merge.md`（§7.12 的内容）。
  `RedisKeys.java:277` 的 `worldZones` 注释由「退役 zone 要运维手工 SREM」改为「退役由合服作业移出、领导者清计划」。
- **路线图**：`roadmap.md:93` 7.3 的状态列。

### 0.6 对盘点与其他规格的勘误

| 位置 | 原说法 | 更正 |
|---|---|---|
| `inventory/tools.md:332`、`:344` | 新进程 `xm-ops`；键 `xm:merge:in-progress:{zone}`；整区 `UPDATE … IN 清单` | xm-data 作业；键 `xm:{merge}:fence:<zone>`；帮会 / 交易由属主改写（§7） |
| `inventory/tools.md:368` | 提示键 `RedisKeys.mergeNotice(pid)` + `getAndDelete` | 改为 `player.merge_notice_ms` 列（§6.3，Q3） |
| `inventory/data.md:281` | 「可退化为事务内 `UPDATE player SET zone_id=dst WHERE zone_id=src`」 | 清单仍然必需（撤销、提示、验证都要用）：按清单分批、`AND zone_id = src`、`FOR UPDATE` 复核、残留复查 |
| `inventory/data.md:306` | 「依赖的帮会 / 交易 / 排行榜在 Java 尚不存在」 | 4.4 / 4.5 / 4.7 已交付 |
| `inventory/data.md:309` | 「公会名在 mmorpg 是区内唯一」 | **全服唯一**（`guild_step.go:7-15`；Java `guild_tables.proto:32`） |
| `inventory/data.md:316` | P3 / P4 Kafka 门禁 | Java 不适用，改为归属静止（§3.3） |
| `inventory/data.md:402` 第 6 条 | 合服在 Java 的范围待定 | 按本稿关闭 |
| `inventory/guild.md:378` | 「按 idx_guild_0 整区改写……重建区榜」 | 基线已改为按清单主键点更新 + ZSET 合并；Java 由 xm-guild 按主键点更新 + 从 MySQL 重建 |
| `inventory/social.md:358` | 分批 `IN` 写法 | 按主键逐条（trade-spec `:1006` 已指出） |
| `zone-travel-spec.md:441` | 世界聊天「基线按物理 zone」 | `go/chat` 的世界频道全服一条（`chat_logic.go:8-10`），两版一致，不是差异 |
| `zone-travel-spec.md:448-449` | 建议键 `xm:merge-fence:{zone}` | `xm:{merge}:fence:<zone>`：两键 Lua 需要同槽（§2.5） |
| `zone-travel-spec.md:463-465` | 约束 ③「预检两区无 `o` / `l` 位置记录、该区玩家全部 `owner_released = 1`」；④ 整区 `UPDATE … WHERE zone_id = src` | ③ 改为「清单（src）玩家 `owner_released = 1` 或租约早于 now − margin」，位置 / 在线目录只查清单玩家，dst 玩家持有归属只报 warn；④ 改为按清单分批、`FOR UPDATE` 复核；①②⑤⑥ 原样保留 |
| `scene-channels-spec.md:1025`；`RedisKeys.java:277` 注释 | 退役 zone 要运维手工 SREM | 合服作业 SREM + 领导者用 `retireZone` 清计划（§4.6） |
| `scene-channels-spec.md:1021` | NOSCRIPT 理由引 `architecture.md:138` | 现为 `architecture.md:153` |
| `guild-spec.md:1716` D4、`MergeFence.java:18` 注释 | 「Java 首批不做合服（`architecture.md:617-620`）」 | 行号已过时（现 `:953-956`）；7.3 起已接线，注释与 D4 随 7.3a 改写（§0.5） |

### 0.7 历次分歧与裁决（上一版三份分区稿 + 完整性评审）

| # | 分歧 | 裁决与核对依据 |
|---|---|---|
| 1 | 合服提示存哪：`player` 列 / Redis 键 90 天 TTL + GETDEL / Redis `getAndDelete`（盘点） | **`player.merge_notice_ms` 列**。`selectById` 是 `SELECT *`（`PlayerMapper.java:36-37`），EnterGame 本来就读这一行，零额外读；带围栏的写回都显式列列（`:64-100`），不会碰它；与 `zone_id` 同一条 UPDATE，没有「提示步骤只做了一半」；不带 TTL 与基线一致（`post_merge_stamp.go:119-136` 的 SET 不带过期），避免「休眠超 90 天看不到提示」这条客户端可见差异（Q3） |
| 2 | 帮会 / 交易 / 玩家三列在 xm-data 的一个事务里改 / 由属主经 Dubbo 改 | **属主改写**。7.2 Q15 已定「直读越过服务边界」都不允许（`data-ops-spec.md:1109`），写更不行；快照缓存（`GuildCacheInvalidator.java:22-38`）与区榜锁（`GuildRanks.java:41-51`）只在 xm-guild 有一份；原子性由「围栏 + 条件更新幂等 + 清单进度」替代（Q4） |
| 3 | 玩家改归属一个大事务 / 每批一个事务 | **每批 1000 人一个事务**，清单进度同事务（Q5） |
| 4 | 区榜：ZSET 合并（基线） / 全量重建 / 只重建两区 | **复用 `GuildRanks.rebuild` 全量重建**：锁内、临时键带 TTL、Lua 原子换榜、重写区索引（`GuildRanks.java:222-270`），xm-guild 每次启动都在跑（`GuildConfiguration.java:269-281`），零新 Lua（Q6） |
| 5 | dst 节点必须下线 / 可继续运行 | **不强制 dst 下线**：两区 MAINTENANCE / CLOSED、清单玩家归属静止、src 的 gate 与 scene 目录为空；dst 节点只报 info、dst 玩家持有只报 warn（Q7、Q26） |
| 6 | 围栏键 `xm:merge-fence:{zone}` / `xm:{merge}:fence:<zone>` | **后者**：两把键一段 Lua，hash tag 保证将来上 Cluster 同槽（先例 `{team}` `RedisKeys.java:138-160`、`{rank}` `:187-221`） |
| 7 | 围栏值 JSON 串 / Hash | **Hash**：参与方要 `HGET run` 精确比较；基线用子串查找比较 run_id（`fence.go:72-85`） |
| 8 | 清单：各参与方自记 / 中央两表 | **中央 `zone_merge_run` + `zone_merge_item`**：参与方无状态，撤销与验证只查一处 |
| 9 | 参与方接口形状 | **list / count / preflight / rezone / finish 五个方法**（§7.7） |
| 10 | 立围栏后的宽限 10 s / 15 s | **15 s**：login 单语句上界是 JDBC `socketTimeout=10000`（`xm-login/src/main/resources/application.yaml:21`）。这**不是**建角链的硬上界（链上有发号与多条语句），硬保证是 A8 残留并入、A10 收尾复查与 verify（Q12） |
| 11 | 热状态步骤缺省开 / 关（基线 `-clear-source-hot-state` 缺省 false） | **缺省开、不致命、只退役源区频道计划**：Java 要清的只有无 TTL 的计划键，前置是 src 无 scene 节点（Q18） |
| 12 | `owner_released = 0` 且租约已过期：阻断并用 `AdminOwnership` 归一 / 算静止 | **算静止**（带 5 s 余量），每批 `FOR UPDATE` 复核（Q8；§10.1 第 6 条） |
| 13 | dry-run：作业 / 同步 | **作业，不立围栏、不写清单、不改数据**，只留 `ops_job` 审计行 |
| 14 | 撤销前置：只看 epoch / epoch + xm-data 直接 join `guild_member` | **epoch + 帮会参与方预检**（`join_time_ms ≥ 合服开始`，`guild_tables.proto:77`），不让 xm-data 读帮会表（Q24） |
| 15 | 匹配票据：阻断 / 不动 | **只报 info**：匹配池不分 zone，票据 zone 只用于观测（`queue.go:56-58`；`match-spec.md:359`、`:986`） |
| 16 | 帮会缓存失效：新增严格变体 / 按版本化协议 | **两者合一**：`rezoneObjects` 提交后对整批调严格失效（失败抛、整批回 ERROR 由 xm-data 重试）；现有 `afterCommit` 永不抛（`GuildCacheInvalidator.java:22-38`） |
| 17 | 删源区登录排队键 | **不做**（§0.3） |
| 18 | 撤销只接受 APPLIED / 任意状态 | **APPLYING / APPLIED / UNMERGING 都可撤**，全部步骤带 `AND zone = dst` |
| 19 | RedirectOnEnter「Unity 只打日志」（`config.go:198-206`、`runbook:851`） | 当前 Unity **已实现 124**（`GameClient.cs:1037-1066`）；基线文档过时（§10.4 B9）。开关仍缺省关（Q10） |
| 20 | 评审 C1–C31（续期节拍、条件更新、终态清理、别区仍发令牌、drill 专用释放、宽限不是硬上界、GONE / ELSEWHERE fail-closed、链式撤销还原提示、`rewriteBatchedStatements`、dst 只 warn、dry-run 不等、迁移核对、消费线程、重定向落点、补扫、`abandon` 限制、`dryRun` 必填、`not_latest_run` 定义、`zone_merging`、删除区服防护、会话锁等待、F4 混版本、战斗锁、trade 读围栏、停服顺序、僵尸写者、预算估算、测试补全） | 全部并入正文对应小节，不再单列 |

### 0.8 本轮（第二轮分区稿）新增裁决与勘误

| # | 议题 | 分区稿 / 上一版的说法 | 回到代码核对后的裁决 |
|---|---|---|---|
| N1 | 源区频道计划由谁清 | 上一版与跨系统稿：xm-data `SREM` 后等领导锁过期，再自己 SCAN + DEL `xm:world:{z:<src>}:*`（含 `ver`）；Java 映射稿：领导者自己清，`ver` 保留 | **领导者清，`ver` 保留**。`RedisKeys.java:225-226` 写明计划键「只经 `WorldChannelStore` 的围栏 Lua 写」，`WorldChannelStore` 的类注释称它是 `xm:world:*` 的「唯一读写入口」、计划「只由分 zone 领导者经 `write` 改」（`WorldChannelStore.java:6-24`）。xm-data 直接 DEL 破坏单写者；`ver` 只增不减（`:249-256`），留着最稳。xm-data 只经新增的 `WorldChannelStore.unregisterZone` 做 SREM（§4.6） |
| N2 | 清单玩家在线目录怎么严格读 | 上一版：「用异步读法或新增批量异步读」；Java 映射稿：新增 `findAllStrictAsync` | **两个严格批量读都已存在**：`PlayerPresenceDirectory.findAllStrictAsync(ids, batchSize)`（`:192`，只回计数）与 `findEachStrictAsync(ids)`（`:280`，逐人 ONLINE / ABSENT / ERROR）。用后者（要样本 id）；**禁用** `findAll` / `findAllAsync`（`:121`、`:165`），它们的 `decode` 把损坏 / 不符的条目当不在线（`:323-338`，fail-open）。位置记录用已有的 `PlayerLocationDirectory.statusesAsync`（`:217`）；`find`（`:360`）是阻塞单查，不用 |
| N3 | 参与方的锁等待 | 上一版 §10.1：「参与方锁等待 5 s」 | 参与方跑在**属主的数据源**上：xm-guild `innodb_lock_wait_timeout=1`、`socketTimeout=4000`、RC（`xm-guild/.../application.yaml:31`）；xm-trade `innodb_lock_wait_timeout=2`（`xm-trade/.../application.yaml:23`）。5 s 只适用于 xm-data 自己借出连接后 `SET SESSION` 的值（§7.5）。xm-guild 用现成 `BackgroundTx.run(attempts = 5)`，1213 / 9007 / 1205 整体重跑、退避 10 ms 起封顶 200 ms（`BackgroundTx.java:115-148`、`:222-238`），不照抄基线 200 ms→5 s |
| N4 | xm-data 的受影响行数口径 | 上一版没提 | xm-data 连接串带 `useAffectedRows=true`、没有 `sessionVariables`、`socketTimeout=30000`（`xm-data/src/main/resources/application.yaml:16`）。合服的状态迁移都会改到列，「恰好 N 行」断言成立；`steps_done = steps_done \| bit` 这类幂等进度更新续跑时可能 0 行，统一同时写严格递增的 `updated_ms`（§3.5、§7.6） |
| N5 | pbmysql 能否让枚举进主键 | 上一版留作「实现前先确认」 | **允许**：主键只拒浮点与前缀索引类型，报错文案本身建议「改用整数或枚举主键」（`TableSchema.java:488-494`），ENUM 键长按 4 字节算（`:654`） |
| N6 | 批量插入 API | 上一版：给 pbmysql 加 `insertAll` | 确认现有 API 没有批量插入（`PbMysql.java:136-331`），维持 |
| N7 | 迁移号 | 上一版没定 | M8 在 `db-migrations.md:204`；`merge_notice_ms` 取当时的下一个空号（7.2b 先落地并占 M9 时顺延为 M10；同批则与 `idx_player_zone` 合成一条） |
| N8 | `architecture.md` 行号 | 上一版：NOSCRIPT `:152`、`syncAll` `:654-658`、归属协议 `:672` | 现为 `:153`、`:725`、`:743`；§10「首批不做」在 `:953-956` |
| N9 | 账号角色上限会不会被合服打破 | 未提 | 不会：上限按账号全局计数（`PlayerMapper.java:33-34`；基线同样按账号，`createplayerlogic.go:157`） |
| N10 | `XM_ZONES=2` 切片 | 上一版：「沿用 5.4 的切片」 | 是 5.4 的交付物；`start-slice.sh` 目前只有 `XM_SCENE_NODES`（`:96-102`），7.3 的 robot 验收依赖 5.4 先落地 |
| N11 | 基线步骤 6 的细节 | 上一版只写「可选」 | 缺省关（`main.go:305-306`）但 runbook T-0 命令带 `-MergeClearSourceHotState`（`runbook:564`）；固定 6 把场景键**不含** `scene:{id}:agones_gs`（`scene_hot_state.go:269-279`），后者用不带 TTL 的 SET 写（`agones_binding.go:305`、`:342`），正常销毁场景时与其余场景键同一段脚本删（`scene_atomic.go:118-122`），只有热状态清理漏了它；验证不扫 `scene:{id}:*` 与 location、只报 warn（`audit_checks.go:491-524`）。登记 B16 |
| N12 | 基线步骤 7 重跑 | 上一版只写「续跑重取时刻」 | plain SET 会给**全体清单玩家**重新打标（`post_merge_stamp.go:43-47`），开服后重跑会让已看过提示的人再看一次。登记进 B14 |
| N13 | 严格失效的直接证据 | 上一版只说「快照存 zone」 | Java 查帮 `GuildService.java:174` 用**快照里的** `zone_id` 判「不在本区 → 14001」，与基线 `visibleIn`（`guild_logic.go:169-171`）同形；不严格失效，dst 玩家半小时内查不到合过来的帮（`GuildProperties.java:45` 快照 TTL 30 min） |
| N14 | 基线帮会闸门的覆盖面 | 上一版只提「没配 `MergeMarkerRedis` 就失效」 | 另有：内部 / GM 路径不查闸门（`merge_run.go:502-503`）。Java xm-guild 没有内部 / GM 写入口（Dubbo 只导出客户端消息服务与只读的 `GuildInternalService.listAppliedAssetOpsSince`），这条缺口在 Java 不存在 |
| N15 | 基线陈旧文档 | 未全列 | 新增 B18：runbook §3 消费者表的帮会行（`runbook:137`，写成 `checkMergeFence` + FailedPrecondition、只覆盖 CreateGuild；代码是 14013、覆盖全部客户端写，`guild_logic.go:293-320`）；`trade_step.go:50-53` 说「每行一条自动提交的点更新」（代码是显式短事务，`merge_run.go:1154-1200` 的 A15 注释）；guild / trade / team 三处 home_zone 注释说合服走 `RemapHomeZoneForMerge`（`go/guild/internal/logic/home_zone.go:19`、`go/trade/internal/logic/home_zone.go:18`、`go/match/internal/team/homezone.go:16`），工具实际不调它 |
| N16 | `verify:guild_rank` 的误报 | 未提 | 要求 `ZCARD dst == COUNT(guild WHERE zone=dst)`（`audit_checks.go:434-489`），建帮入榜失败只记日志时会误报 block（fail-closed，但原因不在合服）。Java 先重建再核对，按构造成立（D7）。登记 B19 |
| N17 | robot 覆盖面 | 上一版：围栏演练 + 三轮 | 合入跨系统稿的项：活队伍阻断（计划回 `team_active` 带 team id，离队后通过）、好友视图 zone、合服前后 4020 的有无、窗口内帮会写 14013（§13.6） |

---

## 1 基线合服主流程

### 1.1 资产

| 资产 | 位置 |
|---|---|
| 主流程 `runMerge` | `merge_run.go:30-658` |
| 入口与模式（merge / audit / unmerge / pin-placement / relocate / relocate-abort / storage-audit / capability-check，另有 `-backfill-home-zone`） | `main.go:204-559` |
| 围栏 `merge:in_progress:{zone}`、`guild_rank:maintenance_lock` | `fence.go:3-34`、`:52-233`、`:237-296` |
| 合走标记 `merge:merged_into:{src}` | `merged_into.go:3-170` |
| 清单（JSON，先写 `.tmp` 再 rename，**没有 fsync**） | `manifest.go:3-26`、`:110-164`、`:251-279` |
| 前置门禁 P2–P7（合服与撤销共用） | `preflight.go:3-25`、`:202-350` |
| 玩家行（copy）/ 钉落点（pin）/ blob | `player_rows.go:333-444`；`placement_ops.go:113-155`、`:661-728`；`player_blob_migrate.go:147-195` |
| 帮会 / 交易 / 区榜 / 热状态 | `guild_step.go`、`trade_step.go`、`scene_hot_state.go` |
| 合服提示标记 | `post_merge_stamp.go:13-150` |
| 撤销 | `unmerge.go:3-47`、`:68-458` |
| 审计与合服后验证 | `audit_resources.go:199-372`、`:421-592`；`audit_checks.go:205-552` |
| 围栏读者 | data_service `router.go:73-109`、`:152-245`；scene_manager `home_zone.go`(SM)`:19-46`、`:173-200`；guild `merge_fence.go:11-74`、`guild_logic.go:293-320`；login 读提示 `entergamelogic.go:406-412`、`:487-548` |
| 一致性巡检（合服后孤儿检查） | `tools/data_consistency_check/main.go:322-336` |
| robot | **无**：`robot/` 下 grep merge 只有 `guild_smoke_scenario.go:867` 一类提示文案（提到 `-backfill-home-zone`） |

### 1.2 运维时间线（runbook）

| 时点 | 动作 | 出处 |
|---|---|---|
| 首次合服之前 | 两个区都跑 `-backfill-home-zone`（存量号历史上没有 `player:zone`） | `runbook:15-18`；`backfill_home_zone.go:3-11` |
| T-7 | 公告、要求玩家离队；容量评估；回填与能力标记登记 | `runbook:331-372` |
| T-1 | 只做与在线无关的检查：审计、dry-run 彩排、备份恢复演练 | `runbook:374-448` |
| T-0 Step 1 | 网关把两个区标成维护，踢人，解散队伍 | `runbook:452-474` |
| T-0 Step 2 | 先停 gate / scene，等源区 db_task 消费完，再 zone-down 两个区 | `runbook:476-512` |
| T-0 Step 3 | 备份 MySQL 与 Redis，失败即终止 | `runbook:514-529` |
| T-0 Step 4 | 第一次 dry-run 拿到 N，再带 `-expected-src-players N` 正式 `-apply`；命令带 `-MergeClearSourceHotState`（`runbook:564`） | `runbook:531-607` |
| T-0 Step 5 | 带清单跑 `-verify-merged` | `runbook:609-637` |
| T-0 Step 6 | 只拉起 dst；pin 模式必须 `capability-check` exit 0 | `runbook:639-667` |
| T-0 Step 7 | robot 冒烟、人工抽查、`/open` dst；**src 保持维护** | `runbook:669-697` |
| T+1 ~ T+7 | 每天 verify；清单永久归档 | `runbook:714`、`:832-844` |

### 1.3 `runMerge` 阶段（按执行顺序）

| # | 阶段 | 做什么 | 出处 | 失败处置 |
|---|---|---|---|---|
| — | 写入意图 | `-dry-run` 与 `-apply` 必须且只能给一个 | `main.go:601-609` | 退出 1 |
| P1 | 静态预检（不立围栏） | 两区 `zone_<N>_db` 存在；按表清单 JSON ∩ `information_schema` 找玩家表；trade / guild 库表就绪（`-skip-*` 才跳过，跳过时验证报「NOT VERIFIED」warn） | `merge_run.go:73-125`；`trade_step.go:241-247` | `log.Fatalf`，没有任何写 |
| R | 读既有清单 | dry-run 预览一律拒读；src / dst 对不上就拒绝 | `merge_run.go:129-139`；`manifest.go:311-346` | 同上 |
| C | 能力标记 | 首跑 pin 模式检查；`-db-capability-zones` 无缺省值，可写 `none` | `merge_run.go:141-169`；`main.go:63-73` | 同上 |
| F | 立围栏 | src、dst 各 `SETNX merge:in_progress:{zone}`；TTL = max(timeout + 30m, 1h)，每 TTL/3 续期 | `merge_run.go:171-178`；`fence.go:103-166` | 撞上别人的围栏即拒绝 |
| R2 | 续跑校验（围栏之下） | 玩家行模式一致；copy 模式玩家表集合不变；pin 模式重查能力标记 | `merge_run.go:204-222` | `refuse`（§1.4） |
| 0 | 收集 | 首跑 `SCAN player:zone:*` + `MGET` 取值为 src 的；续跑读清单、不重扫 | `merge_run.go:224-237`；`player_blob_migrate.go:28-64` | `refuse` |
| G | 守卫 | 0 人拒绝（`-allow-empty-source` 放行）；源库有行却没有映射的人按集合比较、有即拒；首跑人数 ≠ `-expected-src-players` 拒绝（缺省 -1 不检查，`main.go:310`） | `merge_run.go:703-734` | `refuse` |
| P2–P7 | 门禁（源区口径） | 源区无活节点；源 topic 无积压；三个队列空；清单玩家无锁、无会话、不在活队伍 | `merge_run.go:244-258`；`preflight.go:202-283` | `refuse` |
| N | 帮会重名 | 按 `name_norm` JOIN；冲突一个字节都不写；只在步骤 3 未完成时跑 | `merge_run.go:260-267`；`guild_step.go:163-200` | `refuse` |
| S | 落点扫描 | 有冻结 / 畸形记录即拒；已有记录写进清单 | `merge_run.go:269-312`；`placement_ops.go:113-155` | `refuse` |
| X | 合走标记 | 源区已合进别处、或目标区已被合走即拒；读失败也拒 | `merge_run.go:314-324`；`merged_into.go:64-91` | `refuse` |
| **M** | **清单落盘** | 玩家、帮会（步骤 3 完成前每次并入当前仍在 src 的）、榜单成员快照（只取一次，`rank_members_unwritten`）、商品（步骤 3b 完成前每次并入）、表、模式、已有落点；dry-run 只写 `<path>.dryrun.json` | `merge_run.go:326-398`（收集口径 `:343-379`） | 落盘失败 `refuse` |
| 1 | 玩家行 | pin：钉落点；copy：拷行 + 失效缓存 | `merge_run.go:410-466` | `abortKeepingFence` |
| 2 | blob | 多 data Redis 集群才拷；拷到人数必须等于清单人数 | `merge_run.go:468-478`、`:1397-1447` | 同上 |
| 3 | 帮会 MySQL | 按清单逐主键短事务改 `zone_id`，对**全清单**失效 `guild:v2`（失效失败也算写到一半），复查源区计数为 0 | `merge_run.go:480-523` | 同上；残留也中止且不标完成 |
| 3b | 交易 | 按清单逐主键改 `market_zone`，复查；残留用专门文案中止、**故意不释放围栏** | `merge_run.go:525-557`；`trade_step.go:144-176` | 同上 |
| 4 | 帮会区榜 | 拿维护锁（最多等 30 s），锁内重读源榜，写前把撤销依据落进清单，`MULTI/EXEC` 里 `ZADD NX dst` + `DEL src` | `merge_run.go:559-583`；`guild_step.go:328-420` | 同上 |
| 5 | 改映射 | **先写 `merged_into`，再按清单逐键 Lua CAS**；`改成功 + 已是 dst` 必须等于清单人数；全量再扫，清单外仍指向 src 的只告警 | `merge_run.go:585-619`、`:839-951` | 同上 |
| 6 | 热状态（`-clear-source-hot-state`，缺省关） | 源区负载集为空才删 location、场景、频道、节点键（§4.5） | `merge_run.go:621-632`；`main.go:305-306`；`scene_hot_state.go:3-37`、`:327-339` | 同上 |
| 7 | 提示标记 | login Redis DB 0 写 `player_merge_notice:{pid}=<ms>`（plain SET、不带 TTL、分批 pipeline，一批失败记 WARN 继续）；时刻取本次运行的当前时刻 | `merge_run.go:634-648`；`post_merge_stamp.go:89-136` | **不回滚**，步骤不标完成，重跑补齐 |
| — | 正常结束 | defer 里按 run_id 释放围栏 | `merge_run.go:178`；`fence.go:187-210` | — |

两条顺序不能动（`main.go:51-53`）：步骤 1 必须在步骤 5 之前（映射一改玩家就被路由到目标区）；步骤 5 跑完后再扫描就找不到这批人，这是清单存在的理由。
另一条是「全部 zone 列先改完再翻路由」（`merge_run.go:525-526` 的注释）：帮会 → 聚宝斋 → 区榜 → `player:zone`。
每完成一步 `markStep` 并原子落盘（`merge_run.go:400-406`），落盘失败时步骤不算完成、靠幂等重做；续跑跳过已完成步骤。

### 1.4 失败处置三分法与逐步续跑

| 情形 | 判据 | 处置 | 出处 |
|---|---|---|---|
| P1 / R / C（围栏之前） | — | `log.Fatalf`，没有围栏 | `merge_run.go:73-169` |
| 首跑、清单落盘之前被拒 | `existing == nil` | `refuseAndRelease`：按 run_id 释放围栏、退出 1，「this run wrote nothing」 | `fence.go:212-233`；`merge_run.go:179-202` |
| 续跑（清单在、步骤 7 未完成）、清单更新之前被拒 | `halfDone` | `abortKeepingFence`：围栏留在本次 run_id 上 | `merge_run.go:195-202` |
| 清单落盘之后任何失败 | — | `abortKeepingFence`，文案列出 run_id、两把键、GET 核对、DEL、原命令重跑 | `merge_run.go:1309-1368`（`fenceKeptAbortMessage`） |
| 步骤 7 失败 | — | 只打 WARN、步骤不标完成；进程正常结束，defer 释放围栏 | `merge_run.go:634-648` |
| 清单已标记步骤 7 完成后再跑 | — | 拒绝时照常释放（dst 可能已经开服） | `merge_run.go:194` |

续跑**必须先手工 DEL 围栏**：重跑生成新 run_id，`SETNX` 会撞上残留围栏（`fence.go:152-157`）。中止走 `log.Fatal` 不执行 defer，
围栏挂到 TTL（`-timeout + 30m`，下限 1 h）为止，**期间没有续期**；人工排障超过 TTL，半合服状态就没有任何保护（基线缺口，D8 修）。

| 步 | 幂等守卫 | 中途失败留下的状态 | 续跑 | 撤销 |
|---|---|---|---|---|
| N 重名断言 | 只读 | — | 步骤 3 完成之前每次都跑 | — |
| 3 帮会 | `AND zone_id = src`；行不在或在第三区计 0 | 一部分帮会已在 dst；缓存可能没失效 | 清单阶段先并入新出现的 src 帮会；已改的影响 0 行；对**全清单**重做失效 | 3′：清单中当前为 dst 的改回 src 再失效，**排在 4′ 之后** |
| 3b 聚宝斋 | `AND market_zone = src` | 一部分商品已在 dst | 同上（并入新商品） | 3b′：清单中当前为 dst 的改回 |
| 4 区榜 | 锁内重读；源榜为空就不写 | MULTI/EXEC 原子；「写前落盘撤销依据」与 EXEC 之间崩溃时，续跑读到源榜为空，走「不写，只删空壳」 | 重读决定一切；清单快照只用来撤销 | 4′：按清单成员原分 ZADD 回 src、从 dst ZREM，同一条 MULTI/EXEC（`guild_step.go:421-450`） |
| 5 映射 | 逐键 CAS | 部分人已在 dst | 已是 dst 计 ALREADY | 5′：MGET 再 SET（非 CAS） |
| 6 热状态 | 删除天然幂等 | 部分键已删 | 重跑补齐 | **无**（删掉的不恢复） |
| 7 提示 | plain SET | 部分玩家已打标 | **对全清单重打**（`post_merge_stamp.go:43-47`） | 7′：DEL |

### 1.5 基线逐行改写的共通形状（帮会 / 聚宝斋共用）

| 项 | 基线 | 出处 |
|---|---|---|
| 写的形状 | 每个 id 一个显式事务：`SELECT <zone> FROM t WHERE pk=? FOR UPDATE`；行不在或 zone 已不是 from → 回滚、计 0 行；否则 `UPDATE t SET <zone>=to WHERE pk=? AND <zone>=from` → COMMIT | `merge_run.go:1157-1167`、`:1243-1300` |
| 为什么逐行 | 整区 `WHERE zone=src` 可被规划成沿二级索引的 range 扫描：先锁二级项、再回表锁主键，与在线写者（主键 FOR UPDATE → 改二级项）反序成环（死锁审计 #17） | `merge_run.go:1175-1184`；`guild_step.go:31-37`；`trade_step.go:11-19` |
| 为什么用显式事务 | TiDB 悲观模式下自动提交语句默认仍走乐观提交，会在 2PC 阶段与 DisbandGuild 冲突（A15） | `merge_run.go:1185-1192` |
| 重试 | 只重试 1213 / 1205 / 9007；5 次，退避 200 ms·2^n 封顶 5 s，可被 ctx 取消；每次从新事务开始 | `merge_run.go:1043-1124`（策略常量 `:1081-1082`） |
| 顺序 | id 升序：目前只为日志可复现；将来「N 条一个事务」时成为必要条件 | `merge_run.go:1193-1194` |
| 隔离级别 | DSN 强制 RC，与 guild / trade / friend 服同口径 | `merge_run.go:992-1040` |
| dry-run | 非锁定的 `COUNT … IN (…)`，分批 | `guild_step.go:246-263`；`trade_step.go:199-214` |
| 库名注入防线 | `-guild-schema` / `-trade-schema` / `-friend-schema` 共用 `schemaNamePattern` | `guild_step.go:69-74`；`trade_step.go:77-82`；`audit_resources.go:505-518` |

---

## 2 围栏与归属区改写

### 2.1 基线 `merge:in_progress:{zone}`

- **位置**：mapping Redis，**恒为 DB 0**（go-zero `RedisConf` 没有 DB 字段，写错库静默无效，`main.go:104-112`、`fence.go:7-10`）。
- **值**：JSON（tool、run_id、src、dst、started、expires、operator、manifest_path，`fence.go:56-68`），**读者从不解析**，只看键在不在（`router.go:73-89`）。
- **src、dst 各一把**：目标区在窗口里同样不能进新对象（`fence.go:26-28`）。
- **TTL 与续期**：`timeout + 30m`，下限 1h；缺省 timeout 2h（`fence.go:111-114`；`main.go:328`）。续期失败只记日志（`fence.go:168-184`）。
- **释放**：Lua 比较后 DEL；比较用**子串查找**（`string.find(cur, run_id, 1, true)`，`fence.go:72-85`）。
- **dry-run**：不写围栏，但检查是否撞上别人的（`fence.go:135-146`）。

| 读者 | 行为 | 玩家看到 | 出处 |
|---|---|---|---|
| data_service `RegisterPlayerZone`（建角） | EXISTS 预检（读失败按封锁）+ 提交时同一段 Lua 原子复核 | 14 {2020} | `router.go:152-162`、`:220-235`；`createplayerlogic.go:257-268`、`:696-717` |
| `GetPlayerHomeZone` → scene_manager `EnterScene` | 一段 Lua 同时读 home 与围栏；围栏在回 `ErrHomeZoneMerging = 21`，可重试、不写状态 | 26 先成功，再推 23 {3023} | `router.go:282-305`；`home_zone.go`(SM)`:173-200`；`go/scene_manager/internal/constants/errors.go:62-69`；`entergamelogic.go:431-471` |
| 同上，跨区传送第一条腿 | 同上 | 23 {3027} | `zone-travel-spec.md:458` |
| guild 写 RPC | 事务外按请求者归属区 `EXISTS`（读失败也拒）；事务内按锁住行的 `guild.zone_id` 再判 | 14013 | `merge_fence.go:11-74`；`guild_logic.go:293-320`；`economy_logic.go:288-311` |
| data_service `RemapHomeZoneForMerge`（内部 129） | **反过来**：源区必须已有围栏，还要 `x-admin-token` | 无 | `router.go:474-530`；`dataserviceserver.go:214-260` |

已知缺口：围栏只挡「新建」「进场」，挡不住已在线的人，依赖停服（`inventory/tools.md:336` ④）；guild 没配 `MergeMarkerRedis` 时闸门整体失效（`guild.go:316-323`；`runbook:860`），
内部 / GM 路径不查闸门（`merge_run.go:502-503`），真正的防线是写后复查；聚宝斋完全不读围栏（`trade_step.go:29-36`）；不带 GateId 的 EnterScene 不过围栏（`PROGRESS.md:6022`）。

### 2.2 基线 `merge:merged_into:{src}` 与 `guild_rank:maintenance_lock`

- `merged_into` 值 `{"dst", "run_id"}`、**无 TTL**（`merged_into.go:13-20`）；第 5 步改映射**之前**写，已指向别的 dst 即拒（`:96-119`）。
  读：合服 X 阶段（`:64-91`）与回填（防 mapping 丢失后把已合走的人钉回死区，`:123-137`）。撤销在 5′ 之后按 dst + run_id 删（`:142-170`）。
- `guild_rank:maintenance_lock`：与 guild 服互斥，`SETNX` + TTL 5 min，按令牌释放，合服最多等 30 s（`fence.go:237-296`）。

### 2.3 基线归属区改写（步骤 5）

- **只动清单里的键**：逐键 Lua `GET` → 值为 src 则 `SET dst` 回 1、已是 dst 回 2、键不存在回 0、第三个区回 3（`merge_run.go:839-862`）；pipeline + 预先 `SCRIPT LOAD`（`:911-951`）。
- 完整性：`Changed + Already == len(manifest)`，否则中止、不标完成（`merge_run.go:894-901`、`:597-600`）。
- 清单外仍指向 src 的人：全量扫一遍**只告警**，开服前由 `verify:mapping_src` 拦住（`merge_run.go:601-612`；`audit_checks.go:209-232`）。
- 旧写法的两个 bug（全量 SCAN 改了清单外的人；先 MGET 后 SET 覆盖中间变化）已修（A2，`placement.md:388`）；但 data_service 的 `RemapHomeZoneForMerge` 仍是旧写法（§10.4 B2）。

### 2.4 Java：归属就是一列

- **权威**：`xm_java.player.zone_id INT UNSIGNED NOT NULL`（`xm-player-schema.sql:14`），只在建角时与角色行同一条 INSERT 写入（`PlayerMapper.java:40-47` 是唯一写它的语句）；
  5.4 起归属区取会话 zone（`zone-travel-spec.md:411-414`）。
- **读者每次现读、无缓存**：组队 / 帮会 / 交易经 `PlayerHomeZones`（`xm-common/.../player/PlayerHomeZones.java:12-19`），好友经 `PlayerProfiles`（`OnlineDirectory.java:255`、`FriendService.java:321`），
  login 角色列表直读这一列（`zone-travel-spec.md:415`）。改完这一列，各服务下一次请求立即看到新归属。
- **7.3 新增的唯一写者**：合服 / 撤销作业，在围栏之下按清单分批改（§7.3 A8、§7.4 U5）。不变量 **I-Z1**：zone 列只有创建时写入与合服 / 撤销作业改写；`architecture.md` §7 写明。
  帮会 `guild.zone_id`、聚宝斋 `trade_listing.market_zone` 同理（只在建帮 / 上架时写，§4.1）。

### 2.5 Java 围栏：键、值、写侧 Lua

- **键**：`RedisKeys.mergeFence(int zone)` = `xm:{merge}:fence:<zone 无符号十进制>`，DB 12。两把键共用 hash tag `{merge}`。
- **值**：Hash，字段 `run`（run_id 无符号十进制）、`op`（`merge` / `unmerge` / `drill`）、`src`、`dst`、`by`（操作人）、`at`（立起毫秒）、`exp`（预计到期毫秒，只给人看）。
  **读者只做 `EXISTS`**，不解析（同 `router.go:84-89`）；参与方与续期器读 `run`。
- **写侧**（xm-discovery `com.game.discovery.zone.ZoneMergeFences`，Lua，`ByteArrayCodec`，单条 `evalAsync`；不用 Redisson 批处理——批里的 EVALSHA 遇 NOSCRIPT 不重载，`architecture.md:153`）：

| 操作 | Lua 语义 |
|---|---|
| `acquire(run, op, src, dst, by, ttl)` | 两把键各 `HGET run`：任一被**别的** run 持有 → 整体失败，返回持有者 run 与 op，零写入；**每把键要么不存在、要么已由本 run 持有** → 两把都 `HSET`（覆盖 op / by / at / exp）+ `PEXPIRE ttl`，返回 OK（同一 run 即接管，供续跑与撤销用）。drill 传 `src = dst = zone`（同一把键，脚本照常工作） |
| `renew(run, zones, ttl)` | 每把键 `HGET run == run` 才 `PEXPIRE`（并更新 `exp`）；返回每把的结果：RENEWED / MISSING / STOLEN |
| `restore(run, op, src, dst, by, ttl)` | 键缺失（Redis 重启 / 淘汰）时按同一 run 补回（`at` 取补回时刻）；被别人占了不动 |
| `release(run, zones)` | 每把键 `HGET run == run` 才 `DEL`，不误删接手者的 |
| `releaseIfStale(run, zones, beforeMs)` | 终态清理用：`run == run` **且** `at < beforeMs`（围栏立起早于 run 终结）才 `DEL`——不会删掉同一 run 在终结之后被 `resume` 重新立起的围栏 |
| `releaseDrill(run, zone)` | `fence-drill` 撤销用：`run == run` **且** `op == drill` 才 `DEL`；永远删不掉合服 / 撤销的围栏 |
| `holder(zones)` | 只读：返回每把的 `run`、`op`、`at` |
| `checkHolder(run, from, to)` | 只读：两把都存在且 `run` 都等于入参 → 1，否则 0（参与方用） |

- **TTL**：`max(run-budget + 30 min, 1 h)`（同 `fence.go:111-114`），缺省 run-budget 30 min → 1 h。
- **续期器** `ZoneMergeFenceKeeper`（xm-data，跑在 `data-ops-fence` 线程上，每个副本都跑，`data-ops-spec.md:849-850`），**每 `fence-check-interval`（10 s）一拍**。
  每拍从 MySQL 读一次「状态为 APPLYING / UNMERGING 的 run」与「本副本正在执行的作业的 run」，逐个 `renew`：
  - MISSING → 先**重读该 run 的状态**仍为 APPLYING / UNMERGING（或本副本的作业仍在执行、`ops_job` 仍为 RUNNING）才 `restore` 并记 WARN；补回之后**再读一次**，已终结就立即 `release`
    ——关掉「读到 APPLYING → 作业 A10 改 APPLIED 并释放 → 本副本补回」这条竞态（补回前的空窗由残留复查兜底，§2.7）。
  - STOLEN → 记 ERROR、计数，执行中的作业在下一批之前核对持有者失败而停止（`fence_lost`，围栏不归它、不释放）。STOLEN 实际只可能来自人工改键：新 run 在立围栏**之前**就会因
    src / dst 有未终结的 run 被拒（§7.3 A1），drill 用同一段 Lua，撞上已有围栏同样失败。
  - **终态清理**：最近 24 h 内终结（APPLIED / UNMERGED / ABANDONED）的 run，若围栏仍由它持有（A10 / U9 / `abandon` 的释放失败）→ `releaseIfStale(run, zones, 终结时刻)`，计 `cleaned`。
- **状态迁移一律条件更新**：作业推进 run 状态与清扫器 / `abandon` 改 ABANDONED 都写成 `UPDATE zone_merge_run SET status = :to, updated_ms = :u WHERE run_id = ? AND status = :expected`，
  影响 0 行即停止（作业 FAILED `run_busy`，不释放围栏）。否则「清扫器误判心跳超时、把 PLANNED 的 run 改成 ABANDONED 并释放围栏」与「原执行线程其实还活着、随后改 APPLYING 开写」会交错。
- **崩溃语义**：xm-data 全部副本都挂掉时围栏最多一个 TTL（≥ 1 h）后消失。此时两区仍是 MAINTENANCE / CLOSED，网关不为它们发 gate 令牌（白名单不参与准入，`AssignGateService.java:205-229`），
  残留的 gate 令牌（600 s，`GateTokenIssuer.java:22`）与传送票据（300 s）早已过期。**但其他 OPEN 的区照常发令牌**：归属 src / dst 的玩家可以经别区的 gate 进游戏
  （EnterGame 只看会话 zone 的区服状态，F2 是唯一挡板），围栏消失后就会在半合服状态下进场。这是两版共有的暴露面（基线工具死后同样只剩 TTL），Java 的续期器把它缩到「全部副本都挂」；
  手册要求合服期间 xm-data 至少两个副本，并对续期停摆告警（§9）。续跑时重新立围栏、等宽限、做残留复查。

### 2.6 Java 读侧：5.4 检查点换成真实现

`RedisZoneMergeFence implements ZoneMergeFence`（5.4 的接口 `com.game.discovery.zone.ZoneMergeFence`，`zone-travel-spec.md:448`）：zone 为 0 放行；`EXISTS` 判定；Redis 出错 / 超时抛异常，
调用方按封锁处理（fail-closed）；同步版等待上限 500 ms 且不超过请求剩余预算，异步版给 login / scene-manager。

| # | 位置 | 判哪个 zone | 命中或读不到（7.3 起） | 基线 |
|---|---|---|---|---|
| F1 | login `CreatePlayer`，铸号与事务之前（5.4） | 会话 zone（新角色的归属区） | 14 {2020}，不发号、不插行 | `createplayerlogic.go:257-268` |
| F2 | login `EnterGame` 落地路径，`findPlayer`（`EnterGameHandler.java:200`）之后、`callAssign` 与夺权之前（5.4） | `row.zone_id` | 26 {error_message 3023}，不分配、不夺权 | 26 成功后推 23 {3023}（`home_zone.go`(SM)`:176-199`） |
| F3 | EnterGame 重定向路径 | 不查 | — | 同基线「只送连接不查」 |
| F4 | scene-manager `selectTravelTarget` 第 3 步（5.4） | 归属区：7.3 给内部请求 `SelectTravelTargetRequest` 加 `home_zone_id`，xm-scene 填加载时的 `player.zone_id` | scene 留原地、推 23 {3027} | `enterscenelogic.go:413` → `player_lifecycle.cpp:4362-4372` |
| F5 | xm-guild 写 RPC：事务外按请求者归属区（`GuildAccess.mergeFenceTip`，`GuildAccess.java:103-124`），事务内按锁住的 `guild.zone_id`（`MergeFence.java:33-39`） | 同左 | 14013（「zone merging」/「merge fence unreadable」，`GuildTip.java:69-74`） | `merge_fence.go:60-74` |
| — | 4.8 交易写侧（上架、下单、交付之前） | 卖家 / 买家归属区 | 由 4.8 定 tip | 基线 P3 硬约束（`trade_step.go:27-34`） |

- 帮会接线：`GuildConfiguration.java:437` 的 `MergeFence.NONE` 换成 `zoneId -> zoneMergeFence.inProgress(zoneId)`。`GuildAccess.mergeFenceTip` 对非 NONE 已走真实路径（`:108`），
  `MergeFence.inTransaction()` 对非 NONE 已返回真实现（`MergeFence.java:34-38`），解散与 4.5 经济操作的事务内复判随之生效，不用改调用方。
- **F4 的混版本**：`home_zone_id` 是新加的内部字段，旧版 xm-scene 不填（读作 0），而「zone 为 0 放行」会让 F4 静默失效。xm-scene 与 xm-scene-manager 必须同批上线
  （同 5.1 的整体停服切换纪律，`scene-channels-spec.md:1023`）；scene-manager 收到 `home_zone_id = 0` 时计 `xm_merge_fence_checks_total{site="travel", result="unreadable"}` 并记 WARN，
  按封锁处理（23 {3027}）——Java 建角恒写非 0 的 zone，0 只可能来自旧版 scene。
- **Java 没有「闸门未配置」与「内部写路径不查闸门」的形态**：只有一个 Redis（DB 12）；xm-guild 没有内部 / GM 写入口（§0.8 N14）。基线这两处缺口在 Java 都不存在（D18）。
- 指标 `xm_merge_fence_checks_total{site, result}`（§9）。

### 2.7 Java：残余窗口（D-H4）与宽限 / 复查

- 归属在 MySQL、围栏在 Redis，做不到基线一段 Lua 原子读 home 与围栏（`router.go:283-330`）。5.4 已接受这个残余（`zone-travel-spec.md:461-462`）。
- **建角残余**：F1 通过之后、INSERT 提交之前立起围栏，新角色会在收集之后才出现在 src。三道兜底：
  1. 立围栏后**等宽限** `fence-grace`（15 s，> login 单语句上界 `socketTimeout=10000`）再收集——只覆盖常见情形，不是硬上界：建角链在 F1 之后还有发号与锁账号 / 计数 / 插行 / 提交多条语句；
  2. 预检要求 src 的 gate 目录为空（会话 zone 来自 gate，src 无 gate 就不会再有**新的** src 建角请求；但 gate 死前已发到 login 的在途请求仍会执行完，也不是硬保证）；
  3. **硬保证**：玩家步骤结束后 `COUNT(zone_id = src)`，大于 0 就把这些人并进清单再处理一轮，最多 3 轮（§7.3 A8），收尾再复查一次（A10，仍有残留不标 APPLIED）；
     A10 之后才提交的极端情形由 verify `verify:players_src` 拦住开服，再用 `resume`（对 APPLIED 的补扫，§7.3）收进来。
- **进场残余**：F2 读到旧 `zone_id` 之后合服整段恰好完成——只影响这一次的围栏 / RedirectOnEnter 判定（存盘不按归属区路由），可接受。

### 2.8 Java 归属改写（每批一个事务）

```sql
-- data-ops 线程，READ COMMITTED；借出连接后 SET SESSION innodb_lock_wait_timeout = 5（§7.5）
-- ids 为本 run 里 state = PLANNED 的清单玩家（升序，≤ batch-size）
SELECT player_id, zone_id, owner_epoch, owner_released, owner_lease_until, merge_notice_ms
  FROM player WHERE player_id IN (:ids) ORDER BY player_id FOR UPDATE;
-- 逐人分类：zone = src 且归属静止 → MOVED；zone = dst → ALREADY；其他 zone → ELSEWHERE；无行 → GONE
-- 任一 zone = src 但「owner_released = 0 且 owner_lease_until ≥ now − margin」→ 整批 ROLLBACK，作业 FAILED zone_not_quiescent（保留围栏）
UPDATE player SET zone_id = :dst, merge_notice_ms = :T, updated_at = :now
 WHERE player_id IN (:moved) AND zone_id = :src;            -- 影响行数必须等于 |moved|，否则 ROLLBACK
UPDATE zone_merge_item SET state = :state, owner_epoch_at_move = :epoch, notice_before_ms = :before, moved_ms = :now
 WHERE run_id = :run AND kind = PLAYER AND object_id = :id AND state = PLANNED;
-- JDBC batch（不开 rewriteBatchedStatements，§7.6），每条的更新计数必须恰好为 1，否则 ROLLBACK
COMMIT;
```

- 只走主键（`EXPLAIN` 必须为 PRIMARY，§13.2）；不碰 `owner_*` 三列。`T` 是整个 run 的同一时刻（`zone_merge_run.notice_ts_ms`，首次进入玩家步骤时取、续跑沿用）。
- 「恰好 N 行」成立：xm-data 连接带 `useAffectedRows=true`（`application.yaml:16`），而 `zone_id` 从 src 变 dst、item 从 PLANNED 变终态都会改到列（§3.5）。
- **改写前的提示值** `merge_notice_ms` 记进 `zone_merge_item.notice_before_ms`：链式合服 3→2 之后玩家还没领取 3→2 的提示，又跑 2→1 再撤销 2→1 时，撤销要把 3→2 的提示还给他（§7.4 U5）。
- 完整性同基线 `complete()`（`merge_run.go:894-901`）：清单玩家全部为 MOVED / ALREADY 才标步骤完成；**任一 GONE / ELSEWHERE → 步骤不置位、作业 FAILED `manifest_incomplete`**
  （保留围栏，样本 id 进结果）。Java 没有删角色、也没有别的 zone 写者，正常为 0；真出现就是 I-Z1 被破坏，要人工查清再 `resume`。撤销（U5）里的 GONE / ELSEWHERE 只计数、不阻断。
- **ALREADY 的含义因对象而异**：玩家的改写与 item 回写在**同一事务**里，本 run 不可能留下「行已是 dst、item 仍 PLANNED」，所以玩家的 ALREADY 只可能来自外部写者（违反 I-Z1）——
  照样记 ALREADY（撤销时一并改回），同时出 warn `player_already_at_dst`（应为 0）。帮会 / 商品的改写在属主进程、item 回写在 xm-data，两者不同事务，ALREADY 是「参与方已提交、
  xm-data 没来得及记」后重试的正常结局。撤销对 MOVED ∪ ALREADY 一并改回（§7.7）。

---

## 3 玩家数据迁移

### 3.1 基线：pin 与 copy

| | pin（缺省） | copy |
|---|---|---|
| 做什么 | 给清单里**没有落点记录**的人钉 `player:placement="{src}:1"`（Lua CAS：记录不存在且 home 仍是 src 才写，`placement_ops.go:661-728`、`:494-514`） | 只拷没有落点记录的人（`merge_run.go:437-441`） |
| 行与 blob | 不拷行、不拷 blob、不删缓存；源库仍是真源，**源库不能下线**（`runbook:20`） | 列名对齐（`player_rows.go:362-369`）与撞号预检（`:371-405`，首跑目标库有任何一行按 ID 安全事件拒绝，续跑只认逐列 NULL-safe 相等，`:124-170`）；一表一事务 `INSERT…SELECT`、核对行数（`:407-443`）；删共享缓存 `PlayerAllData:{pid}` / `{MsgType}:{pid}`（`:676-751`） |
| 前置 | go/db 能力标记（90 s 心跳），T-0 只能列仍在跑的区（`runbook:639-662`） | 有已存在落点记录者时同样要求能力标记（`merge_run.go:296-309`） |
| 互斥 | 有冻结记录即拒，与搬库互斥（`placement_ops.go:107-112`） | 同左 |
| 清单 | `player_rows_mode`、`placement_existing` | 记 `tables`，撤销删行的依据（`manifest.go:133-135`） |
| 不兼容 | 多 data Redis 集群时不能用（`main.go:567-580`） | — |

共性：续跑必须同模式（`manifest.go:184-201`）；会话固定 READ COMMITTED（`merge_run.go:992-1040`）；锁冲突 1213 / 1205 / 9007 有界重试 5 次（`:1043-1124`）。

### 3.2 Java：没有可迁移的玩家数据

| 基线写入面 | Java 对应 | 结论 |
|---|---|---|
| `zone_<N>_db` 玩家行（`backfill_home_zone.go:97-99`；`merge_run.go:73-80`） | 单库 `xm_java` 的 `player` / `player_state`；`player_state` 不含 zone（`xm-player-schema.sql:37-43`） | 不搬、不拷、不做撞号预检：player / guild / listing 的 id 都是全服号，不会撞 |
| mapping Redis `player:zone:{id}`（`main.go:7-10`） | 列 `player.zone_id` | 改归属就是一条带条件的 UPDATE（§2.8） |
| data Redis blob、`PlayerAllData` 缓存 | 没有玩家数据缓存层（`data-ops-spec.md:578`） | 不适用 |
| 能力标记、回填、落点、搬库 | 没有按落点选库；`zone_id` NOT NULL | 不适用 |
| `PlayerMergeStateComp`（blob 里的休眠字段） | 无 | 不移植，Java 不引用同步来的字段（§0.3） |
| 审计流水 / 快照里的 zone | `transaction_log.zone_id`、`player_snapshot.zone_id`（`xm-data-schema.sql:24`、`:45`）记录**所在进程**的 zone | **不改写**：历史归因；7.2 的整区回档按 `player.zone_id` 选目标，合服后照样成立，快照 zone ≠ 归属区者单列提示（`data-ops-spec.md:545-546`，7.2 D10） |

另外两条「天然无冲突」：角色名全服唯一（`xm-player-schema.sql:31`），帮会名全服唯一（`guild_tables.proto:32`），所以 `force_rename` 恒为 false；
每账号角色上限按账号全局计数（`PlayerMapper.java:33-34`；基线同样按账号，`createplayerlogic.go:157`），合服不会让账号超限。

所以 Java 的「玩家数据」步骤只剩 §2.8 的改归属与 §6.3 的提示列，本节讲它与归属协议的交互。

### 3.3 归属静止：Java 的「全员离线」判据（替代 P3–P6）

| 证据 | 判据 | 级别 | 理由 |
|---|---|---|---|
| `player` 归属三列（权威） | 清单（src）玩家没有 `owner_released = 0 ∧ owner_lease_until ≥ now − margin` 的 | block（最多等 `quiescence-wait` 60 s；dry-run 不等） | 释放就意味着最终写回已落库（`architecture.md:743` 起「玩家数据归属协议」），等价基线 P3–P6 的意图；在线的回合制战斗、5.2 交出在途、5.4 传送在途都表现为持有 |
| 同上，dst 玩家 | dst 区持有中的玩家数与前 20 个样本 | **warn** `dst_players_online` | dst 玩家的行不改写；他们能碰到合服对象的写入口（建角 F1、进场 F2、传送 F4、帮会 F5、4.8 交易）全被 dst 的围栏挡住。基线 P5 / P6 也只查清单玩家（`preflight.go:244-268`）。要求 dst 全员离线在多区生产里不可操作（dst 玩家可能正经 5.4 在别区游玩，合服作业又不踢人，Q16、Q26） |
| `xm:location:<pid>` | 清单玩家 `PlayerLocationDirectory.statusesAsync`（`:217`）不是 `ONLINE` / `RECONNECT_LEASE` / `ERROR` | block（同上等待；`ERROR` 标 `INFRA:`） | 5.4 写侧约束 ③（`zone-travel-spec.md:463`）；TTL 60 s / 30 s（`PlayerLocationDirectory.java:46-49`），传送待落点 `l` 不超过票据剩余（≤ 300 s） |
| `xm:presence:<pid>` | 清单玩家 `PlayerPresenceDirectory.findEachStrictAsync`（`:280`）全为 `ABSENT` | block（`ERROR` 标 `INFRA:`） | 防 gate 侧还挂着会话；**禁用** fail-open 的 `findAll` / `findAllAsync`（`:121`、`:165`，`decode` 把损坏条目当不在线，`:323-338`） |
| 节点目录 | `xm:nodes:gate:<src>`、`xm:nodes:scene:<src>` 为空；dst 只报 info | block | 等价 P2；src 无 gate 才堵死 src 建角，src 无 scene 才能退役频道计划 |
| 每批改写 | 同一事务 `FOR UPDATE` 锁行后再判归属三列 | 不符整批回滚，作业 FAILED，保留围栏 | 把「预检之后又被夺权」压到零：行锁释放前 login 的夺权 UPDATE（`PlayerMapper.java:53-58`）被阻塞，而 F2 已先于夺权拒绝 |

- 实现要点：先用一条 SQL 找出两区「持有中」的玩家名单（`listHeldInZones`，走 `idx_player_zone` 后回表过滤），src 的进 block 判定、dst 的只进 warn；等待期间只按主键轮询 src 名单（每 2 s），
  超时前最后再全量查一次；不在 `idx_player_zone` 里加 `owner_released`（每次夺权 / 释放都会改它，热路径多一次二级索引维护）。
- Redis 逐人检查（位置、在线目录、组队）每批 1000 个同时发出，在 `data-ops` 线程上等结果；这条线程不是 Netty I/O 线程也不是场景逻辑线程（AGENTS.md §3）。
  `findEachStrictAsync` 是一次 MGET（单 Redis 部署成立；上 Cluster 时与 `statusesAsync` 一样逐键发，`PlayerLocationDirectory.java:210-217` 的注释已为此分开）。
- **dry-run 不等待**：计划作业里 `quiescence-wait` 视为 0，只报告当下状态。T-7 / T-1 彩排时两区还开着，等 60 s 只会白白占住单飞。

在途状态在 Java 里的表现与兜底：

| 状态 | 表现 | 谁兜住 |
|---|---|---|
| 在线 | 持有归属，scene 每 10 s 续约 | 归属三列 block |
| 5.2 交出 gap（E+1 未释放） | 持有，带租约 | 同上 |
| 3.3 断线重连租约 | 写回已释放，但 `l` 记录留 30 s（`PlayerLocationDirectory.java:49`） | 位置检查等它过期 |
| 5.4 传送第二条腿 | `l` 记录，TTL 为票据剩余（≤ 300 s，`zone-travel-spec.md:437`） | 超过 60 s 等待上限被拒，稍后重跑 |
| 6.3 战斗中在线 | 持有、冻结 | 归属三列 block |
| 6.3 战斗中断线 | scene 照常写回、释放；锁 `xm:battle:{<pid>}:lock` 与待结算 `:settlement` 留在离线玩家身上（锁是承重的，`scene-battle-spec.md:49-55`、`:546`） | 不阻断，只报 warn：按 pid 存、不带归属区，battle 是不分 zone 的全局池（`battle-node-spec.md:242`），结算按位置记录投递，合服后照常生效 |
| 7.2 作业 | 经 `AdminOwnership` 持有归属（`data-ops-spec.md:389-405`） | 归属三列 block；另有 `ops_active` 单飞与 `zone_merging`（§3.7） |
| 僵尸写者（`owner_released = 0` 但租约过期） | 算静止 | 每批复判；见 §10.1 第 6 条 |

### 3.4 为什么不夺权

- 7.2 要写 `player_state`，必须持有归属；合服只改 `zone_id` / `merge_notice_ms`，这两列不在夺权、在线存盘、最终写回、交出、释放、续约的任何 SQL 里（`PlayerMapper.java:53-138`）。
  租约过期的僵尸写者迟到写回也不会与改写互相覆盖。
- 对 10 万级玩家逐个夺权、每 10 s 续约，开销大且没有收益；7.2 一次作业只许 1 万人（`data-ops-spec.md:782`）。
- **仍要求清单玩家离线的原因**：5.4 的 F4 在 scene 内存里持有加载时的归属区；组队记录存成员 zone（`team-spec.md:256-262`）；帮会有「成员 ⇒ 归属区一致」的不变量
  （`guild-economy-spec.md:79`）；被改写的人不应在半合服状态下游玩。这些理由都只针对归属**被改写**的人，所以 dst 玩家只报 warn。

### 3.5 受影响行数的口径

- **xm-data**：连接串带 `useAffectedRows=true`（`xm-data/src/main/resources/application.yaml:16`；7.2 已为 ODKU 计数加它，`data-ops-spec.md:853`），UPDATE 返回「实际改了的行数」。
  合服的状态迁移都会改到列（`status` 前进、`state` 从 PLANNED 变终态、`zone_id` 从 src 变 dst），「恰好 N 行」成立。
  **例外**是幂等进度更新（如 `steps_done = steps_done | bit` 在续跑时可能 0 行）：一律同时写严格递增的 `updated_ms = max(now, 上次 + 1)`，条件带 `status = APPLYING AND last_job_id = :job`；
  仍是 0 行就重读核对，不直接判失败。
- **xm-guild / xm-trade**：同样 `useAffectedRows=true`（`xm-guild/.../application.yaml:31`；`xm-trade/.../application.yaml:23`）。参与方的改写条件是 `zone = from`，写入值 to ≠ from，计数精确。
- **xm-login**：没有这个开关（`application.yaml:21`），返回匹配行数。`consumeMergeNotice` 把大于 0 的值改成 0，两种口径结果相同。
- **共享库 `PlayerStore` 的既有方法**：`renewOwnerLeases` 的注释按「匹配行数」写（`PlayerMapper.java:132`），在 xm-data 上调用时口径不同——这是 7.2b 的事项，合服不调它。

### 3.6 `owner_epoch` 与撤销

- 改归属时把每人当时的 `owner_epoch` 记进 `zone_merge_item.owner_epoch_at_move`（同一事务，§2.8）。
- 撤销时任何清单玩家 `owner_epoch > owner_epoch_at_move` 即说明合服后进过游戏（从任一 zone 进都会夺权加一，`PlayerMapper.java:53-58`），一律拒绝（§5.6）。这精确落实了基线只写在手册里的
  「撤销只适用于未开服」（`unmerge.go:5-7`；`runbook:808-819`；`inventory/tools.md:348`）。

### 3.7 与其他写者的互斥

| 写者 | 互斥方式 |
|---|---|
| 7.2 回档 / 回收 / 欠款（xm-data 夺权） | 共用 `ops_active` 单飞（`data-ops-spec.md:823`、`:829`）。**单飞只管「同时在跑」**：合服作业 FAILED、run 停在 APPLYING / UNMERGING 时单飞已空，7.2 作业可以受理。所以 7.2 作业受理时若目标玩家的 `zone_id`（整区作业为目标 zone）属于某个未终结 run 的 src / dst → 409 `zone_merging`；run 为 APPLIED 但还没开服时 7.2 对清单玩家的写会让 `owner_epoch` 前进，之后撤销按 `played_since_merge` 拒绝（预期行为，手册写明） |
| 5.2 交出、5.4 传送 | 要求在线；归属静止判据排除；在途的待落点 `l` 让预检等待；F4 拒新的传送（23 {3027}） |
| login 夺权 | F2 先拒；每批 `FOR UPDATE` 兜底（行锁让夺权 UPDATE 等待，提交后围栏已经在了） |
| scene 在线存盘 / 最终写回 / 交出 | 不写 `zone_id`；src scene 目录为空 |
| 帮会写 RPC / 资产指令 | F5（14013）；资产投递只发给在线玩家，按位置记录寻址，与 zone 无关 |
| `ZoneAdminController` | run 未终结时拒绝开 dst（409 `zone_merging`）；拒绝重开已退役的 src（409 `zone_retired`）；拒绝删除未终结 run 的区；查 run 表失败回 503（§7.11） |

### 3.8 维护态的边界与停服顺序

- **能挡住的**：assign-gate 对 MAINTENANCE / CLOSED / PREVIEW 都拒，白名单不参与准入（`AssignGateService.java:205-229`），两区不再签发新的 gate 令牌。
- **挡不住的**：① 已有会话不会被踢；② 已签发的 gate 令牌还有最多 10 min（`GateTokenIssuer.java:22`），传送票据最多 300 s；③ **别的 OPEN 区照常发令牌**，归属 src / dst 的玩家
  可以经别区的 gate 进游戏——EnterGame 只看会话 zone，F2 是唯一挡板（5.4 之后这条路径就存在）。
- **所以运维顺序是**：两区置维护 → 用 `tools/GmShutdown.java` **先停 src 的全部 gate，再停 src 的 scene**（反过来会触发 5.5 的停服前疏散，把玩家挪到同区别的节点、多走一轮，
  `scene-drain-spec.md:71`）→ 视需要停 dst 的 gate → 跑合服作业。作业**不踢人**（唯一可用的 tip 2017「被顶号」会误导玩家，基线工具也不踢）；
  仍在别区游玩的 src 玩家会让预检报 `zone_not_quiescent`，只能等他们下线（Q16）。

---

## 4 跨系统步骤

### 4.1 zone 列登记表（Java，全部带 zone 语义的列）

| 表.列 | 属主 | 合服动作 | 出处 |
|---|---|---|---|
| `player.zone_id` | xm-player-store（共享库） | **改写**（xm-data，§2.8） | `xm-player-schema.sql:14` |
| `guild.zone_id` | xm-guild | **改写**（参与方） | `guild_tables.proto:45`（`idx_guild_0` `:33`） |
| `trade_listing.market_zone` | xm-trade | **改写**（参与方） | `trade_tables.proto:42`（`idx_trade_listing_0` 以它打头，`:34`） |
| `trade_listing.seller_zone_at_listing` | xm-trade | 保留（审计原值） | `trade_tables.proto:43` |
| `transaction_log.zone_id`、`player_snapshot.zone_id` | xm-data | 保留（所在进程 zone，历史） | `xm-data-schema.sql:24`、`:45` |
| `zone_config.zone_id`、`zone_whitelist.zone_id` | xm-gateway 建表、xm-data 运维写 | 保留；src 的 `manual_status` 由作业改 CLOSED（§7.3 A9） | `xm-gateway-schema.sql:5-24` |
| `zone_merge_run.src_zone / dst_zone` | xm-data（7.3 新） | 保留（合服自身记录） | §7.6 |

- 无 zone 列、合服不动的表：`player_state`；`guild_member` / `guild_application` / `guild_player_state` / 资产 outbox / 日计数（`guild_tables.proto:54-196`）；`trade_favorite`（`trade_tables.proto:61-65`）；好友四表。
- Redis 侧带 zone 的组队记录（`team_record.proto` 的队伍 zone 与成员 zone）由活队伍阻断覆盖，不进登记表。
- **守护测试**（xm-data `ZoneColumnRegistryTest`）：从仓库根扫描各模块 `src/main/resources/db/*.sql` 与带 `proto2mysql.table_name` 的 `src/main/proto/**/*.proto`，找出名字匹配
  `(^|_)zone(_|$)` 的列；每一列必须在登记表里归为「改写」或「保留」，否则失败。基线的风险正是「表清单与真实库不一致时合服会漏表」（`inventory/tools.md:288` 一类），
  这条测试把它挡在构建期；4.6 帮会活动、4.8 交易写侧新增表时被强制做出合服决定。

### 4.2 基线：帮会（步骤 N、3）

- **表**：只有 `guild.zone_id` 带 zone 语义（`proto/guild/guild_db.proto:20-35`）；成员、申请、玩家状态、资产 outbox、日计数、活动进度都没有 zone 列（同文件 `:43-212`），
  按 guild_id / player_id 原样存活（`audit_resources.go:594-602`）。
- **名字全服唯一**：`uk_guild(name_norm)` 不带 zone（`guild_db.proto:22`）。步骤 N 的重名探测因此恒为空，被刻意保留成**契约断言**：哪天唯一键改成 `(zone_id, name_norm)`，
  合服停在任何写之前（`guild_step.go:7-15`、`:163-200`）；放在围栏之后、清单之前，只在步骤 3 未完成时跑（`merge_run.go:260-267`）。
- **收集**：步骤 3 完成之前，每次运行都把 `zone_id = src` 的帮会并进清单（去重排序）；完成之后清单就是撤销依据，不再改动（`merge_run.go:343-355`；`guild_step.go:136-161`）。
- **改写**：按 §1.5 的形状逐条（`guild_step.go:202-218`），然后对**全清单**失效缓存（`merge_run.go:496-501`）：`INCR guild:v2:cache_generation:{id}` 与 `DEL guild:v2:{id}` 在同一段 Lua 里
  （`guild_step.go:265-300`，键形照抄 `guild_repo.go:162-166`）；只 DEL 不 INCR 会被途中的旧读者回填；先 `SCRIPT LOAD`，因为 pipeline 里遇到 NOSCRIPT 无法回退。
  失效失败算「写到一半」：围栏保留、步骤不标完成。理由是 guild 服是全局服务、合服期间不停，缓存 TTL 30 min；不失效则半小时内帮会仍挂在旧区（`visibleIn` 用快照里的 `ZoneID`，`guild_logic.go:169-171`）。
- **复查**：改写之后 `COUNT(*) WHERE zone_id = src` 必须为 0，否则中止、不标完成；重跑先并入新帮会再搬（`merge_run.go:502-517`）。
- **不需要失效的键**：`player_guild:v2:{pid}` 只存帮会号；全服榜 `guild_rank` 不分 zone（`guild_repo.go:142-154`）。
- **在线服务侧的合服语义**（读者）：事务外闸门 `mergeFenceTip` 只 `EXISTS merge:in_progress:{请求者归属区}`，读失败当合服中，回 14013（`merge_fence.go:11-28`、`:61-74`；`guild_logic.go:293-320`），
  覆盖建帮（铸号之前）、离帮、解散、公告与全部管理 / 申请写（`clientWrite`，`guild_manage_logic.go:213-232`）；事务内闸门 `economyFence` 用锁住行里的 `guild.zone_id` 再判（解散、经济、活动；
  `guild_logic.go:489-493`、`economy_logic.go:288-311`）。审批入帮申请时并行重查申请人的归属区（`guild_manage_logic.go:705-739`），所以待审申请不需要迁移；客户端查榜一律用自己的归属区
  （`guild_logic.go:596-616`）。内部 / GM 路径不查闸门；整段 `MergeMarkerRedis` 没配时闸门完全不生效（§2.1）。

### 4.3 基线：聚宝斋（步骤 3b）

- `market_zone` = 卖家上架时的 home_zone，服务端写入（`proto/trade/trade_table.proto:41`）；`seller_zone_at_listing` 保存原值、合服不改（`:42`）；`trade_favorite` 没有 zone 列（`trade_step.go:20-21`）。
- **为什么必须改**：zone 范围下非卖家只能看到 `market_zone == 调用者 home_zone` 的商品（`go/trade/internal/logic/phase.go:40-50`），不改则源区商品对所有人消失且不报错（`trade_step.go:5-9`）。
  客户端可见字段 `ListingSummary.market_zone = 10`（`proto/trade/jubaozhai.proto:82`），合服后显示 dst。
- 收集口径：步骤 3b 未完成就每次并入（`merge_run.go:369-379`）；改写形状同 §1.5（`trade_step.go:182-197`）。
- 复查残留 > 0 用专门文案中止，**故意不释放围栏**，要求人工停掉上架入口、核对 run_id、DEL 围栏后续跑（`trade_step.go:144-176`；`merge_run.go:536-551`）。
- 基线说明自己不需要的：冲突探测（listing_id 全局唯一）、缓存失效（P1 不用 Redis）、围栏读者（P1 只有 dev / test 播种入口）；并为 P3 留两条硬约束：「加缓存就要在这里补失效」
  「正式上架前必须接上合服围栏」（`trade_step.go:23-36`）。
- 库表缺失 P1 拒绝，`-skip-trade-mysql` 才跳过，此时验证报 warn「NOT VERIFIED」（`merge_run.go:97-109`；`trade_step.go:241-247`）。

### 4.4 基线：排行榜（步骤 4）

- **键**：全服榜 `guild_rank`、区榜 `guild_rank:zone:{z}`、维护锁 `guild_rank:maintenance_lock`（SETNX 5 min，令牌比较后才删），都在 guild Redis DB 2（`guild_repo.go:154-186`；`fence.go:237-296`）。
  全仓没有别的带 zone 的排行；匹配评分 `match:{mq}:rank:{mode}:{config}` 是每条队列的评分镜像，全服共用（`go/match/internal/logic/keys.go:17`、`:70`）。
- **guild 服的维护者**（都在维护锁下）：`UpdateGuildScore` 先在事务里 `FOR UPDATE` 读权威 zone、写 MySQL、再 ZADD 全服榜与**权威 zone** 的区榜（`guild_repo.go:566-618`）；
  `RebuildRanks` 从 MySQL 全量重建、MULTI/EXEC 换榜，每次启动都跑、失败拒启（`:623-698`；`guild.go:220-224`）；`RemoveGuildFromRank` 解散后 SCAN 全部区榜逐个摘除（`:715-`）。
- **步骤 4 `mergeGuildRank`**（`guild_step.go:350-420`）：拿维护锁最多等 30 s → 锁内**重读源榜**（A8）→ `chooseRankWrite`（`:328-349`）：重读非空则写并记重读结果；重读为空则**一律不写**
  （续跑时上一次已并走，或 guild 服合法清空过），只 DEL 源键空壳，避免复活已解散的帮 → 写前经回调把撤销依据落进清单（`merge_run.go:561-567`）→ 一条 MULTI/EXEC 里 `ZADD NX dst` + `DEL src`
  （NX：不拿旧分覆盖步骤 3 之后 guild 服写进目标榜的新分）。
- **清单快照**只在清单为空且步骤 4 未完成时取一次，`rank_members_unwritten` 标明「还没交给过任何一次写」（`merge_run.go:356-367`）。
- **验证**：源榜键必须不存在，且 `ZCARD(dst 区榜) == COUNT(guild WHERE zone_id = dst)`，不等就 block（`audit_checks.go:434-489`；误报见 B19）。

### 4.5 基线：场景热状态（步骤 6，`-clear-source-hot-state`，缺省关）

- **为什么要清**：`player:{id}:location` 不带 TTL（`owner_epoch.go:119`、`:137` 都是不带 EX 的 SET），崩溃、强踢或 zone-down 之后记录永远指向不存在的 (zone, node)；`scene:*`、`world_channels:*`、
  `node_load` 的残骸会误导 scene_manager 的孤儿清理与再平衡（`scene_hot_state.go:5-14`）。在线侧已有兜底 `playerLocationOwnerGone`（`enterscenelogic.go:900-940`），但 `zone_id = 0` 且
  `scene:{id}:zone` 也已删时它 fail-closed、不放行。
- **前置**：`scene_nodes:zone:{S}:load` 为空（`scene_hot_state.go:16-19`、`:327-339`）。
- **删除内容**（SCAN + pipeline、逐键 DEL 兼容 Cluster、幂等；`:21-36`、`:341-482`、`:608-633`）：清单玩家的 location（`zone_id == S` 直接删；`zone_id == 0` 的旧记录按 `scene:{id}:zone`
  反查，查不到就保留、计 undecided，`:247-265`、`:486-559`）；**在删场景键之前**对清单外的全量 location 补扫 `zone_id == 0` 的旧记录（A11，`:592-606`）；
  `scene:{id}:{zone,node,mirror,source,mirrors}` 与 `instance:{id}:player_count` 固定 6 把（`:269-279`）、`instances:zone:{S}:active`；`world_channels:zone:{S}:*` 及 draining / cooldown / desired；
  `node:zone:{S}:*`（含 `death_at` 屏障）与 `scene_nodes:zone:{S}:load`。
- **漏删**：`scene:{id}:agones_gs`（不带 TTL 的 SET，`agones_binding.go:25`、`:305`、`:342`）不在 6 把里，留下永久垃圾键（正常销毁时与其余场景键同一段脚本删，`scene_atomic.go:118-122`）；
  `scene:{id}:draining` 带 TTL，漏删无害。登记 B16。
- **不碰**：玩家的 owner_epoch / handoff 键（全服按玩家的单调代次）。
- **验证**：只扫 zone 前缀的几种模式与负载集，残留只报 **warn**；不查 `scene:{id}:*` 与 location（`audit_checks.go:491-524`）。

### 4.6 Java：帮会、聚宝斋、区榜、频道计划

**帮会（xm-guild 实现 `ZoneMergeParticipantService`，group `guild`，`DubboGroups.java:24`）**

- **执行线程**：全部投递到新的 `guild-maintenance` 单线程执行器、返回 future，不占 guild-worker 与 Dubbo 线程（AGENTS.md §3）。鉴权：调用方 MAC `XM_DUBBO_SECRET`（同 `GuildInternalService` 的口径）。
- `rezoneObjects(run, from, to, ids ≤ 500)`：先 `ZoneMergeFences.checkHolder(run, from, to)`，不成立回 `FENCE_MISSING`、零变更（同基线 `RemapHomeZoneForMerge`「没有围栏就拒绝」，`router.go:474-500`）。
  然后每个 id 一个 `BackgroundTx.run(op, deadline, attempts = 5, body)` 短事务（xm-guild 自己的数据源：RC、`innodb_lock_wait_timeout=1`、`socketTimeout=4000`，`application.yaml:31`；
  1213 / 9007 / 1205 整体重跑，退避 10 ms 起封顶 200 ms，`BackgroundTx.java:115-148`、`:170-182`、`:222-238`）：`SELECT zone_id FROM guild WHERE guild_id = ? FOR UPDATE` →
  无行 GONE / 等于 to 为 ALREADY / 等于 from 则 `UPDATE guild SET zone_id = :to WHERE guild_id = ? AND zone_id = :from` 为 MOVED / 其他 ELSEWHERE。锁序是主键在前，与在线写者一致（同 `guild_step.go:31-37`）。
- **严格失效**：整批改完后对 MOVED ∪ ALREADY 调新增的 `GuildCacheInvalidator.invalidateStrict(guildIds, deadline)`（与 `afterCommit` 同一套「代次换成唯一值 + 删数据键」，**失败即抛**）。
  失败 → 整批回 `ERROR`，xm-data 重试这一批（已改的行变 ALREADY，失效再做一遍，幂等）。不能用现有 `afterCommit`：它「永不抛」、后台 3 s 后放弃（`GuildCacheInvalidator.java:22-38`），
  而快照里存着 zone（`RedisKeys.java:162-169`）、TTL 30 min（`GuildProperties.java:45`），查帮用快照 zone 判可见性（`GuildService.java:174`）——不失效就是客户端可见故障（dst 成员查不到合过来的帮，14001）。
- `finishRezone(run, from, to)`：核对持有者 → 在 `guild-maintenance` 上执行 `GuildRanks.rebuild(...)`（与启动时同款，`GuildConfiguration.java:269-281`；锁内、等锁上限 5 s，`GuildRanks.java:222-232`、
  `GuildLimits.java:93-105`）。换榜时删除旧区榜、重写区索引，src 区榜与索引成员自然消失；全服榜内容不变。幂等，可任意重复；重建抛异常（等锁超时、锁中途失效、临时键不完整）→ 回 ERROR。
- `preflight(MERGE)`：`SELECT COUNT(*) FROM guild a JOIN guild b ON a.name_norm = b.name_norm WHERE a.zone_id = :src AND b.zone_id = :dst` 必须为 0（契约断言，同基线 N）；
  构建期另有单测钉住 `guild_tables.proto:32` 的 `unique_key = "name_norm"`。
- `preflight(UNMERGE, since, guildIds)`：清单帮会里 `join_time_ms ≥ since` 的成员数必须为 0（`guild_tables.proto:77`），否则 `membership_crossed`（§5.6）。清单帮会超过 1000 个时 xm-data 分页多次调用，
  任一页不过即拒。`since` 取 `run.created_ms`，比围栏立起略早，只会多拒（fail-closed）。
- `listZoneObjects` / `countZoneObjects`：走 `idx_guild_0`；`countZoneObjects` 同时回该 zone 区榜的 `ZCARD` 与是否在区索引里（供验证，xm-data 不读帮会的 Redis 键）。
- **不用动的键**：`xm:guild:{p:<pid>}:gid`（不带 zone）、申请推送冷却、资产指令 outbox（按位置记录找 scene，与 zone 无关）。
- **没有内部 / GM 写路径要防**：xm-guild 的 Dubbo 只导出客户端消息服务与只读的 `GuildInternalService`，基线 H7 一类缺口在 Java 不存在；残留复查只是保险。

**聚宝斋（xm-trade 实现同一接口，group `trade`，`DubboGroups.java:27`，端口 20887）**

- `rezoneObjects`：先 `checkHolder(run, from, to)`，不成立回 `FENCE_MISSING`；然后在现有存储线程池上逐条短事务（xm-trade 数据源：RC、`innodb_lock_wait_timeout=2`，`application.yaml:23`）：
  `SELECT market_zone … FOR UPDATE` 分类 → `UPDATE trade_listing SET market_zone = :to WHERE listing_id = ? AND market_zone = :from`；1213 / 1205 整体重跑至多 5 次。`seller_zone_at_listing`、`trade_favorite` 不动。
- xm-trade 已依赖 xm-discovery 并装配了 Redisson（listing_id 发号租约，`TradeConfiguration.java:139`），读围栏不新增依赖；这是 xm-trade 第一次读业务相关的 Redis 键，trade-spec「不存任何业务键」仍成立（只读）。
- `finishRezone` 为空操作（4.7 没有缓存，`trade-spec.md:757`）；`preflight` 4.7 为空。
- dev 播种 `SeedListingController`（`/admin/trade/seed-listing`，非 dev / test 回 403）不读围栏，同基线 P1；残留复查兜底。
- **登记给 4.8 的约束**（对应基线 `trade_step.go:23-36`）：上架、下单、交付前接 F 检查点；有了缓存就在 `rezoneObjects` 里补失效；`preflight(UNMERGE)` 必须拒绝「清单商品自 `since` 起有订单」；
  新表的 zone 列进 §4.1 登记表。

**区榜**：只有帮会榜，由 `finishRezone` 重建；清单里不存榜单成员、没有 `ZADD NX` 的取舍、没有「源榜为空就不写」的分支。验证改为「重建后 src 区榜不存在、dst 的 `ZCARD == count`」，按构造成立。
匹配评分 ZSET `xm:{match}:rank:*` 全服共用，不动（`match-spec.md:986`）。

**源区频道计划退役（§7.3 A9，缺省开、不致命）**

基线步骤 6 要清的东西在 Java 里大多不存在（§0.3）；唯一会永久残留的是 `xm:world:zones` 里的 src（无 TTL、只增，`RedisKeys.java:274-281`）以及 `xm:world:{z:<src>}:{ch,desired,cooldown,ver}`。
不清的话，scene-manager 的协调器会对 src 永远竞选、续期领导锁（`WorldChannelCoordinator.java:122-158`：`tick` 对 `store.zones()` 逐个 `computeIfAbsent`，`renewLeaders` 续内存表里的每个 zone，从不移除）。

| 步骤 | 执行者 | 动作 |
|---|---|---|
| 1 | xm-data（A9） | 前置 `xm:nodes:scene:<src>` 为空（否则跳过、warn）；调新增的 `WorldChannelStore.unregisterZone(src)`（`SREM xm:world:zones src`）。`WorldChannelStore` 是 `xm:world:*` 的唯一读写入口（`WorldChannelStore.java:6-24`），xm-data 经它写，不另拼键 |
| 2 | scene-manager 协调器（tick 线程） | `tick` 读到的 `zones()` 里没有、但内存表里有的 zone：若本副本持锁 → 用持有的令牌调新增的 `WorldChannelStore.retireZone(zone, token)`；不持锁 → `tryAcquireLeader` 试一次，拿到就同样退役，拿不到（别的副本持有）就只移出内存表。之后移出内存表、清 gauge |
| 3 | `retireZone` Lua（`{z:<zone>}` 同槽） | 领导锁的值是本令牌才执行：DEL `ch`、`desired`、`cooldown`，按 `ch` 里的 scene_id DEL 对应的 `resv:<scene_id>`（本来就带 TTL，顺手删、不 SCAN），最后 DEL `leader`；**`ver` 保留**（只增不减、不过期，下次重建取 max(旧值 + 1, TIME)，`RedisKeys.java:249-256`）；令牌不符返回 0、什么都不删 |
| 4 | xm-data（A9） | 轮询 `WorldChannelStore.readPlan(src)` 频道表为空且领导锁不存在（新增只读 `leaderPresent(zone)`），上限 = 领导锁 TTL（30 s，`WorldChannelProperties.java:21`）+ 一拍（5 s）；超时只记 warn `retire_failed`、步骤位 `PLAN_RETIRED` 不置，`resume` 可重做 |
| — | 全局 | `xm:node-id-epoch:*:<src>:*` **绝不删**（代次必须跨租约单调，`RedisKeys.java:20-26`）；`xm:location`、`xm:presence`、`xm:nodes:*:<src>`、排空 / 疏散标记、登录排队 / 限流键、推送频道不动（带 TTL 或不可达） |

- `retireZone` 不在 Lua 里读 `xm:world:zones`（不同槽，上 Cluster 会跨槽）；「集合里已没有它」由协调器在同一拍读到的 `zones()` 保证。src 的 scene 节点在退役后又被拉起会重新 `registerZone` 并重建计划
  （`scene-channels-spec.md:796-798` §4.10.5）——预检已要求 src 无 scene 节点，事后发生只是垃圾，verify 报 warn。
- 全部 scene-manager 副本都在 SREM 之后才重启时，没有副本的内存表里有 src，计划键留作垃圾：健康检查跳过没有节点的 zone（`WorldChannelsHealthIndicator.java:38-46`），不影响正确性，verify 报 warn。
- 撤销不恢复：src 的 scene 启动时会 `registerZone` 并拉取 / 重建计划。5.3 选了「节点自有 + 目录登记」，没有实例键要清。

### 4.7 好友、聊天、组队、匹配、战斗

| 系统 | 基线 | Java | 合服后客户端所见（两版一致） | 出处 |
|---|---|---|---|---|
| 好友（`friend` / `friend_request` 等表） | 无 zone 列；**不写**；审计各出一条 info 计数行，库表查不到报 INFRA | 不写、不计数（D17） | `FriendEntry.zone_id`（`friend.proto:68`）每次读时现查归属区，自动显示 dst；在线目录只返回与调用者同归属区的玩家，合服后两区玩家互相可见 | 基线 `audit_resources.go:488-592`、`go/friend/.../friend_profiles.go:73-79`、`online_directory.go:100-108`；Java `FriendService.java:321`、`OnlineDirectory.java:255` |
| 好友缓存 | 只存关系 | 只存关系 | — | 基线 `friend_repo.go:280-296`；Java `RedisKeys.java:78-107` |
| 聊天 | 世界频道全服一条，私聊按玩家对 | 同；`xm:chat:{world}:log` | 无变化 | `chat_logic.go:8-15`；`RedisKeys.java:116-119` |
| 组队 | 记录存队伍 zone 与成员 zone，**不迁移**；P7：索引 tid ≠ 0 且 `team:rec` 存在即拒，孤儿索引不阻断，读错误按拒绝；无管理员解散入口，runbook 明令不要手工 DEL `team:*` | 同口径阻断：`TeamMembershipReader.readAsync`（`:69`）一段只读 Lua 原子读索引 + 投影；投影存在即在队（记录与投影同一段脚本写、同 TTL、同删，`TeamScript.java:92-97`，所以不需要基线的 `EXISTS rec`）；`KEY_MISSING` / tid = 0 / 投影缺失（孤儿索引）放行；异常完成（损坏 / 读失败）fail-closed；不改写记录 | 合服前两区玩家组队回 4020（`team_error_tip.proto:52`）；合服后同区、可组队；新建队伍按新归属记 zone | `preflight.go:270-350`；`runbook:466-474`；`TeamMembershipReader.java:13-33`、`:80-106` |
| 匹配票据 / 评分 | 票据有 zone_id，只用于观测；评分 ZSET 全服共用；工具不读不写 | 只报 info | — | `queue.go:56-58`；`keys.go:17`、`:70`；`match-spec.md:359`、`:986` |
| 战斗锁 / 待结算 | — | 只报 warn（计数），理由见 §3.3 在途状态表 | — | `scene-battle-spec.md:49-55`、`:546`；`battle-node-spec.md:242` |
| 角色名 | 全服唯一；只出一条 info 行 | 同；只出 info | 不改名 | `audit_resources.go:421-486` |

### 4.8 基线键空间与合服动作（对照用）

| 键 | 库 | 合服 | 撤销 |
|---|---|---|---|
| `player:zone:{id}` | mapping DB 0 | 步骤 5：按清单逐键 Lua CAS src→dst | 5′：只改当前值是 dst 的（MGET 再 SET） |
| `merge:in_progress:{z}` | mapping DB 0 | F 段立两把；正常结束按 run_id 比较后删 | 同 |
| `merge:merged_into:{src}` | mapping DB 0 | 步骤 5 改映射之前写 | 5′a：dst 与 run_id 都对得上才删 |
| `guild:v2:{id}` + `:cache_generation:{id}` | guild DB 2 | 步骤 3：对全清单 INCR + DEL | 3′：同样失效 |
| `guild_rank:zone:{z}` / `guild_rank:maintenance_lock` | guild DB 2 | 步骤 4：ZADD NX dst + DEL src / 互斥 | 4′：按清单分数 ZADD 回 src、从 dst ZREM |
| `player_merge_notice:{pid}`、`player_force_rename:{pid}` | login DB 0 | 步骤 7 SET | 7′：DEL |
| `player:session:{pid}`、`lock:player:{id}`、`kafka:*:queue:db_task_zone_{z}`、`team:*` | 各自 | P4–P7 只读 | 同（目标区口径） |
| `scene_nodes:zone:{S}:load`、`player:{id}:location`、`scene:{id}:*`、`instance:{id}:player_count`、`instances:zone:{S}:active`、`world_channels*:zone:{S}*`、`node:zone:{S}:*` | scene DB 0 | P2 与步骤 6 前置；步骤 6 删（漏 `scene:{id}:agones_gs`） | **不恢复** |
| `player:{id}:*`（blob） | 按 home 选的 data Redis | copy 模式多集群时步骤 2 拷贝 | 不删（runbook §10.1） |
| `friend:*`、`chat:*`、`match:{mq}:*`、网关 `rl:zone:{z}` 等 | 各自 | 不动（网关状态由运维经 `/admin/zones/{id}/maintenance` 改，`runbook:452-465`） | 不动 |

### 4.9 Java Redis 键空间与合服动作（DB 12，全部经 `RedisKeys`）

| 键 | 写者 | 合服 | 撤销 |
|---|---|---|---|
| `xm:{merge}:fence:<zone>`（新） | xm-data | 立 / 续 / 补回 / 释放 | 同 |
| `xm:guild:{g:<id>}:snap(:gen)`（`RedisKeys.java:162-169`） | xm-guild | 参与方严格失效 | 同 |
| `xm:guild:{rank}:all / zone:<z> / zones / lock / tmp:*`（`:187-221`） | xm-guild | `finishRezone` 全量重建（`:all` 内容不变） | 同 |
| `xm:guild:{p:<pid>}:gid`、申请推送冷却 | xm-guild | 不动 | 不动 |
| `xm:{team}:*`（`:138-160`） | xm-team | 只读（预检） | 只读 |
| `xm:world:zones`（`:274-281`） | scene 启动时 `registerZone`；7.3 起合服作业 `unregisterZone` | SREM src | 不恢复（src 的 scene 启动时重新登记） |
| `xm:world:{z:<src>}:{ch,desired,cooldown,leader,resv:*}` | 领导者（`WorldChannelStore`） | 领导者 `retireZone` 删除 | 不恢复 |
| `xm:world:{z:<src>}:ver` | 领导者 | **保留** | — |
| `xm:presence`、`xm:location`、`xm:nodes:*`（`:29`、`:45`、`:54`） | gate / scene / 节点 | 只读（预检） | 只读 |
| `xm:node-id-epoch:*`（`:20-26`） | 节点 | **绝不删** | 同 |
| `xm:friend:*`、`xm:chat:*`、`xm:{match}:*`、`xm:battle:{<pid>}:*`、`xm:owner-takeover`、`xm:gate-push:*`、`xm:scene-evacuating:*`、`xm:login-queue*:<z>`、`xm:rl:zone:<z>`、登录令牌 / 设备、产出封禁、热关停 | 各自 | 不动：带 TTL、不带 zone，或 src 置 CLOSED 后不可达 | 不动 |

---

## 5 预检 / 审计 / 验证 / 撤销

### 5.1 基线

**门禁**（`preflight.go:202-350`；合服按**源区**查 `merge_run.go:244-258`，撤销按**目标区**查 `unmerge.go:172-187`；句柄缺失也算没通过 `preflight.go:173-174`）：

| 门禁 | 判据 | 出处 |
|---|---|---|
| P2 | `ZCARD scene_nodes:zone:{z}:load == 0` | `preflight.go:203-210` |
| P3 | `db_task_zone_{z}[_g<gen>]` LAG 为 0（kafka-consumer-groups 表头解析；`-assume-kafka-drained` 是运维担保） | `:83-143`、`:212-226`；`merge_run.go:1379-1390` |
| P4 | `kafka:{retry,processing,dead}:queue:<topic>` 为空 | `:228-242` |
| P5 / P6 | 清单玩家无 `lock:player:{id}`、无 `player:session:{id}` | `:244-268` |
| P7 | `team:player:<pid>.tid ≠ 0` 且 `team:rec:<tid>` 存在即拒；孤儿索引不阻断；读错误按拒绝 | `:270-350` |

**审计 `-mode audit`**：只读、不立围栏；在线、锁、Kafka、源区节点、名字规则、好友 / 申请、帮会成员（`audit_resources.go:335-362`）；单项超时 30 s（`:362-370`）；
退出码 2 = 基础设施问题没查成、1 = 有 block、0 = 通过（`audit_resources.go:283-300`；`audit_checks.go:36-40`）。

**合服后验证 `-verify-merged`**（必须带清单，`main.go:481-486`；`runbook:621-630`）：`verify:mapping_src`、`verify:manifest_mapping`（逐 id，人数 ≠ expected 即 block）、`verify:manifest_rows`、
`verify:guild_zone`、`verify:guild_rank`（源榜不存在且 `ZCARD dst == COUNT(guild WHERE zone=dst)`）、`verify:trade_listing`、`verify:source_hot_state`（warn）、`verify:merge_fence`
（`audit_checks.go:205-552`；`trade_step.go:241-273`）。旧的「目标库总行数 ≥ N」下界已删（A6，`placement.md:392`）。

**撤销 `-mode unmerge`**（`unmerge.go:3-47`）：src / dst 只从清单读（`main.go:456-464`）；顺序与合服完全相反、**先改路由**（`unmerge.go:9-22`）：前置（目标区 P2–P7、冻结记录、能力标记，
`:172-228`；清单有帮会 / 商品时对应库表必须存在，`:117-130`）→ 7′ 删提示 / 改名键（`:230-235`、`:437-458`）→ 5′ 映射改回（**MGET 再 SET，非 CAS**，`:391-433`）→ 5′a 清 `merged_into`（`:244-250`）→
5′b copy 模式失效缓存（`:252-264`）→ 4′ 区榜回写（`:266-278`）→ 3b′ 交易（`:280-296`）→ 3′ 帮会（`:298-317`）→ 1′ copy 模式删逐字节相同的行（`:319-357`；`player_rows.go:577-646`）。blob 不删（`:361-364`）。

- **撤销不记步骤进度**，靠「每一步按对象当前值过滤」幂等。立围栏后先判半撤销：清单玩家里只要有人 `player:zone == src` 就当续跑，读失败也按半撤销处理，门禁被拒也保留围栏（`unmerge.go:151-170`）。
- **中止方式不一致**：3b′ / 3′ / 5′a / 5′b 的写失败走 `abortKeepingFence`（带指引）；7′ / 5′ / 4′ 的失败直接 `log.Fatalf`（`unmerge.go:231-242`、`:268-276`），`os.Exit` 不跑 defer，
  围栏照样保留，但**没有续跑指引**（§10.4 B8）。
- **不强制「还没开服」**（§10.4 B4）。

### 5.2 Java 预检（围栏之下，与计划 / 审计共用一套检查 `ZoneMergeChecks`）

| 检查 | 级别 | 对应基线 |
|---|---|---|
| src ≠ dst，都不为 0，都在 `zone_config` | block（`bad_request` 在受理时） | — |
| 两区 `manual_status ∈ {MAINTENANCE, CLOSED}` | block `zone_open` | runbook Step 1 |
| src 或 dst 有未终结 run；src 已被合走；dst 已被合走 | block `merge_in_progress` / `src_retired` / `dst_retired` | X、`merged_into.go:64-91` |
| 别的 run 持有围栏（计划时只读核对） | block `merge_in_progress` | `fence.go:135-158` |
| `idx_player_zone` 与 `player.merge_notice_ms` 列存在（JDBC `DatabaseMetaData.getIndexInfo` / `getColumns`，H2 与 MySQL 同一写法；不用 MySQL 专有的 `SHOW INDEX`） | block `missing_index` / `missing_migration`（存量库漏跑 `ALTER` 时，否则第一批改写才会因未知列失败，此时已在写阶段） | — |
| src 的 gate / scene 节点目录为空；dst 有节点 | block `src_nodes_alive`；info | P2 |
| 清单（src）玩家归属静止、无 `o` / `l` 位置、无在线目录（§3.3） | block `zone_not_quiescent`（最多等 60 s；dry-run 不等）；读失败 block `INFRA:` | P3–P6 |
| dst 玩家持有归属 | warn `dst_players_online`（计数 + 样本） | 基线不查 |
| 清单玩家不在活队伍（`TeamMembershipReader`，§4.7） | block `team_active`（报告列 team id 与成员，只进报告与日志；写进 T-7 公告） | P7 |
| 参与方可达、`preflight(MERGE)` 通过（帮会重名必为 0）；被 `skip` 的列出原因 | block `participant_unavailable` / `guild_name_collision`；warn `participant_skipped` | P1、N |
| 首跑玩家数 = `expectedSrcPlayers`；为 0 且未 `allowEmptySource` | block `count_mismatch` / `empty_source`（只在 apply） | G（`merge_run.go:703-734`） |
| 计数：两区玩家 / 帮会 / 商品；`zone_id` 不在 `zone_config` 里的玩家 | info / warn | `audit_resources.go:335-372` |
| 战斗锁 / 匹配票据 | warn `battle_lock_present` / info `match_ticket_present` | — |
| 角色名（全服唯一） | info | `audit_resources.go:479-486` |

查询失败一律 block 并标 `INFRA:`，CLI 退出码 2；单项超时 30 s（同 `audit_resources.go:362-370`）。`ZoneMergeChecks` 的检查顺序固定为表中自上而下（先静态、再节点、再归属与逐人），
结果码取第一个 block 项，robot / 测试据此断言（例：撤销时玩家仍在线，先报 `zone_not_quiescent` 而不是 `played_since_merge`）。

### 5.3 Java 计划（dry-run）与审计

- `POST /admin/zone-merges {dryRun: true}` → 作业 `ZONE_MERGE`（`request.dryRun = true`）：执行 §7.3 的 A1、A3、A4，**不立围栏、不写清单、不改任何数据**，只写 `ops_job` 行与事件。
  结果摘要给出 N（src 玩家数）、各计数、全部 findings（code、severity、count、≤ 20 个样本 id）、verdict（PASS / BLOCK / INFRA）。
- CLI `MergeZone audit` 与 `MergeZone plan` 都走这个作业（不另设同步审计接口：50 万人的逐人检查不能占 Tomcat 线程）。dry-run 不需要 `xm.data.ops.enabled`（同 7.2 回档 dry-run）。
- 彩排（T-7 / T-1）时两区还开着：`zone_open`、`src_nodes_alive`、`zone_not_quiescent` 必然是 block，verdict 必然 BLOCK、CLI 退出 1。手册写明彩排看的是**其余** findings 与 N；
  T-0 两区进维护、停服之后再跑一次 plan，那一次必须 PASS。
- 基线 dry-run 写 `<path>.dryrun.json` 并需要两道拒读（A3，`manifest.go:323-346`）；Java 的计划不产生任何可被引用的清单，这类风险不存在（D3）。

### 5.4 Java 合服后验证

`GET /admin/zone-merges/{runId}/verify`（同步、只读）：

| 行 | 断言 | 级别 |
|---|---|---|
| `verify:run_status` | run 为 APPLIED | block |
| `verify:players_src` | `COUNT(player WHERE zone_id = src) = 0` | block |
| `verify:manifest_players` | `zone_merge_item(PLAYER) ⋈ player` 中 `state ∈ {MOVED, ALREADY} ∧ zone_id ≠ dst` 为 0、`state = PLANNED` 为 0（列前 20 个） | block |
| `verify:expected_count` | `COUNT(item PLAYER WHERE late = 0) = expected_src_players` | block |
| `verify:guild_zone` / `verify:trade_zone` | 参与方 `countZoneObjects(src).count = 0`；被跳过报「NOT VERIFIED」 | block / warn |
| `verify:guild_rank` | src：`rank_card = 0` 且不在区索引；dst：`rank_card = count` | block |
| `verify:merge_fence` | 两把围栏都不存在 | block |
| `verify:src_closed` | `zone_config.src` 为 CLOSED | warn |
| `verify:src_nodes`、`verify:src_world_plan` | src 节点目录为空；`WorldChannelStore.zones()` 不含 src、`readPlan(src)` 频道表为空、领导锁不存在 | warn |
| `verify:notice_pending` | 清单玩家里 `merge_notice_ms = notice_ts` 的人数（未领取） | info |
| `verify:played_since` | `owner_epoch > owner_epoch_at_move` 的人数（开服前应为 0） | info |

结论聚合：任一查询失败 → INFRA（退出 2）优先；否则有 block → BLOCK（退出 1）；否则 PASS（退出 0，可带 warn）。同 `audit_checks.go:3-40` 的三档。
verify 在 Tomcat 线程上同步执行，几条按清单 join 的 SQL（50 万行级）各设 30 s 语句超时（`Statement.setQueryTimeout`），超时按 INFRA；不做逐人 Redis 读。
任一 `verify:players_src` / `verify:guild_zone` / `verify:trade_zone` 为 block（A10 之后才落进 src 的残留）时，处置是 `resume` 补扫（§7.3），不是手工改库。

### 5.5 Java 撤销（作业 `ZONE_UNMERGE`，§7.4）

- **适用面**：run 为 APPLYING（半途失败的合服整体退回）、APPLIED、UNMERGING（撤销续跑），且是涉及 src / dst 的**最近一次**未作废 run（否则 `not_latest_run`）。
  「未作废」= 状态不是 ABANDONED / UNMERGED；「最近一次」= 满足 `src_zone` 或 `dst_zone` ∈ {本 run 的 src, dst} 的未作废 run 里 `created_ms` 最大者。3→2、2→1 之后撤掉 2→1，允许再撤 3→2；
  这条判据刻意保守：3→2 之后又有 4→2 时，即使清单不相交，也要先撤 4→2。
- **顺序**（同基线「先改路由」）：立围栏（op = unmerge，同一 run 接管）→ 宽限 → 预检 → run 改 UNMERGING → 玩家改回（提示同语句还原）→ 聚宝斋改回 → 帮会改回 + 区榜重建 →
  src 的 `zone_config` 改回 MAINTENANCE、文案清空 → run 改 UNMERGED → 释放围栏。
- 只动 `state ∈ {MOVED, ALREADY}` 的对象，条件 `AND zone = dst`；GONE / ELSEWHERE / PLANNED 不动；dst 原住民一个不碰。
- 没有基线 5′b、1′（Java 从来没拷过行、没有缓存），没有 4′ 的快照回写（区榜从 MySQL 重建，修 §10.4 B11）。
- 失败：保留围栏、run 留在 UNMERGING，重新提交撤销即续跑（每步幂等）；**每条失败路径都有同一种出口**（job FAILED + 结果码 + 「resume / unmerge / abandon」指引），修基线 B8。
  MySQL 里没有「半撤销」的歧义状态：每批玩家的改回与 item 状态同事务。

### 5.6 Java 撤销前置

| 检查 | 级别 |
|---|---|
| 两区不是 OPEN / PREVIEW | block `zone_open` |
| src 节点目录为空；清单玩家归属静止、无在线目录 / 位置记录 / 活队伍；dst 原住民持有归属只报 warn | block（同 §5.2） |
| 清单玩家（MOVED ∪ ALREADY）`owner_epoch > owner_epoch_at_move` | block `played_since_merge`（列样本） |
| 帮会参与方 `preflight(UNMERGE, since = run.created_ms, 清单帮会)`：有合服后入帮的成员 | block `membership_crossed` |
| 聚宝斋参与方 `preflight(UNMERGE)`（4.8 起：清单商品有新订单） | block |
| 每批改回事务内复判 `owner_epoch == owner_epoch_at_move` | 不符则整批回滚、作业 FAILED `played_since_merge`（保留围栏） |

**不提供强制选项**（Q17）：开服后发现合错，转为单玩家修复（7.2 工具）或从备份还原。为什么两条就够：清单玩家合服后的任何动作都要先进游戏（epoch 加一）；
原住民能影响清单对象的唯一离线痕迹是入帮（`join_time_ms`），4.7 的聚宝斋只读；4.8 交易落地时由它扩展预检。

---

## 6 客户端可见行为

### 6.1 总表

| 时机 | 消息 | Java | 基线 | 当前客户端 |
|---|---|---|---|---|
| 窗口内建角（src / dst 会话） | 14 | `{2020}`（F1） | 同（`createplayerlogic.go:696-717`） | 同 |
| 窗口内进游戏（归属 src / dst，从任何 zone 进） | 26 | **26 `{error_message 3023}` 同步回**（F2，5.4 已定口径，`zone-travel-spec.md:456`） | 26 成功后推 23 {3023}（`entergamelogic.go:431-471`） | 两种都按失败处理（`GameClient.cs:802-803` 判 error_message；23 走 tip 处理器） |
| 窗口内跨区传送（归属 src / dst） | 226 → 23 | 23 `{3027}`「目标区服繁忙,请稍后再试」（`tip_text.json:156`），玩家原地不动 | 同 | 同 |
| 窗口内帮会写 | 帮会应答 | in-band 14013「区服合并维护中，帮会操作暂停，请稍后再试」（`tip_text.json:37`） | 同（`MergeMarkerRedis` 配置正确时） | 同 |
| 区服列表 / assign-gate | HTTP | src 为 **CLOSED**，带合服文案（`ServerListController.java:46-54`）；assign-gate 503 `zone_closed`；dst 开服前为 MAINTENANCE 503 `zone_maintenance` | src 保持 MAINTENANCE（`runbook:669-697`） | 同一 JSON 形状 |
| 合服后角色列表 | 48 | `players[].zone_id = dst`（直读 `player.zone_id`） | 映射覆盖后同值（`homezone.go:200-236`） | 按选中区过滤（`GameClient.cs:645-652`）：源区玩家要选 dst |
| 合服后首次成功进游戏 | 26 | 字段 3 = 合服时刻毫秒（run 内固定），只出现一次；字段 4 = false | 字段 3 在进场结果出来之前消费；字段 4 恒 false | **不读字段 3 / 4**（`GameClient.cs:795-805`） |
| 合服后帮会 / 区榜 | 60、35、27、52 | 源区帮会 `zone_id = dst`（`guild.proto:36` 的 `GuildInfo.zone_id`）、出现在 dst 区榜；两区帮会互相可查、可申请 | 同 | 同 |
| 合服后聚宝斋 | 196、197、198 | 源区商品 `market_zone = dst`（`jubaozhai.proto:82`），在 dst 可浏览；收藏不变 | 同（`trade-spec.md:477`；`runbook:681-683`） | 同 |
| 合服后好友 / 组队 | 好友列表、在线目录；组队邀请 | 好友视图 `zone_id = dst`；在线目录两区合并；两区玩家组队不再回 4020 | 同 | 同 |
| 撤销之后 | 48 / 26 | 角色回到 src、没有本次合服的提示（链式合服时还原上一次合服未领取的提示）；帮会 / 商品回到 src | 同（`unmerge.go:230-242`；基线 7′ 直接删键，链式时上一次的提示也一起丢） | 同 |
| RedirectOnEnter（开关打开） | 26 + 124 | 见 §6.4 | 缺省关（`go/login/etc/login.yaml:209-211`） | **已实现 124**（`GameClient.cs:1037-1066`） |

### 6.2 区服列表与源区文案

- 作业的退役步骤把 `zone_config.src` 改为 `manual_status = 2 CLOSED`，`maintenance_msg` 取配置模板（缺省「本区已合并至 {dst_name}（{dst} 区），请在区服列表中选择该区登录」，
  渲染后 ≤ 256 字符，`xm-gateway-schema.sql:10`），区服列表随 CLOSED 下发（`ServerListController.java:51`），1 s 内生效（`ZoneDirectory` 快照）。
- `ZoneAdminController` 拒绝把已退役的 src 改回 OPEN / PREVIEW（409 `zone_retired`），也拒绝在 run 未终结时打开 dst（409 `zone_merging`）（§7.11）。
- 基线 runbook 让 src「保持维护」，Java 由作业置 CLOSED：客户端可见差异 D13。

### 6.3 合服后提示（26 字段 3 / 4）

- **写**：`player.merge_notice_ms BIGINT NOT NULL DEFAULT 0`；玩家改归属的同一条 UPDATE 写 `T`（§2.8），撤销同一条 UPDATE 写回 `notice_before_ms`（通常 0；链式合服时是上一次合服的时刻）。
- **读**：EnterGame 一开始的 `findPlayer` 已读整行（`EnterGameHandler.java:200`；`PlayerMapper.java:36-37` `SELECT *`），零额外读。
- **消费**：在 `claim()` 拿到 `Claimed` 的那一刻（`EnterGameHandler.java:295-302`，此时在 login 工作线程上）执行 `PlayerStore.consumeMergeNotice`：
  `row.merge_notice_ms > 0` 才 `UPDATE player SET merge_notice_ms = 0 WHERE player_id = ? AND merge_notice_ms = ? AND merge_notice_ms > 0`；影响 1 行才把值填进字段 3，结果随 `ClaimStep`（`:419`）
  带到 `accepted`（`:390`）。影响 0 行（并发的另一次进游戏先拿走了）→ 填 0。SQL 出错 → 记 ERROR、填 0、**不阻断登录**，值仍在，下一次进游戏再给。
  - 不能等到构造 `accepted` 时再做：`rerouteIfStale` 重新分配时，它的 future 在 Dubbo 回调线程上完成（`:334`、`:358` 的 `handle`），随后的 `thenApply(… accepted …)` 也跑在那条线程上，
    不能在那里做阻塞的 MySQL 写（AGENTS.md §3）。`Claimed` 之后的链路只会以 `accepted` 结束（`rerouteIfStale` 的失败都回落原分配），所以提前消费不改变「只在受理时消费」的语义。
- 拒绝路径（F2 3023、2005、2011 等）与重定向路径（GO-5 / RedirectOnEnter）**不读也不删**。字段 4 恒 false，不加列。
- 7.2 回档恢复「player 行可变列」（等级 / 场景 / 坐标，`data-ops-spec.md:63`），不得改写 `zone_id` 与 `merge_notice_ms`；回档 SQL 显式列列即可保证。
- **与基线的差别**（D11，客户端可见）：① 基线在知道进场结果之前就消费（`entergamelogic.go:406-412`），进场随后失败（3023）时提示永久丢失，Java 被拒不消费；
  ② 基线 GET 与 DEL 两个 pipeline 不原子（`:494-547`），并发两次进游戏可能都看到，Java 条件 UPDATE 恰好一次；③ 基线续跑会用新时刻对**全清单**重打（`merge_run.go:636`；`post_merge_stamp.go:43-47`），
  开服后重跑会让看过提示的人再看一次，Java 时刻按 run 固定、只在改归属那一刻写；④ 基线的重定向（RedirectOnEnter、GO-5）也走 `:412` 的成功出口，字段 3 出现在**第一跳**的 26 里，
  Java 重定向路径不消费、字段 3 出现在目标 gate 上落地的那个 26 里——两者都满足契约「只在第一次成功进游戏时非 0」（`login.proto:88-98`）。
  残留：`accepted` 之后 scene 侧进场失败（gate 推 23 {3023}，`PARITY.md:36`）或应答没送到客户端（gate 已断）仍算已消费，窗口远小于基线，可接受。

### 6.4 RedirectOnEnter（124）

- 配置 `xm.login.redirect-on-enter`，缺省 false（同基线 `login.yaml:211`）。
- 打开时，在 5.4 去向表「位置记录没有 / 墓碑」那一格（`zone-travel-spec.md:758`）新增分支：`row.zone_id ∉ {0, 当前 zone}` → `redirectToZone(row.zone_id, pid)` →
  回 `EnterGameResponse{player_id}` + `RedirectToGate` 指令，不夺权、不分配；调用失败 / 超时 / `tip ≠ 0` → 退回本 zone 首登。等价基线「首登且无在场 scene 才按归属区送回」
  （`config.go:211-215`；`homezone.go:245-275`）。
- 只在 robot 与开关打开的切片上验证（§13.6）。基线文档说 Unity 只打 124 日志（`config.go:200-202`、`runbook:851`），与当前客户端不符：`RedirectFlow`（`GameClient.cs:1037-1066`）
  已处理登录期重定向（`:1058-1063` 注释）。生产是否打开由运营决定，两版都缺省关。
- **在 Java 里它对合服几乎没有用处**（写进手册）：src 被作业置 CLOSED、`ZoneAdminController` 拒绝重开，源区 gate 也已停掉，没有人还能「从源区进来」；开关打开后的实际效果是通用的
  「首登送回归属区」。另一个两版共有的性质：重定向票据不经过 assign-gate 的区服准入，归属区处于维护态（且有 scene 节点）时照样会被送过去——合服窗口内由目标 gate 上的 F2 兜住（26 {3023}），
  窗口外的普通维护会被绕过（基线 scene_manager 的跨区重定向同样不看区服状态）。这也是缺省关的理由之一。
