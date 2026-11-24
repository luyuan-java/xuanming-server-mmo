# 赛季技能与单槽赛季印（方案 A，`SceneSeasonClientPlayer` 453–455）Java 实现设计

## 1. 需求分析

对齐权威仓 `xuanming-server-mmo-cpp@de4107d76` 的赛季玩法（`SeasonRule` / `SeasonSeal` 配表、`Skill.season_id = 58`、`season_error` 34000–34011、`SceneSeasonClientPlayer` 453–455）：

1. **赛季免费限定技能**：当前活跃赛季的 `Skill`（`season_id == activeSeasonId`）对所有角色免费生效，随角色生效等级自动达到 `ClassSkillRules.rankLimit` 上限，不消耗银两，在 `ListSkills` 暴露 `season_id` 且禁止 `UpgradeSkill` 扣费（回 `kSeasonSkillNoUpgrade = 34010`），并自动写入战斗快照；季末切季自动退场。
2. **单槽赛季印（方案 A）**：
   - `453 GetSeasonState`（只读、战斗/冻结放行）：下发当季排期、进度点（战斗 + 追赶拆分）、轻属性加成（仅气血与防御，硬上限 `<= 500` 万分比，绝不接入速度/暴击/控制）、4 选 1 赛季印列表、追赶报价与余额、以及本季免费技能预览。
   - `454 EquipSeasonSeal`（写操作，冻结回 `34008`，战斗回 `34009` 并计 `BattleGate.SEASON`）：0 点即可战外免费切换本季赛季印，立即重算属性、推送属性面板并触发关键进度存盘。
   - `455 CatchupSeasonSeal`（写操作，幂等查账排在冻结/战斗闸之前，冻结 `34008` / 战斗 `34009` + `BattleGate.SEASON`）：支持指定补 `points_to_buy` 点或 `0` 一键补满，严格受 `seal_max_points` 硬上限与报价防呆（`34006`）保护，复用 `ClientRequestLedgerState` 32 槽环形幂等账本（`KIND_CATCHUP_SEASON_SEAL = 9`），扣款记 `TX_SEASON_SEAL_CATCHUP` 流水。
3. **战斗积分与战斗服执行**：
   - 正规 PVE 胜（`+pve_win_points`）与正常排位 1V1/5V5/PVP_TEAM（胜 `+pvp_win_points`、平/负 `+pvp_other_points`）在结算应用同帧原子累计进度（受 `seal_max_points` 夹取）；逃跑/0 回合/切磋不加点。
   - `BattleSnapshots` 开局冻结 `active_season_id`、`season_seal` 及 `season_max_health_bps`/`season_defense_bps`；`game-battle-engine` 复核 `season_id` 与 5% 硬上限，用独立运行态 `season_seal_state` 执行赛季印触发并在 `BATTLE_EVENT_ARTIFACT` 填充 `season_seal_id`（`artifact_item_id == 0`），下发公开状态前清除 `season_seal_state`。

## 2. 设计分析

- **持久化（`game-player-store` 第 10 代台账 `0010.schema`）**：
  - `ClientRequestRecordState` 追加 `season_catchup_points = 13`、`season_catchup_spent = 14`。
  - `PlayerState` 追加 `PlayerSeasonState season = 18`（按 `season_id` 索引保留历史赛季记录与未知字段，默认为空即合法初值）。
- **双端共享纯规则（`game-battle-engine`）**：
  - 新增 `SeasonRules`（纯静态方法，不查表不取时钟），供 `game-battle-engine` 与 `game-scene` 共用校验与线性换算。
  - `BattleEquipment` 通过哨兵下标 `-1`（`SEASON_SEAL_INDEX`）将单槽赛季印接入全部 8 类法宝机制，事件填 `season_seal_id` 且独立记账 `BattleActorState.season_seal_state`。
  - `TurnBattleEngine.initPlayers` 按 `snapshot.getActiveSeasonId()` 复核 `skill.getSeasonId()`；`stripEngineOnlyState` 清除 `season_seal_state`。
