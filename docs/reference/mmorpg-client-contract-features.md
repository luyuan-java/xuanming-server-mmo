# 任务与活动列表：客户端可见行为参考（基线 mmorpg@26ceb70ca）

> 范围：190 GetActivityList、193 GetMissionList、194 AcceptMission、195 ClaimMissionReward 的请求 / 应答形态、错误码、列表内容与客户端行为。
> 只写客户端能看到的部分；服务端内部流程（推进、连锁、持久化）见 `docs/design/architecture.md` §4.8 与 PARITY「任务」「活动列表」行。
> 路径相对 mmorpg 仓库根目录（客户端路径相对 mmorpg-client）。

---

## 1. 消息号与服务

| id | 契约名 | 请求 | 应答 |
|---|---|---|---|
| 190 | `SceneActivityClientPlayerGetActivityList` | `GetActivityListRequest{}` | `GetActivityListResponse` |
| 193 | `SceneMissionClientPlayerGetMissionList` | `GetMissionListRequest{}` | `GetMissionListResponse` |
| 194 | `SceneMissionClientPlayerAcceptMission` | `MissionActionRequest{scope, mission_id}` | `GetMissionListResponse` |
| 195 | `SceneMissionClientPlayerClaimMissionReward` | `MissionActionRequest{scope, mission_id}` | `GetMissionListResponse` |

- 两个服务都标了 `OptionIsPlayerService` + `OptionIsClientProtocolService`，gate 照常转发；gate 不认识它们的语义，限频取缺省（每消息号每秒 3 条）。
- **没有任何任务 / 活动的 S2C 推送**：进度变化客户端只能重拉 193 / 190。
- 永远为空的字段：`PlayerMissionInfo.name / description`、`MissionObjectiveInfo.description`、`PlayerActivityInfo.name / description / icon_key`（表没有文案列）。
- `PLAYER_MISSION_FAILED` 与原因「任务已失效」不可达（没有代码设置失败 / 超时状态）。

## 2. 错误放在应答体里

- 应答体字段 1 `error_message` 总在：成功是 `error_message{}`（id 0，线上字节 `0a 00`），失败是 `error_message{id}`。`kSuccess`(1000) 从不发。
- 194 / 195 失败时只有 tip：`missions` 为空、`state_persistent=false`。成功时带完整列表、`state_persistent=true`。
- 193 / 190 只在实体无效时失败（1009）——Java 从会话解析玩家，到不了。
- 客户端（`Assets/Scripts/Game/PlayerFeatures/PlayerFeaturesClient.cs`）把应答体 tip 显示成「读取任务失败 / 读取活动失败 / 接取任务失败 / 领取奖励失败（错误码 N）」。

## 3. 错误码（先后即优先级）

**194 接取**：scope≠0 → 1005；（冻结 1005）；任务表里没有 → 1001；无条件或 `condition_order>1` → 1002；已接 → 5004；已完成或待领 → 5001；
同（类型, 子类型）有进行中的任务 → 5000；逐个条件格：条件行缺失 / 比较符不是 >=、>、== / 生效目标为 0 → 1002，类别不是击杀 1 / 等级 6 / 完成任务 8 或有时效 → 1003，
condition2–4 非空 → 1003，击杀条件没有打得到的怪（副本里出现且怪物表里有）→ 1003，计数方式非法 → 1003，`>` 且目标为 uint32 上限 → 1002；
奖励配置非法 → 1002（奖励号 0 放行）；活动任务（类型 2）不在开放窗口 → 1006（排期配置非法 1002）。

**195 领奖**：scope≠0 → 1005；（冻结 1005）；不在待领：已完成 → 12000 `kRewardAlreadyClaimed`，否则 5002 `kMissionIdNotInRewardList`；
待领却未完成（存档不一致）→ 1002；任务表里没有或奖励号 0 → 1002；奖励配置非法 → 1002；之后是入包结果：批量满包 6006 `kBagItemNotStacked`、
发不出物品号 6004、物品被全服禁发 1005。**领奖闸不看背包空间**：满包时列表照样 `can_claim=true`，领取回 6006 并保留资格。

奖励只解释为物品：每条物品与数量都非 0、物品存在、同物品累加不超 uint32、合并后非空，否则 1002。正式表奖励 1–7 都是物品 1 × 4（不可叠加，要 4 个空格）。

## 4. 193 列表内容

