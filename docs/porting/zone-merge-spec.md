# 合服（批次 7.3）移植统一规格：围栏、归属改写、跨系统步骤、预检 / 审计 / 验证 / 撤销、合服后提示

> **基线**：mmorpg `26ceb70ca`（`D:\work\mmorpg` 稀疏克隆，与 `contract/SOURCE.properties` 的 `mmorpg.commit` 相同）。本稿用到的目录全部已检出、没有缺失：
> `tools/merge_zone/**`、`tools/data_consistency_check/`、`go/{data_service,scene_manager,login,guild,trade,friend,chat,match}/**`、`proto/**`、
> `docs/ops/merge-zone-runbook.md`、`docs/design/**`、`java/gateway_node/**`、`PROGRESS.md`。组队代码在 `go/match/internal/team/`（`go/team/` 只有 `generated/`）。
> 只读的 Unity 客户端是另一个稀疏克隆 `D:\work\mmorpg-client`（`a8577c7`），只核对了 `Assets/Scripts`。
>
> **路径约定**
> - 不带目录的 `*.go` 都在 `tools/merge_zone/`。`runbook` = `docs/ops/merge-zone-runbook.md`；`placement.md` = `docs/design/player-storage-placement.md`。
> - `router.go` = `go/data_service/internal/routing/router.go`；`home_zone.go` = `go/scene_manager/internal/logic/home_zone.go`；
>   `entergamelogic.go` = `go/login/internal/logic/clientplayerlogin/entergamelogic.go`；`createplayerlogic.go` 同目录；
>   `config.go`(login) = `go/login/internal/config/config.go`；`homezone.go` = `go/login/internal/logic/pkg/homezone/homezone.go`；
>   `merge_fence.go`(guild)、`guild_logic.go`、`economy_logic.go`、`guild_manage_logic.go` 在 `go/guild/internal/logic/`；`guild_repo.go` 在 `go/guild/internal/data/`；
>   `chat_logic.go` 在 `go/chat/internal/logic/`；`queue.go`(match) 在 `go/match/internal/logic/`。
> - `GameClient.cs` = `mmorpg-client/Assets/Scripts/Game/GameClient.cs`。
> - Java 路径都相对 `D:\work\xuanming-server-mmo`；只写类名时可在仓库里唯一定位。`message_id.txt` 两版逐字节相同，**号 N 在第 N+1 行**。
>
> **Java 侧**：HEAD `aa8b5b5` 加工作区（5.2 / 6.2 / 7.1 / 7.2a 有未提交改动），行号按工作区计。5.4（`zone-travel-spec.md`）与 7.2b（`data-ops-spec.md`）
> 尚未落地，本稿把它们的规格当权威设计；依赖它们的类 / 方法标「(5.4)」「(7.2b)」。
>
> **本稿的来历**：由三份分区稿合并（基线端到端流程稿、跨系统步骤稿、Java 映射稿）。三稿互相矛盾、或与代码不符之处都回到代码重新核对，
> 裁决逐条列在 §0.7。本稿只读代码，没有改任何源文件。
>
> **契约影响**：7.3 **不改任何客户端契约**，不需要跑 ContractSync。用到的客户端可见面全部已在同步产物里：26 `EnterGameResponse` 字段 3 / 4
> （`xm-proto/src/main/proto/proto/login/login.proto:88-120`）、124 RedirectToGate、tip 2020 `kLoginDataSerializeFailed`（`xm-table/src/main/proto/tip/login_error_tip.proto:52`）、
> 3023 `kEnterSceneFailed`（`scene_error_tip.proto:58`）、3027 `kZoneTravelTargetBusy`（`:66`）、14013 `kGuildZoneMerging`（`guild_error_tip.proto:38`；文案
> `config-data/tables/tip_text.json:37`）。

---

## 0 概览与范围

### 0.1 盘点 id 与本批产出

| 盘点 id | 盘点出处 | 基线位置 | Java 7.3 产出 |
|---|---|---|---|
| merge-fence-and-remap | `inventory/data.md:275-285` | `fence.go`、`merged_into.go`、`manifest.go`、`merge_run.go` 步骤 5、`router.go` RemapHomeZoneForMerge | 围栏写侧与读侧（F1 / F2 / F4 / F5）接真实现；按清单分批改 `player.zone_id`；合走记录落 MySQL |
| merge-player-data | `data.md:287-297` | `player_rows.go`、`pin_placement.go`、`player_blob_migrate.go`、能力标记 | **不适用**（单库，玩家行不随归属移动，§3） |
| merge-cross-system-steps | `data.md:299-309` | `guild_step.go`、`trade_step.go`、`scene_hot_state.go` | 帮会 / 聚宝斋由属主改写（Dubbo 参与方）、帮会区榜重建、源区频道计划退役（§4） |
| merge-preflight-audit-unmerge | `data.md:311-320` | `preflight.go`、`audit_*.go`、`unmerge.go` | 归属静止预检、计划（dry-run）、合服后验证、按清单撤销（§5） |
| post-merge-notice | `data.md:323-334` | `post_merge_stamp.go`、`consumePostMergeFlags` | 26 字段 3 来自 `player.merge_notice_ms`；字段 4 恒 false（§6） |
| merge-zone-core | `inventory/tools.md:326-336` | 合服工具主流程 | xm-data 运维作业 `ZONE_MERGE` + JDK-only CLI `tools/MergeZone.java`（§7） |
| merge-zone-audit-unmerge | `tools.md:338-348` | audit / unmerge / backfill / capability-check | 验证、撤销作业；backfill、capability-check 不适用 |
| post-merge-login-signals | `tools.md:362-372`；`login.md:245-251` | 同 post-merge-notice | 同上 |
| storage-placement-relocate | `tools.md:350-360` | `relocate*.go`、`placement_*.go`、`storage_audit.go` | 不适用（盘点已判） |
| 5.4 钩子 | `zone-travel-spec.md:446-470` | `router.go:73-109`、`home_zone.go:173-200`、`homezone.go:245-275` | `ZoneMergeFence` 换成 Redis 实现；`RedirectOnEnter`（缺省关）；`SelectTravelTargetRequest.home_zone_id`（F4） |
| 帮会钩子 | `guild-spec.md:1716` D4；`MergeFence.java:18-31` | `merge_fence.go`(guild) | `MergeFence.NONE` 换成适配器，14013 开始出现 |

### 0.2 结论（一页纸）

1. **基线是一个停服维护窗口里跑的 Go 命令行工具**（`main.go:3-53`），步骤顺序本身就是正确性的一部分；四条规矩是「先证明再写、先立围栏再收集、
   清单先于写、玩家 id 只收集一次」（`merge_run.go:5-15`）。它通过了 83 个集成测试（`PROGRESS.md:6003-6004`），但**从没在真实集群上演练过**（`PROGRESS.md:6030`）。
2. **Java 的合服只剩「改三列、刷新派生状态、留一次性提示」**：`player.zone_id`、`guild.zone_id`、`trade_listing.market_zone`。玩家行在单库 `xm_java` 里不用搬，
   基线大约一半的代码（pin / copy、blob、能力标记、回填、搬库、Kafka 积压门禁）都不适用（§3、§11 D2）。
3. **形态：xm-data 的运维作业**（`OPS_JOB_ZONE_MERGE = 7`、`OPS_JOB_ZONE_UNMERGE = 8`），复用 7.2b 的作业框架（单飞 `ops_active`、心跳与清扫、幂等键、
   事件审计、`xm.data.ops.enabled` 写闸），另配只用 JDK 的薄壳 `tools/MergeZone.java`。**不新开 `xm-ops` 进程**（推翻 `inventory/tools.md:332`）。
   单飞让合服与 7.2 的整区回档不会同时在跑；run 停在 APPLYING / UNMERGING 时单飞已空，7.2 受理另查 `zone_merging`（§3.6）。
4. **清单在 MySQL，第一笔写之前落库**：pbmysql 表 `zone_merge_run`（一次合服）+ `zone_merge_item`（每个玩家 / 帮会 / 商品一行），同时承担基线清单文件
   （`manifest.go:3-26`）与 `merge:merged_into`（`merged_into.go:3-20`）的职责。玩家每批（1000 人）的改归属与清单进度**在同一个事务里**提交。
5. **跨服务的写一律走属主**（同 7.2 Q15，`data-ops-spec.md:1105`）：xm-api 新增 Dubbo 接口 `ZoneMergeParticipantService`，xm-guild、xm-trade 各自实现。
   帮会按主键逐条改写、严格失效快照缓存，再用现成的 `GuildRanks.rebuild` 从 MySQL 重建区榜；聚宝斋按主键逐条改 `market_zone`。玩家行经共享库 `PlayerStore` 由 xm-data 改。
6. **围栏**：键 `xm:{merge}:fence:<zone>`（Hash，记 run / op / src / dst / 操作人 / 时刻），src、dst 两把**在一段 Lua 里同时立**；读者只看键在不在、读失败按封锁；
   参与方核对 `run` 字段。run 处于写阶段期间由 `ZoneMergeFenceKeeper` 持续续期，半合服状态不会因 TTL 过期而失去保护（修基线 `merge_run.go:1328-1335` 的缺口）。
7. **合服不夺权**：`zone_id` 不在任何 `owner_epoch` 围栏 SQL 里（`PlayerMapper.java:53-100`），迟到的带围栏写回与改归属互不覆盖。判据是**归属静止**
   （清单玩家 = src 区玩家 `owner_released = 1` 或租约早于 now − 余量）加清单玩家无在线目录 / 位置记录 / 活队伍，每批改写在同一事务里 `FOR UPDATE` 复核；
   dst 区玩家持有归属只报 warn（评审修订：他们的行不改写，写入口全被围栏挡住，同基线 P5 / P6 只查清单玩家，§3.3）。
8. **合服提示**：`player.merge_notice_ms` 与 `zone_id` 在**同一条 UPDATE** 里写；login 在夺权成功之后用条件 UPDATE 原子消费（只出现一次、进场被拒不吞提示）。撤销在同一条语句里还原成改写前的值（通常是 0）。
9. **撤销**：只在「合服后没人进过游戏」时允许——清单玩家的 `owner_epoch` 不得比改归属时大，清单帮会不得有合服后入帮的成员；不提供强制选项（修基线 M4）。
10. **客户端可见**：窗口内建角 14 {2020}、进游戏 26 {3023}（同步口径，5.4 已定）、跨区传送 23 {3027}、帮会写 14013；合服后角色列表、帮会、区榜、聚宝斋、好友视图都按目标区；
    源区在区服列表里是 CLOSED；26 字段 3 只出现一次，字段 4 恒 false。当前 Unity 客户端不读字段 3 / 4（`GameClient.cs:795-805`），但**已实现 124**（`:1037-1066`）。

### 0.3 不做 / 不适用

| 基线项 | 处置 | 依据 |
|---|---|---|
| pin / copy 玩家行、data Redis blob、共享缓存失效、go/db 能力标记、`-backfill-home-zone`、relocate / pin-placement / storage-audit | 不适用 | 单库、`zone_id NOT NULL` 建角时写入（`xm-player-schema.sql:14`）；Java 没有玩家数据缓存层（`data-ops-spec.md:574`） |
| P3 Kafka 积压、P4 重试 / 处理中 / 死信队列 | 不适用，改为归属静止判据 | Java 写回同步且带 `owner_epoch` 围栏（`architecture.md` §7「归属协议」第 4 条） |
| data_service `RemapHomeZoneForMerge`（内部消息 129） | 不移植 | 全量 SCAN、不认清单、不 CAS（`router.go:478-530`），与工具的 A2 口径矛盾（§10.4 B2） |
| force_rename 打标与改名 RPC | 不做，字段 4 恒 false | 名字全服唯一（`xm-player-schema.sql:31`），基线同样不启用（`post_merge_stamp.go:69-77`；`merge_run.go:636` 传 nil） |
| 清理玩家位置等场景热状态 | 不做 | `xm:location` / `xm:presence` 都带 TTL（`PlayerLocationDirectory.java:46-50`），由预检兜住 |
| 删除源区登录排队 / 限流键 | 不做 | src 置为 CLOSED 后准入先拒（`AssignGateService.java:205-229`），这些键不可达（YAGNI） |
| 组队记录里的 zone 改写、管理员解散队伍 | 不做，活队伍阻断合服 | 同基线 P7（`preflight.go:270-350`）；队伍空闲 24 h 自动过期（`team-spec.md:313`） |

### 0.4 拆批与依赖

| 子批 | 内容 | 前置 |
|---|---|---|
| **7.3a** 读侧与提示 | `RedisKeys.mergeFence`、`RedisZoneMergeFence` 与写侧 Lua 工具类（xm-discovery）；F1 / F2 / F4 / F5 接真实现（帮会 `MergeFence` 适配器；F4 的 `home_zone_id` 由 xm-scene 填）；`player.merge_notice_ms` 列与 login 消费；`RedirectOnEnter` 开关；scene-manager 协调器放掉已移出 `xm:world:zones` 的 zone；xm-data 的 `fence-drill`（dev / test）；指标 | 5.4 |
| **7.3b** 合服 | xm-api 参与方接口与 proto；xm-guild / xm-trade 提供方；xm-data 合服作业、计划、验证、续跑 / 补扫、`abandon`、清单两表、围栏续期器（含终态清理）、`ZoneAdminController` 防护、7.2 受理的 `zone_merging` 检查；zone 列登记表守护测试；CLI（含 `--self-test`）；robot `merge-prepare` / `merge-check`；切片脚本 | 7.2b、7.3a |
| **7.3c** 撤销 | 撤销作业、参与方 UNMERGE 预检；robot `merge-unmerge-check`、撤销拒绝分支 | 7.3b |

其他依赖：4.4 / 4.5 帮会、4.7 聚宝斋、4.3 组队、5.1 频道计划（都已交付）；5.2 交出（归属三列语义）。每个子批各自提交、推送、登记 PARITY（AGENTS.md §5）。

### 0.5 PARITY 与文档登记

- **PARITY 新增行**：
  - 「合服（围栏、主流程、帮会 / 聚宝斋 / 区榜 / 频道计划、计划 / 验证 / 撤销 / 补扫）」，写明 §11 的 D1–D22；mmorpg 列写 `tools/merge_zone/**`。
  - 「合服后一次性提示（26 字段 3 / 4）」，写明 D11。
  - 「进游戏按归属区重定向（RedirectOnEnter，缺省关）」。
- **PARITY 更新行**：5.4 的「合服围栏」行由「检查点恒放行」改为「已接 Redis 实现」；帮会核心行（`PARITY.md:105`）的 D4 改为「已接线，14013 开始出现」；
  聚宝斋只读面行（`:108`）补「合服改写已对齐；4.8 写侧必须接 F 检查点并扩展参与方」；场景实例与主世界频道行（`:107`）补「退役 zone 由合服作业移出集合」。
- **文档**：`architecture.md` 新增「合服（批次 7.3）」小节，§7 写明「`zone_id` / `merge_notice_ms` 只有建角 INSERT 与合服 / 撤销作业写」「归属静止判据」，§4.17 帮会合服闸已生效，
  §10 删去「合服」，§11 补指标；`db-migrations.md` 登记 `merge_notice_ms`（与 7.2b 的 `idx_player_zone` 同批时合成一条）与两张 pbmysql 表；
  `tech-stack.md` 不新增第三方依赖，只登记「xm-pbmysql 新增批量插入」与「xm-data 引入 trade 的 Dubbo 消费端」；新增运维手册 `docs/design/zone-merge.md`（§7.14 的内容）。
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
| `scene-channels-spec.md:1025`；`RedisKeys.java:277` 注释 | 退役 zone 要运维手工 SREM | 合服作业的退役步骤执行，配合协调器改动（§4.6） |
| `scene-channels-spec.md:1021`（评审补） | NOSCRIPT 理由引 `architecture.md:138` | 工作区行号已移到 `architecture.md:152`（本稿同样改正） |
| `guild-spec.md:1716` D4、`MergeFence.java:18-19` 注释 | 「Java 首批不做合服」（`architecture.md:617-620`） | 7.3 起已接线；注释与 D4 随 7.3a 改写（§0.5） |

### 0.7 三份分区稿的分歧与裁决

| # | 分歧 | 裁决与核对依据 |
|---|---|---|
| 1 | 合服提示存哪：`player` 列（稿一） / Redis 键 90 天 TTL + GETDEL（稿三） / Redis `getAndDelete`（盘点） | **`player.merge_notice_ms` 列**。`selectById` 是 `SELECT *`（`PlayerMapper.java:36-37`），EnterGame 本来就读这一行，零额外读；所有带围栏写回都显式列列（`:64-100`），不会碰它；与 `zone_id` 同一条 UPDATE，没有「提示步骤只做了一半」；不带 TTL 与基线一致（`post_merge_stamp.go:125` 的 SET 不带过期），避免稿三「休眠超 90 天看不到提示」这条客户端可见差异（Q3） |
| 2 | 帮会 / 交易 / 玩家三列在 xm-data 的一个 MySQL 事务里改（稿二） / 由属主经 Dubbo 改（稿一、稿三） | **属主改写**。7.2 Q15 已定「直读越过服务边界」都不允许（`data-ops-spec.md:1105`），写更不行；快照缓存（`GuildCacheInvalidator.java:22-38`）与区榜锁（`GuildRanks.java:41-51`）只在 xm-guild 有一份；稿二的原子性收益由「围栏 + 条件更新幂等 + 清单进度」替代（Q4） |
| 3 | 玩家改归属一个大事务 / 每批一个事务 | **每批 1000 人一个事务**，清单进度同事务。50 万人一个事务的 binlog 与行锁持有时间都不可接受（Q5） |
| 4 | 区榜：ZSET 合并（基线） / 全量重建（稿二） / 新写按 zone 重建（稿三） | **复用 `GuildRanks.rebuild` 全量重建**：它已经是锁内、临时键带 TTL、Lua 原子换榜、重写区索引（`GuildRanks.java:222-270`），每次 xm-guild 启动都在跑（`GuildConfiguration.java:270-278`），零新 Lua；src 区榜与区索引成员自然消失（Q6） |
| 5 | dst 节点必须下线（稿一） / dst 节点可继续运行（稿三） | **不强制 dst 下线**：两区都必须 MAINTENANCE / CLOSED、清单（src）玩家归属静止（dst 玩家持有只报 warn，评审修订）、src 的 gate 与 scene 目录为空；dst 节点只报 info。Java 存盘不按归属区路由（`zone-travel-spec.md:429`），dst 节点不缓存别人的归属（Q7） |
| 6 | 围栏键 `xm:merge-fence:{zone}`（5.4 建议、稿一） / `xm:{merge}:fence:<zone>`（稿三） | **后者**：立围栏是一段 Lua 同时读写两把键，hash tag 保证将来上 Cluster 同槽（先例 `{team}` `RedisKeys.java:138-145`、`{rank}` `:187-190`）；5.4 只是建议、尚未实现 |
| 7 | 围栏值 JSON 串（稿一） / Hash（稿三） | **Hash**：参与方要 `HGET run` 精确比较；基线用子串查找比较 run_id（`fence.go:72-85` 的 `string.find(..., true)`） |
| 8 | 清单表：`zone_merge` + `zone_merge_player` + 各参与方自记（稿一） / 中央 `zone_merge_run` + `zone_merge_item`（稿三） | **中央两表**：参与方无状态，撤销与验证只查一处 |
| 9 | 参与方接口：ready/preflight/plan/apply/verify/revert（稿一） / list/rezone/finish/count（稿三） / 只要一个 `refreshAfterZoneMerge`（稿二） | **list / count / preflight / rezone / finish 五个方法**（§7.7）：清单在 xm-data，参与方只按 id 做条件改写 |
| 10 | 立围栏后的宽限 10 s（稿一） / 15 s（稿三） | **15 s**：login 建角链上单语句上界是 JDBC `socketTimeout=10000`（`xm-login/src/main/resources/application.yaml:21`），加余量。评审修订：这**不是**整条建角链的硬上界（链上有发号与多条语句，每条各 ≤ 10 s），只是让常见在途建角在收集之前落库；硬保证是 A8 残留并入、A10 收尾复查与 verify（Q12） |
| 11 | 热状态步骤缺省开（稿一、稿三） / 关（稿二，同基线 `-clear-source-hot-state` 缺省 false，`main.go:305`） | **缺省开、不致命、只退役源区频道计划**：Java 要删的只有无 TTL 的计划键，前置是 src 无 scene 节点，风险远小于基线删位置键（Q18） |
| 12 | `owner_released = 0` 且租约已过期的玩家：阻断并用 `AdminOwnership` 归一（稿一） / 算静止（稿三） | **算静止**（带 5 s 余量），每批 `FOR UPDATE` 复核。`zone_id` 不在围栏 SQL 里，僵尸写者的迟到写回与改归属不冲突（Q8）。注意批量续约 `renewOwnerLeases` 不看租约是否已过期（`PlayerMapper.java:133-138`），僵尸写者事后续约成功是可能的，见 §10.1 第 6 条 |
| 13 | dry-run：作业 + DRY_RUN 状态（稿一） / 同步、什么都不写（稿三） | **作业，不立围栏、不写清单、不改数据**，只留 `ops_job` 审计行；50 万人的逐人检查不能占 Tomcat 线程；没有可被误引用的预览清单（基线 A3 问题在 Java 不存在） |
| 14 | 撤销前置：只看 epoch（稿一） / epoch + xm-data 直接 join `guild_member`（稿三） | **epoch + 帮会参与方预检**（清单帮会里 `join_time_ms ≥ 合服开始` 的成员数为 0，`guild_tables.proto:77`），不让 xm-data 读帮会表 |
| 15 | 匹配票据：阻断（稿一） / 不动（稿二） | **只报 info**：票据 zone 只用于观测，匹配池不分 zone（`queue.go:56-58`；`match-spec.md:359`、`:986-988`——队列 / 评分键不带 zone，票据的 `zone_id` 只做观测） |
| 16 | 帮会缓存失效：新增严格变体（稿二） / 按版本化协议（稿三） | **两者合一**：`rezoneObjects` 提交后对整批调严格失效（失败抛、整批回 ERROR 由 xm-data 重试）；现有 `afterCommit` 永不抛（`GuildCacheInvalidator.java:31-33`），不适合合服 |
| 17 | 删源区登录排队键（稿三） | **不做**（§0.3） |
| 18 | 撤销只接受 APPLIED（稿三） / 清单任意状态（基线） | **APPLYING / APPLIED / UNMERGING 都可撤**：半途失败的合服也能整体退回；全部步骤带 `AND zone = dst` 条件 |
| 19 | RedirectOnEnter「依赖客户端 124，Unity 端目前只打日志」（`config.go:198-206`、`runbook:851`，稿三沿用） | 当前 Unity 客户端**已实现 124**（`GameClient.cs:1037-1066`，`:1060-1063` 注释专门处理登录期重定向）；基线这两处文档已过时（§10.4 B9）。开关仍缺省关（Q10） |
| 20 | 稿一引 `runbook:863` 说 guild 未配 `MergeMarkerRedis` 闸门失效 | 正确行号是 `runbook:860`（`:863` 是「合服公告 UI 靠客户端」） |
| 21 | 稿二引 `scene-channels-spec.md:798`「src scene 启动时 SADD 并重建计划」 | 内容无误，行号应为 `:797`（§4.10.5 第一条；`:798` 是「拉取失败只 WARN」）（评审修订） |

