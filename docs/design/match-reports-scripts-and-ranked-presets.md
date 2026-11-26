# 战报回放（379/380）、战斗剧本与运营审核（387–392）及论剑排位预设档案（448–452）设计文档

对应权威参考：`xuanming-server-mmo-cpp` `go/match/internal/{authority,logic,battlescript}`、`docs/planning/ranked-play-v1-implementation.md`，以及已同步契约 `proto/match/{match_service,match_internal}.proto`、`proto/team/team.proto`、`RankedRule` / `RankedPreset` 表。

## 1. 需求分析

1. **战报回放（`MatchService` 379 / 380）**：
   - 终局提交（`CommitBattleOutcome` `COMPLETE`）且建房原件 `record_replay = true` 时，在同一 SQL 事务中写入战报正文（`match_battle_report`）与全员战报列表（`match_player_battle_report`）。
   - 每名玩家仅保留最近 100 条（`KEPT_PER_PLAYER = 100`），每次终局事务最多按批 16 条挤出超额旧行，按 `battle_id` 升序扣减正文 `ref_count`，归零即删正文。
   - 回放外壳不合格（版本非 1、非 GZIP、原始大小超 1 MiB、压缩载荷超 512 KiB 或未要求录制却携带回放）只丢回放正文并记错误日志，绝不阻塞终局提交。终局重放时比对回放摘要，不一致仅告警不覆写。
   - 客户端 `ListBattleReports`（379）按 `created_ms DESC, battle_id DESC` 返回本人最近 100 条摘要；`GetBattleReport`（380）先验本人列表中存在该 `battle_id` 再读大正文，未上榜或无正文统一回 `kMatchBattleReportNotFound`（16025）。
2. **战斗剧本与运营审核（`MatchService` 389–392 + `MatchInternal` 387–388）**：
   - 内置官方剧本目录 `official_scripts.json`（protobuf JSON，严格模式禁止未知字段），启动期按正式配表（`Skill`/`Buff`/`Monster`/`Dungeon`）全量规范化与校验，坏配置直接拒启。
   - 玩家提交/改写剧本（`SubmitBattleScript` 390）：容量上限每人 10 份、单份序列化上限 900 字节；校验通过后按 `order`/`priority` 确定性规范化落盘为 `PENDING`；改写按 `expected_revision` 做 CAS，且不动 `approved_*`（审核通过前对局继续使用上一份通过版）。
   - 玩家删除剧本（`DeleteBattleScript` 391）：按 `expected_revision` 做 CAS，同一事务清除本人指向该剧本的选用，保留审核审计流水。
   - 玩家选用剧本（`SetBattleScriptLoadout` 392）：上限 32 条；清除填 `UNSPECIFIED`；官方剧本核对存在且适用于玩家方；玩家剧本锁内复核本人拥有且至少通过一次审核；评分模式（`1V1`/`5V5`/`PVP_TEAM`）禁止显式绑定玩家剧本，通用模式（`match_mode = 0`）的玩家剧本在评分局解析时自动跳过。
   - 运营审核（`MatchInternal.ListPendingBattleScripts` 387 / `ReviewBattleScript` 388）：独立运营密钥验签（`ServiceCallSignatures` caller `"ops"`），未开启或验签失败带内回 `DENIED`；审核按 `(script_id, revision, PENDING)` 做 CAS，通过前按当前配表复核，同一事务落 `match_battle_script_review` 审计行；支持按 `(reviewed_ms, script_id, revision)` 游标分批清理到期审核流水。
   - 建房解析（`BattleScriptResolver`）：开局组 `CreateBattleRequest.team_scripts` 时按「队长或首位成员选用（具体→通用） > 官方绑定 > 不带」解析并冻结；读库或复核失败 fail-open 退回官方绑定或不带剧本，不挡开局。