- **键集**：任务表全部行 ∪ 玩家进行中 / 已完成 / 待领出现过的任务号（表里删掉的照样列出），按任务号**无符号**升序。
- **状态**：待领 > 已完成 > 进行中 > 未接。
- **表里没有的行**：`configured=false`、`unavailable_reason="任务配置暂不可用"`，不带类型 / 奖励 / 目标，`can_accept / can_claim` 都是 false。
- **表里有的行**：`configured=true`；`mission_type / mission_sub_type / reward_id / auto_reward` 照表；`can_accept = 未接 && 接取闸通过`；
  `can_claim = 待领 && 领奖闸通过`。两者都 false 时给原因（第一条命中）：

  | 条件 | 原因 |
  |---|---|
  | 进行中 | 完成任务目标后领取奖励 |
  | 已完成 | 任务已完成 |
  | 待领（领奖闸不过） | 当前状态暂不可领奖，请稍后重试 |
  | 接取闸 5000 | 请先完成同类型任务（先于活动分支） |
  | 活动任务（类型 2） | 排期原因：活动尚未排期，敬请期待 / 活动尚未开始 / 活动已结束 / 活动配置异常，暂不可参与；**开放中但接取闸因别的原因不过时是空串** |
  | 接取闸 1003 | 任务所需玩法暂未开放 |
  | 其余 | 当前条件下暂不可接取 |

- **目标**：每个条件格一条，`objective_index` = 格序号（同一条件可出现多次）、`condition_id`；条件行缺失的格子只填这两个（后面的格子不移位）；
  否则 `category`、`target`（任务行 `target_count[i]` > 0 时覆盖条件的目标，只有前两格可覆盖）、`progress`（进行中的格子值，否则 0——完成后不伪造）、
  `completed`（已完成 / 待领，或进行中且该格达成）。等级条件的进度显示原始等级。

## 5. 190 活动列表

- 行：任务表里类型 2 的行，按 `activity_id` 升序；`server_time_ms` = 服务器 UTC 毫秒。
- 排期（`ActivitySchedule`，按任务号查）：
  - 没有排期行或 `enabled=false` → 未排期，**不带** `starts_at_ms / ends_at_ms`，原因「活动尚未排期，敬请期待」；
  - 启用 → 带窗口，`[start, end)` 按 uint64 比较：之前「活动尚未开始」、之后「活动已结束」、之间开放（原因空）；
  - 非法（有窗口但起点 0 或终点不晚于起点、启用却没有窗口——停用的行也校验）→ 整条清零：activity_id / mission_id / reward_id 都是 0、未排期、
    「活动配置异常，暂不可参与」。
- `activity_id = mission_id = 任务号`，`reward_id` 照表（未排期也带）。
- `can_participate = 开放 && 接取闸通过`；开放但不能参与时原因：已接 / 已完成（5004 / 5001）「请在任务页查看活动进度」、
  同类型占用（5000）「请先完成同类型活动任务」、其余「活动所需玩法或当前状态暂不满足参与条件」。参与就是对同一任务号发 194。
- 正式表：15 / 16 / 17 三个活动的排期都未启用，全部未排期、不可参与，对它们发 194 回 1006。

## 6. 正式表下新号的期望（两版一致）

| 任务 | can_accept | 原因 |
|---|---|---|
| 4、7、8、9、12、13、14 | true | — |
| 1、2、6、10、11 | false | 任务所需玩法暂未开放（击杀的怪 3 / 4 打不到，或条件类别没有来源） |
| 3、5 | false | 当前条件下暂不可接取（无条件） |
| 15、16、17 | false | 活动尚未排期，敬请期待 |

接了任一（1, 1）类型的任务后，其余（1, 1）任务（1、4、6、7、8、9、10、11、12）变成「请先完成同类型任务」；5 仍是「当前条件下暂不可接取」。
目标数：7 → 8、8 → 9、9 → 10、10 → 11、11 → 12、13 → 2、17 → 2、2 → [1, 2, 1, 2, 2, 2]，其余 1。

## 7. 客户端行为（`PlayerFeaturesClient.cs`）

- 只有列表里 `can_accept` 才发 194（列表里没有、但活动 `can_participate` 时也发）；只有 `can_claim` 才发 195；否则显示服务端给的原因
  （没有原因时用「当前任务暂不可接取 / 暂不可领奖，请刷新查看」）。
- 任一操作成功后重拉 190；领奖成功后再拉 191。

## 8. 两版的已知差异（客户端可见）

- **完成后的连锁**：基线自动领奖、链式接取、「完成任务 X」三件事进了事件队列但线上从不派发——自动领奖的任务完成后停在待领（要手动 195）、
  链式任务不自动接、完成事实只在接取时回填。Java 同步执行：自动领奖的任务完成后直接是已完成并到账，链式任务自动接（走完整接取闸）。
- 详见 PARITY「任务」行。
