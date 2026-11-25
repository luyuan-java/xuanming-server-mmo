# 核心玩法 V1 收口、统一经济、五行试炼志 V2、装备品相制书与交易风控 Java 对齐设计

> 对应 C++/Go 权威仓 `xuanming-server-mmo-cpp` 提交 `5676d207a` / `5d96a4f79` / `3759fdad7` 与设计文档：
> `core-gameplay-loop.md`、`season-pvp-pve-economy-unified-v1.md`、`long-term-progression-v2.md`、`equipment-quality-and-skill-crafting-v1.md`。
> 所有客户端契约（proto 142 份、配表 68 张）已通过 `ContractSync` 同步至本仓 `game-proto` / `game-table` / `config-data`。

## 1. 需求分析

在不引入任何冗余第三方依赖、严守 Java 21 惯用法与双版本契约零偏差的前提下，完成以下 5 个里程碑的权威服务端对齐：

1. **持久层第 11 代升级 + 核心玩法 V1 定稿收口**：
   - `PlayerState` 一次性扩至第 11 代台账（`0011.schema`），涵盖绑定标记 `BagItemState.bound`、起步首宠 `PetState.starter_pet_granted`、强化保底与宗师署名 `EquipmentState`、`EquipmentFixedAffixState` 品相与打磨保底、两阶段材料总账、近期战果试炼志/材料字段、客户端幂等记录 15–29、`PlayerTrialJournalState` 与 `PlayerCraftState`。
   - 正式人宠会武（`PVP_TEAM`）使用本人真实养成（`use_free_preset=false`、禁临时竞技宠），与免费 3×3 预设娱乐轮换（护卫救援 `Dungeon 109` / 物法易势 5V5 `Dungeon 110`，`use_free_preset=true`）解耦。
   - `PlayMode.allowed_team_sizes` 白名单生效（`PVP_TEAM` 默认 `[1, 3]`），`RankedSeasonInfo` 补齐 20–23 字段；`PrepareBattleRequest` 透传 `battle_config_id` / `match_mode` 并逐人强校困难首领试炼门禁；新号进场景一次性发放并出战 1 只灵狐（`pet_table_id=1`）。
2. **统一经济 + `+11` 强化保底 + 首领试炼 / 护卫救援 / 物法易势 5V5**：
   - 四基础装唯一 `+11`（`id=1011`）10% 概率不掉级、20 次硬保底与 `expected_pity_count` 幂等校验。
   - 装备材料两阶段总账（阶段一永久 660 含至多 60 可交易 + 阶段二每周 100 全绑定 UTC+8 周一 05:00 重置，PVP 负/平反挂机门槛）。
   - 三首领试炼（`Dungeon 101–108` 预告/慢速重击/回合上限）、护卫救援（`Dungeon 109` 保护 NPC）、物法易势 5V5（`MATCH_MODE_POLARITY_5V5=8` / `Dungeon 110` 物法极性交替）。
3. **五行试炼志 V2 全三卷（456–458）与 M901「星阵宗师」**：
   - `game-match` 编译并冻结 `TrialContext`；`game-battle-engine` 冻结权威战斗事实（双阵营物/法直伤、破灵障、护卫存活、断愈/驱散等）；`game-scene` 实现三张试炼志表、`TrialJournalService`、RPC 456–458、永久 24 印位图与卷三按季隔离、`M901` 授形 `CharacterAppearance id=11`、绑定发奖 `3403`/`3404` 及审计类型 `36`/`37`。
4. **装备 5 档品相打磨/重铸 + 分卷宗永久技能制书 + 3 叠层战法 + 宗师原子代打（459–464）**：
   - 3 个回合叠层成长战法（`12020/12021/12022`，Buff `120200/120210/120220`，Modifier 18–20 加算、5 层封顶、不可驱散、复活保留、守 60% 减伤红线）。
   - 装备 5 档品相（80%–100%，`G02` 速度豁免）、只升不降 + 第 8 次保底打磨（`POLISH_AFFIX=4`）、`TRADEABLE_ONLY` 防绑定染毒（`28027`）、无损换词条（`REFORGE_AFFIX=3`）、`SkillScrollCraft` 3 卷制书/研习/拆页与 3 档手艺 + 原子代打摊位（459–464，审计 `38`/`39`/`40`）。
5. **装备交易风控与卖家信用（`game-trade` / `game-mall`）**：
   - 移植 `player_trade_guard`、`reference_price`、`seller_credit` 与寄售风控拦截，完成全仓回归与门禁验收。

## 2. 设计分析与架构思想

