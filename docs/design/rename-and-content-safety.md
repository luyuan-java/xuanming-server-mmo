# 帮会改名、聊天举报撤回防刷与角色改名（内容安全全链路）设计文档

> 对应 C++/Go 权威版 `docs/design/rename-and-content-safety.md` 与 `5c0a518a6`：覆盖 `game-guild` 帮会改名（439）与内容审核接入、`game-chat` 防刷/举报（433）/世界频道撤回（434/435）与内容审核接入、`game-scene` 角色改名（428/429）与宠物改名审核接入、`game-login` 建角名内容审核接入。

---

## 一、需求分析

1. **核心功能需求**：
   - **`game-guild`（帮会改名 439 与内容安全）**：
     - 接入 `GuildService.RenameGuild`（消息号 439）：仅帮主可操作；校验帮名合法性、归属区与合服围栏、调用内容安全服务（`TEXT_SCENE_GUILD_NAME`），在单事务内按严格锁序（`guild` → `guild_member` → `UPDATE guild` 抢 `uk_guild` → `guild_name_hold` 核查/写入旧名保护 → 扣帮会资金并写 `renamed_ms`）提交，提交后失效缓存并推送 `GUILD_CHANGE_KIND_RENAMED`。
     - 建帮（`CreateGuild`）校验新名不能落入他帮未过期的 `guild_name_hold`，并对帮名（`TEXT_SCENE_GUILD_NAME`）与帮会公告（`SetAnnouncement`，`TEXT_SCENE_GUILD_ANNOUNCEMENT`）接入内容安全审查。
     - 对齐 `guild.renamed_ms`（列 13）、`guild_name_hold` 表（`GuildNameHoldRow`）、`GuildRule[1]` 改名列（`rename_cooldown_seconds` / `name_hold_seconds` / `rename_cost_funds`）与 `guild_error` 14043–14047。
   - **`game-chat`（防刷、举报 433、世界撤回 434/435 与内容安全）**：
     - 世界频道发言最低等级门槛（可配）、归一化摘要跨副本重复刷屏拦截（`chat_repeat_event`）、新账号每日私聊人数上限（`chat_private_daily_target`）。
     - `ReportChat`（433）：核实消息存在且对举报人可见后追加写入 `chat_report`。
     - `RetractWorldChat`（434）+ `NotifyWorldChatRetracted`（435）：追加写入 `chat_world_retraction`（不改写 `chat_send_authority` 原件），拉历史过滤已撤回条目，并向在线玩家广播 435 通知移除。
     - 接入 `ContentGuardService`（配置开关启用，未配置时回落既有本地词表以保证单测与离线切片平滑过渡），命中 `MASKED` 存打码文、`REJECT` 按类别回内容拒码或联系方式广告拒码、`pending_platform_review` 在后台触发同步复检拒则自动撤回。
   - **`game-scene`（角色改名 428/429 与宠物改名内容安全）与 `game-login`（建角内容安全）**：
     - `SceneClientPlayerCommon.RenamePlayer`（428）/ `NotifyPlayerRenamed`（429）：受交接冻结与战斗在途闸约束；校验 `RoleNameRule`（最小/最大码点数、冷却、改名卡道具与数量），经内容安全校验（`TEXT_SCENE_ROLE_NAME`）后在 `PlayerStore` 单事务内校验同名/冷却/重名/旧名保护并原子更新 `player.name` / `name_key` / `renamed_ms`，成功扣改名卡并写审计流水、推 429 给本人。
     - `RenamePet`（宠物改名）与 `game-login` `CreatePlayer`（建角自定义名）可选接入 `ContentGuardService`。

2. **非功能与约束**：
   - 内容安全 RPC 必须在数据库事务锁外执行，严禁持锁等远程 RPC。
   - 被检原文绝不写入日志或 Micrometer 标签；所有新增指标严格低基数。
   - 保持现有单测、离线构建与双版契约（proto / DDL / tip 码）逐字节一致。

---

## 二、设计分析