---

## 1 基线合服主流程

### 1.1 资产

| 资产 | 位置 |
|---|---|
| 主流程 `runMerge` | `merge_run.go:30-658` |
| 入口与模式（merge / audit / unmerge / pin-placement / relocate / relocate-abort / storage-audit / capability-check，另有 `-backfill-home-zone`） | `main.go:204-559` |
| 围栏 `merge:in_progress:{zone}`、`guild_rank:maintenance_lock` | `fence.go:3-34`、`:51-233`、`:237-294` |
| 合走标记 `merge:merged_into:{src}` | `merged_into.go:3-170` |
| 清单（JSON，先写 `.tmp` 再 rename） | `manifest.go:3-26`、`:110-164`、`:251-279` |
| 前置门禁 P2–P7（合服与撤销共用） | `preflight.go:3-25`、`:202-350` |
| 玩家行（copy）/ 钉落点（pin）/ blob | `player_rows.go:333-444`；`placement_ops.go:113-155`、`:661-728`；`player_blob_migrate.go:147-195` |
| 帮会 / 交易 / 区榜 / 热状态 | `guild_step.go`、`trade_step.go`、`scene_hot_state.go` |
| 合服提示标记 | `post_merge_stamp.go:13-150` |
| 撤销 | `unmerge.go:3-47`、`:68-458` |
| 审计与合服后验证 | `audit_resources.go:199-372`、`audit_checks.go:205-552` |
| 围栏读者 | data_service `router.go:73-109`、`:152-245`；scene_manager `home_zone.go:19-46`、`:173-200`；guild `merge_fence.go:11-74`、`guild_logic.go:293-320`；login 读提示 `entergamelogic.go:406-412`、`:487-548` |
| 一致性巡检（合服后孤儿检查） | `tools/data_consistency_check/main.go:322-336` |
| robot | **无**：`robot/` 下 grep merge 只有 guild_smoke 的一句文案 |

### 1.2 运维时间线（runbook）

| 时点 | 动作 | 出处 |
|---|---|---|
| 首次合服之前 | 两个区都跑 `-backfill-home-zone`（存量号历史上没有 `player:zone`） | `runbook:15-18`；`backfill_home_zone.go:3-11` |
| T-7 | 公告、要求玩家离队；容量评估；回填与能力标记登记 | `runbook:331-372` |
| T-1 | 只做与在线无关的检查：审计、dry-run 彩排、备份恢复演练 | `runbook:374-448` |
| T-0 Step 1 | 网关把两个区标成维护，踢人，解散队伍 | `runbook:452-474` |
| T-0 Step 2 | 先停 gate / scene，等源区 db_task 消费完，再 zone-down 两个区 | `runbook:476-512` |
| T-0 Step 3 | 备份 MySQL 与 Redis，失败即终止 | `runbook:514-529` |
| T-0 Step 4 | 第一次 dry-run 拿到 N，再带 `-expected-src-players N` 正式 `-apply` | `runbook:531-607` |
| T-0 Step 5 | 带清单跑 `-verify-merged` | `runbook:609-637` |
| T-0 Step 6 | 只拉起 dst；pin 模式必须 `capability-check` exit 0 | `runbook:639-667` |
| T-0 Step 7 | robot 冒烟、人工抽查、`/open` dst；**src 保持维护** | `runbook:669-697` |
| T+1 ~ T+7 | 每天 verify；清单永久归档 | `runbook:714`、`:832-844` |

### 1.3 `runMerge` 阶段（按执行顺序）

| # | 阶段 | 做什么 | 出处 | 失败处置 |
|---|---|---|---|---|
| — | 写入意图 | `-dry-run` 与 `-apply` 必须且只能给一个 | `main.go:601-609` | 退出 1 |
| P1 | 静态预检（不立围栏） | 两区 `zone_<N>_db` 存在；按表清单 JSON ∩ `information_schema` 找玩家表；trade / guild 库表就绪（`-skip-*` 才跳过） | `merge_run.go:73-125` | `log.Fatalf`，没有任何写 |
| R | 读既有清单 | dry-run 预览一律拒读；src / dst 对不上就拒绝 | `merge_run.go:129-139`；`manifest.go:311-346` | 同上 |
| C | 能力标记 | 首跑 pin 模式检查；`-db-capability-zones` 无缺省值，可写 `none` | `merge_run.go:141-169`；`main.go:63-73` | 同上 |
| F | 立围栏 | src、dst 各 `SETNX merge:in_progress:{zone}`；TTL = max(timeout + 30m, 1h)，每 TTL/3 续期 | `merge_run.go:171-178`；`fence.go:103-166` | 撞上别人的围栏即拒绝 |
| R2 | 续跑校验（围栏之下） | 玩家行模式一致；copy 模式玩家表集合不变；pin 模式重查能力标记 | `merge_run.go:204-222` | `refuse`（§1.4） |
| 0 | 收集 | 首跑 `SCAN player:zone:*` + `MGET` 取值为 src 的；续跑读清单、不重扫 | `merge_run.go:224-237`；`player_blob_migrate.go:28-64` | `refuse` |
| G | 守卫 | 0 人拒绝（`-allow-empty-source` 放行）；源库有行却没有映射的人按集合比较、有即拒；首跑人数 ≠ `-expected-src-players` 拒绝 | `merge_run.go:703-734` | `refuse` |
| P2–P7 | 门禁（源区口径） | 源区无活节点；源 topic 无积压；三个队列空；清单玩家无锁、无会话、不在活队伍 | `merge_run.go:244-258`；`preflight.go:202-283` | `refuse` |
| N | 帮会重名 | 按 `name_norm` JOIN；冲突一个字节都不写 | `merge_run.go:260-267`；`guild_step.go:163-198` | `refuse` |
| S | 落点扫描 | 有冻结 / 畸形记录即拒；已有记录写进清单 | `merge_run.go:269-312`；`placement_ops.go:113-155` | `refuse` |
| X | 合走标记 | 源区已合进别处、或目标区已被合走即拒；读失败也拒 | `merge_run.go:314-324`；`merged_into.go:64-91` | `refuse` |
| **M** | **清单落盘** | 玩家、帮会、榜单成员、商品、表、模式、已有落点；dry-run 只写 `<path>.dryrun.json` | `merge_run.go:326-398` | 落盘失败 `refuse` |
| 1 | 玩家行 | pin：钉落点；copy：拷行 + 失效缓存 | `merge_run.go:410-466` | `abortKeepingFence` |
| 2 | blob | 多 data Redis 集群才拷；拷到人数必须等于清单人数 | `merge_run.go:468-478`、`:1397-1447` | 同上 |
| 3 | 帮会 MySQL | 按清单逐主键短事务改 `zone_id`，失效 `guild:v2`，复查源区 | `merge_run.go:480-523` | 同上；残留也中止且不标完成 |
| 3b | 交易 | 按清单逐主键改 `market_zone`，复查 | `merge_run.go:525-557` | 同上 |
| 4 | 帮会区榜 | 拿维护锁，锁内重读源榜，`MULTI/EXEC` 合并 | `merge_run.go:559-583`；`guild_step.go:350-418` | 同上 |
| 5 | 改映射 | **先写 `merged_into`，再按清单逐键 Lua CAS**；`改成功 + 已是 dst` 必须等于清单人数；全量再扫，清单外仍指向 src 的只告警 | `merge_run.go:585-619`、`:839-951` | 同上 |
| 6 | 热状态（可选） | 源区负载集为空才删 location、场景、频道、节点键 | `merge_run.go:621-632`；`scene_hot_state.go:3-37`、`:327-345` | 同上 |
| 7 | 提示标记 | login Redis DB 0 写 `player_merge_notice:{pid}=<ms>`；尽力而为、失败只 WARN；时刻取本次运行的当前时刻 | `merge_run.go:634-648`；`post_merge_stamp.go:97-150` | **不回滚**，重跑补齐 |
| — | 正常结束 | defer 里按 run_id 释放围栏 | `merge_run.go:178`；`fence.go:187-210` | — |

两条顺序不能动（`main.go:51-53`）：步骤 1 必须在步骤 5 之前（映射一改玩家就被路由到目标区）；步骤 5 跑完后再扫描就找不到这批人，这是清单存在的理由。
每完成一步 `markStep` 并原子落盘（`merge_run.go:401-406`），续跑跳过已完成步骤。

### 1.4 失败处置三分法

| 情形 | 判据 | 处置 | 出处 |
|---|---|---|---|
| 首跑、清单落盘之前被拒 | `existing == nil` | `refuseAndRelease`：释放围栏、退出 1，「this run wrote nothing」 | `fence.go:212-233`；`merge_run.go:187-202` |
| 续跑（清单在、步骤 7 未完成）、清单更新之前被拒 | `halfDone` | `abortKeepingFence`：围栏留在本次 run_id 上 | `merge_run.go:195-202` |
| 清单落盘之后任何失败 | — | `abortKeepingFence`，文案列出 run_id、两把键、GET 核对、DEL、原命令重跑 | `merge_run.go:1323-1368` |

续跑**必须先手工 DEL 围栏**：重跑生成新 run_id，`SETNX` 会撞上残留围栏（`fence.go:152-157`；`merge_run.go:1360-1362`）。中止走 `log.Fatal` 不执行 defer，
围栏挂到 TTL 为止，**期间没有续期**（`merge_run.go:1328-1335`）。

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
| `GetPlayerHomeZone` → scene_manager `EnterScene` | 一段 Lua 同时读 home 与围栏；围栏在回 `ErrHomeZoneMerging = 21`，可重试、不写状态 | 26 先成功，再推 23 {3023} | `router.go:282-305`；`home_zone.go:173-200`；`go/scene_manager/internal/constants/errors.go:62-69`；`entergamelogic.go:431-471` |
| 同上，跨区传送第一条腿 | 同上 | 23 {3027} | `zone-travel-spec.md:458` |
| guild 写 RPC | 事务外与事务内各查一次；读失败也拒 | 14013 | `merge_fence.go:11-74`；`guild_logic.go:293-320`；`economy_logic.go:288-311` |
| data_service `RemapHomeZoneForMerge`（内部 129） | **反过来**：源区必须已有围栏，还要 `x-admin-token` | 无 | `router.go:474-530`；`dataserviceserver.go:214-260` |

已知缺口：围栏只挡「新建」「进场」，挡不住已在线的人，依赖停服（`inventory/tools.md:336` ④）；guild 没配 `MergeMarkerRedis` 时闸门整体失效（`runbook:860`）；
不带 GateId 的 EnterScene 不过围栏（`PROGRESS.md:6022`）。

### 2.2 基线 `merge:merged_into:{src}` 与 `guild_rank:maintenance_lock`

- `merged_into` 值 `{"dst", "run_id"}`、**无 TTL**（`merged_into.go:13-20`）；第 5 步改映射**之前**写，已指向别的 dst 即拒（`:96-119`）。
  读：合服 X 阶段（源区已合进别处或目标区已被合走即拒、读失败也拒，`:64-91`）与回填（防 mapping 丢失后把已合走的人钉回死区，`:123-137`）。撤销在 5′ 之后按 dst + run_id 删（`:142-170`）。
- `guild_rank:maintenance_lock`：与 guild 服互斥，`SETNX` + TTL 5 min，按令牌释放，合服最多等 30 s（`fence.go:237-294`）。

### 2.3 基线归属区改写（步骤 5）

- **只动清单里的键**：逐键 Lua `GET` → 值为 src 则 `SET dst` 回 1、已是 dst 回 2、键不存在回 0、第三个区回 3（`merge_run.go:839-848`）；pipeline + 预先 `SCRIPT LOAD`（`:911-951`）；
  dry-run 用 `MGET` 按同口径分类（`:851-862`）。
- 完整性：`Changed + Already == len(manifest)`，否则中止、不标完成（`merge_run.go:894-901`、`:597-600`）。
- 清单外仍指向 src 的人：全量扫一遍**只告警**，开服前由 `verify:mapping_src` 拦住（`merge_run.go:601-612`；`audit_checks.go:209-232`）。
- 旧写法的两个 bug（全量 SCAN 改了清单外的人；先 MGET 后 SET 覆盖中间变化）已修（A2，`placement.md:388`）；但 data_service 的 `RemapHomeZoneForMerge` 仍是旧写法（§10.4 B2）。

### 2.4 Java：归属就是一列

- **权威**：`xm_java.player.zone_id INT UNSIGNED NOT NULL`（`xm-player-schema.sql:14`），只在建角时与角色行同一条 INSERT 写入（`PlayerMapper.java:40-47` 是唯一写它的语句）；
  5.4 起归属区取会话 zone（`zone-travel-spec.md:411-414`，X16）。
- **读者每次现读、无缓存**：组队 / 帮会 / 交易经 `PlayerHomeZones`（`xm-common/.../PlayerHomeZones.java:11-20`），好友经 `PlayerProfiles`（`OnlineDirectory.java:255`、`FriendService.java:321`），
  login 角色列表直读这一列（`zone-travel-spec.md:415`）。改完这一列，各服务下一次请求立即看到新归属。
- **7.3 新增的唯一写者**：合服 / 撤销作业，在围栏之下按清单分批改（§7.3 A8、§7.4 U5）。`architecture.md` §7 要写明这条不变量（I-Z1：zone 列只有创建时写入与合服 / 撤销作业改写）。
  帮会 `guild.zone_id`、聚宝斋 `trade_listing.market_zone` 同理（只在建帮 / 上架时写，§4.1）。

### 2.5 Java 围栏：键、值、写侧 Lua

- **键**：`RedisKeys.mergeFence(int zone)` = `xm:{merge}:fence:<zone 无符号十进制>`，DB 12。两把键共用 hash tag `{merge}`。
- **值**：Hash，字段 `run`（run_id 无符号十进制）、`op`（`merge` / `unmerge` / `drill`）、`src`、`dst`、`by`（操作人）、`at`（立起毫秒）、`exp`（预计到期毫秒，只给人看）。
  **读者只做 `EXISTS`**，不解析（同 `router.go:84-89`）；参与方与续期器读 `run`。
- **写侧**（xm-discovery `com.game.discovery.zone.ZoneMergeFences`，Lua，`ByteArrayCodec`，单条 `evalAsync`，不用 Redisson 批处理——批里的 EVALSHA 遇 NOSCRIPT 不重载，`architecture.md:152`）：

| 操作 | Lua 语义 |
|---|---|
| `acquire(run, op, src, dst, by, ttl)` | 两把键各 `HGET run`：任一被**别的** run 持有 → 整体失败，返回持有者 run 与 op，零写入；**每把键要么不存在、要么已由本 run 持有** → 两把都 `HSET`（覆盖 op / by / at / exp）+ `PEXPIRE ttl`，返回 OK（同一 run 即接管，供续跑与撤销用）。drill 传 `src = dst = zone`（同一把键，脚本照常工作） |
| `renew(run, zones, ttl)` | 每把键 `HGET run == run` 才 `PEXPIRE`（并更新 `exp`）；返回每把的结果：RENEWED / MISSING / STOLEN |
| `restore(run, op, src, dst, by, ttl)` | 键缺失（Redis 重启 / 淘汰）时按同一 run 补回（`at` 取补回时刻）；被别人占了不动 |
| `release(run, zones)` | 每把键 `HGET run == run` 才 `DEL`，不误删接手者的 |
| `releaseIfStale(run, zones, beforeMs)` | 终态清理用：每把键 `run == run` **且** `at < beforeMs`（围栏立起早于 run 终结）才 `DEL`——不会删掉同一 run 在终结之后被 `resume` 重新立起的围栏 |
| `releaseDrill(run, zone)` | `fence-drill` 撤销用：`run == run` **且** `op == drill` 才 `DEL`；永远删不掉合服 / 撤销的围栏 |
| `holder(zones)` | 只读：返回每把的 `run`、`op`、`at` |
| `checkHolder(run, from, to)` | 只读：两把都存在且 `run` 都等于入参 → 1，否则 0（参与方用） |

- **TTL**：`max(run-budget + 30 min, 1 h)`（同 `fence.go:111-114`），缺省 run-budget 30 min → 1 h。
- **续期器** `ZoneMergeFenceKeeper`（xm-data，`data-ops-fence` 线程，每个副本都跑，`data-ops-spec.md:843-846`）：**每 `fence-check-interval`（10 s）一拍**（评审修订：原稿按 TTL/3 = 20 min
  一拍，Redis 重启后最长 20 min 没有围栏）。每拍从 MySQL 读一次「状态为 APPLYING / UNMERGING 的 run」与「本副本正在执行的作业的 run」，逐个 `renew`：
  - MISSING → 先**重读该 run 的状态**仍为 APPLYING / UNMERGING（或本副本的作业仍在执行、`ops_job` 仍为 RUNNING）才 `restore` 并记 WARN；补回之后**再读一次**，已终结就立即 `release`
    ——关掉「读到 APPLYING → 作业 A10 改 APPLIED 并释放 → 本副本补回」这条竞态（补回前的空窗由残留复查兜底，§2.7）。
  - STOLEN → 记 ERROR、计数，执行中的作业在下一批之前核对持有者失败而停止（`fence_lost`，围栏不归它、不释放）。
    STOLEN 实际只可能来自人工改键：新 run 在立围栏**之前**就会因 src / dst 有未终结的 run 被拒（§7.3 A1），drill 用同一段 Lua，撞上已有围栏同样失败。
  - **终态清理**：最近 24 h 内终结（APPLIED / UNMERGED / ABANDONED）的 run，若围栏仍由它持有（A10 / U9 / `abandon` 的释放失败）→ `releaseIfStale(run, zones, 终结时刻)`，计 `cleaned`。
    这样释放失败不会让 dst 被一把孤儿围栏挡到 TTL（≥ 1 h）结束，也不需要额外的人工接口。
- **状态迁移一律条件更新**（评审补）：作业推进 run 状态（COLLECTING → PLANNED → APPLYING → APPLIED，UNMERGING → UNMERGED）与清扫器 / `abandon` 改 ABANDONED 都写成
  `UPDATE zone_merge_run SET status = :to WHERE run_id = ? AND status = :expected`，影响 0 行即停止（作业 FAILED `run_busy`，不释放围栏）。否则「清扫器误判心跳超时、把 PLANNED 的 run
  改成 ABANDONED 并释放围栏」与「原执行线程其实还活着、随后改 APPLYING 开始写」会交错。
- **崩溃语义**：xm-data 全部副本都挂掉时围栏最多一个 TTL（≥ 1 h）后消失。此时两区仍是 MAINTENANCE / CLOSED，网关不为它们发 gate 令牌（白名单不参与准入，`AssignGateService.java:205-229`；
  `PARITY.md:95`），残留的 gate 令牌（600 s，`GateTokenIssuer.java:22`）与传送票据（300 s）早已过期。**但其他 OPEN 的区照常发令牌**：归属 src / dst 的玩家可以经别区的 gate 进游戏
  （EnterGame 不看归属区的区服状态，只看围栏 F2），围栏消失后就会在半合服状态下进场。这是 Java 与基线共有的暴露面（基线工具死后同样只剩 TTL），Java 的续期器把它缩到「全部副本都挂」；
  运维手册要求合服期间 xm-data 至少两个副本，并对 `xm_data_merge_fence_renew_total` 停止增长告警（§9）。续跑时重新立围栏、等宽限、做残留复查。
- **与基线的差别**：基线失败后进程退出、围栏只剩 TTL（`merge_run.go:1328-1335`）；Java 在 run 未终结期间持续续期，半合服状态一直被挡住，直到续跑、撤销或人工 `abandon`（D8）。

### 2.6 Java 读侧：5.4 检查点换成真实现

`RedisZoneMergeFence implements ZoneMergeFence`（5.4 的接口 `com.game.discovery.zone.ZoneMergeFence`，`zone-travel-spec.md:448`）：zone 为 0 放行；`EXISTS` 判定；Redis 出错 / 超时抛异常，
调用方按封锁处理（fail-closed）；同步版等待上限 500 ms 且不超过请求剩余预算，异步版给 login / scene-manager。

| # | 位置 | 判哪个 zone | 命中或读不到（7.3 起） | 基线 |
|---|---|---|---|---|
| F1 | login `CreatePlayer`，铸号与事务之前（5.4） | 会话 zone（新角色的归属区） | 14 {2020}，不发号、不插行 | `createplayerlogic.go:257-268` |
| F2 | login `EnterGame` 落地路径，分配与夺权之前（5.4） | `row.zone_id` | 26 {error_message 3023}，不分配、不夺权 | 26 成功后推 23 {3023}（`home_zone.go:176-199`） |
| F3 | EnterGame 重定向路径 | 不查 | — | 同基线「只送连接不查」 |
| F4 | scene-manager `selectTravelTarget` 第 3 步（5.4） | 归属区：7.3 给内部请求 `SelectTravelTargetRequest` 加 `home_zone_id`，xm-scene 填加载时的 `player.zone_id` | scene 留原地、推 23 {3027} | `enterscenelogic.go:413` → `player_lifecycle.cpp:4362-4372` |
| F5 | xm-guild 写 RPC：事务外按请求者归属区（`GuildAccess.java:103-125`），事务内按锁住的 `guild.zone_id`（`MergeFence.java:33-39`） | 同左 | 14013（「zone merging」/「merge fence unreadable」，`GuildTip.java:69-74`） | `merge_fence.go:60-74` |
| — | 4.8 交易写侧（上架、下单、交付之前） | 卖家 / 买家归属区 | 由 4.8 定 tip | 基线 P3 硬约束（`trade_step.go:27-34`） |

