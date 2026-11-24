# 场景临时背包取回（374）、近期战斗结果恢复（371/372）与落地同步摘要（253/254）设计

## 1. 需求分析

对照 `xuanming-server-mmo-cpp` 权威契约与 `PARITY.md`，`game-scene` 尚有三组客户端直连场景能力待补齐：

1. **临时背包取回（`SceneBagClientPlayer.RetrieveTemporaryItems`，374）**：主背包满时战斗掉落落入 `BagType.TEMPORARY`；玩家需支持按单件 `item_id` 或 `take_all = true` 原子搬回人物背包，同时遵守「交接冻结拒 `6017`、战斗中放行」契约，避免因两包皆满导致战斗结算永久延后死锁。
2. **近期战斗结果恢复（`SceneBattleResultClientPlayer.GetRecentBattleResults` 371 / `AckBattleResults` 372）**：结算应用同帧将实际入账的本人战果记入玩家存档（至多 10 条，按 `result_seq` 单调递增），且仅在随玩家存档确认落盘后才对客户端可见并可确认已读，供重登/重连后补看战报明细。
3. **落地按域版本摘要（`SceneSceneClientPlayer.GetSyncDigest` 253 / `NotifySyncDigest` 254）**：客户端进场后拉取玩家在社交/帮会等外部域的版本摘要，读自 Redis Hash `<ns>:player:{<player_id>}:sync_digest`（`g` 代次 + 十进制域号计数）；Redis 不可用或应答畸形时 fail-closed 推 `SYNC_DIGEST_STATUS_UNAVAILABLE`，触发客户端全量拉取。

同步在本轮 `player_state.proto` 第 9 代持久 schema 台账中一并追加 `battle_results = 15`、`client_request_ledger = 16`、`mystery_merchant = 17`，供本批战果恢复与下一批神秘商人/装备锻造复用，避免频繁递增台账代数。

## 2. 设计分析

- **临时背包取回（374）**：
  - `BagService.retrieveTemporary(player, itemId, takeAll)`：参数二选一互斥校验（`(itemId != 0) == takeAll` → `1005`）先于状态检查；交接冻结返回 `kBagTemporaryRetrieveBusy (6017)`；战斗中放行；`takeAll` 时临时格为空幂等成功；单件不存在回 `kBagTemporaryItemNotFound (6015)`；在 `PlayerBags.restore` 候选副本上整批原子搬运（可叠加且无装备/未知字段的物品先并入人物背包未满同配置堆、余量带原 `item_uuid` 落空位；不可叠加/装备原样 `putInstance` 保留 guid/词条/强化/宝石），任一放不下回 `kBagTemporaryRetrieveBagFull (6016)` 且两包零改动。
  - `BagFeature` 注册 `RetrieveTemporaryItems`（`FreezePolicy.GATED`, `BattlePolicy.ALLOW`），成功回 `INVENTORY` 与 `TEMPORARY` 全量快照。
- **近期战斗结果（371/372）**：
  - `PlayerBattleResults`（`game-scene`）管理 `BattleResultHistoryState`：维护 `last_result_seq` 单调递增、按 `battle_id` 去重、最多保留 10 条（超限按最小 `result_seq` 淘汰）、物品明细按 `item_table_id` 升序最多 32 行并置 `lines_truncated`、未知字段透传。
  - 公开与已读可见性：比对 `player.persistedState()` 中已落盘的 `battle_results` 集合，未确认落盘的条目视为 `hidden`，对 371 不可见且 372 不予标记，确保与 `BattleEndS2C` 同公开时机。
  - `BattleSettlementService` 在 `applyInbox` 与 `applyOnce` 结算提交后同帧调用 `recordBattleResult(...)` 写入 `player.battleResults()`。
  - `BattleResultFeature` 注册 `GetRecentBattleResults`（`READ_ONLY`, `ALLOW`）与 `AckBattleResults`（`GATED`, `ALLOW`；超过 10 条回 `kArraySizeTooLargeInMessage (1015)`，冻结回 `kFeatureUnavailable (1006)`）。