3. **论剑排位档案、预设、饰品佩戴与战绩查询（`MatchService` 448–451）及队内预设确认（`ClientPlayerTeam.ConfirmRankedPreset` 452）**：
   - `GetRankedProfile`（448）：返回当前赛季信息、个人本季进度（未亮段/段位/评分/场次/里程碑位图）、单排默认预设（缺省 101 破阵 + 203 护主）、饰品佩戴、当前生效 6 套正式预设及可选往季历史与全部权益。
   - `SaveRankedPreset`（449）：校验 `rule_version` 与当前 `RankedRule.rule_version` 一致（不符回 `16036`）、校验人物预设（`role_kind=1`）与宝宝预设（`role_kind=2`）合法（非法回 `16035`）、结算中拒写（`16034`）。
   - `UpdateRankedCosmetic`（450）：校验请求佩戴的玉符/头像纹样/名片底纹/称号均已存在于本人 `match_ranked_reward` 权威权益表，未拥有拒绝（`16035`）；支持 `hide_talisman` 显式隐藏玉符。
   - `GetRankedBattleResult`（451）：`battle_id = 0` 查本人最近一局排位战果，否则查指定 `battle_id`，返回评前后分、段位变化、是否首次亮段、本季有效场次与新解锁权益。
   - `ClientPlayerTeam.ConfirmRankedPreset`（452）：队员在非开战锁状态下按当前 `rule_version` 确认本人本队排位的人物与宝宝预设，推进队伍版本并广播 `TEAM_CHANGE_REASON_RANKED_PRESET_CONFIRMED`；任何名单变动（入队、离队、踢人、修复移出）自动将留队成员的 `ranked_preset_confirmed` 置 `false`；`TeamView` 汇总 `ranked_presets_all_confirmed` 与 `ranked_rule_version`；队长发起 `PVP_TEAM` 整队开战时，核对规则版本与全员确认态（未确认回 `4033` 带未确认队员 id，预设/版本无效回 `4034`）。

## 2. 设计分析

- **表结构与权威边界（`PbMysql` 只扩不缩）**：
  - 在 `game-match/src/main/proto/game/match/match_feature_tables.proto` 声明 10 张自有表（`match_battle_report`、`match_player_battle_report`、`match_battle_script`、`match_battle_script_review`、`match_battle_script_loadout`、`match_ranked_season`、`match_ranked_player_prefs`、`match_ranked_player_season`、`match_ranked_reward`、`match_ranked_battle`），由 `MatchFeatureTables` 统一同步，不修改既有 `MatchRatingTables`（保持既有 DDL 逐字锁测试不动）。
  - 在 `game-team/src/main/proto/game/team/team_record.proto` 的 `TeamMemberRecord` 末尾追加字段 7–10（`ranked_character_preset_id`、`ranked_pet_preset_id`、`ranked_rule_version`、`ranked_preset_confirmed`），零 schema 破坏。
- **派发收口**：
  - `MatchMethods` 补齐 10 个方法常量，`ALL` 覆盖契约 `MatchService` 全部 22 个方法；`MatchMethodsTest` 移除 `deferred` 集合，启动期与单测双向钉死零未实现客户端入口。
  - `TeamMethods`、`TeamDispatcher`、`TeamService` 补齐 `ConfirmRankedPreset`（452），`ClientPlayerTeam` 全部 13 个 C2S + 3 个 S2C 推送占位全量收口。
  - `MatchInternalService`（`game-api`）追加 `listPendingBattleScripts` / `reviewBattleScript` 默认方法，`MatchInternalServiceImpl` 用 `ServiceCallSignatures`（caller `"ops"`）验签后投递 `match-worker` 执行。

## 3. 思想分析

- **事务内不变式 vs 事务外校验**：
  - 战报写入与终局提交共事务，保证「终局落账即有战报、重放幂等不双写」；剧本校验、规范化与发号放事务外，份数上限与 `expected_revision` CAS 放行锁事务内。
  - 组队预设确认沿用 `TeamRules` 纯函数状态机 + `TeamStore` 乐观/悲观提交模型，把「名单变动自动失效旧确认」直接收口在 `TeamRules` 的所有名单变更路径上，避免遗漏分支。