- 帮会接线：`GuildConfiguration.java:437` 的 `MergeFence.NONE` 换成 `zoneId -> zoneMergeFence.inProgress(zoneId)`。`MergeFence.inTransaction()` 对非 NONE 已返回真实现
  （`MergeFence.java:33-39`），解散与 4.5 经济操作的事务内复判随之生效，不用改调用方。
- **F4 的混版本**（评审补）：`home_zone_id` 是新加的内部字段，旧版 xm-scene 不填（读作 0），而「zone 为 0 放行」会让 F4 静默失效。xm-scene 与 xm-scene-manager 必须同批上线
  （同 5.1 的整体停服切换纪律，`scene-channels-spec.md:1024`）；scene-manager 收到 `home_zone_id = 0` 时计 `xm_merge_fence_checks_total{site="travel", result="unreadable"}` 并记 WARN，
  按封锁处理（23 {3027}，fail-closed）——Java 建角恒写非 0 的 zone，0 只可能来自旧版 scene。
- **Java 没有「闸门未配置」的形态**：只有一个 Redis（DB 12），基线 B5（`runbook:860`）的缺口不存在（D18）。
- 指标 `xm_merge_fence_checks_total{site, result}`（§9）。

### 2.7 Java：残余窗口（D-H4）与宽限 / 复查

- 归属在 MySQL、围栏在 Redis，做不到基线一段 Lua 原子读 home 与围栏（`router.go:283-330`）。5.4 已接受这个残余（`zone-travel-spec.md:461-462`）。
- **建角残余**：F1 通过之后、INSERT 提交之前立起围栏，新角色会在收集之后才出现在 src。三道兜底：
  1. 立围栏后**等宽限** `fence-grace`（15 s，> login 建角链单语句上界 `socketTimeout=10000`）再收集——只覆盖常见情形，不是硬上界：建角链在 F1 之后还有发号与
     锁账号 / 计数 / 插行 / 提交多条语句，每条各自 ≤ 10 s；
  2. 预检要求 src 的 gate 目录为空（会话 zone 来自 gate，src 无 gate 就不会再有**新的** src 建角请求；但 gate 死前已发到 login 的在途请求仍会执行完，所以这一条也不是硬保证）；
  3. **硬保证**：玩家步骤结束后 `COUNT(zone_id = src)`，大于 0 就把这些人并进清单再处理一轮，最多 3 轮（§7.3 A8），收尾再复查一次（A10，仍有残留不标 APPLIED）；
     A10 之后才提交的极端情形由 verify `verify:players_src` 拦住开服，再用 `resume`（对 APPLIED 的补扫，§7.3）收进来。
- **进场残余**：F2 读到旧 `zone_id` 之后合服整段恰好完成——只影响这一次的围栏 / RedirectOnEnter 判定（存盘不按归属区路由），可接受。

### 2.8 Java 归属改写（每批一个事务）