- **场景侧装配（`game-scene`）**：
  - 新增 `PlayerSeason`、`SeasonTables`、`SeasonService`、`SeasonFeature`，接入 `ScenePlayer`、`AttributeService`（`withSeasonEffects` 可选钩子）、`ClassSkillCatalog`/`ClassSkillService`/`ClassSkillFeature`、`BattleSnapshots`、`BattleSettlementService`、`SceneNode`/`SceneNodeConfiguration`。

## 3. 思想分析

遵循 Java 21 不可变配表视图与组合根装配原则：纯规则下沉到无依赖 `game-battle-engine`（避免 Scene 与 Battle 规则漂移），场景侧保持单逻辑线程无锁原子提交（扣灵石 + 改进度 + 记幂等回执 + 重算面板 + 请求存盘在同步帧内完成），所有新增接口通过可选注入或构造重载保持既有单测与旧存档零破坏。

## 4. 漏洞分析

- **连点/弱网重放双扣灵石**：`CatchupSeasonSeal` 强制 `client_request_id != 0` 且幂等查账排在冻结/战斗闸之前，重放直接返回首次 `added_points` 与 `spent_currency`（`replayed = true`），同键异参回 `1005`。
- **结算瞬间报价漂移**：校验 `expected_currency_type` 与 `expected_total_price` 以及 `points_to_buy <= remaining`，不符回 `34006` 并附最新 `state` 供前端刷新。
- **配表误填超标属性或重叠赛季**：`SeasonTables` 与 `SeasonRules.validateRule` 强制 `max_health_bps_cap <= 500`、`defense_bps_cap <= 500`、启用窗口不重叠、默认印存在且合法，不合法时 fail-closed（当季不开放）。
- **跨季或伪造快照**：战斗开局冻结 `active_season_id` 与 `season_seal`，引擎独立复核 `season_id` 与 5% 硬上限；切季后旧赛季技能自动退出技能书与战斗快照，历史记录只读归档不提供数值。

## 5. 步骤分析

1. 扩展 `player_state.proto` 并用 `PersistentSchemaTool append` 生成第 10 代持久化台账 `0010.schema`，通过 `PlayerStateSchemaLedgerTest` 并安装 `game-player-store`。
2. 在 `game-battle-engine` 实现 `SeasonRules` 并接入 `BattleEquipment` / `TurnBattleEngine`，新增 `SeasonBattleEngineTest` 并安装 `game-battle-engine`。
3. 在 `game-scene` 新增 `PlayerSeason`、`SeasonTables`、`SeasonService`、`SeasonFeature`，接入 `ScenePlayer`、`SceneMetrics`、`AssetAudit`、`KafkaAssetAudit`、`PlayerClientRequestLedger`、`AttributeService`、`ClassSkill*`、`BattleSnapshots`、`BattleSettlementService`、`SceneNode`、`SceneNodeConfiguration`。
4. 更新 `BattleFixture`、`InBattleGateMatrixTest`、`SceneAuditPolicyTest` 并新增 `SeasonFeatureAndServiceTest`。
5. 运行受影响模块回归与 `game-robot` 门禁，更新 `PARITY.md` 并推送 `origin/main`。

## 6. 执行步骤

- [x] 编写设计文档 `docs/design/season-skills-and-seal.md`
- [x] 扩展 `game-player-store` 第 10 代台账 `0010.schema` 并通过校验（`PlayerStateSchemaLedgerTest` 1/1 PASS）
- [x] 实现 `game-battle-engine` 赛季规则、赛季技能复核与赛季印触发机制（全模块 2,414/2,414 PASS，1 外部跳过）
- [x] 实现 `game-scene` 赛季服务/处理器（453–455）与属性/技能/战斗快照/结算全链路接线，同步补齐场景 5（碧渊水宫）新版导航加载
- [x] 更新闸表夹具、新增单测并通过全部回归与门禁（`game-scene` 定向 307/307、`game-robot` 门禁 35/35），推送 `origin/main`
