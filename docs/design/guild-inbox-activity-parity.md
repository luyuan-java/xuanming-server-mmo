# 帮会残余（14042/积分版本/删角让位）、收件箱物品封锁与活动活跃度领奖设计

## 需求分析
围绕 `PARITY.md` 剩余的 5 项高频业务对账待做条目，在当前契约基线上用最小、原生的 Java 21 实现全部收口：
1. **帮会历练被 match 作废回 `14042`（`game-guild`）**：`GuildActivityStore.closeTerminalLobby` 在有签名取消回执且 `battle_id != 0`（对局已建但被作废）时写入 `kGuildTrialBattleAborted`（`14042`），仅在 `battle_id == 0`（开战未成）时写 `kGuildTrialServiceBusy`（`14041`）。
2. **帮会 `score_version` 原子递增（`game-guild`）**：`GuildStore` / `JdbcGuildStore` 补齐 `updateGuildScore(guildId, score, deadline)`，在 `guild` 行锁同一事务内原子写 `score` 并将 `score_version + 1`，提供带版本号的 `VersionedScore` 查询，不破坏既有 `INSERT_GUILD` / `SELECT_ALL_SCORES` 护栏。
3. **账号注销帮会收尾：帮主自动让位或解散（`game-guild`）**：`GuildStore.purgeDeletedPlayer` 在账号注销执行期自动移除已删角成员；若删角者为帮主，按「职位最高 → 累计帮贡最多 → 入帮最早 → player_id 最小」自动让位给下任帮主，仅剩帮主一人时自动解散帮会并清理相关记录。
4. **收件箱 GM 物品获取封锁 / 解封（类型 5 / 6，`game-player-store` + `game-inbox` + `game-scene`）**：在 `PlayerState` 第 8 代持久台账追加 `ItemGainBlockState`（字段 13），`BagService.addItems` 同时核对全局与个人封锁清单，`InboxConsumers` 注册 `GM_BLOCK_ITEM_GAIN` / `GM_UNBLOCK_ITEM_GAIN` 执行器，`InboxRegistry.V1_DISABLED` 解禁类型 5 / 6。
5. **活动中心活跃度快照与领奖（`SceneActivityClientPlayer.ClaimActivityVitalityReward` + `GetActivityList.vitality`，`game-player-store` + `game-scene`）**：在 `PlayerState` 第 8 代持久台账追加 `ActivityVitalityState`（字段 14），实现周期计算、一次性活动任务位图永久去重、积分上限截断、原 `request_id` 优先重放、`kRewardPeriodEnded`（`12001`）、已结束周期领取记录 64 条裁剪窗口与原子发奖。

## 设计分析
- **`game-guild`**：
  - `GuildActivityStore.cancelledLobbyEndTip(long battleId)` 纯函数区分 `battleId != 0 → 14042` 与 `battleId == 0 → 14041`。
  - `JdbcGuildStore.updateGuildScore` 使用 `SELECT zone_id, score_version FROM guild WHERE guild_id = ? FOR UPDATE` 与 `UPDATE guild SET score = ?, score_version = ? WHERE guild_id = ?`，单事务强一致推进版本号。
  - `JdbcGuildStore.purgeDeletedPlayer` 先查成员所在帮会，事务内按 `G → M` 锁序核验；若为帮主则按 `ORDER BY role DESC, total_contribution DESC, join_time_ms ASC, player_id ASC LIMIT 1` 选定继任者提拔为 `role=3` 并改 `guild.leader_id`，无继任者则删成员、删申请、删帮会。
- **`game-player-store`**：
  - 在 `player_state.proto` 的 `PlayerState`（已在持久台账 `ALLOW_ADDITIONS` 内）追加字段 13 `ItemGainBlockState` 与字段 14 `ActivityVitalityState`，通过 `PersistentSchemaTool append` 登记第 8 代 `0008.schema`。
- **`game-inbox` & `game-scene` 物品获取封锁**：
  - `ScenePlayer` 持有 `PlayerItemGainBlock`（维护有序 `blockedConfigIds` 与未知字段往返），`BagService.addItems` 放行前先判全局与个人封锁；`InboxConsumers.gmSanction(currency, bags)` 接入类型 5/6 执行器，并在 `SceneNode` 生产装配启用。