```sql
-- data-ops 线程，READ COMMITTED；ids 为本 run 里 state = PLANNED 的清单玩家（升序，≤ batch-size）
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
- **改写前的提示值** `merge_notice_ms` 记进 `zone_merge_item.notice_before_ms`（评审补）：链式合服 3→2 之后玩家还没领取 3→2 的提示，又跑 2→1 再撤销 2→1 时，撤销要把
  3→2 的提示还给他，而不是清零（§7.4 U5）。
- 完整性同 A2：清单玩家全部为 MOVED / ALREADY 才标步骤完成；**任一 GONE / ELSEWHERE → 步骤不置位、作业 FAILED `manifest_incomplete`（保留围栏，样本 id 进结果）**
  （评审修订：原稿写「只计数报告」与「全部为 MOVED / ALREADY 才标完成」自相矛盾；按基线 `complete()` 的 fail-closed 口径，`merge_run.go:894-901`）。
  Java 没有删角色、也没有别的 zone 写者，正常为 0；真出现就是不变量 I-Z1 被破坏，要人工查清再 `resume`。撤销（U5）里的 GONE / ELSEWHERE 只计数、不阻断。
- **ALREADY 的含义因对象而异**（评审修订）：玩家的改写与 item 回写在**同一事务**里，本 run 早先的尝试不可能留下「行已是 dst、item 仍 PLANNED」，所以玩家的 ALREADY
  只可能来自外部写者（违反 I-Z1）——照样记 ALREADY（撤销时一并改回，恢复「合服之前」的意图），同时出 warn `player_already_at_dst`（应为 0）。帮会 / 商品的改写在属主进程、
  item 回写在 xm-data，两者不同事务，ALREADY 是「参与方已提交、xm-data 没来得及记」后重试的正常结局。撤销对 MOVED ∪ ALREADY 一并改回（§7.7）。

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

共性：续跑必须同模式（`manifest.go:184-201`）；会话固定 READ COMMITTED（`merge_run.go:980-1040`）；锁冲突 1213 / 1205 / 9007 有界重试 5 次（`:1044-1124`）。

### 3.2 Java：没有可迁移的玩家数据

| 基线写入面 | Java 对应 | 结论 |
|---|---|---|
| `zone_<N>_db` 玩家行 | 单库 `xm_java` 的 `player` / `player_state`；`player_state` 不含 zone（`xm-player-schema.sql:37-43`） | 不搬 |
| data Redis blob、`PlayerAllData` 缓存 | 没有玩家数据缓存层（`data-ops-spec.md:574`） | 不适用 |
| 能力标记、回填、落点、搬库 | 没有按落点选库；`zone_id` NOT NULL | 不适用 |
| 审计流水 / 快照里的 zone | `transaction_log.zone_id`、`player_snapshot.zone_id`（`xm-data-schema.sql:24`、`:45`）记录**所在进程**的 zone | **不改写**：历史归因；7.2 的整区回档按 `player.zone_id` 选目标，合服后照样成立，快照 zone ≠ 归属区者单列提示（`data-ops-spec.md:541-542`，D10） |

所以 Java 的「玩家数据」步骤只剩 §2.8 的改归属与 §6.3 的提示列，本节讲它与归属协议的交互。

### 3.3 归属静止：Java 的「全员离线」判据（替代 P3–P6）

| 证据 | 判据 | 级别 | 理由 |
|---|---|---|---|
| `player` 归属三列（权威） | 清单（src）玩家没有 `owner_released = 0 ∧ owner_lease_until ≥ now − margin` 的 | block（最多等 `quiescence-wait` 60 s） | 释放就意味着最终写回已落库（`architecture.md:672` 起「玩家数据归属协议」），等价基线 P3–P6 的意图；在线的回合制战斗、5.2 交出在途、5.4 传送在途都表现为持有 |
| 同上，dst 玩家 | dst 区持有中的玩家数与前 20 个样本 | **warn** `dst_players_online`（评审修订，原稿为 block） | dst 玩家的行不改写；他们能碰到合服对象的写入口（建角 F1、进场 F2、传送 F4、帮会 F5、4.8 交易）全被 dst 的围栏挡住，在线只会看到读视图逐步变化。基线 P5 / P6 也只查清单玩家（`preflight.go:244-268`）。要求 dst 全员离线在多区生产里不可操作：dst 玩家可能正经 5.4 在别区游玩，而合服作业不踢人（Q16） |
| `xm:location:<pid>` | 清单玩家没有 `s ∈ {o, l}` 的记录 | block（同上等待） | 5.4 写侧约束 ③（`zone-travel-spec.md:463`）；TTL 60 s / 30 s（`PlayerLocationDirectory.java:46-50`），传送待落点 `l` 不超过票据剩余（≤ 300 s） |
| `xm:presence:<pid>` | 清单玩家没有在线目录条目 | block | 防 gate 侧还挂着会话（`RedisKeys.java:41-47`） |
| 节点目录 | `xm:nodes:gate:<src>`、`xm:nodes:scene:<src>` 为空；dst 只报 info | block | 等价 P2；src 无 gate 才堵死 src 建角，src 无 scene 才能退役频道计划 |
| 每批改写 | 同一事务 `FOR UPDATE` 锁行后再判归属三列 | block，保留围栏 | 把「预检之后又被夺权」压到零：行锁释放前 login 的夺权 UPDATE（`PlayerMapper.java:53-58`）被阻塞，而 F2 已先于夺权拒绝 |

- 实现要点：先用一条 SQL 找出两区「持有中」的玩家名单（走 `idx_player_zone` 后回表过滤），src 的进 block 判定、dst 的只进 warn；等待期间只按主键轮询 src 名单（每 2 s），
  超时前最后再全量查一次；不在 `idx_player_zone` 里加 `owner_released`（每次夺权 / 释放都会改它，热路径多一次二级索引维护）。
- 清单玩家的 `xm:presence` / `xm:location` 用 Redisson 异步逐键读（`PlayerLocationDirectory.find` 是阻塞版，`:39`，这里用异步读法或新增批量异步读）、每批 1000 并发发出，
  在 `data-ops` 线程上等结果；读失败按 block（`INFRA:`）处理。
- dst 玩家只看归属三列、且只报 warn：他们的行不改写；在线目录 / 位置记录的逐人检查对 dst 没有决策价值（勘误 `zone-travel-spec.md:463` ③）。
- **dry-run 不等待**（评审补）：计划作业里 `quiescence-wait` 视为 0，只报告当下状态。T-7 / T-1 彩排时两区还开着，等 60 s 只会白白占住单飞。

### 3.4 为什么不夺权

- 7.2 要写 `player_state`，必须持有归属；合服只改 `zone_id` / `merge_notice_ms`，这两列不在夺权、在线存盘、最终写回、交出的任何 SQL 里（`PlayerMapper.java:53-100`）。
  租约过期的僵尸写者迟到写回也不会与改写互相覆盖。
- 对 10 万级玩家逐个夺权、每 10 s 续约，开销大且没有收益；7.2 一次作业只许 1 万人（`data-ops-spec.md:778`）。
- **仍要求清单玩家离线的原因**：5.4 的 F4 在 scene 内存里持有加载时的归属区；组队记录存成员 zone（`team-spec.md:256-262`）；帮会有「成员 ⇒ 归属区一致」的不变量
  （`guild-economy-spec.md:79`）；被改写的人不应在半合服状态下游玩。这些理由都只针对归属**被改写**的人，所以 dst 玩家只报 warn（§3.3）。

### 3.5 `owner_epoch` 与撤销

- 改归属时把每人当时的 `owner_epoch` 记进 `zone_merge_item.owner_epoch_at_move`（同一事务，§2.8）。
- 撤销时任何清单玩家 `owner_epoch > owner_epoch_at_move` 即说明合服后进过游戏（从任一 zone 进都会夺权加一），一律拒绝（§5.6）。这精确落实了基线只写在手册里的
  「撤销只适用于未开服」（`unmerge.go:5-7`；`runbook:808-819`；`inventory/tools.md:348`）。

### 3.6 与其他写者的互斥

| 写者 | 互斥方式 |
|---|---|
| 7.2 回档 / 回收 / 欠款（xm-data 夺权） | 共用 `ops_active` 单飞（`data-ops-spec.md:819`）。**单飞只管「同时在跑」**（评审补）：合服作业 FAILED、run 停在 APPLYING / UNMERGING 时单飞已空，7.2 作业可以受理。7.2 作业受理时若目标玩家的 `zone_id`（整区作业为目标 zone）属于某个未终结 run 的 src / dst → 409 `zone_merging`；run 为 APPLIED 但还没开服时 7.2 对清单玩家的写会让 `owner_epoch` 前进，之后撤销按 `played_since_merge` 拒绝（预期行为，手册写明） |
| 5.2 交出、5.4 传送 | 要求在线；归属静止判据排除；在途的待落点 `l` 让预检等待 |
| login 夺权 | F2 先拒；每批 `FOR UPDATE` 兜底 |
| scene 在线存盘 / 最终写回 | 不写 `zone_id`；src scene 目录为空 |

---

## 4 跨系统步骤

### 4.1 zone 列登记表（Java，全部带 zone 语义的列）

| 表.列 | 属主 | 合服动作 | 出处 |
|---|---|---|---|
| `player.zone_id` | xm-player-store（共享库） | **改写**（xm-data，§2.8） | `xm-player-schema.sql:14` |
| `guild.zone_id` | xm-guild | **改写**（参与方） | `guild_tables.proto:45`（`idx_guild_0` `:33`） |
| `trade_listing.market_zone` | xm-trade | **改写**（参与方） | `trade_tables.proto:42` |
| `trade_listing.seller_zone_at_listing` | xm-trade | 保留（审计原值） | `trade_tables.proto:43` |
| `transaction_log.zone_id`、`player_snapshot.zone_id` | xm-data | 保留（所在进程 zone，历史） | `xm-data-schema.sql:24`、`:45` |
| `zone_config.zone_id`、`zone_whitelist.zone_id` | xm-gateway-store | 保留；src 的 `manual_status` 由作业改 CLOSED（§7.3 A9） | `xm-gateway-schema.sql:5-24` |
| `zone_merge_run.src_zone / dst_zone` | xm-data（7.3 新） | 保留（合服自身记录） | §7.6 |

**守护测试**（xm-data，`ZoneColumnRegistryTest`）：从仓库根扫描各模块 `src/main/resources/db/*.sql` 与带 `proto2mysql.table_name` 的 `src/main/proto/**/*.proto`，
找出名字匹配 `(^|_)zone(_|$)` 的列；每一列必须在登记表里归为「改写」或「保留」，否则失败。基线的风险正是「表清单与真实库不一致时合服会漏表」（`inventory/tools.md:288` 一类），
这条测试把它挡在构建期；4.6 帮会活动、4.8 交易写侧新增表时被强制做出合服决定。

### 4.2 基线：帮会

- **名字全服唯一**：`uk_guild(name_norm)` 不带 zone（`guild_step.go:7-15`）。步骤 N 的重名探测因此恒为真，被刻意保留成契约断言：哪天唯一键改成 `(zone_id, name_norm)`，合服停在任何写之前（`guild_step.go:155-192`）。
- **按清单主键逐条短事务**：`SELECT zone_id … FOR UPDATE` → `UPDATE … WHERE guild_id=? AND zone_id=src` → COMMIT（`guild_step.go:194-214`；`merge_run.go:1138-1149`、`:1240-1270`）。
  不整区改写：沿 `idx_guild_0` 先锁二级索引再回表，与在线 `DisbandGuild`（主键 FOR UPDATE → 删二级项）加锁顺序相反会成环（死锁审计 #17，`guild_step.go:31-37`；`merge_run.go:1175-1197`）。
- **缓存失效**：`INCR guild:v2:cache_generation:{id}` + `DEL guild:v2:{id}` 同一段 Lua（`guild_step.go:114-126`、`:262-297`）；只 DEL 不 INCR 会被途中旧读者回填。
- **复查**：`COUNT(*) WHERE zone_id=src` 必须为 0，否则中止、不标完成；续跑先把新帮会并进清单（`merge_run.go:502-517`）。
- **成员 / 职位 / 经济表**无 zone 列，原样存活（`guild_db.proto` 只有 `guild.zone_id`）；待审申请在审批时重查申请人归属区（`guild_manage_logic.go:686-750`）。
- **闸门**：只 `EXISTS`，读失败 fail-closed（`merge_fence.go:11-28`、`:61-74`）；事务内 `economyFence` 按锁住行里的 zone 再判（`guild_logic.go:489-493`；`economy_logic.go:288-311`）。

### 4.3 基线：聚宝斋

- `market_zone` = 卖家上架时的 home_zone；`Scope=zone` 时浏览 / 详情 / 收藏只认 `market_zone == 买家 home_zone`（`go/trade/internal/logic/phase.go:41`），不改写则源区商品对所有人消失且不报错（`trade_step.go:5-9`）。
- 按清单主键点更新，形状同帮会（`trade_step.go:11-19`、`:178-195`）；`seller_zone_at_listing` 与 `trade_favorite` 不动（`:20-21`）。
- 没有冲突探测（listing_id 全局唯一）、没有缓存失效（P1 不用 Redis）、trade 不读围栏（`:23-36`）；复查有残留即中止并**故意不释放围栏**（`:144-176`；`merge_run.go:536-551`）。
- 库表缺失 P1 拒绝，`-skip-trade-mysql` 才跳过（`merge_run.go:97-109`）；P3 留下的硬约束：上架必须读围栏、加缓存要补失效（`trade_step.go:27-34`）。

### 4.4 基线：排行榜

- 全仓只有帮会榜：全服 `guild_rank` 与区榜 `guild_rank:zone:{z}`，guild Redis DB 2（`guild_repo.go:154-186`）；权威分是 MySQL `guild.score`，`RebuildRanks` 维护锁下全量重建（`:623-698`）。
- 合服步骤 4：拿维护锁 → 锁内**重读**源榜（A8）→ 写之前把撤销依据落进清单（`merge_run.go:561-567`）→ 一条 MULTI/EXEC 里 `ZADD NX dst` + `DEL src`（`guild_step.go:316-417`）。
  `ZADD NX` 是为了不用旧分覆盖步骤 3 之后 guild 服写进目标榜的新分；源榜为空时不写，避免复活已解散的帮（`chooseRankWrite`，`:316-336`）。全服榜不动。

### 4.5 基线：场景热状态（步骤 6，可选）

`player:{id}:location` 无 TTL，源区被强停后会永远指向不存在的节点（`scene_hot_state.go:5-14`）。前置 `scene_nodes:zone:{S}:load` 为空（`:16-19`、`:326-337`）；
删 location（`zone_id==S`，`zone_id==0` 的反查 `scene:{id}:zone`，A11 补扫 `:590-604`）、场景键、`instances:zone:{S}:active`、`world_channels:*{S}`、`node:zone:{S}:*`（`:21-68`、`:341-482`）；
SCAN + pipeline、幂等；合服后验证只算 warn（`audit_checks.go:488-522`）。

### 4.6 Java：帮会、聚宝斋、区榜、频道计划

**帮会（xm-guild 实现 `ZoneMergeParticipantService`，group `guild`）**

- `rezoneObjects(run, from, to, ids ≤ 500)`：先 `ZoneMergeFences.checkHolder(run, from, to)`，不成立回 `FENCE_MISSING`、零变更（同基线「没有围栏就拒绝」，`router.go:474-500`）。
  然后每个 id 一个 `BackgroundTx` 短事务（RC，`BackgroundTx.java:169-182`）：`SELECT zone_id FROM guild WHERE guild_id=? FOR UPDATE` → 无行 GONE / 等于 to 为 ALREADY / 等于 from 则
  `UPDATE guild SET zone_id=:to WHERE guild_id=? AND zone_id=:from` 为 MOVED / 其他 ELSEWHERE。锁序与在线写者一致（主键在前），同 `guild_step.go:31-38`。
  锁冲突整事务重试，最多 5 次、退避 200 ms 起封顶 5 s（同 `merge_run.go:1044-1124`）。
- **严格失效**：整批改完后对 MOVED ∪ ALREADY 调 `GuildCacheInvalidator.invalidateStrict(guildIds, deadline)`（新增；与 `afterCommit` 同一套「新代次 + 删数据键」，失败即抛）。
  失败 → 整批回 `ERROR`，xm-data 重试这一批（重试时已改的行是 ALREADY，失效再做一遍，幂等）。快照里存着 zone（`RedisKeys.java:162-169`），不失效会陈旧一个 TTL（30 min），
  其间目标区成员看不到自己的帮——这是客户端可见故障，所以不能用「永不抛」的 `afterCommit`。
- `finishRezone(run, from, to)`：核对持有者 → 在 `guild-maintenance` 单线程上执行 `GuildRanks.rebuild(store::scanGuildScores)`（`GuildConfiguration.java:270-278` 同款）。
  换榜时删除旧区榜、重写区索引，src 区榜与索引成员自然消失；全服榜内容不变。幂等，可任意重复。
- `preflight(MERGE)`：`SELECT COUNT(*) FROM guild a JOIN guild b ON a.name_norm = b.name_norm WHERE a.zone_id=:src AND b.zone_id=:dst` 必须为 0（契约断言，同基线 N）；
  构建期另有单测钉住 `guild_tables.proto:32` 的 `unique_key = "name_norm"`。
- `preflight(UNMERGE, since, guildIds)`：清单帮会里 `join_time_ms ≥ since` 的成员数必须为 0（`guild_tables.proto:77`），否则 `membership_crossed`（§5.6）。
  清单帮会超过 1000 个时 xm-data 分页多次调用（`object_ids ≤ 1000`，§7.7），任一页不过即拒。`since` 取 `run.created_ms`，比围栏立起略早，只会多拒（fail-closed）。
- `listZoneObjects` / `countZoneObjects`：走 `idx_guild_0`；`countZoneObjects` 同时回该 zone 区榜的 `ZCARD` 与是否在区索引里（供验证，免得 xm-data 读帮会的 Redis 键）。
- **执行线程**：全部在新的 `guild-maintenance` 单线程执行器上跑、返回 future，不占 guild-worker 与 Dubbo 线程（AGENTS.md §3）。鉴权：调用方 MAC `XM_DUBBO_SECRET`（`GuildInternalService.java:8-11`）。
- **不用动的键**：`xm:guild:{p:<pid>}:gid`（不带 zone）、申请推送冷却、资产指令 outbox（按位置记录找 scene，与 zone 无关）。

**聚宝斋（xm-trade 实现同一接口，group `trade`，端口 20887）**

- `rezoneObjects`：同样先 `checkHolder(run, from, to)`，不成立回 `FENCE_MISSING`、零变更；然后 `UPDATE trade_listing SET market_zone=:to WHERE listing_id=? AND market_zone=:from` 逐条
  （先 `SELECT … FOR UPDATE` 分类）；`seller_zone_at_listing`、`trade_favorite` 不动。xm-trade 已依赖 xm-discovery 并装配了 Redisson（只用于 listing_id 发号租约与热关停，`xm-trade/src/main/resources/application.yaml:9`），读围栏不新增依赖；
  这是 xm-trade 第一次读业务相关的 Redis 键，trade-spec §1.11「不存任何业务键」仍成立（只读）。
- `finishRezone` 为空操作（4.7 没有缓存，`trade-spec.md:757`）；`preflight` 4.7 为空。
- dev 播种 `SeedListingController`（`/admin/trade/seed-listing`，非 dev / test 回 403）不读围栏，同基线 P1；残留复查兜底。
- **登记给 4.8 的约束**：上架、下单、交付前接 F 检查点；有了缓存就在 `rezoneObjects` 里补失效；`preflight(UNMERGE)` 必须拒绝「清单商品自 `since` 起有订单」；新表的 zone 列进 §4.1 登记表。

**区榜**：只有帮会榜，由 `finishRezone` 重建；清单里不存榜单成员、没有 `ZADD NX` 的取舍。验证口径同基线 `verify:guild_rank`（`audit_checks.go:434-489`）。
匹配评分 ZSET `xm:{match}:rank:*` 全服共用，不动（`match-spec.md:986`）。

**源区频道计划退役（§7.3 A9，缺省开、不致命）**

| 键族 | 动作 | 理由 |
|---|---|---|
| `xm:world:zones` 里的 src | 先 `SREM` | 集合只增，不移除则 src 领导者永远竞选（`scene-channels-spec.md:1025`；`RedisKeys.java:274-281`） |
| `xm:world:{z:<src>}:leader` | 等它消失，最多领导锁 TTL（30 s，`WorldChannelProperties.java:21`）加一拍 | 需协调器配合（下条） |
| `xm:world:{z:<src>}:{ch,desired,cooldown,ver}`、`resv:*`（SCAN） | DEL | 前缀带右花括号，删 `{z:1}` 不误伤 `{z:10}`（`RedisKeys.java:283-285`）；`ver` 重建取 max(旧值+1, TIME 毫秒)（`:249-256`），删掉不会撞号 |
| `xm:node-id-epoch:*:<src>:*` | **绝不删** | 代次必须跨租约单调（`RedisKeys.java:20-26`） |
| `xm:location`、`xm:presence`、`xm:nodes:*:<src>`、排空 / 疏散标记、登录排队 / 限流键、推送频道 | 不动 | 带 TTL，或不可达 |

- **前置**：`xm:nodes:scene:<src>` 为空，否则跳过并报 warn。
- **xm-scene-manager 配合**：`WorldChannelCoordinator.tick` 发现内存表里某 zone 已不在 `store.zones()` 结果中，就释放它的领导锁、移出内存表、清 gauge。
  现状 `renewLeaders` 会一直续期见过的每个 zone（`WorldChannelCoordinator.java:122-156`），不改这一处第 2 步永远等不到。
- 不退役的代价只是垃圾键：健康检查跳过没有节点的 zone（`WorldChannelsHealthIndicator.java:40-46`），所以失败只记 warn。
- 撤销不恢复：src 的 scene 启动时会 `SADD` 并拉取 / 重建计划（`scene-channels-spec.md:797`）。5.3 选了「节点自有 + 目录登记」（`dungeon-mirror-spec.md` §6.2 方案 C），没有实例键要清。

### 4.7 好友、聊天、组队、匹配、战斗

| 系统 | 基线 | Java | 出处 |
|---|---|---|---|
| 好友（四张表） | 无 zone 列；在线目录现查 home zone；只读审计计数 | 不动；好友视图与推荐现读 `profile.zoneId`，合服后自动显示 dst（客户端可见，与基线一致）；审计不计数（D17） | `audit_resources.go:488-592`；`friend_tables.proto:18-65`；`OnlineDirectory.java:255`；`FriendService.java:321` |
| 聊天 | 世界频道全服一条，私聊按玩家对 | 同；`xm:chat:{world}:log` | `chat_logic.go:8-15`；`RedisKeys.java:116-119` |
| 组队 | 记录存成员 zone，不迁移；P7：tid ≠ 0 且 `team:rec` 存在即拒，孤儿索引不阻断，读错误按拒绝 | 同口径阻断（§5.2）：`TeamMembershipReader` 一段只读 Lua 原子读索引 + 投影（`TeamMembershipReader.java:13-33`），投影存在即在队；数据损坏 / 读失败 fail-closed；不改写记录 | `preflight.go:270-350`；`team-spec.md:256-262` |
| 匹配票据 | 有 zone_id，只用于观测 | 只报 info | `queue.go:56-58`；`match-spec.md:988` |
| 战斗锁 / 待结算 | — | 在线的回合制战斗表现为持有归属，归属静止已覆盖（`data-ops-spec.md:391`、`:571`）。**战斗中断线**时 Java 照常写回、释放、移除实例，锁 `xm:battle:{<pid>}:lock` 与待结算 `:settlement` 在离线玩家身上留存（锁是承重的，`scene-battle-spec.md:49-50`）——评审修订：原稿「战斗必在线」不准确。它们按 pid 存、不带归属区，battle 节点是不分 zone 的全局池（`battle-node-spec.md:242`、`:823`），结算按位置记录 + 节点目录投递，合服后照常生效；只报 warn（计数） | `scene-battle-spec.md:47-55` |

### 4.8 Java Redis 键空间与合服动作总表

| 键 | 写者 | 合服 | 撤销 |
|---|---|---|---|
| `xm:{merge}:fence:<zone>`（新） | xm-data | 立 / 续 / 释放 | 同 |
| `xm:guild:{g:<id>}:snap(:gen)` | xm-guild | 参与方严格失效 | 同 |
| `xm:guild:{rank}:zone:<z>`、`:zones`、`:all`、`:lock`、`:tmp:*` | xm-guild | `finishRezone` 全量重建（`:all` 内容不变） | 同 |
| `xm:{team}:*` | xm-team | 只读（预检） | 只读 |
| `xm:world:zones`、`xm:world:{z:<src>}:*` | scene-manager / scene | 退役（可选） | 不恢复 |
| `xm:presence`、`xm:location`、`xm:nodes:*` | gate / scene / 节点 | 只读（预检） | 只读 |
| `xm:friend:*`、`xm:chat:*`、`xm:{match}:*`、战斗键、`xm:node-id-epoch:*`、`xm:owner-takeover`、`xm:gate-push:*`、登录令牌 / 设备、产出封禁、热关停 | 各自 | 不动 | 不动 |

---

## 5 预检 / 审计 / 验证 / 撤销

### 5.1 基线

**门禁**（`preflight.go:202-350`；合服按**源区**查 `merge_run.go:250-251`，撤销按**目标区**查 `unmerge.go:172-187`；句柄缺失也算没通过 `preflight.go:173-174`）：

| 门禁 | 判据 | 出处 |
|---|---|---|
| P2 | `ZCARD scene_nodes:zone:{z}:load == 0` | `preflight.go:203-210` |
| P3 | `db_task_zone_{z}[_g<gen>]` LAG 为 0（kafka-consumer-groups 表头解析；`-assume-kafka-drained` 是运维担保） | `:83-143`、`:212-226`；`merge_run.go:1379-1390` |
| P4 | `kafka:{retry,processing,dead}:queue:<topic>` 为空 | `:228-242` |
| P5 / P6 | 清单玩家无 `lock:player:{id}`、无 `player:session:{id}` | `:244-268` |
| P7 | `team:player:<pid>.tid ≠ 0` 且 `team:rec:<tid>` 存在即拒 | `:270-350` |

**审计 `-mode audit`**：只读、不立围栏；在线、锁、Kafka、源区节点、名字规则、好友 / 申请、帮会成员（`audit_resources.go:335-362`）；单项超时 30 s（`:362-370`）；
退出码 2 = 基础设施问题没查成、1 = 有 block、0 = 通过（`audit_resources.go:283-300`；`audit_checks.go:36-40`）。

**合服后验证 `-verify-merged`**（必须带清单，`main.go:481-486`；`runbook:621-630`）：`verify:mapping_src`、`verify:manifest_mapping`（逐 id，人数 ≠ expected 即 block）、
`verify:manifest_rows`、`verify:guild_zone`、`verify:guild_rank`（源榜不存在且 `ZCARD dst == COUNT(guild WHERE zone=dst)`）、`verify:trade_listing`、`verify:source_hot_state`（warn）、
`verify:merge_fence`（`audit_checks.go:205-552`；`trade_step.go:237-241`）。旧的「目标库总行数 ≥ N」下界已删（A6，`placement.md:392`）。

**撤销 `-mode unmerge`**（`unmerge.go:3-47`）：src / dst 只从清单读（`main.go:456-464`）；顺序与合服完全相反、**先改路由**：前置（目标区 P2–P7、冻结记录、能力标记，`:172-228`）→
7′ 删提示 / 改名键（`:230-235`、`:437-458`）→ 5′ 映射改回（**MGET 再 SET，非 CAS**，`:391-433`）→ 5′a 清 `merged_into`（`:244-250`）→ 5′b copy 模式失效缓存（`:252-264`）→
4′ 区榜回写（`:266-278`）→ 3b′ 交易（`:280-296`）→ 3′ 帮会（`:298-317`）→ 1′ copy 模式删逐字节相同的行（`:319-357`；`player_rows.go:577-646`）。blob 不删（`:361-364`）。
半撤销判定：清单玩家已有人回到 src → 门禁被拒也保留围栏；映射读失败按半撤销处理（`:151-170`）。**不强制「还没开服」**（§10.4 B4）。

### 5.2 Java 预检（围栏之下，与计划 / 审计共用一套检查 `ZoneMergeChecks`）

| 检查 | 级别 | 对应基线 |
|---|---|---|
| src ≠ dst，都不为 0，都在 `zone_config` | block（`bad_request` 在受理时） | — |
| 两区 `manual_status ∈ {MAINTENANCE, CLOSED}` | block `zone_open` | runbook Step 1 |
| src 或 dst 有未终结 run；src 已被合走；dst 已被合走 | block `merge_in_progress` / `src_retired` / `dst_retired` | X、`merged_into.go:64-91` |
| 别的 run 持有围栏（计划时只读核对） | block `merge_in_progress` | `fence.go:135-158` |
| `idx_player_zone` 存在、`player.merge_notice_ms` 列存在（JDBC `DatabaseMetaData.getIndexInfo` / `getColumns`，H2 与 MySQL 同一写法；不用 MySQL 专有的 `SHOW INDEX`） | block `missing_index` / `missing_migration`（评审补：存量库漏跑 `ALTER` 时，第一批改写才会因未知列失败，此时已在写阶段） | — |
| src 的 gate / scene 节点目录为空；dst 有节点 | block `src_nodes_alive`；info | P2 |
| 清单（src）玩家归属静止、无 `o` / `l` 位置、无在线目录 | block `zone_not_quiescent`（最多等 60 s；dry-run 不等） | P3–P6 |
| dst 玩家持有归属 | warn `dst_players_online`（计数 + 样本） | 基线不查 |
| 清单玩家不在活队伍 | block `team_active`（报告列 team id，只进报告与日志） | P7 |
| 参与方可达、`preflight(MERGE)` 通过（帮会重名必为 0）；被 `skip` 的列出原因 | block `participant_unavailable` / `guild_name_collision`；warn | P1、N |
| 首跑玩家数 = `expectedSrcPlayers`；为 0 且未 `allowEmptySource` | block `count_mismatch` / `empty_source`（只在 apply） | G（`merge_run.go:703-734`） |
| 计数：两区玩家 / 帮会 / 商品；`zone_id` 不在 `zone_config` 里的玩家 | info / warn | `audit_resources.go:335-372` |
| 战斗锁 / 匹配票据 | warn / info | — |
| 角色名（全服唯一） | info | `audit_resources.go:479-486` |

查询失败一律 block 并标 `INFRA:`，CLI 退出码 2；单项超时 30 s（同 `audit_resources.go:362-370`）。
`ZoneMergeChecks` 的检查顺序固定为表中自上而下（先静态、再节点、再归属与逐人），结果码取第一个 block 项，robot / 测试据此断言（例：撤销时玩家仍在线，先报 `zone_not_quiescent` 而不是 `played_since_merge`）。

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
| `verify:src_nodes`、`verify:src_world_plan` | src 节点目录为空；`xm:world:zones` 不含 src 且 `:ch` 不存在 | warn |
| `verify:notice_pending` | 清单玩家里 `merge_notice_ms = notice_ts` 的人数（未领取） | info |
| `verify:played_since` | `owner_epoch > owner_epoch_at_move` 的人数（开服前应为 0） | info |

结论聚合：任一查询失败 → INFRA（退出 2）优先；否则有 block → BLOCK（退出 1）；否则 PASS（退出 0，可带 warn）。同 `audit_checks.go:3-40` 的三档。
verify 在 Tomcat 线程上同步执行，几条按清单 join 的 SQL（50 万行级）各设 30 s 语句超时（`Statement.setQueryTimeout`），超时按 INFRA；不做逐人 Redis 读。
任一 `verify:players_src` / `verify:guild_zone` / `verify:trade_zone` 为 block（A10 之后才落进 src 的残留）时，处置是 `resume` 补扫（§7.3），不是手工改库。

### 5.5 Java 撤销（作业 `ZONE_UNMERGE`，§7.4）

- **适用面**：run 为 APPLYING（半途失败的合服整体退回）、APPLIED、UNMERGING（撤销续跑），且是涉及 src / dst 的**最近一次**未作废 run（否则 `not_latest_run`：链式合服 3→2→1 之后不能单独撤 3→2）。
  「未作废」= 状态不是 ABANDONED / UNMERGED；「最近一次」= 满足 `src_zone` 或 `dst_zone` ∈ {本 run 的 src, dst} 的未作废 run 里 `created_ms` 最大者（评审补：原稿没定义，
  3→2、2→1 之后撤掉 2→1，应当允许再撤 3→2）。这条判据刻意保守：3→2 之后又有 4→2 时，即使两者清单不相交，也要先撤 4→2。
- **顺序**（同基线「先改路由」，`unmerge.go:9-22`）：立围栏（op = unmerge，同一 run 接管）→ 宽限 → 预检 → run 改 UNMERGING → 玩家改回（提示同语句清零）→ 聚宝斋改回 → 帮会改回 + 区榜重建 →
  src 的 `zone_config` 改回 MAINTENANCE、文案清空 → run 改 UNMERGED → 释放围栏。
- 只动 `state ∈ {MOVED, ALREADY}` 的对象，条件 `AND zone = dst`；GONE / ELSEWHERE / PLANNED 不动；dst 原住民一个不碰。
- 没有基线 5′b、1′（Java 从来没拷过行、没有缓存），没有 4′ 的快照回写（区榜从 MySQL 重建，修 §10.4 B11）。
- 失败：保留围栏、run 留在 UNMERGING，重新提交撤销即续跑（每步幂等）。MySQL 里没有「半撤销」的歧义状态：每批玩家的改回与 item 状态同事务。

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
| 窗口内进游戏（归属 src / dst，从任何 zone 进） | 26 | **26 `{error_message 3023}` 同步回**（F2，5.4 已定口径，`zone-travel-spec.md:456`；`PARITY.md:27`） | 26 成功后推 23 {3023}（`entergamelogic.go:431-471`） | 两种都按失败处理（`GameClient.cs:802-803` 判 error_message；23 走 tip 处理器） |
| 窗口内跨区传送（归属 src / dst） | 226 → 23 | 23 `{3027}`「目标区服繁忙,请稍后再试」（`tip_text.json:156`），玩家原地不动 | 同 | 同 |
| 窗口内帮会写 | 帮会应答 | in-band 14013「区服合并维护中，帮会操作暂停，请稍后再试」（`tip_text.json:37`） | 同（配置正确时） | 同 |
| 区服列表 / assign-gate | HTTP | src 为 **CLOSED**，带合服文案；assign-gate 503 `zone_closed`；dst 由运维开服前为 MAINTENANCE 503 `zone_maintenance` | src 保持 MAINTENANCE（`runbook:669-697`） | 同一 JSON 形状 |
| 合服后角色列表 | 48 | `players[].zone_id = dst`（直读 `player.zone_id`） | 映射覆盖后同值（`homezone.go:200-236`） | 按选中区过滤（`GameClient.cs:645-652`）：源区玩家要选 dst |
| 合服后首次成功进游戏 | 26 | 字段 3 = 合服时刻毫秒（run 内固定），只出现一次；字段 4 = false | 字段 3 在进场结果出来之前消费；字段 4 恒 false | **不读字段 3 / 4**（`GameClient.cs:795-805`） |
| 合服后帮会 / 区榜 / 聚宝斋 / 好友 | 60、35、27、52、196、197、198、好友列表 | 源区帮会 `zone_id = dst`、出现在 dst 区榜；源区商品 `market_zone = dst`、在 dst 可浏览；收藏不变；好友视图 zone = dst | 同（`trade-spec.md:477`；`runbook:681-683`） | 同 |
| 撤销之后 | 48 / 26 | 角色回到 src、没有本次合服的提示（链式合服时还原上一次合服未领取的提示）；帮会 / 商品回到 src | 同（`unmerge.go:230-242`；基线 7′ 直接删键，链式时上一次的提示也一起丢） | 同 |
| RedirectOnEnter（开关打开） | 26 + 124 | 见 §6.4 | 缺省关（`go/login/etc/login.yaml:209-211`） | **已实现 124**（`GameClient.cs:1037-1066`） |

### 6.2 区服列表与源区文案

- 作业的退役步骤把 `zone_config.src` 改为 `manual_status = 2 CLOSED`，`maintenance_msg` 取配置模板（缺省「本区已合并至 {dst_name}（{dst} 区），请在区服列表中选择该区登录」，
  渲染后 ≤ 256 字符，`xm-gateway-schema.sql:10`），区服列表随 CLOSED 下发（`ServerListController.java:46-52`），1 s 内生效（`ZoneDirectory` 快照）。
- `ZoneAdminController` 拒绝把已退役的 src 改回 OPEN / PREVIEW（409 `zone_retired`），也拒绝在 run 未终结时打开 dst（409 `zone_merging`）（§7.11）。
- 基线 runbook 让 src「保持维护」，Java 由作业置 CLOSED：客户端可见差异 D13。

### 6.3 合服后提示（26 字段 3 / 4）

- **写**：`player.merge_notice_ms BIGINT NOT NULL DEFAULT 0`；玩家改归属的同一条 UPDATE 写 `T`（§2.8），撤销同一条 UPDATE 写回 `notice_before_ms`（通常 0；链式合服时是上一次合服的时刻）。
- **读**：EnterGame 一开始的 `findPlayer` 已读整行（`EnterGameHandler.java:199-200`；`PlayerMapper.java:36-37` `SELECT *`），零额外读。
- **消费**：夺权成功之后、构造 `accepted` 应答之前（`EnterGameHandler.java:297-302`、`:390-403`），**必须在 login 工作线程上**：
  `row.merge_notice_ms > 0` 才执行 `UPDATE player SET merge_notice_ms = 0 WHERE player_id = ? AND merge_notice_ms = ?`（`PlayerStore.consumeMergeNotice`）；影响 1 行才把值填进字段 3。
  影响 0 行（并发的另一次进游戏先拿走了）→ 填 0。SQL 出错 → 记 ERROR、填 0、**不阻断登录**，值仍在，下一次进游戏再给。
  线程（评审补）：`rerouteIfStale` 重新分配时，它的 future 在 Dubbo 回调线程上完成（`:358-367` 的 `handle`），随后的 `thenApply(… accepted …)` 也跑在那条线程上——
  不能在那里做阻塞的 MySQL 写（AGENTS.md §3）。做法：在 `claim()` 拿到 `Claimed` 的那一刻（已在工作线程上，`:298-301`）先消费，把结果随 `ClaimStep` 带到 `accepted`；
  `Claimed` 之后的链路只会以 `accepted` 结束（`rerouteIfStale` 的失败都回落原分配），所以提前消费不改变「只在受理时消费」的语义。
- 拒绝路径（F2 3023、2005、2011 等）与重定向路径（GO-5 / RedirectOnEnter）**不读也不删**。
- 字段 4 恒 false，不加列。
- 7.2 回档恢复「player 行可变列」（等级 / 场景 / 坐标，`data-ops-spec.md:63`、`:265`），不得改写 `zone_id` 与 `merge_notice_ms`；回档 SQL 显式列列即可保证。
- 与基线的差别（D11，客户端可见）：① 基线在知道进场结果之前就消费（`entergamelogic.go:406-412`），进场随后失败（3023）时提示永久丢失，Java 被拒不消费；
  ② 基线 GET 与 DEL 两个 pipeline 不原子（`:494-547`），并发两次进游戏可能都看到，Java 条件 UPDATE 恰好一次；③ 基线续跑会用新时刻重打（`merge_run.go:636`），Java 时刻按 run 固定；
  ④（评审补）基线的重定向（RedirectOnEnter、GO-5 的「只送连接」）也走 `:412` 的成功出口，字段 3 出现在**第一跳**的 26 里（随后才是 124）；Java 重定向路径不消费，
  字段 3 出现在目标 gate 上落地的那个 26 里。两者都满足契约「只在第一次成功进游戏时非 0」（`login.proto:88-98`），只是落在哪一个 26 上不同。
  残留：`accepted` 之后 scene 侧进场失败（gate 推 23 {3023}，`PARITY.md:36`）或应答没送到客户端（gate 已断）仍算已消费，窗口远小于基线，可接受。

### 6.4 RedirectOnEnter（124）

- 配置 `xm.login.redirect-on-enter`，缺省 false（同基线 `login.yaml:211`）。
- 打开时，在 5.4 去向表「位置记录没有 / 墓碑 · 未钉」那一格（`zone-travel-spec.md:758`）新增分支：`row.zone_id ∉ {0, G}` → `redirectToZone(row.zone_id, pid)` →
  回 `EnterGameResponse{player_id}` + `RedirectToGate` 指令，不夺权、不分配；调用失败 / 超时 / `tip ≠ 0` → 退回本 zone 首登。等价基线「首登且无在场 scene 才按归属区送回」
  （`config.go:211-215`；`homezone.go:245-275`）。
- 只在 robot 与开关打开的切片上验证（§13.6）。基线文档说 Unity 只打 124 日志（`config.go:200-202`、`runbook:851`），与当前客户端不符：`RedirectFlow`（`GameClient.cs:1037-1066`）
  已处理登录期重定向（`:1060-1063` 注释）。生产是否打开由运营决定，两版都缺省关。
- **在 Java 里它对合服几乎没有用处**（评审补，写进手册）：src 被作业置 CLOSED、`ZoneAdminController` 拒绝重开，源区 gate 也已停掉，没有人还能「从源区进来」；
  开关打开后的实际效果是通用的「首登送回归属区」。另一个两版共有的性质：重定向票据不经过 assign-gate 的区服准入，归属区处于维护态（且有 scene 节点）时照样会被送过去——
  合服窗口内由目标 gate 上的 F2 兜住（26 {3023}），窗口外的普通维护会被绕过（基线 scene_manager 的跨区重定向同样不看区服状态）。这也是缺省关的理由之一。

---

## 7 Java 设计

### 7.1 形态：xm-data 运维作业 + JDK-only CLI

| 方案 | 结论 | 理由 |
|---|---|---|
| A：`tools/MergeZone.java` 单文件做全部逻辑 | 只做薄壳 | 单文件启动器带不了 JDBC 驱动、Redisson、Dubbo；与 7.2 作业不互斥 |
| B：xm-data 里的 CLI main（如 `AuditFallbackReplay`） | 否 | 没有单飞，要再写一套审计、心跳、幂等 |
| C：新进程 `xm-ops`（`inventory/tools.md:332`） | 否 | 多一套部署与秘密；与 7.2「运维面全在 xm-data、不新增进程」冲突（`data-ops-spec.md:56`） |
| **D：xm-data 运维作业** | **采纳** | 与回档 / 回收共用 `ops_active` 单飞；STARTED / RESULT 审计、心跳、清扫、幂等键、`ops.enabled` 写闸、`SCENE_GUID` 发号、`PlayerStore` bean 与 Dubbo 消费端都是 7.2b 现成的 |

xm-data 新包 `com.game.data.merge`：`ZoneMergeAdminController`、`ZoneMergeService`（受理）、`ZoneMergeJob`、`ZoneUnmergeJob`、`ZoneMergeChecks`、`ZoneMergeVerifier`、
`ZoneMergeStore`（pbmysql）、`ZoneMergeFenceKeeper`、`ZoneMergeParticipants`（两个 Dubbo 引用 + 超时 / 重试）、`ZoneMergeMetrics`。领域对象 + XxxService，不用 ECS 命名。

### 7.2 HTTP 接口（全部在 `/admin/**`，`AdminAuthFilter` 统一鉴权：`X-Xm-Admin-Token` / `X-Xm-Operator`，`AdminAuthFilter.java:17-28`；`opOf` 新增前缀 `zone_merges`）

| 方法 | 路径 | 形态 | 需要 `ops.enabled` |
|---|---|---|---|
| POST | `/admin/zone-merges` `{src, dst, dryRun, expectedSrcPlayers, allowEmptySource=false, skip:[{participant, reason}], closeSource=true, retirePlan=true, reason}` | 作业 → 202 `{jobId, runId}`（dry-run 时 `runId` 为空；apply 时 `runId = jobId`）。**`dryRun` 必填、没有缺省值**，缺了回 400（同基线「`-dry-run` 与 `-apply` 必须且只能给一个」，`main.go:601-609`，评审补） | apply 要，dry-run 不要 |
| POST | `/admin/zone-merges/{runId}/resume` `{reason}` | 作业：run 为 APPLYING → 续跑；run 为 APPLIED → 补扫（§7.3） | 是 |
| POST | `/admin/zone-merges/{runId}/unmerge` `{dryRun, reason}` | 作业（dry-run 只跑 U1 与 U3 的检查：不立围栏、不等宽限、不等归属静止） | apply 要 |
| GET | `/admin/zone-merges?src=&dst=&status=&limit=` | 同步：run 列表 | 否 |
| GET | `/admin/zone-merges/{runId}` | 同步：状态、步骤位、计数、`notice_ts`、最近作业、两把围栏的持有者 | 否 |
| GET | `/admin/zone-merges/{runId}/items?kind=&state=&after=&limit=` | 同步：清单分页导出（JSON，给维护工单永久归档） | 否 |
| GET | `/admin/zone-merges/{runId}/verify` | 同步：§5.4 | 否 |
| POST | `/admin/zone-merges/{runId}/abandon` `{reason}` | 同步：最后手段——run 条件更新改 ABANDONED、按 run 释放围栏、写事件；只接受无作业在跑、状态为 COLLECTING / PLANNED / APPLYING / UNMERGING 的 run。**APPLIED 不可 abandon**（评审补：否则 `src_retired` 与 `ZoneAdminController` 的「已退役不许重开」随之失效，src 可被重新打开），已终结的更不可 | 是 |
| POST / DELETE | `/admin/zone-merges/fence-drill` `{zone, seconds ≤ 300}` / `{zone, run}` | 同步：POST 用 `acquire(src = dst = zone)` 立一把 op = drill 的围栏（run = 随机号，应答带回），到期自动消失；DELETE 走 `releaseDrill`，只删 op = drill 且 run 相符的那把，**删不掉合服 / 撤销的围栏**；**不改任何数据**；运行模式（`xm.run-mode`，xm-common `RunMode`）不是 dev / test 回 403（同 `SeedListingController.java:22-39`、`SeedListingService.java:69`） | 否 |

- 写请求必须带 `Idempotency-Key`，规则同 7.2（`data-ops-spec.md:832-833`）；`reason` 必填（≤ 256）；操作人取 `X-Xm-Operator`。
- 作业进度用现有 `GET /admin/ops-jobs/{id}` 轮询（`data-ops-spec.md:841`）。`/admin/ops-jobs/{id}/cancel` 对合服作业生效：写阶段之前等同被拒（释放围栏），之后在批边界停止（作业 CANCELLED，run 留 APPLYING，围栏保留）。

### 7.3 合服作业（`OPS_JOB_ZONE_MERGE`）

```
A0 受理（Tomcat）：参数 → 幂等键 → 一个事务：占 ops_active + 插 ops_job(QUEUED) + STARTED 事件 → 202
data-ops 线程：
A1 静态前置（只读，围栏之前）：§5.2 的 zone_config 状态、run 表（未终结 / 已合走）、参与方 ready、idx_player_zone 与 merge_notice_ms 列（DatabaseMetaData）
     首跑：插 zone_merge_run(COLLECTING, run_id = job_id, expected, skipped, operator, reason)
A2 立围栏（两把、同一段 Lua；别的 run 持有 → REJECTED merge_in_progress，附持有者）→ 等 fence-grace（15 s）
A3 收集：player 按 idx_player_zone 键集分页读入内存（每页 1000）；守卫 count_mismatch / empty_source / plan_too_large
A4 预检（围栏之下）：§5.2 全部 block 项；归属静止最多等 quiescence-wait
     A1–A4 任一 block → 按 run 释放围栏，job REJECTED，run ABANDONED（零数据写入，同 fence.go:222-233）
A5 清单落库：zone_merge_item 批量插入（PLAYER / GUILD / LISTING，PLANNED，late = 0）→ run 改 PLANNED、写计数 → PLANNED 事件
     A5 失败（插入出错、超时）同 A1–A4：还没有任何数据写入 → 按 run 释放围栏，job FAILED，run ABANDONED；已插的 item 属于 ABANDONED run，
     verify / unmerge / 合走检查都不读它们，无害（评审补）
     （dry-run 到 A4 为止：不立围栏、不插 run / item，结果写进 job 摘要）
A6 帮会：run 改 APPLYING（此后任何失败都保留围栏）→ listZoneObjects(src) 并入清单（新增者 late = 1）→
     每 500 个 rezoneObjects（ERROR 重试 3 次）→ 回写 item 状态 → countZoneObjects(src) == 0（否则再并一轮，最多 3 轮，仍不为 0 → FAILED residual_guilds）→
     finishRezone → 步骤位 GUILD
A7 聚宝斋：同 A6，没有 finish → 步骤位 TRADE
A8 玩家：首次进入时取 T 写进 run.notice_ts_ms → 每 1000 人一个事务（§2.8；GONE / ELSEWHERE → FAILED manifest_incomplete）→
     COUNT(zone_id = src) > 0 就把这些人插为 late item 再处理一轮（late 玩家同样过 §2.8 的 FOR UPDATE 判静止），最多 3 轮，
     仍不为 0 → FAILED residual_players → 步骤位 PLAYERS
A9 退役（不致命，失败记 warn、步骤位不置）：closeSource → zone_config.src 改 CLOSED + 文案；retirePlan → §4.6 频道计划退役
A10 收尾：再复查 src 的玩家 / 帮会 / 商品都为 0（评审补：任一不为 0 → 回到对应步骤再并一轮，计入同一个 residual-rounds 上限，用尽 → FAILED residual_*，
     run 留 APPLYING、围栏保留）→ run 改 APPLIED（条件更新）、写 applied_ms → 按 run 释放两把围栏（失败重试 3 次，仍失败只记 warn
     fence_release_failed，由续期器的终态清理收尾，§2.5）→ RESULT 事件 → job SUCCEEDED（带 warn）
```

- **不变量 M-I1（清单先于写）**：A6 之前 `zone_merge_item` 已覆盖 A3 收集到的全部对象；A6–A8 并入的新对象一律先插 item 再改写。
- **每步开始与每批之前**都 `checkHolder`（读两把围栏的 `run`），不是本 run → 停止，job FAILED `fence_lost`，不释放。
- **作业超时**：`run-budget` 到点时，写阶段之前等同被拒，写阶段之后在批边界停止（job FAILED `timeout`，run APPLYING，围栏保留），同 7.2 `job-timeout` 口径（`data-ops-spec.md:779`）。
- **清扫器**（7.2 `OpsJobSweeper`）把心跳超时的合服作业改为 INTERRUPTED 时：run 处于 COLLECTING / PLANNED → **条件更新**改 ABANDONED（`AND status IN (COLLECTING, PLANNED)`，
  影响 0 行说明原线程已推进到 APPLYING，什么都不做）成功后才按 run 释放围栏（还没写过数据）；run 处于 APPLYING → 不动，续期器继续保护。
  原执行线程若其实还活着：它下一次推进 run 状态的条件更新影响 0 行、或下一批之前 `checkHolder` 失败，都会停下，不会在没有围栏时写（§2.5）。
- **续跑** `POST /{runId}/resume`：要求 run 为 APPLYING 且没有作业在跑 → 新作业：A1（续跑口径：run 存在、src / dst 一致）→ A2（同一 run 接管围栏）+ 宽限 →
  A4 的 zone 级子集（节点、清单玩家归属静止；逐人检查只针对还没 MOVED 的）→ 从第一个未置位的步骤继续。续跑的拒绝**保留围栏**（同 `merge_run.go:195-202`）。
  合服写阶段之后只能续跑、撤销或 `abandon`，**不自动续跑**（同 `data-ops-spec.md:838`）。
- **补扫**（评审补）`POST /{runId}/resume` 作用在 **APPLIED** 的 run 上：用于 verify 发现 A10 之后才落进 src 的残留（极慢的在途建角 / 建帮 / dev 播种），以及 A9 没做成的退役。
  只有 A9 缺位、没有残留时：不立围栏，直接重做缺位的 A9 子步骤（幂等）。有残留时：要求两区回到 MAINTENANCE / CLOSED（dst 已开服就先置维护）→ run 条件更新 APPLIED → APPLYING
  （此后 `ZoneAdminController` 拒绝开 dst）→ A2 + 宽限 → A4 子集（只查残留对象）→ 残留并为 late item 走 A6–A8 → A10。清单原有对象不重做，`notice_ts_ms` 沿用。
  基线没有对应物：基线跑完后清单外仍在 src 的人只告警，由 `verify:mapping_src` 拦住开服、「逐人决定」（`merge_run.go:601-612`）；Java 若没有补扫，A1 的 `src_retired` 会让任何新 run 都收不走它们（D21）。

**run 状态**

```
受理 ─► COLLECTING ─► PLANNED ─► APPLYING ─► APPLIED ─(unmerge)─► UNMERGING ─► UNMERGED
            │             │          │  ▲  └──────(unmerge)─────────┘
            │             │          │  └──(resume 补扫：APPLIED → APPLYING)
            └─────────────┴──► ABANDONED（写之前被拒 / 中断：围栏已释放）
                                     └──(abandon，人工)──► ABANDONED
```
所有箭头都是 `WHERE status = 期望值` 的条件更新（§2.5）。

**合走检查**（A1，取代 `merged_into.go:74-91`）：存在 run 满足 `src_zone ∈ {src, dst}` 且状态 ∈ {COLLECTING, PLANNED, APPLYING, UNMERGING}，或 `dst_zone ∈ {src, dst}` 且状态同上 → `merge_in_progress`；
存在 `src_zone = :src` 且状态 = APPLIED → `src_retired`；存在 `src_zone = :dst` 且状态 = APPLIED → `dst_retired`。链式合服允许（3→2 APPLIED 之后再 2→1）。

### 7.4 撤销作业（`OPS_JOB_ZONE_UNMERGE`）

```
U0 受理：run 存在、状态 ∈ {APPLYING, APPLIED, UNMERGING}、无作业在跑
U1 静态前置：最近一次 run（not_latest_run）；两区不是 OPEN / PREVIEW
U2 立围栏（op = unmerge，同一 run 接管）→ 宽限
U3 预检：§5.6；任一 block → job REJECTED、零写入；run 为 APPLIED 时按 run 释放围栏；
         run 为 APPLYING / UNMERGING 时保留围栏（续期器继续保护半成品状态）
U4 run 改 UNMERGING
U5 玩家改回：每 1000 人一个事务：FOR UPDATE → 复判 epoch → 按 notice_before_ms 分组（通常只有 0 一组）：
    UPDATE player SET zone_id = :src, merge_notice_ms = :before, updated_at = :now WHERE player_id IN (…) AND zone_id = :dst
    （每组影响行数 = 组内人数）→ item 改 REVERTED；GONE / ELSEWHERE 只计数
U6 聚宝斋：rezoneObjects(from = dst, to = src, 清单 MOVED ∪ ALREADY) → item REVERTED
U7 帮会：同上 → finishRezone（区榜从 MySQL 重建）
U8 zone_config.src 改 MAINTENANCE、文案清空 → run 改 UNMERGED、写 unmerged_ms
U9 按 run 释放围栏 → RESULT
```

U5–U8 任一失败：job FAILED，run 留 UNMERGING，续期器保护围栏；重新提交 `unmerge` 即续跑。频道计划不恢复（§4.6）。

### 7.5 线程、事务与连接

- 线程沿用 7.2（`data-ops-spec.md:843-846`）：`data-ops` 单线程跑作业，可阻塞 JDBC / Redis / Dubbo `get(timeout)`；`data-ops-fence` 负责续约、心跳与合服围栏续期；`data-ops-sweeper` 清扫。
  都不是 Netty I/O 或场景逻辑线程（AGENTS.md §3）。
- A8 / U5 的事务混用 MyBatis（`PlayerStore` 新方法）与 pbmysql（`zone_merge_item`），用 `DataSourceUtils.getConnection` 取 Spring 事务绑定的同一连接（`data-ops-spec.md:847-848`）；
  隔离级别 READ COMMITTED；`innodb_lock_wait_timeout` 会话级设 5 s，锁冲突（1213 / 1205）整批重试最多 5 次。xm-data 的连接串没有 `sessionVariables`
  （`xm-data/src/main/resources/application.yaml:16`，MySQL 缺省 50 s），所以在借来的连接上事务开始前 `SET SESSION innodb_lock_wait_timeout = 5`、归还前恢复原值
  （连接回池，不能把 5 s 泄漏给消费线程与 7.2 作业）。H2 不认这条语句，经方言开关跳过。
- 连接池：7.2 已调到 8（`data-ops-spec.md:856`），合服只多一个作业连接，够用。
- xm-guild：`guild-maintenance` 单线程执行器（新增），参与方方法全部投递到它；xm-trade：参与方方法投递到现有存储线程池。

### 7.6 清单表（pbmysql，追加在 `xm-data/src/main/proto/xm/data/ops_tables.proto`）

```proto
// ops_job.kind 只追加
//   OPS_JOB_ZONE_MERGE = 7;   // 合服（apply / resume / dry-run，按 request_json 区分）
//   OPS_JOB_ZONE_UNMERGE = 8; // 撤销

enum ZoneMergeStatus {
  ZONE_MERGE_STATUS_UNSPECIFIED = 0; ZONE_MERGE_COLLECTING = 1; ZONE_MERGE_PLANNED = 2; ZONE_MERGE_APPLYING = 3;
  ZONE_MERGE_APPLIED = 4; ZONE_MERGE_UNMERGING = 5; ZONE_MERGE_UNMERGED = 6; ZONE_MERGE_ABANDONED = 7;
}
message ZoneMergeRunRow {
  option (proto2mysql.table_name) = "zone_merge_run";
  option (proto2mysql.primary_key) = "run_id";
  option (proto2mysql.index) = "src_zone,status;dst_zone,status";   // idx_zone_merge_run_0 / _1：合走检查、最近一次 run
  uint64 run_id = 1;               // = 首个 apply 作业的 job_id（SCENE_GUID 雪花）
  uint32 src_zone = 2;
  uint32 dst_zone = 3;
  ZoneMergeStatus status = 4;
  uint32 steps_done = 5;           // 位图，只追加位、位义永不改：1 GUILD、2 TRADE、4 PLAYERS、8 SOURCE_CLOSED、16 PLAN_RETIRED（同 manifest.go:43-44 的纪律）
  uint64 last_job_id = 6;
  string operator = 7;
  string reason = 8;
  uint32 expected_src_players = 9;
  uint32 players = 10;             // 清单玩家（含 late）
  uint32 guilds = 11;
  uint32 listings = 12;
  int64 notice_ts_ms = 13;         // 合服时刻；首次进入玩家步骤时取，续跑沿用
  string skipped = 14;             // 显式跳过的参与方与原因（JSON），只在首跑时定
  int64 created_ms = 15;
  int64 applied_ms = 16;
  int64 unmerged_ms = 17;
  string summary_json = 18;        // ≤ 64 KB
}
enum ZoneMergeItemKind { ZONE_MERGE_ITEM_KIND_UNSPECIFIED = 0; ZONE_MERGE_PLAYER = 1; ZONE_MERGE_GUILD = 2; ZONE_MERGE_LISTING = 3; }
enum ZoneMergeItemState {
  ZONE_MERGE_ITEM_STATE_UNSPECIFIED = 0; ZONE_MERGE_ITEM_PLANNED = 1; ZONE_MERGE_ITEM_MOVED = 2; ZONE_MERGE_ITEM_ALREADY = 3;
  ZONE_MERGE_ITEM_GONE = 4; ZONE_MERGE_ITEM_ELSEWHERE = 5; ZONE_MERGE_ITEM_REVERTED = 6;
}
message ZoneMergeItemRow {
  option (proto2mysql.table_name) = "zone_merge_item";
  option (proto2mysql.primary_key) = "run_id,kind,object_id";
  option (proto2mysql.index) = "kind,object_id";                    // 「这个玩家 / 帮会被哪些 run 动过」
  uint64 run_id = 1;
  ZoneMergeItemKind kind = 2;
  uint64 object_id = 3;
  ZoneMergeItemState state = 4;
  uint32 late = 5;                 // 1 = 清单落库之后才并入（围栏前在途的建角 / 建帮 / dev 播种）
  uint64 owner_epoch_at_move = 6;  // 仅 PLAYER：改归属那一刻的 owner_epoch
  int64 moved_ms = 7;
  int64 notice_before_ms = 8;      // 仅 PLAYER：改写前的 merge_notice_ms（撤销时还原，链式合服不丢上一次的提示；评审补）
}
```

- 启动时 `syncAll` 建表 / 只扩不缩（`architecture.md:654-658`），不需要手工迁移；`db-migrations.md` 仍登记一条说明（同 M8 的写法）。
- **批量插入**：50 万行逐条插约分钟级。给 xm-pbmysql 加 `insertAll(Connection, List<M>)`：**自己拼多行 `INSERT … VALUES (…),(…)`**（每条 ≤ 500 行，单条语句的更新计数 = 插入行数，可核对），
  **不**给 xm-data 的连接串加 `rewriteBatchedStatements=true`（评审修订）：打开后 Connector/J 把批量 INSERT 改写成多值语句，`executeBatch` 的逐条计数变成 `SUCCESS_NO_INFO`（-2），
  §2.8 / 7.2 依赖「每条恰好 1 行」的 JDBC batch 判定会静默失效；这个开关作用于整个数据源，7.2 的代码路径也会受影响。
  pbmysql 是用户自有库（AGENTS.md §2 的 star 门槛不适用），登记 tech-stack 的新用途。主键里的 `kind` 是枚举列（落 INT），实现前先确认 pbmysql 允许枚举进主键，不允许就改成 `uint32 kind`。

### 7.7 参与方接口 `ZoneMergeParticipantService`

xm-api 新增 `xm/api/zone_merge.proto` 与 `com.game.api.ZoneMergeParticipantService`；参数与返回值都是 protobuf（`architecture.md` §4.1）；不占消息号；
xm-guild 在 group `guild`、xm-trade 在 group `trade` 提供（`DubboGroups.java:20-27`）；调用方 MAC 鉴权（`XM_DUBBO_SECRET`）。

```
CompletableFuture<ListZoneObjectsResponse>   listZoneObjects(ListZoneObjectsRequest{zone, after_id, limit ≤ 1000})
                                             → {result, ids（升序）, next_after, done}
CompletableFuture<CountZoneObjectsResponse>  countZoneObjects(CountZoneObjectsRequest{zone})
                                             → {result, count, rank_card（仅帮会）, rank_indexed（仅帮会）}
CompletableFuture<ZoneMergePreflightResponse> preflight(ZoneMergePreflightRequest{op: MERGE | UNMERGE, run_id, src, dst, since_ms, object_ids ≤ 1000})
                                             → {result, findings[{code, severity, count, sample ≤ 20}]}
CompletableFuture<RezoneObjectsResponse>     rezoneObjects(RezoneObjectsRequest{run_id, from_zone, to_zone, ids ≤ 500})
                                             → {result: OK | FENCE_MISSING | INVALID_ARGUMENT | ERROR, moved[], already[], gone[], elsewhere[]}
CompletableFuture<FinishRezoneResponse>      finishRezone(FinishRezoneRequest{run_id, from_zone, to_zone})
                                             → {result, detail}
```

- **失败方向**：除 `OK` 外的任何结果（含 future 异常完成、超时 60 s）调用方都按失败处理；`rezoneObjects` 回 ERROR 时不知道哪些已提交，重试同一批（已改的变 ALREADY）。
- **幂等**：全部方法可任意重复；改写条件带 `AND zone = from`。
- 参与方没有清单状态：清单只在 xm-data。

### 7.8 xm-player-store 新增（唯一出处）

| 方法 | SQL 形状 | 调用者 |
|---|---|---|
| `countPlayersInZone(zone)` | `SELECT COUNT(*) FROM player WHERE zone_id = ?` | xm-data |
| `listPlayerIdsInZone(zone, afterId, limit)` | `… WHERE zone_id = ? AND player_id > ? ORDER BY player_id LIMIT ?`（走 `idx_player_zone`） | xm-data |
| `listHeldInZones(zones, heldAfterMs, limit)` | `… WHERE zone_id IN (?, ?) AND owner_released = 0 AND owner_lease_until >= ?` | xm-data |
| `selectZoneOwnersForUpdate(ids)`（必须在事务里调） | §2.8 第一条 | xm-data |
| `rezone(ids, from, to, noticeMs, now)`（必须在事务里调） | `UPDATE player SET zone_id = :to, merge_notice_ms = :notice, updated_at = :now WHERE player_id IN (…) AND zone_id = :from` | xm-data（合服 notice = T；撤销按 `notice_before_ms` 分组调用） |
| `consumeMergeNotice(playerId, expectedMs)` | `UPDATE player SET merge_notice_ms = 0 WHERE player_id = ? AND merge_notice_ms = ? AND merge_notice_ms > 0` | xm-login |
| `PlayerRow.mergeNoticeMs` | `SELECT *` 自动映射（`mapUnderscoreToCamelCase`） | — |

`xm-player-schema.sql` 的 `CREATE TABLE player` 加列（新库），存量库按 `db-migrations.md`：`ALTER TABLE player ADD COLUMN merge_notice_ms BIGINT NOT NULL DEFAULT 0 COMMENT '合服提示：合服时刻毫秒，0 = 没有；只由合服 / 撤销作业写、login 原子清零'`
（追加到末尾，MySQL 8 为 INSTANT DDL）；`idx_player_zone` 由 7.2b 加（Q20）。

### 7.9 其他模块改动

| 模块 | 改动 |
|---|---|
| xm-discovery | `RedisKeys.mergeFence`；`ZoneMergeFences`（写侧 Lua，§2.5）；`RedisZoneMergeFence`（读侧，§2.6）；`worldZones()` 注释改为「退役由合服作业移除」 |
| xm-login | F1 / F2 换用 `RedisZoneMergeFence`（5.4 的检查点不动）；提示消费（§6.3）；`RedirectOnEnter`（§6.4）；指标 |
| xm-scene / xm-scene-manager | xm-scene 在 226 第一条腿的 `SelectTravelTargetRequest` 填 `home_zone_id`（加载时的 `player.zone_id`）；scene-manager F4 用它查围栏；`WorldChannelCoordinator` 放掉移出集合的 zone（§4.6） |
| xm-guild | `MergeFence` 适配器（`GuildConfiguration.java:437`）；参与方提供者；`GuildCacheInvalidator.invalidateStrict`；`guild-maintenance` 执行器 |
| xm-trade | 参与方提供者 |
| xm-data | §7.1 的包；Dubbo 消费端 `xm.dubbo.trade-url`；`ZoneAdminController` 防护（§7.11）；读 `xm.run-mode`（`XM_RUN_MODE`，复用 xm-common `RunMode`，同 xm-trade / xm-battle；fence-drill 用）；7.2 作业受理加 `zone_merging` 检查（§3.6） |
| xm-api | `zone_merge.proto`、`ZoneMergeParticipantService`；`scene_directory.proto` 的 `SelectTravelTargetRequest` 加 `home_zone_id`（内部，非客户端契约） |
| xm-pbmysql | `insertAll` 与批量条件更新 |
| tools | `MergeZone.java`；`tools/local/merge-smoke.sh` |
| xm-robot | §13.6 的场景 |

### 7.10 CLI `tools/MergeZone.java`

- 只用 JDK（`java.net.http`），形状照 `tools/GmShutdown.java:19-35`：令牌只从环境变量 `XM_ADMIN_TOKEN` 读；`--url`（缺省 `http://127.0.0.1:18106`）；`--operator` 必填（1–64 个可见 ASCII）；
  `--idempotency-key` 不给时生成并打印，重试时带回。
- 子命令：`plan`（= `audit`）`--src --dst`；`apply --src --dst --expected-src-players N [--allow-empty-source] [--skip trade:原因]`；`resume --run`；`status --run [--json]`；
  `verify --run`；`unmerge --run [--dry-run]`；`export-manifest --run --out <file>`；`abandon --run --reason`；`fence-drill --zone --seconds | --release`。
- 提交作业后轮询 `GET /admin/ops-jobs/{id}`，打印步骤、计数、findings。
- 退出码：0 通过 / 作业成功；1 被拒、有 block、作业失败；2 没查成（连不上、鉴权失败、5xx、用法错误）。同 `audit_checks.go:36-40` 的语义。

### 7.11 `ZoneAdminController` 防护（xm-data，与作业同进程）

- 把 zone Z 改为 OPEN / PREVIEW（`PUT /admin/zones/{id}`、`POST /admin/zones/{id}/open`、`POST /admin/zones` 的 upsert）之前查 `zone_merge_run`：
  - Z 是某 run 的 src 且状态 ∈ {COLLECTING, PLANNED, APPLYING, APPLIED, UNMERGING} → 409 `zone_retired`；
  - Z 是某 run 的 dst 且状态 ∈ {COLLECTING, PLANNED, APPLYING, UNMERGING} → 409 `zone_merging`（防止半合服时开服）。
- `DELETE /admin/zones/{id}`（`ZoneAdminController.java:114-119`）：Z 是未终结 run（COLLECTING / PLANNED / APPLYING / UNMERGING）的 src 或 dst → 409 `zone_merging`（评审补：
  删掉再 `POST` 重建虽然会被上一条挡住，但中途删除会让 A9 / U8 改 `zone_config` 时找不到行、verify 的 `verify:src_closed` 失真）。已退役的 src 允许删除（运维清理区服列表）。
- 置 MAINTENANCE / CLOSED 不受限。查 `zone_merge_run` 失败 → 503（fail-closed，不放行开服）。

### 7.12 T-0 运维手册要点（写进 `docs/design/zone-merge.md`）

1. T-7：公告时间与「合服前请离队」（P7 同基线 `runbook:461-470`）；`MergeZone plan` 彩排，记下 N 与 findings（此时 `zone_open` 等必然 BLOCK，看其余项，§5.3）。
   确认存量库已跑 `merge_notice_ms` 与 `idx_player_zone` 迁移（plan 的 `missing_migration` / `missing_index`）。
2. T-0：`/admin/zones/{src,dst}/maintenance`（之后网关不再为这两个区发 gate 令牌，白名单也不例外；**别的 OPEN 区照常发**，两区玩家仍可能经别区进游戏，由围栏挡住）。
3. 用 `tools/GmShutdown.java` **先停 src 的全部 gate**（会话断开 → scene 写回并释放归属），**再停 src 的全部 scene**（先停 scene 会触发 5.5 的停服前疏散，把玩家挪到同区别的节点、
   多走一轮）；停 dst 的 gate（dst 的 scene 可以不停）。合服作业不负责踢人（Q16）；仍在别区游玩的 src 玩家会让预检 `zone_not_quiescent`，只能等他们下线。
   合服期间 xm-data 至少两个副本在跑（续期器，§2.5）。
4. 备份 MySQL（含 `zone_merge_*` 两表）与 Redis；需要按人回档时可另用 7.2 的整区快照（`/admin/zone-snapshots`）。
5. `MergeZone plan` 得到 N → `MergeZone apply --expected-src-players N` → `MergeZone verify`，退出码必须为 0。
6. `MergeZone export-manifest` 归档清单（永久保留，同 `runbook:714`）。
7. 重启 dst 的 gate，`/admin/zones/{dst}/open`；src 已被作业置 CLOSED。冒烟（生产跑 robot `merge-check` 的只读子集）。
8. **撤销只能在第 7 步之前**；作业半途失败时选 `resume`、`unmerge` 或（最后手段）`abandon`。verify 报 src 残留时用 `resume` 补扫（§7.3），不手工改库。
9. `RedirectOnEnter` 与合服无关，保持关闭（§6.4）。

---

## 8 配置与常量

### 8.1 配置项

| 键 | 缺省 | 进程 | 说明 |
|---|---|---|---|
| `xm.data.merge.batch-size` | 1000 | xm-data | 每批玩家 / item 数 |
| `xm.data.merge.participant-batch` | 500 | xm-data | 每次 `rezoneObjects` 的 id 数 |
| `xm.data.merge.participant-timeout` / `participant-retries` | 60 s / 3 | xm-data | 参与方调用超时与 ERROR 重试次数 |
| `xm.data.merge.max-players` | 500000 | xm-data | 超出回 `plan_too_large`（不与 7.2 的 `max-players-per-job` 共用） |
| `xm.data.merge.run-budget` | 30 min | xm-data | 作业超时；围栏 TTL = max(预算 + 30 min, 1 h)。基线 `-timeout` 缺省 2 h（`main.go:328`）；Java 取 7.2 `job-timeout` 同值：按 50 万人估算（逐人 Redis 检查约 1.5M 次异步读、50 万 item 多行插入、500 个玩家事务）在 10 min 量级，超时也只是在批边界停下、run 留 APPLYING 待 `resume`，不需要基线那样大的余量 |
| `xm.data.merge.fence-check-interval` | 10 s | xm-data | 续期器一拍的间隔：核对 / 续期 / 补回 / 终态清理（§2.5；评审补） |
| `xm.data.merge.fence-grace` | 15 s | xm-data | 立围栏后等多久再收集（Q12） |
| `xm.data.merge.quiescence-wait` / `quiescence-poll` / `quiescence-margin` | 60 s / 2 s / 5 s | xm-data | 归属静止的等待、轮询间隔、租约余量 |
| `xm.data.merge.residual-rounds` | 3 | xm-data | 残留并入轮数 |
| `xm.data.merge.retired-zone-message` | 「本区已合并至 {dst_name}（{dst} 区），请在区服列表中选择该区登录」 | xm-data | src 关闭文案模板 |
| `xm.data.merge.fence-drill-max` | 300 s | xm-data | drill 最长时长 |
| `xm.run-mode`（`XM_RUN_MODE`） | prod | xm-data | 复用全仓已有的运行模式键与 xm-common `RunMode`（xm-trade / xm-battle 同键，评审修订：原稿另起 `xm.data.run-mode`）；dev / test 才开放 fence-drill |
| `xm.dubbo.guild-url`（7.2b）/ `xm.dubbo.trade-url` | `tri://127.0.0.1:20886` / `:20887`；nacos profile 为空 | xm-data | 参与方直连 |
| `xm.login.redirect-on-enter` | false | xm-login | §6.4 |
| 围栏同步读上限 | 500 ms（且不超过请求剩余预算） | discovery 常量 | F5 帮会同步调用 |

### 8.2 常量与枚举

| 名称 | 值 |
|---|---|
| `RedisKeys.mergeFence(zone)` | `xm:{merge}:fence:<zone>`；字段 `run` `op` `src` `dst` `by` `at` `exp` |
| 围栏 op | `merge` / `unmerge` / `drill` |
| `OpsJobKind` | `OPS_JOB_ZONE_MERGE = 7`、`OPS_JOB_ZONE_UNMERGE = 8`（只追加） |
| `ZoneMergeStatus` / `ZoneMergeItemKind` / `ZoneMergeItemState` | §7.6 |
| 步骤位 | 1 GUILD、2 TRADE、4 PLAYERS、8 SOURCE_CLOSED、16 PLAN_RETIRED |
| 结果码（job `result_code` / HTTP） | `merge_in_progress`、`zone_open`、`src_retired`、`dst_retired`、`missing_index`、`missing_migration`、`src_nodes_alive`、`zone_not_quiescent`、`team_active`、`participant_unavailable`、`guild_name_collision`、`count_mismatch`、`empty_source`、`plan_too_large`、`manifest_incomplete`、`residual_guilds`、`residual_listings`、`residual_players`、`fence_lost`、`timeout`、`played_since_merge`、`membership_crossed`、`not_latest_run`、`run_busy`；`ZoneAdminController` 409 `zone_retired` / `zone_merging`；7.2 作业受理 409 `zone_merging`（§3.6）；7.2 已有 `ops_busy`、`idempotency_conflict`、`ops_disabled` |
| warn / info 码（只进 findings） | `dst_players_online`、`player_already_at_dst`、`fence_release_failed`、`battle_lock_present`、`match_ticket_present`、`participant_skipped`、`retire_failed` |
| 客户端 tip（不新增） | 2020、3023、3027、14013 |
| 迁移 | `player.merge_notice_ms BIGINT NOT NULL DEFAULT 0` |

---

## 9 指标与审计

所有指标都不以 player_id、run_id、zone_id、team_id 作标签（AGENTS.md §5）；标签取值固定集合、启动时预注册。

| 进程 | 指标 | 类型 | 标签 |
|---|---|---|---|
| xm-data | `xm_data_merge_jobs_total` | Counter | `op` = plan / merge / resume / unmerge，`result` = succeeded / rejected / failed / interrupted / cancelled |
| | `xm_data_merge_steps_total` | Counter | `op`，`step`（固定集合），`outcome` = done / failed / skipped |
| | `xm_data_merge_objects_total` | Counter | `op`，`kind` = player / guild / listing，`outcome` = moved / already / gone / elsewhere / reverted |
| | `xm_data_merge_checks_total` | Counter | `check`（固定集合），`severity` = block / warn / info |
| | `xm_data_merge_fences_held` | Gauge | 无（本副本续期中的围栏数，空闲为 0） |
| | `xm_data_merge_fence_renew_total` | Counter | `outcome` = renewed / restored / stolen / cleaned / error（`cleaned` = 终态清理，§2.5） |
| login、scene-manager、guild | `xm_merge_fence_checks_total` | Counter | `site` = create / enter / travel / guild，`result` = open / fenced / unreadable（`zone-travel-spec.md:908`，7.3 加 guild） |
| login | `xm_login_post_merge_notice_total` | Counter | `result` = shown / raced / error（只在 `merge_notice_ms > 0` 时计） |
| | `xm_login_redirect_on_enter_total` | Counter | `result` = redirected / fallback |
| guild / trade | `xm_guild_rezone_total` / `xm_trade_rezone_total` | Counter | `outcome` |

- **审计事件**：沿用 `ops_job_event` 的类型（`ops_tables.proto:52-64`）——STARTED、PLANNED（清单落库）、CHECK（预检 findings）、WRITE（每步完成）、RECHECK（残留 / 收尾复查）、RESULT、INTERRUPTED；
  镜像到 `xm.audit.ops` 日志。被拒或失败时日志打印清单摘要与「resume / unmerge / abandon」指引（对应基线 `fenceKeptAbortMessage`，`merge_run.go:1323-1368`）。
- **告警建议**：`xm_data_merge_fences_held > 0` 持续超过 `run-budget`；窗口外 `xm_merge_fence_checks_total{result!="open"}` 仍在增长（对应 runbook §0 第 9 条）；
  `xm_data_merge_fence_renew_total{outcome="stolen"}` 任何增长；有 APPLYING / UNMERGING 的 run 时 `xm_data_merge_fence_renew_total{outcome="renewed"}` 超过 3 个 `fence-check-interval`
  没有增长（续期器全部停摆，围栏只剩 TTL，§2.5 崩溃语义）。

---

## 10 隐患与边界

### 10.1 Java 设计的残余风险

1. **D-H4 残余窗口**（§2.7）：归属与围栏不同存储；由宽限、src gate 为空、残留并入与收尾复查兜底。
2. **围栏丢失**：Redis 重启 / 淘汰导致 APPLYING 期间键丢失，续期器在一个 `fence-check-interval`（10 s）内补回；有作业在跑时下一批之前的 `checkHolder` 也会发现并停下。
   空窗内 F1 / F2 / F4 / F5 可能放行：两区本身是维护态、不发 gate 令牌，但**别的 OPEN 区照常发**，归属 src / dst 的玩家可以经别区进游戏（评审修订：原稿写「实际不可达」不对）。
   空窗 ≤ 10 s，事后由残留复查 / 下一批 `FOR UPDATE` 判静止兜住（进来的人表现为持有归属 → `zone_not_quiescent`，run 留 APPLYING 待 `resume`）。
3. **长时间持锁**：参与方每个 id 一个短事务，锁等待 5 s、重试 5 次；与 xm-guild 后台循环（资产指令重投、日计数清理）冲突时整批重试。
4. **区榜重建的锁窗口**：重建持锁期间请求路径的入榜 / 清榜最多等 5 s 后放弃（`GuildRanks.java:41-51`），放弃的只能等下次重建修复（`guild-spec.md:1599` 第 10 条）；
   全量重建是秒级以下（帮会数小），实测超过 1 s 再考虑按 zone 重建（Q6）。
5. **清单规模**：50 万玩家 → 50 万 item 行 + 500 个玩家事务；没有 `insertAll` 时清单落库是分钟级（§7.6）。
6. **僵尸写者**：`owner_released = 0` 且租约过期的玩家算静止；持有它的 scene 若其实还活着，批量续约 `renewOwnerLeases` 不看租约是否已过期（`PlayerMapper.java:133-138`），
   之后仍可能续约成功。改写**之前**续上的，每批 `FOR UPDATE` 复判会挡住；改写**之后**续上的，它持有的是一个归属区已变的玩家（只影响 scene 内存里的 F4 归属区，存盘不受影响）。
   要真正服务客户端还得有 gate 会话，而清单玩家的在线目录检查（gate 写、TTL 60 s）会先挡住；剩下的只有「scene 活着、gate 已断」的玩家，没有客户端可见后果。可接受。
7. **组队阻断最长 24 h**：没有管理员解散入口（同 `runbook:859`）。
8. **开服后无法撤销**：设计如此；操作顺序错误（先开 dst 后发现合错）只能单玩家修复或备份还原。
9. **`abandon` 留下半合服状态**：只作最后手段，必须先人工核对；verify 会把残留列成 block。
10. **4.8 交易写侧**：合服改写依赖「`market_zone` 只在上架时写」（I-Z1）；4.8 若引入改 `market_zone` 的路径或缓存，必须同步扩展参与方与预检。
11. **合服后整区回档（7.2）**：来自 src 的玩家快照 zone ≠ 归属区，按 7.2 §4.9 单列、只提示（`data-ops-spec.md:541-542`）。
12. **时钟**：`notice_ts` 用 xm-data 墙钟；归属租约比较用各进程墙钟，要求 NTP（`architecture.md:672` 起「玩家数据归属协议」）。
13. **客户端选区**：客户端按选中区过滤角色（`GameClient.cs:651`），源区玩家必须选 dst；src 的关闭文案必须写明去向（§6.2）。

### 10.2 边界情况

| 情况 | 处置 |
|---|---|
| 一次合并多个区（A、B → C） | 依次跑两个 run；单飞保证串行；合进已被合走的区被拒（`dst_retired`） |
| 链式合服（3→2 之后 2→1）再撤 3→2 | `not_latest_run`；先撤 2→1（撤掉的 2→1 是 UNMERGED，不再算「最近一次」）；撤 2→1 时原 3 区玩家的 3→2 提示按 `notice_before_ms` 还原 |
| src 有玩家正在别的 zone 游戏（传送过去） | 归属持有 → 阻断，等他下线 |
| dst 有玩家正在别的 zone 游戏 | warn `dst_players_online`，不阻断（他们的写入口被 dst 围栏挡住） |
| A10 之后 src 又出现对象（极慢的在途建角） | verify block → 两区置维护 → `resume` 补扫 |
| 7.2 作业想动未终结 run 的区 | 409 `zone_merging`（§3.6） |
| 传送第二条腿在途（`l` 记录、票据 ≤ 300 s） | 预检等待；超过 `quiescence-wait` 被拒，稍后重跑 |
| 帮会 / 商品在窗口内新建（dev 播种） | 残留并入（late = 1），最多 3 轮 |
| 清单玩家在合服后被删（Java 无删角色） | GONE，只计数 |
| 参与方在部署里不存在（本地切片没起 trade） | 首跑显式 `skip`，verify 报 NOT VERIFIED |
| 合服中途 xm-data 换副本 | 作业由清扫器标 INTERRUPTED；run 为 APPLYING 时由任一副本续期；人工 `resume` |
| dst 在 run 未终结时被误开 | `ZoneAdminController` 409 `zone_merging` |

### 10.3 与其他批次的约束

- **5.4**：F1 / F2 / F4 检查点、`redirectToZone`、建角取会话 zone、`XM_ZONES=2` 切片都是前置。
- **7.2b**：作业框架、`PlayerStore` bean、Dubbo 消费端、`idx_player_zone`；7.3 反过来给 7.2 的受理加 `zone_merging` 检查（§3.6），写进 7.2b 的待办或由 7.3b 补。
- **4.6 帮会活动 / 4.8 交易写侧**：新表的 zone 列进登记表；写入口接 F 检查点；参与方扩展（§4.6）。
- **6.3 战斗**：战斗锁只在审计里报 warn；结算待办按 pid 存，合服无影响。6.2 battle 节点是全局池（作用域 0），不在 src 节点检查里。
- **5.5 排空**：停 src 的顺序是先 gate 后 scene（§7.12 第 3 步），否则停服前疏散会多挪一轮。

### 10.4 基线自身的问题（mmorpg 待做候选，交 mmorpg 侧裁决）

| # | 问题 | 后果 | 出处 |
|---|---|---|---|
| B1 | 提示在进场结果出来之前就被消费，且 GET 与 DEL 不原子 | 进场随后失败时提示永久丢失；并发两次进游戏可能都看到 | `entergamelogic.go:406-412`、`:494-547` |
| B2 | `RemapHomeZoneForMerge` 仍是全量 SCAN、不认清单、不 CAS | 绕过 A2 修复：清单外的人也被改、撤销找不回 | `router.go:478-530` |
| B3 | 撤销 5′ 是 MGET 再 SET，不是 CAS | 有围栏，风险低，但与合服口径不一致 | `unmerge.go:385-433` |
| B4 | 撤销不强制「开服之前」 | pin 模式下撤销一个已在 dst 玩过的人：home 改回去，他在 dst 建立的帮会 / 交易关系留在原地 | `unmerge.go:5-7`；`runbook:808-819` |
| B5 | 合服门禁只查源区活节点 | dst 是否下线只靠 runbook | `merge_run.go:250-251` |
| B6 | Unity 客户端不读 26 字段 3 / 4 | 合服公告实际看不到 | `GameClient.cs:795-805` |
| B7 | 一致性巡检的区榜孤儿检查在 mapping DB 0 上 SCAN `guild_rank:zone:*`，区榜实际在 guild Redis DB 2 | 这项检查恒为干净（正是「写错库静默无效」） | `tools/data_consistency_check/main.go:103`、`:185`、`:322-331`；`main.go:12` |
| B8 | 撤销 7′ / 5′ / 4′ 等前段失败直接 `log.Fatalf`，不打印 run_id 与 DEL 指引 | 文案与 `abortKeepingFence` 不一致 | `unmerge.go:231-243`、`:268-276` |
| B9 | runbook 与 login 配置注释说 Unity 只打 124 日志 | 与当前客户端不符（`GameClient.cs:1037-1066` 已实现 `RedirectFlow`），文档过时 | `runbook:851`；`config.go:200-202` |
| B10 | runbook §12「本批代码全部未编译、未测试」 | 与 `PROGRESS.md:6003-6004`（83 个 `TestIT_*` 全过）不一致；真实集群演练从未做过（`PROGRESS.md:6030`） | `runbook:850` |
| B11 | 撤销 4′ 在 3′ 之前用快照分数回写源榜 | 两步之间改分会在 dst 榜留下幽灵条目，等下次 `RebuildRanks` 才消失（基线今天没有推分来源，潜在） | `unmerge.go:266-278`；`guild_step.go:419-450` |
| B12 | 步骤 3 → 4 之间的解散缺口 | zone 已是 dst、源榜还在时解散按 dst 清榜，源榜里的成员随后被 `ZADD NX` 合进 dst；只在 `MergeMarkerRedis` 未配时可能 | `guild_logic.go:503-508`；`economy_logic.go:297-300` |
| B13 | 围栏释放 / 续期按子串查找比较 run_id | 理论上可误认 | `fence.go:72-85` |
| B14 | 提示键不带 TTL、续跑重取时刻 | 休眠玩家的键永久存在；续跑后提示时刻漂移 | `post_merge_stamp.go:125`；`merge_run.go:636` |
| B15 | 没有合服 robot | 客户端可见面无端到端回归 | `robot/` |

---

## 11 建议的有意差异（落地后逐条登记 PARITY）

| # | 差异 | 理由 | 客户端可见 |
|---|---|---|---|
| D1 | 形态是 xm-data 运维作业 + JDK-only CLI，不是独立命令行工具 | 与 7.2 作业单飞互斥；审计、心跳、幂等复用（§7.1） | 否 |
| D2 | 不移植 pin / copy、blob、能力标记、回填、搬库、Kafka 积压与重试队列门禁、`RemapHomeZoneForMerge` | 单库、同步带围栏写回、归属就是一列（§3.2）；修 B2 | 否 |
| D3 | 清单在 MySQL（可导出 JSON 永久归档）；玩家每批改写与清单进度同事务；dry-run 不写清单 | 消掉「步骤已执行、清单未落盘」（`merge_run.go:401-406`）与 `.dryrun.json` 误读（`manifest.go:20-23`） | 否 |
| D4 | `merged_into` 改为 `zone_merge_run` 状态；没有回填读者 | Java 没有回填，也不会映射丢失 | 否 |
| D5 | 「全员离线」判据改为清单玩家归属静止 + 无在线目录 / 位置记录，每批 `FOR UPDATE` 复核；dst 玩家持有归属只报 warn；合服不夺权 | Java 写回语义（§3.3–§3.4）；dst 口径同基线 P5 / P6 只查清单玩家 | 否 |
| D6 | 帮会 / 聚宝斋由属主经 Dubbo 参与方改写，xm-data 不碰它们的表与键 | 缓存协议与锁序只在属主里有一份（同 7.2 Q15） | 否 |
| D7 | 帮会区榜从 MySQL 权威分全量重建，不做 ZSET 合并，清单不存榜单成员 | 复用 `GuildRanks.rebuild`；同时修 B11、B12 | **是（仅边缘情况）**：基线取源榜缓存分（`ZADD NX`），Java 取权威分；正常情况两者相同 |
| D8 | 围栏是 Hash、两把一段 Lua 原子地立、按 `run` 精确比较、run 未终结期间每 10 s 核对 / 续期 / 补回、终态 run 的孤儿围栏自动清理、续跑 / 撤销同一 run 接管 | 修基线 TTL 过期后半合服无保护（`merge_run.go:1328-1335`）与子串比较（B13）；删掉「GET 核对 → DEL → 重跑」手工步骤 | 否（窗口内被拒的时间可能更长） |
| D9 | 立围栏后等宽限，玩家步骤后残留并入、收尾复查 | 归属与围栏不同存储（D-H4） | 否 |
| D10 | 强制两区 MAINTENANCE / CLOSED、src 节点为空；dst 节点可运行 | 修 B5 的意图（基线只靠 runbook）；Java 存盘不按归属区路由 | 否 |
| D11 | 合服提示存 `player.merge_notice_ms`，与改归属同语句写入；夺权成功后条件 UPDATE 原子消费；时刻按 run 固定；重定向路径不消费；撤销还原改写前的值 | 修 B1、B14 | **是**：进场被拒不吞提示；并发进游戏只有一个看到；经重定向进游戏时字段 3 在落地那一跳的 26 里（基线在第一跳的 26 里，§6.3 ④） |
| D12 | F2 命中在 26 应答里同步回 3023 | 沿用 Java 进场拒绝的同步口径（5.4 已定，`zone-travel-spec.md:456`；`PARITY.md:27`） | **是**：基线 26 成功后推 23 {3023} |
| D13 | 作业把 src 置为 CLOSED 并写去向文案，拒绝重新打开已退役的区、拒绝在合服未终结时打开 dst | 防漏操作、防误开 | **是**：区服列表里 src 显示 CLOSED（基线 runbook 保持 MAINTENANCE） |
| D14 | 撤销强制「合服后无人进过游戏」（epoch）与「清单帮会无合服后入帮者」，不提供强制选项；允许撤销半途失败的合服 | 修 B4 | 否 |
| D15 | 首跑 `expectedSrcPlayers` 必填（基线缺省 -1 不检查，`main.go:310`） | fail-closed：防填错 src | 否 |
| D16 | 热状态：不删玩家位置；只退役源区频道计划（缺省开、不致命）；不删登录排队键 | 位置等键带 TTL；计划键无 TTL | 否 |
| D17 | 审计不统计好友 / 好友申请 / 帮会成员 | 没有 zone 列，没有可决策的东西 | 否 |
| D18 | 帮会合服闸始终接线，没有「未配置即失效」的形态 | 只有一个 Redis；关闭基线 `runbook:860` 的缺口 | 是：14013 在窗口内出现（与配置正确的基线一致） |
| D19 | zone 列登记表 + 构建期守护测试 | 把「漏表」挡在编译期 | 否 |
| D20 | dev / test 专用的 `fence-drill` | robot 能在不改数据的前提下验证 F1 / F2 / F4 / F5 | 否 |
| D21 | 对 APPLIED 的 run 提供 `resume` 补扫（残留并入 + 重做未完成的退役） | 基线跑完后清单外的人只告警、逐人处置（`merge_run.go:601-612`）；Java 的 `src_retired` 会挡住任何新 run，没有补扫就无路可走 | 否 |
| D22 | F4 收到 `home_zone_id = 0`（旧版 scene）按封锁处理 | 内部字段的混版本 fail-closed（§2.6） | 否（只在混版本时） |
| — | 两版一致、不算差异：force_rename 恒 false；RedirectOnEnter 缺省关；活队伍阻断；`seller_zone_at_listing` 与审计流水 zone 不改；好友 / 聊天不动 | — | — |

---

## 12 开放问题（各带推荐答案）

| # | 问题 | 推荐答案 |
|---|---|---|
| Q1 | 合服跑在哪：xm-data 作业、新进程 `xm-ops`、还是独立 CLI？ | **xm-data 作业 + JDK-only CLI 薄壳**（§7.1）；`inventory/tools.md:332` 的 `xm-ops` 作废 |
| Q2 | 清单存文件还是 MySQL？ | **MySQL**（`zone_merge_run` / `zone_merge_item`），另可导出 JSON 归档 |
| Q3 | 合服提示存哪：`player` 列、Redis 键、还是单独一张表？ | **`player.merge_notice_ms` 列**：与改归属同语句、零额外读、不带 TTL 与基线一致、撤销同语句清零（§0.7 第 1 条） |
| Q4 | 帮会 / 交易由 xm-data 直写（可与玩家同事务）还是属主执行？ | **属主执行**（7.2 Q15 先例；缓存与区榜只在属主）；原子性由围栏 + 条件幂等 + 清单进度替代 |
| Q5 | 玩家改写一个大事务还是分批？ | **每批 1000 人一个事务**，item 状态同事务；大事务的 binlog 与锁持有不可接受，半成品状态被围栏挡住 |
| Q6 | 区榜：全量重建还是只重建 src / dst？ | **全量重建，复用现成代码**；实测重建持锁 > 1 s 再加按 zone 重建 |
| Q7 | dst 必须停机吗？ | **不必**：两区维护态 + 清单玩家归属静止 + src 节点为空；运维手册仍建议停 dst 的 gate 以清空 dst 在线玩家（dst 玩家在线只报 warn，Q26） |
| Q8 | `owner_released = 0` 且租约过期的玩家怎么办？ | **算静止**（5 s 余量），每批 `FOR UPDATE` 复判；不需要先用 `AdminOwnership` 归一 |
| Q9 | 续跑时要不要把仍在 src 的对象并进清单？ | **要**，玩家 / 帮会 / 商品都并入并标 `late = 1`，最多 3 轮；基线对玩家只告警（`merge_run.go:601-612`），Java 复查成本低 |
| Q10 | `RedirectOnEnter` 做不做？ | **7.3 做，缺省关**（两版都做的纪律；复用 5.4 的 `redirectToZone`）；当前 Unity 已支持 124，是否打开由运营决定 |
| Q11 | 已合走的区要不要在 gate 启动自检 / login 拒建角等代码层拒绝？ | **不要**：src 置 CLOSED 后 assign-gate 回 503；`ZoneAdminController` 拒绝重开；verify 再断言一次 |
| Q12 | 宽限取多长？ | **15 s**（> login 建角链单语句上界 10 s），可配；残留复查兜底 |
| Q13 | `force_rename` 要不要接？ | **不接**，字段 4 恒 false（名字全服唯一，`architecture.md` §7） |
| Q14 | 一次合多个区（A、B → C）？ | 依次跑多个 run，单飞保证串行 |
| Q15 | 组队阻断会不会挡合服最长 24 h？ | **与基线一致：阻断**，T-7 公告要求离队；演练证明是瓶颈再给 xm-team 加内部「维护期解散」（走它自己的 Lua、正确推进 epoch），现在不做 |
| Q16 | 作业要不要用顶号通路踢在线玩家（像 7.2 的 kick）？ | **不踢**：基线工具也不踢；唯一可用的 tip 2017「被顶号」会误导玩家（`data-ops-spec.md` Q5）；运维用维护态 + GmShutdown |
| Q17 | 开服后还要撤销怎么办？ | **拒绝，不提供强制选项**；转为单玩家修复（7.2 工具）或从备份还原 |
| Q18 | 频道计划退役缺省开还是关？ | **开、不致命**；前置 src 无 scene 节点 |
| Q19 | dev 播种要不要读闸门？ | **不读**（同基线 P1）；4.8 写侧必须读 |
| Q20 | `idx_player_zone` 谁来加？ | **7.2b**（`data-ops-spec.md` Q9）；7.3 先落地则由 7.3 加，迁移号顺延 |
| Q21 | 本地 / 测试环境没部署帮会或交易怎么办？ | 首跑显式 `skip` 并写原因；参与方不可达且没 skip → block |
| Q22 | 要不要改 mmorpg？ | 本批不改；把 §10.4 的 B1–B15 登记为「mmorpg 待做候选」，其中 B1、B2、B4、B7、B9 优先 |
| Q23 | 匹配票据 / 战斗锁要不要阻断？ | **不阻断**：票据只报 info（匹配池不分 zone），战斗锁报 warn（战斗必在线，归属静止已覆盖） |
| Q24 | 撤销时成员交叉检查放在 xm-data 直接 join 还是参与方？ | **参与方 `preflight(UNMERGE)`**：xm-data 不读帮会表（同 Q4 的边界） |
| Q25 | 半途失败的合服能否撤销？ | **能**（APPLYING 可撤），每步按 `AND zone = dst` 条件改回 |
| Q26（评审补） | dst 玩家持有归属（例如正经 5.4 在别区游玩）要不要阻断合服？ | **不阻断，只报 warn**：dst 的行不改写，写入口全被 dst 围栏挡住；基线 P5 / P6 也只查清单玩家；作业不踢人，阻断会让多区生产里的合服无法进行 |
| Q27（评审补） | A10 之后才落进 src 的对象（极慢的在途建角）怎么处理？ | **`resume` 补扫 APPLIED 的 run**（两区先回维护态）；verify 的 `verify:players_src` 拦住开服。不提供手工改库的指引 |
| Q28（评审补） | 续期器多久一拍？ | **10 s**（核对 / 续期 / 补回 / 终态清理）；按 TTL/3（20 min）一拍时 Redis 重启后最长 20 min 没有围栏，而别区的 gate 仍在放人进来 |
| Q29（评审补） | 7.2 的作业能否在合服 run 未终结时动这两个区？ | **不能**：受理时 409 `zone_merging`；单飞只挡「同时在跑」 |
| Q30（评审补） | xm-data 要不要开 `rewriteBatchedStatements`？ | **不开**：会让 JDBC batch 的逐条计数变成 -2，破坏「恰好 1 行」判定；`insertAll` 在 pbmysql 里自己拼多行 INSERT |

---

## 13 测试计划

本机没有 Docker、Go、Python、Node；Redis / MySQL / Kafka 作为普通进程由 `D:/work/.tools/with-backends.sh` 起停（`data-ops-spec.md:1113`）。基线 Go 测试只作对照、不在本机运行。
按 AGENTS.md §4，没有运行证据不得声称通过；CI 结论写 run id。

### 13.1 单测（缺省就跑，`./mvnw -B -pl xm-discovery,xm-player-store,xm-login,xm-guild,xm-trade,xm-scene,xm-scene-manager,xm-data -am test`）

| 模块 | 用例 | 对应基线 |
|---|---|---|
| xm-discovery | `RedisKeys.mergeFence` 形状；`RedisZoneMergeFence`：键不在放行、键在拦、读出错抛、zone 0 放行 | `TestIT_MergeFence_*`（`integration_test.go:1398`） |
| xm-player-store | `consumeMergeNotice` 条件更新恰好一次；`rezone` 只改 `zone_id = from` 的行；`PlayerRow.mergeNoticeMs` 映射 | — |
| xm-login | F1：命中 / 读不到 → 14 {2020}，`PlayerIdGenerator` 零调用、零插行；F2：26 {3023}，不分配、不夺权；提示只在 accepted 消费、拒绝与重定向路径不碰（重定向应答的字段 3 = 0、值留在行里，随后的落地 accepted 才给出）、SQL 出错仍成功且填 0、并发两次只有一次 > 0；**消费只发生在工作线程上**（`rerouteIfStale` 走重新分配、由测试线程完成 Dubbo future 的用例里断言消费调用的线程是 login 工作线程）；`resolveEnterRoute` 表驱动覆盖 RedirectOnEnter 开 / 关 | — |
| xm-scene | 226 第一条腿的 `SelectTravelTargetRequest.home_zone_id` = 加载时的 `player.zone_id`（非 0） | — |
| xm-scene-manager | F4 命中 → 3027；`home_zone_id = 0` → 按封锁 3027 并计 unreadable；`WorldChannelCoordinator` 在 zone 被 SREM 后放锁、移出内存表、清 gauge | — |
| xm-guild | `MergeFence` 适配器（命中 / 抛异常 → 14013 两种文案；事务内闸门对解散与经济生效，扩展 `GuildServiceTest`）；参与方四种结局；`FENCE_MISSING` 零变更；`invalidateStrict` 失败抛、整批回 ERROR；`finishRezone` 后 src 区榜与索引消失、dst 榜 = MySQL；不在 guild-worker 上执行；无调用方 MAC 被拒；`uk_guild` 形状单测 | `TestGuild*`（`guild_step` 相关） |
| xm-trade | 参与方点更新、计数、`seller_zone_at_listing` 不变 | `trade_step_test.go` |
| xm-data | 纯规划器：步骤顺序、续跑从第一个未置位步骤继续、合走裁决矩阵、`count_mismatch` / `empty_source` 守卫；`ZoneMergeChecks` 分级与 INFRA 优先；状态机故障注入（每步之后抛异常 → 围栏与 run 状态的处置：写前释放、写后保留）；完整性 `MOVED + ALREADY ≠ N` 不置步骤位、GONE / ELSEWHERE → `manifest_incomplete`；7.2 作业被 `ops_active` 挡住、run 未终结时 7.2 受理 409 `zone_merging`；dry-run 不立围栏不插 run、不等归属静止；`dryRun` 缺失 400；`ZoneAdminController` 409 三种（开 src、开 dst、删未终结 run 的区）；`ZoneColumnRegistryTest`（故意加一个未登记 zone 列应失败）；run 状态条件更新（清扫器已改 ABANDONED 后原线程推进失败、不写）；续期器（MISSING 时重读状态才补回、补回后发现已终结立即释放、终态清理只删 `at` 早于终结时刻的围栏）；`resume` 对 APPLIED：只缺 A9 时不立围栏、有残留时要求两区维护态；dst 持有归属只出 warn | `TestMappingRemapReport_AlreadyPlusChangedMustEqualTheManifest`（`gap_fixes_test.go:140`）、`TestMergedIntoConflict`（`:485`）、`TestGuardPlayerSet_*`（`:44`、`:60`）、`TestIT_Gap_RefusalsUnderTheFenceReleaseIt`（`integration_test.go:2353`）、`TestIT_Gap_ResumeRefusalKeepsTheFence`（`:2674`）、`TestIT_Gap_DryRunPreviewIsNeverConsumed`（`:2404`）、`TestIT_Audit_ExitsTwoWhenAHandleIsMissing`（`:2270`） |
| tools | 仓库里没有给 `tools/*.java` 跑单测的框架（`GmShutdown` / `TestReport` 都没有单测，评审修订：原稿「同 `GmShutdown` 的做法」不成立）。`MergeZone.java` 只做参数解析、HTTP 与轮询，内置 `--self-test`（参数解析与退出码映射的表驱动断言，失败退出 1），`ci.yml` 跑一次 `java tools/MergeZone.java --self-test` | — |

### 13.2 SQL 测试（缺省 H2；`-Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306` 连真 MySQL，口令 `XM_MYSQL_PASSWORD`）

- `PlayerStoreRezoneSqlTest`：条件改写影响行数、无符号 id、`FOR UPDATE` 判静止；真 MySQL 上对键集分页与改写跑 `EXPLAIN`，断言 `idx_player_zone` / `PRIMARY`（对应 `TestIT_ZonePointUpdatesArePrimaryKeyPointLookups`，`integration_test.go:1111`）。
- `ZoneMergeStoreSqlTest`：pbmysql DDL 与 `syncAll`；item 状态迁移与 MyBatis 改写同一事务同生同死（中途回滚两边都没有）；`insertAll` 多行 INSERT 的计数 = 行数；
  JDBC batch 的逐条计数恰好为 1（连接串不带 `rewriteBatchedStatements`，真 MySQL 上回归）；`notice_before_ms` 记录与 U5 按组还原；`DatabaseMetaData` 查索引 / 列在 H2 与真 MySQL 上结果一致；
  `SET SESSION innodb_lock_wait_timeout` 归还连接前已恢复（同一连接下一次借出读回原值）。
- `GuildRezoneMysqlIT`（只连真 MySQL）：参与方改写与并发解散、经济事务同跑，不出现 1213 环（对应 `TestIT_MigrateGuildZone_ConcurrentDisbandDoesNotDeadlock`，`integration_test.go:1199`）。
- `TradeRezoneSqlTest`；`GuildUnmergePreflightSqlTest`（`join_time_ms` 交叉检查）。

### 13.3 Redis 集成测试（`-Dxm.it.redis=redis://127.0.0.1:6379`，DB 13）

围栏 Lua：两把同时成功、任一冲突整体失败、一把空一把本 run 时接管成功、同 run 接管、按 run 续期 / 补回 / 释放、`releaseIfStale` 不删 `at` 晚于终结时刻的围栏、
`releaseDrill` 删不掉 op = merge 的围栏、`SCRIPT FLUSH` 后 NOSCRIPT 重载、`checkHolder`；续期器在 `DEL` 围栏后一拍内补回、在 run 已终结时不补回；频道计划退役 + 真实协调器
（删 `{z:1}` 不碰 `{z:10}`，`node-id-epoch` 永不在删除集合里）；`TeamMembershipReader` 批量读（tid 为 "0" / 缺失放行、孤儿索引放行、损坏 fail-closed，对应
`TestIT_Preflight_IgnoresStaleTeamIndexes`，`integration_test.go:1548`）。

### 13.4 作业集成测试（xm-data `ZoneMergeJobIT`：真 MySQL 临时库、真 Redis，参与方用进程内假实现 + 一组连真 xm-guild 存储的实现）

- 端到端：合服 → verify PASS → 撤销 → 状态还原（对应 `TestIT_EndToEnd_MergeVerifyUnmerge`，`integration_test.go:1650`）。
- 在 A6、A7、A8 的中间一批、A10 之前各注入一次失败 → 围栏保留、续期器续期 → `resume` 后结果一致；A1–A4 注入失败 → 围栏释放、run ABANDONED、零数据写入。
- 围栏立起之前已过 F1 的建角在围栏之后才提交 → 被残留并入（late = 1），verify `expected_count` 只数 late = 0（对应 `TestIT_*_ResidualAbortKeepsFenceThenResumesAfterDEL`，`integration_test.go:2040`、`:2159`，语义按 D9 改写）。
- 预检逐项阻断（对应 `TestIT_Preflight_BlocksOnEachHazard`，`:1499`）；src 有活节点、两区不是维护态在任何写之前被拒（对应 `TestIT_Gap_Unmerge_RefusesALiveTargetZoneBeforeAnyWrite`，`:2622`）。
- 撤销：`owner_epoch` 前进过 → `played_since_merge` 零写入；`join_time_ms ≥ since` → `membership_crossed`；撤 APPLYING 的半途 run；`not_latest_run`。
- 链式合服 3→2→1；合进已退役区被拒；幂等键重放；与 7.2 整区回档互斥（同时在跑被单飞挡、run 停在 APPLYING 时 7.2 受理 409 `zone_merging`）；清扫器把 COLLECTING 的中断作业的 run
  置 ABANDONED 并释放围栏，而原执行线程（模拟心跳卡住后恢复）随后推进 run 状态失败、零写入。
- 链式合服 3→2（不领取提示）→ 2→1 → 撤 2→1：原 3 区玩家的 `merge_notice_ms` 回到 3→2 的时刻；再撤 3→2 被允许（2→1 已 UNMERGED）。
- 补扫：APPLIED 之后直接往 src 插一个玩家 → verify `verify:players_src` block → 两区维护 → `resume` → 该玩家成为 late item、改到 dst、verify PASS；只缺 A9 时 `resume` 不立围栏。
- A10 之后释放围栏失败（注入）→ job SUCCEEDED 带 `fence_release_failed` → 续期器终态清理一拍内删除；verify `verify:merge_fence` 随后 PASS。
- dst 有持有归属的玩家 → 合服照常进行，findings 带 `dst_players_online`。

### 13.5 CI

- 单测与 H2 SQL 测试进 `ci.yml`；真依赖的 IT 进 `integration.yml`（compose `infra.yaml`，`TestReport --require-it-executed`，`deploy-ci-spec.md:56`、`:64`）。
- 双 zone 切片与 robot 不进 CI（同 `zone-travel-spec.md` §11.9），7.1b 整栈冒烟落地后再评估。

### 13.6 本机双 zone 切片与 robot（客户端可见面验收；基线没有合服 robot）

前提：5.4 的 `XM_ZONES=2` 切片（`zone-travel-spec.md:823`）、`XM_RUN_MODE=dev`（xm-data 与 xm-trade 的运行模式）、`XM_DATA_OPS_ENABLED=true`、`XM_ADMIN_TOKEN` 从环境变量注入。
zone 2 作 src、zone 1 作 dst。新增脚本 `tools/local/merge-smoke.sh`（Git Bash，由 `with-backends.sh` 包住）与 xm-robot 场景，状态文件 `run/merge-robot.json`。

**场景 `merge-fence-drill`**（两区 OPEN）

1. A（zone 2 建角、进游戏）在线；新账号 N 在 zone 2 的 gate 上登录（48）停在选角。
2. `MergeZone fence-drill --zone 2 --seconds 60`。
3. 断言：N 建角 14 → `{2020}`；A 帮会写（15 建帮）→ 14013；A 跨区传送 226 → 23 `{3027}`、A 仍在原场景；A 17 离开后 26 → `{error_message 3023}`。
4. `MergeZone fence-drill --zone 2 --release`；断言上面四项全部恢复成功；`xm_merge_fence_checks_total{result="fenced"}` 四个 site 各自增长。

**第一轮：合服后在开服前撤销**

1. `merge-prepare --src 2 --dst 1`：A、C 在 zone 2 建角（48 → 14 → 26 字段 3 = 0 → 17）；A 建帮 GA；`POST /admin/trade/seed-listing` 给 A 播一件商品，C 收藏（198）；
   B 在 zone 1 建角、建帮 GB。断言 B 查 GA（60）→ 14001、zone 1 区榜无 GA、B 浏览（196）看不到 A 的商品。全体离队、离开。
2. `/admin/zones/{1,2}/maintenance`；`GmShutdown` 停 zone 2 的 gate / scene 与 zone 1 的 gate。
3. `MergeZone plan` 得 N → `apply --expected-src-players N` → `verify` 退出 0 → `status --json` 取 `notice_ts`。
4. `MergeZone unmerge --run R` 退出 0。
5. 两区改 OPEN、重启 gate / scene；`merge-unmerge-check`：A 的角色 48 `zone_id = 2`；26 字段 3 = 0；GA 在 zone 2 区榜；B 又看不到 GA 与 A 的商品。

**第二轮：合服并开服**

1. 再跑一次 `merge-prepare`（新角色 A′ / C′、新帮 GA′、新商品）。
2. 同第一轮第 2–3 步；重启 zone 1 的 gate，`/admin/zones/1/open`。
3. `merge-check`：A′ 经 zone 1 登录，48 的角色 `zone_id = 1`；26 字段 3 = `notice_ts`、字段 4 = false、收到 79；17 后再 26，字段 3 = 0；
   A′ 查自己的帮（35）得 GA′ 且 `zone_id = 1`；B 能查到 GA′；zone 1 区榜（27 / 52）同时有 GA′ 与 GB；B 浏览 / 详情（196 / 197）看到 A′ 的商品且 `market_zone = 1`；C′ 的收藏仍在；
   B 的好友视图里 A′ 的 `zone_id = 1`（若先互加好友）；zone 1 新建角色成功；zone 2 的 assign-gate 回 503 `zone_closed`、区服列表里 zone 2 为 CLOSED 且带合服文案；
   `/admin/zones/2/open` 回 409 `zone_retired`。

**第三轮：撤销被拒**

A′、C′ 先 17 离开并等归属释放（否则先报 `zone_not_quiescent`，§5.2 的检查顺序），B 保持在线；`/admin/zones/1/maintenance` → `MergeZone unmerge --run R′` 退出 1，
结果码 `played_since_merge`，findings 带 warn `dst_players_online`（B）；断言 A′ 仍是 zone 1、GA′ 仍在 zone 1（零写入）。

**场景 `redirect-on-enter`**（独立切片，`XM_LOGIN_REDIRECT_ON_ENTER=true`）

归属 zone 2、没有位置记录的角色从 zone 1 的 gate 进游戏：26 成功（不带 error_message）+ 124 指向 zone 2 的 gate → 跟随后在 zone 2 落地（79）；开关关闭时在 zone 1 落地。
沿用 5.4 `travel` 场景（`RobotOptions.Scenario.TRAVEL`，`zone-travel-spec.md` §11.11）的 124 跟随实现；账号命名沿用现有惯例（`CrossNodeScenario.accountName` 的 `<prefix><tag><runTag>_<role>`）。
「重定向那一跳不消费提示、落地那一跳才给字段 3」（§6.3 ④，D11）由 xm-login 单测覆盖：双 zone 切片里合服后 src 已 CLOSED，构造不出「带未领取提示、从别区进来再被重定向」的真实路径。

**跨版本**：基线没有合服 robot（B15），Go robot 无对应场景；把「mmorpg 补合服 robot」登记为 mmorpg 待做候选。

---

## 评审修订记录

> 完整性评审（只改本文件）。核对方式：逐条回到基线 `26ceb70ca` 与 Java 工作区源码。抽查的引用共 40 余处，确认无误的有：`main.go:3-53`、`:305`、`:310`、`:328`、`:601-609`；
> `merge_run.go:5-15`、`:171-202`、`:401-406`、`:585-648`、`:703-734`、`:839-901`、`:1044-1060`、`:1323-1368`；`fence.go:72-85`、`:103-166`、`:212-233`、`:237-294`；
> `post_merge_stamp.go:69-77`、`:97-150`；`entergamelogic.go:406-412`、`:487-548`、`:646-683`；`router.go:73-92`、`:280-300`、`:470-482`；`home_zone.go:170-200`；`errors.go:62-69`；
> `guild_step.go:1-16`；`trade_step.go:25-36`；`chat_logic.go:8-15`；`queue.go:52-60`；`config.go:195-216`；`login.yaml:205-213`；`runbook:850`、`:851`、`:859`、`:860`、`:863`；
> `PROGRESS.md:6003-6004`、`:6022`、`:6030`；`integration_test.go` 的 13 个用例行号与 `gap_fixes_test.go` 的 4 个用例行号；`GameClient.cs:645-652`、`:795-805`、`:1037-1066`；
> Java 侧 `PlayerMapper.java:36-37`、`:40-47`、`:53-100`、`xm-player-schema.sql:14`、`:31`、`EnterGameHandler.java:199-200`、`:297-302`、`:390-403`、`GuildConfiguration.java:270-278`、`:437`、
> `MergeFence.java:18-39`、`GuildAccess.java:103-125`、`GuildTip.java:69-74`、`RedisKeys.java:20-26`、`:41-47`、`:116-119`、`:138-145`、`:162-169`、`:187-190`、`:249-256`、`:274-285`、
> `AssignGateService.java:205-229`、`PlayerLocationDirectory.java:46-50`、`WorldChannelProperties.java:21`、`WorldChannelsHealthIndicator.java:40-46`、`DubboGroups.java:20-27`、
> `GuildInternalService.java:8-11`、`guild_tables.proto:32-33`、`:45`、`:77`、`trade_tables.proto:42-43`、`xm-data-schema.sql:24`、`:45`、`xm-gateway-schema.sql:5-24`、各 tip 码与 `tip_text.json:37`、`:156`、
> `login.proto:88-120`、xm-login `application.yaml:21`、`roadmap.md:93`、`PARITY.md:27`、`:36`、`:91`、`:95`、`:105`、`:107`、`:108`、`data-ops-spec.md:541-542`、`:778-779`、`:819`、`:832-848`、`:856`、`:1105`。
> 目录核对：本稿用到的基线目录在稀疏克隆里都在；Unity 客户端在另一个稀疏克隆 `D:\work\mmorpg-client`（`a8577c7`）。

### 引用改正

| # | 位置 | 原文 | 改正 |
|---|---|---|---|
| R1 | §2.5 | NOSCRIPT 理由 `architecture.md:138` | `:152`（工作区行号；`:138` 是 gate↔scene 链路一段）；§0.6 另记 `scene-channels-spec.md:1021` 的同一处旧行号 |
| R2 | §7.6 | `syncAll` 只扩不缩 `architecture.md:635-640` | `:654-658` |
| R3 | §0.3、§3.2 | 「没有玩家数据缓存层」`data-ops-spec.md:575-576`（空行与分隔线） | `:574` |
| R4 | §4.7 战斗行 | `data-ops-spec.md:390`（表格分隔线） | `:391`、`:571` |
| R5 | §9 | 事件类型 `ops_tables.proto:51-62` | `:52-64` |
| R6 | §0.7 第 21 条、§4.6 | SADD 在 `scene-channels-spec.md:798` | `:797`（`:798` 是「拉取失败只 WARN」） |
| R7 | §0.7 第 15 条 | 匹配池不分 zone 引 `match-spec.md:352` | `:352` 讲的是 battle 节点池；改为 `:359`、`:986-988` |
| R8 | §2.6 | 「闸门未配置」形态不存在 → D20 | 应为 D18（D20 是 fence-drill） |
| R9 | §3.3、§10.1 | 「`architecture.md` §7 归属协议第 4 / 5 条」 | 该节没有编号条目；改为 `architecture.md:672` 起「玩家数据归属协议」 |
| R10 | §13.6 | 「沿用 xm-robot 的 `ZonesScenario` 一族」 | 不存在这个名字；5.4 的是 `travel` 场景（`RobotOptions.Scenario.TRAVEL`，zone-travel-spec §11.11） |
| R11 | §13.1 | tools「纯函数单测，同 `GmShutdown` 的做法」 | `GmShutdown` / `TestReport` 都没有单测；改为 `MergeZone --self-test` 由 `ci.yml` 跑 |
| R12 | §7.9、§8.1 | 新配置键 `xm.data.run-mode` | 全仓已有 `xm.run-mode`（`XM_RUN_MODE`）与 xm-common `RunMode`（xm-trade、xm-battle 在用），复用 |

### 内容修订（错误或缺失）

| # | 位置 | 问题 | 修订 |
|---|---|---|---|
| C1 | §2.5、§8.1、Q28 | 续期器按 TTL/3（20 min）一拍：Redis 重启后最长 20 min 没有围栏；MISSING 直接 `restore` 会与「作业 A10 改 APPLIED 并释放」竞态，把已终结 run 的围栏补回来 | 每 10 s 一拍；补回前重读 run 状态、补回后再读一次，已终结立即释放 |
| C2 | §2.5、§7.3 | 清扫器把 PLANNED 的 run 改 ABANDONED 并释放围栏，而原执行线程可能还活着、随后改 APPLYING 开写，两者没有互斥 | run 状态的每次迁移都是 `WHERE status = 期望值` 的条件更新，影响 0 行即停 |
| C3 | §2.5、§7.3 A10、§9 | A10 / U9 / `abandon` 释放围栏失败时，围栏挡住 dst 直到 TTL（≥ 1 h），没有恢复手段 | 续期器「终态清理」：新 Lua `releaseIfStale(run, zones, 终结时刻)` 只删 `at` 早于终结时刻的围栏（不误删补扫重新立起的）；指标加 `cleaned` |
| C4 | §2.5、§10.1 第 2 条 | 「两区维护态、无 gate 令牌，实际不可达」 | 不对：别的 OPEN 区照常发令牌，归属 src / dst 的玩家可经别区进游戏，围栏是唯一挡板。改写崩溃语义与围栏丢失两条；手册要求合服期间 xm-data ≥ 2 副本，告警加「续期停摆」 |
| C5 | §2.5、§7.2 | fence-drill 的撤销用通用 `release` 按 run 删除；acquire 写成「都空或都是同一 run」，漏了「一把空、一把本 run」 | 新增 `releaseDrill`（只删 op = drill）；acquire 改为「每把要么不存在、要么本 run」；drill 传 `src = dst = zone` |
| C6 | §2.7、§0.7 第 10 条 | 宽限 15 s 被说成「> 建角链上界」；「src 无 gate 就不会再有 src 建角」 | 15 s 只是单语句上界（链上有多条语句与发号）；死 gate 已发出的在途请求照样执行。硬保证是 A8 残留并入 + A10 + verify + 补扫 |
| C7 | §2.8 | 「GONE / ELSEWHERE 只计数报告」与「全部 MOVED / ALREADY 才标完成」自相矛盾 | 按基线 `complete()` 的 fail-closed：GONE / ELSEWHERE → FAILED `manifest_incomplete`、保留围栏；撤销里只计数 |
| C8 | §2.8 | 「玩家 ALREADY 只可能是本 run 早先的尝试」 | 玩家改写与 item 回写同事务，本 run 不会留下这种状态；玩家 ALREADY 只能来自外部写者，照记 ALREADY 并出 warn `player_already_at_dst`；帮会 / 商品的 ALREADY 才是重试的正常结局 |
| C9 | §2.8、§7.4 U5、§7.6、§6.1、§6.3 | 撤销把 `merge_notice_ms` 一律清零：链式合服 3→2（未领取）→ 2→1 → 撤 2→1 会吞掉 3→2 的提示（客户端可见） | item 加 `notice_before_ms`（字段 8），改写时记录、撤销按组还原 |
| C10 | §7.6、§2.8、Q30 | 建议「必要时给 xm-data 连接串加 `rewriteBatchedStatements=true`」 | 打开后 JDBC batch 的逐条计数变 -2，§2.8 与 7.2 的「恰好 1 行」判定静默失效，且作用于整个数据源。不开；`insertAll` 在 pbmysql 里自己拼多行 INSERT |
| C11 | §3.3、§3.4、§5.2、§5.6、§0.2、§0.6、§0.7 第 5 条、D5、Q7、Q26 | dst 玩家持有归属也 block：比基线 P5 / P6（只查清单玩家）严格，作业又不踢人，dst 玩家经 5.4 在别区游玩时合服无法进行 | dst 只报 warn `dst_players_online`；理由：dst 的行不改写、写入口全被 dst 围栏挡住 |
| C12 | §3.3、§5.3 | dry-run 也等 `quiescence-wait` 60 s；彩排时两区开着必然 BLOCK 却没说明 | dry-run 不等；手册写明彩排看哪些 findings、T-0 的 plan 必须 PASS |
| C13 | §5.2、§7.3 A1 | 只查 `idx_player_zone`，且用 MySQL 专有的 `SHOW INDEX`（H2 测不了）；存量库漏跑 `merge_notice_ms` 迁移要到写阶段第一批才暴露 | A1 用 `DatabaseMetaData` 同时查索引与列，新增 `missing_migration` |
| C14 | §6.3、§13.1 | 「在 login 工作线程上消费」与代码不符：`rerouteIfStale` 重新分配时 `accepted` 在 Dubbo 回调线程上构造，阻塞的 MySQL 写会落在那条线程上 | 在 `claim()` 拿到 `Claimed`（已在工作线程）时消费，结果随 `ClaimStep` 带到 `accepted`；单测断言消费线程 |
| C15 | §6.3 ④、D11、§13 | 漏了一条客户端可见差异：基线重定向路径也走 `:412` 的消费，字段 3 落在第一跳的 26 里；Java 落在落地那一跳 | 补进 §6.3 与 D11；由单测覆盖（切片里构造不出真实路径） |
| C16 | §6.4、§7.12 | 没说明 RedirectOnEnter 在 Java 里对合服几乎无用（src CLOSED 且不许重开），也没说明重定向票据绕过区服准入 | 补充；两版共有，是缺省关的理由之一 |
| C17 | §7.3 | A5 失败的处置没写；A10 复查不为 0 时的处置没写 | A5 失败同 A1–A4（释放、ABANDONED）；A10 不为 0 回到对应步骤、计入残留轮数上限 |
| C18 | §7.3、§7.2、§5.4、D21、Q27 | APPLIED 之后才落进 src 的残留（verify block）与没做成的 A9 无路可走：`src_retired` 挡住任何新 run | `resume` 作用于 APPLIED 即「补扫」：只缺 A9 时不立围栏；有残留时两区回维护态、APPLIED → APPLYING → 并入 late item → APPLIED |
| C19 | §7.2 | `abandon` 可作用于 APPLIED 的 run，会让 src 失去「已退役」保护、可被重开 | 只接受 COLLECTING / PLANNED / APPLYING / UNMERGING |
| C20 | §7.2 | `dryRun` 没有必填约束，缺省成 false 就直接 apply | 必填、无缺省值（同基线 `main.go:601-609`）；撤销 dry-run 明确不立围栏、不等待 |
| C21 | §5.5、§10.2 | `not_latest_run` 的「最近一次未作废 run」没定义，撤掉 2→1 之后能否再撤 3→2 不确定 | 未作废 = 非 ABANDONED / UNMERGED；按 `created_ms` 取最大；刻意保守 |
| C22 | §3.6、§10.3、Q29 | 单飞只挡「同时在跑」：合服 FAILED、run 停在 APPLYING 时 7.2 作业可以受理并夺权改档 | 7.2 受理时对未终结 run 的区回 409 `zone_merging`；APPLIED 未开服时 7.2 的写会让撤销按 `played_since_merge` 拒绝（预期） |
| C23 | §7.11 | `DELETE /admin/zones/{id}` 不受限，合服中途可删掉 src / dst 的区服行 | 未终结 run 的区删除回 409；查 run 失败 503 |
| C24 | §7.5 | 「`innodb_lock_wait_timeout` 会话级设 5 s」没说在池化连接上怎么设 | xm-data 连接串没有 `sessionVariables`；借出后 `SET SESSION`、归还前恢复 |
| C25 | §2.6、D22、§13.1 | F4 依赖 xm-scene 新填的 `home_zone_id`；旧版 scene 不填（0）时「zone 0 放行」让 F4 静默失效 | 同批上线；scene-manager 对 0 按封锁（3027）并计 unreadable；xm-scene 进单测模块列表 |
| C26 | §4.7 | 「回合制战斗必在线」不准确：战斗中断线时锁与待结算留在离线玩家身上（`scene-battle-spec.md:49-50`） | 改写理由：按 pid 存、battle 是全局池、结算按位置投递，合服后照常生效；只报 warn |
| C27 | §4.6 | 聚宝斋参与方没写 `checkHolder`；没说明 xm-trade 能否读 Redis | 同样先核对围栏；xm-trade 已装配 Redisson（租约、热关停），只读围栏不新增依赖 |
| C28 | §7.12 | 停服顺序没写；没提别区仍发令牌 | 先停 src 全部 gate 再停 scene（否则 5.5 停服前疏散多挪一轮）；写明别区照常放人、xm-data ≥ 2 副本 |
| C29 | §10.1 第 6 条 | 「每批 `FOR UPDATE` 复判会挡住续约成功的情形」只对改写之前成立；`renewOwnerLeases` 不看租约是否过期 | 改写：改写之后续上的只影响 scene 内存里的 F4 归属区；真正服务客户端需要 gate 会话，在线目录检查先挡住 |
| C30 | §8.1 | run-budget 30 min 与基线 2 h 不同却没说明 | 补估算与理由（超时只在批边界停、可 `resume`） |
| C31 | §13 | 新增设计缺测试 | 补：续期器补回 / 不补回 / 终态清理、条件更新竞态、补扫、链式撤销还原提示、`zone_merging`、`missing_migration`、`dryRun` 必填、消费线程、批计数；robot 第三轮先让 A′ / C′ 离开（否则结果码是 `zone_not_quiescent`），并顺带断言 `dst_players_online` |

### 未改、但记录在案

- 基线 B1–B15 的描述与行号抽查无误，未改。
- §4.1 的 zone 列登记表与仓库现状一致（MySQL 侧只有 `player.zone_id`、`guild.zone_id`、`trade_listing.{market_zone, seller_zone_at_listing}`、`transaction_log.zone_id`、
  `player_snapshot.zone_id`、`zone_config.zone_id`、`zone_whitelist.zone_id`）；Redis 侧带 zone 的组队记录（`team_record.proto:17`、`:33`、`:41`、`:52`）由活队伍阻断覆盖，不进登记表。
- 契约影响结论（不改客户端契约、不跑 ContractSync）复核无误：新增的 `home_zone_id`、`zone_merge.proto` 都是 xm-api 的内部 proto。