- **落地同步摘要（253/254）**：
  - `RedisKeys.playerSyncDigest(playerId)` 返回 `<ns>:player:{<player_id>}:sync_digest`，受部署命名空间隔离守卫约束。
  - `SyncDigestParser.parse(Map<String, String>)` 严格对齐 C++ `ParseSyncDigestFields`：空 Map 返回 `OK(generation=0)`；非空缺 `g`、或 `g == 0`、或非规范十进制无符号整数、或已知域值非法均判畸形并返回空触发 `UNAVAILABLE`；未知域字段向前兼容跳过。
  - `PlayerSyncDigests` 异步接口 + `RedisPlayerSyncDigests`（Redisson `RMap.readAllMapAsync` 回调切回逻辑线程，按 `playerId` 重查在场实例防已销毁/换人串推），注册 `GetSyncDigest` 于 `ClientRequestHandler.registerCore()`（`FreezePolicy.READ_ONLY`, `BattlePolicy.ALLOW`）。

## 3. 思想分析

- **全或无候选提交**：临时背包搬运沿用 `BagService` 的「克隆候选→验证搬运→一次切换」范式，避免部分搬运后满包回滚复杂度。
- **单源持久化可见性**：近期战斗结果直接以 `ScenePlayer.persistedState()` 为已落盘单一真相，无需维护额外的内存待提交集合，也避免与存盘回调竞态漂移。
- **Fail-closed 与零阻塞**：同步摘要在异步线程读取 Redis、回调切回逻辑线程重查实体；任何连接异常、畸形字段或无摘要读源一律推 `SYNC_DIGEST_STATUS_UNAVAILABLE`，客户端安全退化为全量拉取且不会挂死等待。

## 4. 漏洞分析

- **战斗中临时格死锁**：若 374 对战斗中拒绝，两包满导致结算延后时玩家无法腾位；按 C++ 评审结论放行战斗中取回，且仅增不减不破坏战斗消耗夹紧安全。
- **未落盘战果提前曝光**：若结算写内存后立即在 371 返回，崩溃回档后玩家会看到未生效奖励；以 `persistedState()` 过滤 `hidden` 彻底堵住。
- **同步摘要字段契约漂移 / 串推**：接受前导零（如 `"04"`）或缺失 `g` 会破坏代次重置保证，异步返回时直接复用旧对象句柄会导致离场后误推；严格规范十进制校验 + 回调按 `playerId` 重查实体与会话规避两类风险。
- **跨包命名空间硬编码**：所有 Redis 键收口于 `RedisKeys`，满足 `NamespaceLiteralGuardTest`。

## 5. 步骤分析

1. 扩展 `player_state.proto`（字段 15/16/17），通过 `PersistentSchemaTool append` 生成 `0009.schema` 并更新 `PlayerStateSchemaLedgerTest`，编译安装 `game-player-store`。
2. 在 `game-discovery` `RedisKeys` 新增 `playerSyncDigest(long)` 并安装 `game-discovery`。
3. 在 `game-scene` 实现：
   - `BagService.retrieveTemporary` 与 `BagFeature`（374）；
   - `PlayerBattleResults`、`BattleResultFeature`（371/372）及 `ScenePlayer`/`BattleSettlementService`/`SceneNode` 接线；
   - `SyncDigestParser`、`PlayerSyncDigests`、`RedisPlayerSyncDigests`、`SceneMessageIds`、`SceneWorld`/`ClientRequestHandler`（253/254）接线。
4. 更新 `BattleFixture`、`FreezeGateTest`、`InBattleGateMatrixTest`，新增 `TempBagBattleResultSyncDigestTest` 覆盖全部正常与异常路径。
5. 运行测试、安装 `game-scene`、运行 `game-robot` 门禁、更新 `PARITY.md` 并提交推送。

## 6. 执行步骤

- [x] 编写设计文档 `docs/design/scene-temp-bag-battle-result-sync-digest.md`
- [x] `game-player-store` 追加第 9 代持久 schema 台账 `0009.schema` 并通过 `PlayerStateSchemaLedgerTest`
- [x] `game-discovery` 补齐 `RedisKeys.playerSyncDigest` 与 `RedisKeys.mysteryMerchant`
- [x] `game-scene` 实现 374、371/372、253/254 并更新闸表与单测
- [x] 验证通过、`game-robot` 门禁全绿、更新 `PARITY.md` 并推送 `origin/main`