1. **模块职责与跨进程通信**：
   - `game-api` 已有 `ContentGuardService`（Dubbo group `content-guard`），调用方（`game-guild` / `game-chat` / `game-scene` / `game-login`）均通过轻量函数式接口（Strategy 模式）注入审核器：生产按配置引用 Dubbo `ContentGuardService`，关闭或单测可注入桩或默认放行/本地过滤，不引入额外框架耦合。
   - 跨模块/跨线程传递不可变 Protobuf / Java `record`（Actor 隔离）。
2. **`game-guild` 锁序与一致性设计**：
   - 表间全序追加 `guild_name_hold` 至末尾（锁序位置 9，在 `guild_client_request` 之后）：
     - `RenameGuild`：`player`（若启用）→ `G`（锁 `guild` 行核帮主、同名、冷却、资金）→ `M`（锁帮主成员行复核权威 `role`）→ `UPDATE guild SET name=?, name_norm=?`（先拿 `uk_guild` 唯一索引锁序列化抢注）→ `guild_name_hold`（查新名是否在他帮未过期保护期内；是则回滚返 `NAME_TAKEN`，否则过期/本帮则删，再把旧名写入保护表）→ `UPDATE guild SET funds=funds-?, renamed_ms=?` → `txSnapshot`。
     - `CreateGuild`：在最后 `INSERT INTO guild` 拿到 `uk_guild` 后，顺手调 `claimGuildNameHold` 阻止建帮抢注他帮未过期保护名。
   - 不改 `GuildData` 主构造器兼容性（通过重载或只在 `GuildRow` / SQL 事务内读 `renamed_ms`，不破坏 90+ 现有单测构造点）。
3. **`game-chat` 追加写表与幂等设计**：
   - 复用 `JdbcChatStore` 所在数据源，新增 4 张 append-only 表（`chat_world_retraction`、`chat_report`、`chat_repeat_event`、`chat_private_daily_target`），严禁 `UPDATE`/`DELETE` `chat_send_authority`。
   - `RetractWorldChat` 用 `INSERT IGNORE` 幂等落库，仅首条新增时发布 `NotifyWorldChatRetracted`（435）。
4. **`game-scene` 角色改名设计**：
   - Java `game-scene` 直连权威 `PlayerStore`（同仓 `game-player-store`，不像 C++ 需要跨 gRPC 调 `data_service`），但遵循同等安全不变量：
     1. 场景逻辑线程先做纯预检（可写/非冻结/非战斗/无在途改名、`RoleNameRule` 合法性、同名、冷却、改名卡充足）；
     2. 异步在工作线程调 `ContentGuardService`（`TEXT_SCENE_ROLE_NAME`）与 `PlayerStore.renamePlayer(...)`（带 `owner_epoch` 围栏、查 `player_name_hold` 旧名保护、更新 `player.name` / `name_key` / `renamed_ms` 并记录旧名保护）；
     3. 回调切回场景逻辑线程复核会话仍存活且 `owner_epoch` 未变，扣改名卡并更新内存 `ScenePlayer.name` / `renamedMs`，请求存盘并推送 `NotifyPlayerRenamed`（429）。

---

## 三、思想分析

- **设计模式选型**：
  - **策略模式（Strategy）**：各调用方抽象极薄的 `@FunctionalInterface` 内容审核器，屏蔽 Dubbo `ContentGuardService`、本地兜底过滤与单测桩差异，零多余包装层。
  - **不可变值对象与显式结果和类型（Algebraic Result）**：`TxOutcome<Renamed>`、`GuildReject` 穷举 `switch`，由编译器强制校验每一个新增拒绝分支都有对应客户端 tip 映射，避免漏分支。
  - **先占唯一索引再查保护表（Two-Phase Lock Ordering）**：`RenameGuild` 与 `CreateGuild` 均先写 `guild(name_norm)` 持有 `uk_guild` 再访问 `guild_name_hold`，彻底消除「A 刚释放旧名、B 并发抢注」的幻读窗口与死锁环。
- **反过度设计（Ponytail）**：
  - 不为 `ContentGuardService` 在 `game-common` 发明复杂的客户端 SDK 框架；各业务服务只需 15 行内联适配方法把 `CheckTextResponse` 映射为本服务 tip 码即可。
  - 不新增无必要线程池：复用各服务已有的 `guild-worker` / chat worker / scene async I/O executor。

