# 战斗击杀任务推进的固定基线复核

日期：2026-11-06。只读核查 Java 当前源码与固定 C++ Git 对象，未编译、未运行测试，未修改业务代码。本文不改写 `PARITY.md`。

## 结论

Java 会把一条 `defeated_monsters` 中的数量展开为逐只击杀事实，每只完成同步连锁。后续任务能获得同一条结算记录剩余的击杀；固定 C++ `92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5` 和 `993b6da9d4ea51d5e4a27cc6ec17004af0fb97c1` 的生产路径也是这个行为。

因此旧 PARITY 任务行中的“Java 待把同一结算多只击杀放进同一次连锁”不能视为相对这两个基线的移植缺口。将整场结算的击杀先全部派发、最后才接后续任务，会改变当前两版的玩法行为，不应以补齐移植的名义实施。

旧文中“C++ 只入事件队列，自动领奖和链式任务在线上不生效”的描述也已过时；两个固定基线都直接执行完成后续动作。

## Java 生产路径与反例

- `game-scene/src/main/java/com/game/scene/battle/BattleSettlementService.java:187`：永久 Inbox 路径对每条 defeat 调用 `onMonsterKilled(player, configId, count)`。
- 同文件 `:263`：旧 `applyOnce` 路径的击杀步骤也调用该方法。
- `game-scene/src/main/java/com/game/scene/mission/MissionService.java:208`：`onMonsterKilled` 跳过怪物号 0，把 `count` 解释为 uint32；`:212` 的循环逐只调用 `dispatch`，每次事实 `amount=1`。
- 同文件 `:217`：每次 `dispatch` 新建 `Cascade` 并立即 `drain`。`:251` 先处理事实、再接后续任务；`:329` 的完成处理注销索引、登记完成、尝试自动领奖、排入完成事实与后续任务。
- `game-scene/src/main/java/com/game/scene/player/PlayerMissions.java:133`：每次取得当前任务索引的快照；`:159` 接取会登记新任务索引，`:166` 完成会立即释放旧索引。因此下一只击杀可以看到新接取的任务，无须新增持久字段或批次状态。

确定性例子已由现有测试明确表达：`game-scene/src/test/java/com/game/scene/battle/SettlementApplyValuesTest.java:383` 先应用一场 `kill(1,3)`，使任务 7 为 3/8；第二场在 `:390` **只有一个** `kill(1,10)` 条目，前五只完成任务 7，后五只推进任务 8，`:393` 断言任务 8 进度为 5。它不是下一条 defeat 贡献的进度。

同文件 `:397` 保留多种怪物、重复同怪条目、怪物号 0 和数量 0 的区分。`MissionServiceTest.java:419` 覆盖自动领奖与接取后续任务；`:460` 覆盖先派发前置完成事实、再接取与回填，避免完成事实重复计数。上述均是源码断言证据，本次未执行。

## 固定 C++ 生产路径

以下行号均对应列出的提交，由 `git show` / `git grep` 取得，未读取移动工作树作为依据。

| 提交 | 文件及位置 | 生产行为 |
|---|---|---|
| `92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5` | `cpp/libs/services/scene/battle/system/player_battle.cpp:2218` | 遍历每条 defeat，再按 count 逐只构造 `ConditionEvent`，`amount=1`，立即调用 `PlayerMissionSystem::OnCondition`。 |
| 同上 | `cpp/libs/services/scene/player/system/player_mission.cpp:211`、`:84` | `OnCondition` 调 `HandleConditionEvent` 后立即 `ApplyFollowUps`；后者同步自动领奖、接取后续任务、派发完成事实。 |
| `993b6da9d4ea51d5e4a27cc6ec17004af0fb97c1` | `cpp/libs/services/scene/battle/system/player_battle.cpp:2070` | 仍是每只 `amount=1`，逐次同步调用 `OnCondition`。 |
| 同上 | `cpp/libs/services/scene/player/system/player_mission.cpp:217`、`:85` | 保留同步完成后续动作；相对 929 的相关变化是活动完成通知和关键进度存盘请求，未改成整场结算批次。 |

两提交间 `cpp/libs/modules/mission/system/mission.cpp` 无差异。`HandleConditionEvent:175` 只推进当条事实关注的任务；`OnMissionCompletion:321` 输出自动奖励、后续接取、完成事实，由玩家服务同步执行。

993 的 `cpp/tests/bag_test/player_battle_settlement_test.cpp:280` 明确测试没有 dispatcher sink 时，战斗击杀仍完成任务并自动发奖；`cpp/tests/bag_test/player_mission_system_test.cpp:255` 注释说明旧 enqueue 问题已修复。任务规则测试的 `Progress(monster,count)` 直接发一条聚合 amount 事实，是规则夹具，不能拿它替代生产战斗路径的逐只展开语义。

## 幂等与后续验证边界

旧应用入口在 `BattleSettlementService.java:220` 的持久 `BattleLedger` 命中后提前返回，后续击杀不会重复执行；`SettlementApplyTest.java:150` 和 `:489` 已覆盖重复投递与从库加载账本后不重发奖励、不重推任务。

永久 Inbox 路径不使用可裁剪的旧战斗账本：`InboxService.java:736` 先分类永久域水位与原决定，`:752` 的 REPLAY 分支不会调用执行器，`:804` 才执行新条目。`BattleInboxLifecycleTest.java:135` 覆盖 Redis 丢失及最近环淘汰后仍不重复金币、消耗或击杀；`:250` 附近覆盖自动奖励失败后待领资格随持久状态恢复。本次未改动这些边界。

若后续另行决定改变玩法为“同一结算内后接任务不得吃剩余击杀”，须明确登记新语义，并同时验证单条数量、多条不同怪物、顺序多目标、自动领奖失败、完成事实与回填不重复、跨下一场继续推进，以及重复终局与重登的账本保护。不能简单把 `amount=1` 改成聚合 count：顺序任务要求不同击杀推进后续条件格，该简化会吞掉合法进度。

本次只确认上述具体移植差距不成立，不宣称任务与活动的全部功能已经对齐，也不将源码阅读当作构建或运行证据。
