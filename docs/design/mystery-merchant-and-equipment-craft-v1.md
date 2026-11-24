# 神秘商人（314/315/316）与装备打造及材料自选（404–408）设计

## 1. 需求分析

完成当前已同步契约（`5c0a518a6`）在 `game-scene` 中最后两组客户端 RPC：

1. **神秘商人（`SceneMysteryMerchantClientPlayer` 314 `GetMysteryMerchantInfo` / 315 `BuyMysteryMerchantGoods` / 316 `NotifyMysteryMerchant`）**：
   - `scene_manager` 写入本 zone 权威轮次状态至共享 Redis Hash `<ns>:world:{z:<zone>}:mystery-merchant`（字段 `state`）；`game-scene` 节点缓存当前轮次并广播 316，响应 314 只读查询（仅与商人同线时返回 `here=true` 与货架 `goods`）和 315 购买（报价防呆、距离校验、个人每轮限购、个人+全服禁获拦截、先发物后扣款免退款回路、`client_request_id` 持久幂等重放）。
2. **装备打造与材料自选（`SceneBagClientPlayer` 404–408）**：
   - `GetEquipmentCraftRules`（404）：按 `Item.equip_profile`（1/2/3）、`EquipV1Affix` 与 `EquipArtifact` 配表产出配方、词条列表、SHA-256 配置指纹 `rules_fingerprint` 与玩家持久水位 `forge_revision`。
   - `PreviewEquipmentCraft`（405）：预览打造新装备（`CREATE_ITEM`）或首次安装固定词条（`INSTALL_AFFIX`，禁覆盖已占槽、禁同件重复词条），展示产物与材料持有/可用数及可用性。
   - `CommitEquipmentCraft`（406）：强制非 0 `client_request_id` 持久幂等、`expected_forge_revision` 乐观防重（回执淘汰后水位已变回 `UNKNOWN + 28022`）、`rules_fingerprint` 核对、原子扣料造物/装词条、递增 `forge_revision`、穿戴件装词条同步重算属性并推面板。
   - `GetEquipmentMaterialReward`（407）与 `ClaimEquipmentMaterialReward`（408）：权威战斗结算按 UTC+8 05:00 游戏日累计每日上限 20 份（单局 +10 份，跨日保留余额）；408 按 `expected_revision` 与 `client_request_id` 幂等自选领取打造材料（3001）与强化石（3002）。

## 2. 设计分析

- **共享持久化组件（复用第 9 代 `player_state.proto` 字段 16/17）**：
  - `PlayerClientRequestLedger`（`com.game.scene.player`）：封装 `ClientRequestLedgerState`（最多 32 条回执、按 `seq` 淘汰最旧、SHA-256 前 16 字节参数指纹、`forge_revision`、`material_reward` 账本与未知字段保留）。
  - `PlayerMysteryMerchant`（`com.game.scene.player`）：封装 `PlayerMysteryMerchantState`（同轮累加已购数、跨轮 `clear` 逻辑重置、同轮保留未知字段）。
- **神秘商人（`com.game.scene.merchant`）**：
  - `MysteryMerchantTables` 加载 `MysteryMerchantRule` / `MysteryMerchantGoods` / `Item`，统一折后价公式 `ceil(price * percent / 100)` 与合法性校验。
  - `MysteryMerchantService` 维护本节点缓存的 `MysteryMerchantState`，校验 `ShouldDiscardAsStale`（手里未过期轮次拒收旧版本），实现 `buildInfo` 与 `buy`（严格按「幂等重放 → 冻结 30008 → 战斗 30007 → 份数 30009 → 在场 30000 → 轮次/货架/报价 30003 → 同线 30001 → 距离 30002 → 限购 30004 → 禁获 30010 → 余额 30005 → 满包 30006 → 发物 → 扣款 → 记限购 → 记幂等」顺序）。
  - `MysteryMerchantFeature` 注册 314（`READ_ONLY, ALLOW`）与 315（`GATED, GATED`，战斗闸记 `BattleGate.MYSTERY_MERCHANT`）。
  - `SceneNode` 启动后台轮询读取 `redisKeys.mysteryMerchant(zoneId)`，新版本在逻辑线程广播 316。