---

## 四、漏洞分析

1. **事务内调内容安全 RPC 导致数据库连接池雪崩**：
   - 规避：内容安全审核一律排在开启 JDBC 事务之前，超时或不可用按 fail-closed 返 `kGuildTextCheckUnavailable`（14045）/ `kRoleNameCheckUnavailable` / 聊天审核不可用码。
2. **并发抢注刚改名释放的旧名或死锁**：
   - 规避：强制 `guild` → `guild_member` → `UPDATE guild`（持 `uk_guild`）→ `guild_name_hold` 锁序；`CreateGuild` 在 `INSERT guild` 后同样检查 `guild_name_hold`。
3. **改名资金扣减或冷却时间戳无符号溢出**：
   - 规避：资金比较用 `Long.compareUnsigned(funds, costFunds) < 0`；SQL 用 `WHERE guild_id = ? AND funds >= ?` 防负扣；冷却计算防加法溢出。
4. **敏感词原文泄露到日志或监控**：
   - 规避：审核拒绝与改名失败日志仅记录 `guild_id` / `player_id` / `tip_id`，绝不打印原始输入文本。
5. **帮会改名推送与指标高基数**：
   - 规避：`GuildPushes.KIND_LABELS` 仅增加固定值 `"renamed"`，`GuildTxOp` 与 `InvalidationOp` 仅增加 `RENAME("rename")`，绝不拼动态名。
6. **角色改名跨线程竞态（改名期间掉线/交接/消耗掉改名卡）**：
   - 规避：改名卡预扣或回调切回场景线程时复核 `owner_epoch` 与可写闸；数据库更新带 `owner_epoch` 围栏，且改名卡先预检并在数据库成功后扣减（或在途标记阻止并发改名/移卡）。

---

## 五、步骤分析

1. **子步 3b-1（`game-guild` 帮会改名 439 + 内容安全 + 修复全仓阻断单测）**：
   - 更新 `guild_tables.proto`（`GuildRow.renamed_ms = 13` + `GuildNameHoldRow`）、`GuildTables`、`GuildTxOp`、`InvalidationOp`、`GuildPushes`、`GuildTips`、`GuildTip`、`GuildReject`、`GuildMethods`、`GuildTableRules`、`GuildTableLookup`。
   - 在 `GuildStore` / `JdbcGuildStore` 实现 `renameGuild` 及 `createGuild` 的旧名保护校验。
   - 在 `GuildService` 实现 `renameGuild` 及 `createGuild`/`setAnnouncement`/`renameGuild` 的内容审核钩子，`GuildDispatcher` 接入 `RenameGuild`，`GuildConfiguration` 按配置注入可选 Dubbo `ContentGuardService`。
   - 补齐 `GuildRenameTest` 并更新 `GuildTablesTest`、`GuildTipsTest`、`GuildDispatcherTest`、`GuildPushesTest`、`GuildCacheInvalidatorTest`，验证整个 `game-guild` 模块单测全绿后提交推送。
2. **子步 3b-2（`game-chat` 举报 433 / 撤回 434-435 / 防刷 / 内容安全）**：
   - 扩展 `ChatDispatcher`、`ChatService`、`JdbcChatStore`、`ChatProperties`、`ChatMetrics`、`ChatConfiguration`，补单测验证提交推送。
3. **子步 3b-3（`game-scene` 角色改名 428-429 / 宠物改名审核 + `game-login` 建角审核）**：
   - 扩展 `PlayerStore` / `PlayerMapper` 原子改名与旧名保护、`game-scene` `RenameFeature` / `RenameService`、`PetService` 审核、`CreatePlayerHandler` 审核，更新冻结/战斗闸矩阵测试并验证提交推送。

---

## 六、执行步骤（最终确定版）

按上述 3 个独立可验证子步顺序实施：每完成一个子步立即执行对应模块 Maven 测试与 `PARITY.md` 登记，全绿后提交中文 commit 并推送 `origin/main`。