- **`game-scene` 活动中心活跃度**：
  - `MissionTables` 从 `ConfigTables` 加载 `ActivityVitalityRuleTable`、`ActivityVitalityRewardTable` 与物品展示信息。
  - `ActivityVitalityService` 对齐 C++ `PlayerActivitySystem`：`onMissionCompleted` 与任务完成同帧记来源并置去重位、裁剪旧周期来源；`claim` 先校验 `request_id` 重放（优先于周期过期/规则变化），再判重领（`kRewardAlreadyClaimed`）、再判跨周期过期（`kRewardPeriodEnded = 12001`）、再裁剪旧周期领取记录保留最新 64 条、最后走 `BagService.addItems` 原子入包后记账。
  - `ActivityFeature` 在 `GetActivityList` 填充 `vitality` 快照并注册 `ClaimActivityVitalityReward`（声明 `FreezePolicy.GATED` 与 `BattlePolicy.ALLOW` 以便原请求重放，由 `ActivityVitalityService` 在非重放写入路径严格拒绝冻结与战斗中）。

## 思想分析
- **单事务强一致与台账可演进**：新增持久字段严格追加在 `PlayerState` 根闭包并用 `PersistentSchemaTool` 登记第 8 代快照，保证滚动升级与回滚不丢数据。
- **不破坏存量护栏与构造器**：`GuildSqlShapeTest`、`FreezeGateTest`、`InBattleGateMatrixTest`、`InboxConsumers` 旧重载均保持兼容或同步覆盖。
- **存储有界防膨胀**：活跃度积分来源仅留当前周期，旧周期领取记录固定裁剪到最新 64 条，一次性任务去重使用紧凑位图，确保单玩家存档体积长期有界。

## 漏洞分析
1. **重放请求被冻结或跨日误拒导致重复领奖/丢确认**：`ClaimActivityVitalityReward` 先查 `request_id` 命中原记录，匹配则直接回 `replayed=true`（不碰背包、不改状态）；身份不一致则拒 `kInvalidParameter`。
2. **发奖失败（满包/物品封锁）提前消耗领取权**：先调 `BagService.addItems` 原子预检与入包，只有返回 `tip == 0` 才写入 `claims` 记录。
3. **账号注销帮主卡死**：若删角者是帮主且直接退帮会触发 `LEADER_CANT_LEAVE` 导致注销死锁 → `purgeDeletedPlayer` 在同一事务内完成继任者选拔与提拔或单人帮解散。
4. **收件箱载荷 `item_config_id == 0` 误封全部物品**：`InboxConsumers` 强制拒绝 `item_config_id == 0` 为 `Rejected(kPlayerInboxPayloadInvalid)`。

## 步骤分析
1. 实施 `game-guild`（14042、`score_version`、`purgeDeletedPlayer`）并跑全量单测安装。
2. 扩展 `player_state.proto`，经 `PersistentSchemaTool` 生成 `0008.schema`，更新 `PlayerStateSchemaLedgerTest` 并安装 `game-player-store`。
3. 解除 `game-inbox` `V1_DISABLED` 中的类型 5/6 并验证安装。
4. 在 `game-scene` 实施个人物品获取封锁（`PlayerItemGainBlock` + `BagService` + `InboxConsumers`）与活动活跃度（`PlayerActivityVitality` + `ActivityVitalityService` + `ActivityFeature`），更新 `FreezeGateTest` / `InBattleGateMatrixTest` 并全量回归。
5. 更新 `PARITY.md` 并提交推送。

## 执行步骤
1. 已核对 `GuildActivityStore`、`JdbcGuildStore`、`PlayerStateSchemaLedgerTest`、`InboxRegistry`、`BagService` 与 C++ `player_activity.cpp`。
2. 依次落码并验证 `game-guild` → `game-player-store` → `game-inbox` → `game-scene`。
3. 更新 `PARITY.md`，校验 `game-robot` 门禁并推送到 `origin/main`。