- **单一真相源与不可变消息边界**：持久化状态只落在 `game.storage.PlayerState` 与 SQL 权威表，不在多处缓存显示态；跨模块（`match ↔ scene ↔ battle ↔ audit`）全部经不可变 proto 消息传递，场景逻辑状态严守单逻辑线程读写。
- **零历史兼容包袱、严格未知字段保留**：所有新增持久字段遵循 proto3 向前兼容与 `PersistentSchemaLedger` 台账登记规范；允许新增字段的 message 均具备领域层 `restore → modify → toState` 未知字段往返单测证据。
- **位图存储优先**：试炼志永久战法印与永久里程碑使用紧凑 `bytes` 位图（按导表 `cfg_bit_index` 映射），有效数按当前有效配表行遍历统计，严禁裸字节 popcount；卷三赛季数据按 `season_id` 独立归档，绝不混入永久位图。
- **原子提交与防染毒**：背包按 `(configId, bound)` 严格分堆隔离、普通消耗优先扣绑定堆、交易/可交易装备默认纯可交易扣料；装备打造/打磨/制书/试炼志领奖均在候选快照预检全部通过后同帧提交并写入幂等账本，失败零副作用。

## 3. 漏洞分析与关键风险防护

1. **绑定物品洗白风险**：若背包合并或扣除未按 `(configId, bound)` 隔离，或 `AssetOpService`/`BagService.prepareDebit` 未拦截 `bound=true`，自用绑定材料或拓印法宝可流入交易行。对策：在容器层 `Bag` 硬隔离 `(configId, bound)`，`prepareDebit` 严拒绑定堆与绑定实例，可交易装备打磨未显式传 `allow_bound_materials=true` 时仅扣可交易堆并回 `28027`。
2. **正式排位与免费预设混线风险**：若 `use_free_preset=false` 的正式排位仍放行 `competitive_temporary=true` 临时宠，或 `RankedPresetRules` 拒掉 `season_id=0, use_free_preset=true` 的娱乐局，将导致排位作弊或娱乐场开局失败。对策：引擎与场景以 `ranked_context.use_free_preset` 为唯一预设开关，与 `season_id` 解耦；隔离结算统一以 `competitive_ranked || use_free_preset` 判定。
3. **困难首领试炼越级与跨季污染**：组队开战必须对每个成员独立校验 `normal_boss_trial_cleared`；卷三赛季试炼志按 `active_season_id == entry.season_id` 强校，跨季不记旧印。
4. **幂等账本与未知字段退化**：新增 `ClientRequestRecordState` / `BattleResultEntryState` / `BagItemState` / `EquipmentFixedAffixState` / `EquipmentMaterialRewardLedgerState` 必须经 `PersistentSchemaLedger` 第 11 代校验并补齐领域往返单测。

## 4. 步骤分析与执行步骤

1. **里程碑 1（持久层第 11 代 + 核心玩法 V1 收口）**：扩展 `player_state.proto` 并追加 `0011.schema` → 实现 `BagItem.bound` 分堆/优先扣绑定/交易拦截 → 实现 `use_free_preset` 解耦、`PlayMode.allowed_team_sizes`、`RankedSeasonInfo` 20–23、`starter_pet_granted` 灵狐与 `normal_boss_trial_cleared` 困难门禁 → 编译测试并提交推送。
2. **里程碑 2（统一经济 + `+11` 保底 + 首领试炼 / 护卫救援 / 物法易势 5V5）**：扩展 `game-scene` 装备强化保底与两阶段材料总账 → 扩展 `game-battle-engine` / `game-battle` / `game-match` 首领预告/慢速重击/护卫救援/物法易势 → 编译测试并提交推送。
3. **里程碑 3（五行试炼志 V2 全三卷与 456–458）**：扩展 `game-audit` `TX 36/37`、`game-match` `TrialContext`、`game-battle-engine` 权威事件事实、`game-scene` `TrialJournalTables`/`Service`/`Feature` 与 `M901` 授形 → 更新门禁测试、编译测试并提交推送。
4. **里程碑 4（装备品相打磨/重铸 + 技能制书 + 3 叠层战法 + 宗师代打 459–464）**：扩展 `game-audit` `TX 38–40`、`game-battle-engine` 叠层战法、`game-scene` 5 档品相打磨/重铸、分卷宗制书与原子代打 → 更新门禁测试、编译测试并提交推送。
5. **里程碑 5（装备交易风控与卖家信用 + 全仓验收）**：移植 `game-trade` 与 `game-mall` 风控/参考价/卖家信用 → 跑全仓关键测试、`NamespaceLiteralGuardTest`、`SliceScriptsTest`、`StackCheck`、`PlayerStateSchemaLedgerTest` → 更新 `PARITY.md` 并提交推送。