- **Fail-Closed 与 Fail-Open 分层**：
  - 资产/安全/身份/审核/排位预设与饰品佩戴全链路 fail-closed（缺会话、签名失败、配表缺失、未拥有饰品、未确认预设一律拒绝）。
  - 建房解析剧本、战报回放外壳损坏遵循 fail-open（退回官方绑定/丢弃回放正文，记录错误日志），不因非关键展示/托管功能阻断正常对局与终局结算。

## 4. 漏洞分析

1. **越权读取他人战报正文**：若直接按 `battle_id` 查 `match_battle_report` 大 BLOB，恶意玩家可遍历 `battle_id` 拖垮 DB 并窥视私密局。防线：`GetBattleReport` 必先查 `match_player_battle_report(player_id, battle_id)`，未命中直接回 `16025`，绝不触碰大行。
2. **并发删剧本与选用/引用计数死锁**：多局终局同时挤出同一战报或并发改删剧本易死锁。防线：战报减引用严格按 `battle_id` 升序加锁，被挤出行按实删行数 `RowsAffected == 1` 计数；剧本删除与清选用共事务且单行 `FOR UPDATE` 与审核 CAS 串行。
3. **评分局绕过玩家剧本限制**：客户端先将玩家剧本设到 `match_mode = 0`（任意模式），再打评分局。防线：`SetBattleScriptLoadout` 拒绝显式评分模式绑定玩家剧本，`BattleScriptResolver` 在 `OfficialOnly = true`（`1V1`/`5V5`/`PVP_TEAM`）时过滤掉所有 `PLAYER` 来源选用，只走官方选用或官方绑定。
4. **未拥有饰品伪造佩戴 / 旧规则版本预设带入排位**：客户端直接传任意 `item_code` 或旧 `rule_version`。防线：`UpdateRankedCosmetic` 逐项对照 `match_ranked_reward` 权威行；`SaveRankedPreset` 与 `ConfirmRankedPreset` / `StartTeamMatch` 严格校验 `rule_version` 与正式 `RankedPreset` 表。
5. **队伍名单变化后沿用旧预设确认**：新队员加入或原队员离开后队伍组合已变。防线：`TeamRules` 在审批入队、接受邀请、离队、踢人、修复移出路径统一重置剩余成员 `ranked_preset_confirmed = false`。

## 5. 步骤分析

1. 扩展 `game-api` `MatchInternalService`（387/388 接口与常量）。
2. 在 `game-match` 新增 `match_feature_tables.proto`、`official_scripts.json`、`MatchFeatureTables`，实现战报（`report`）、战斗剧本与审核（`script`）、排位档案预设与结算（`ranked`）三大模块，接入 `GlobalMatchStore`/`GlobalMatchService`/`GatherPipeline`/`MatchInternalServiceImpl`/`MatchMethods`/`MatchFeatureConfiguration`。
3. 在 `game-team` 扩展 `team_record.proto`、`TeamTips`、`TeamRankedRules`、`Op`/`OpKind`/`TeamRules`、`TeamViews`、`TeamService`、`TeamMethods`、`TeamDispatcher` 与 `TeamConfiguration`。
4. 编写单元与 SQL 集成测试覆盖全路径，同步更新 `MatchMethodsTest`、`TeamTipsTest`、`TeamDispatcherTest` 与 `RatingTestDatabase`，运行 `game-api`、`game-match`、`game-team` 与 `game-robot` 门禁验证。
5. 更新 `PARITY.md` 并精准提交推送至 `origin/main`。

## 6. 执行步骤

- [x] 核对 C++/Go 权威源码与契约表结构，完成六段式设计文档。
- [ ] 完成 `game-api` 扩展并 `mvn -o -B install -pl game-api`。
- [ ] 完成 `game-match` 战报（379/380）、战斗剧本与审核（387–392）、排位档案预设（448–451）实现与测试。
- [ ] 完成 `game-team` 排位预设确认（452）与 `StartTeamMatch` 复核实现与测试。
- [ ] 全量通过 `game-api`、`game-match`、`game-team`、`game-robot` 验证，更新 `PARITY.md` 并推送 `origin/main`。