- **装备打造与材料自选（`com.game.scene.equip`）**：
  - `EquipmentDisplay` 补齐 `equip_profile`、`fixed_affix_slots` 与 `artifact` 字段填充。
  - `EquipmentCraftService` 实现 404–408 及战斗结算材料额度累计 `creditBattleMaterialReward`（接入 `BattleSettlementService` 并写入 `BattleResultEntryState.material_credit_units_added`）。
  - `EquipmentFeature` 注册 404/405/407（`READ_ONLY, ALLOW`）与 406/408（`GATED, GATED`，战斗闸复用 `BattleGate.EQUIPMENT`）。

## 3. 思想分析

- **先发物后扣款（神秘商人）**：`BagService.addItems` 具备全或无候选语义，先发物失败直接拒且零资产变动，避免先扣款后因发物失败退款撞上欠款抵扣或币种禁获。
- **回执与水位双保险（打造与材料自选）**：32 条回执环提供精确重放；当旧回执被淘汰时，独立单调水位 `forge_revision` / `material_reward.revision` 拦截过时重试并返回 `UNKNOWN`，杜绝重复扣料或重复领奖。
- **零反射单线程状态机**：所有玩家状态变更均在 `game-scene` 逻辑线程同步完成，与现有 `SceneWorld` 存盘围栏无缝一致。

## 4. 漏洞分析

- **同键换参重放**：若客户端误复用旧 `client_request_id` 购买其他商品或打造其他装备，指纹校验（SHA-256 前 16 字节）直接拒绝 `kInvalidParameter (1005)`。
- **配表热更导致报价/规则漂移**：神秘商人购买强制核对四项 `expected_*` 报价，不符回 `30003`；装备打造核对 `rules_fingerprint`，不符拒 `1005`。
- **战斗中重放与新操作区分**：重放命中幂等账本排在战斗/冻结闸之前，保证已成交的断线重试不误报失败；未命中账本的新写操作严格走冻结/战斗闸。
- **时钟回拨刷材料日上限**：`creditBattleMaterialReward` 仅在游戏日起点严格递增时清零 `earned_today`，时钟回拨时不重新开放当日额度。

## 5. 步骤分析

1. 新增 `PlayerClientRequestLedger` 与 `PlayerMysteryMerchant` 并接入 `ScenePlayer`。
2. 在 `SceneMetrics.BattleGate` 追加 `MYSTERY_MERCHANT`，在 `AssetAudit.Reason` / `KafkaAssetAudit` 追加 `MYSTERY_MERCHANT_BUY` 与 `EQUIP_CRAFT`。
3. 实现 `com.game.scene.merchant.{MysteryMerchantTables,MysteryMerchantService,MysteryMerchantFeature}`，并在 `SceneNode` 接入与配置轮询广播。
4. 扩展 `EquipmentTables`、`EquipmentDisplay`、`BattleSettlementService`，实现 `EquipmentCraftService` 并在 `EquipmentFeature` 注册 404–408。
5. 更新 `BattleFixture`、`FreezeGateTest`、`InBattleGateMatrixTest`，新增 `MysteryMerchantAndEquipmentCraftTest` 覆盖端到端主链路与边界。
6. 运行 `game-scene` 测试并安装，通过 `game-robot` 门禁，更新 `PARITY.md` 并推送。

## 6. 执行步骤

- [x] 编写设计文档 `docs/design/mystery-merchant-and-equipment-craft-v1.md`
- [x] 实现共享幂等账本与神秘商人限购持久化包装并接入 `ScenePlayer`
- [x] 实现神秘商人（314/315/316 + `game-scene-manager` 领导者调度器）与装备打造/材料自选（404–408）
- [x] 更新闸表夹具与新增回归单测并全绿通过（`game-scene` 定向 271/271，`game-scene-manager` 6/6）
- [x] 安装 `game-scene` / `game-scene-manager`、通过 `game-robot` 门禁、更新 `PARITY.md` 并推送 `origin/main`
