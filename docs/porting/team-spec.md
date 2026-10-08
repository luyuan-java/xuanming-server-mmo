# 组队服务（team）移植统一规格：从 mmorpg 基线到 Java 版

> **基线**：mmorpg `26ceb70ca`，与 `contract/SOURCE.properties:4` 的 `mmorpg.commit` 相同。Java 侧以 `acc5684` 为准。
> 写作时工作区里有一批未提交的「热关停」改动，涉及 `xm-gate/.../session/ClientDispatcher.java`、`MessageRoutes.java`、
> `xm-discovery/.../RedisKeys.java`、`xm-gate/src/main/resources/application.yaml` 等文件。这些文件的行号按当前工作区给出，提交后可能小幅漂移，所以引用时同时写出方法名。
>
> **路径怎么读**
> - 不带目录的 `rules.go` / `store.go` / `scripts.go` / `service.go` / `notify.go` / `view.go` / `presence.go` / `homezone.go` / `keys.go` / `errors.go` / `server.go`，以及它们的 `*_test.go`：都在 mmorpg `go/match/internal/team/` 下。
> - `team_battle.go`、`queue.go`、`matcher.go`：在 mmorpg `go/match/internal/logic/` 下。`playercontract.go`：在 mmorpg `go/match/internal/playercontract/` 下。
> - `team-system.md`：mmorpg `docs/design/team-system.md`。这是设计稿，与代码冲突时以代码为准，冲突项见 §8.4。
> - 以 `go/`、`proto/`、`cpp/`、`robot/`、`generated/` 开头的路径：在 mmorpg 下。
> - 以 `xm-`、`docs/`、`config-data/`、`contract/`、`tools/` 开头的路径，以及 `PARITY.md`、`AGENTS.md`：在 `D:\work\xuanming-server-mmo` 下。
> - `TeamClient.cs` / `TeamViewMapper.cs` / `TeamAppearanceTransport.cs` / `TeamModels.cs`：在 `D:\work\mmorpg-client\Assets\Scripts\Game\Team\` 下。其余以 `mmorpg-client/` 开头的路径在 `D:\work\mmorpg-client` 下。
> - `team.proto` 指 Java 同步副本 `xm-proto/src/main/proto/proto/team/team.proto`。它只比 mmorpg `proto/team/team.proto` 多第 2–4 行 java option，所以 **Java 行号 = mmorpg 行号 + 3**。
> - `team_error_tip.proto` 指 `xm-table/src/main/proto/tip/team_error_tip.proto`；`tip_text.json` 指 `config-data/tables/tip_text.json`。
> - Redis 键里的 `xm:` 是前缀。Java 键里的 `{...}` 是 hash tag（§1.3）。
>
> **本稿的来历**：由三份分区规格合并而成，三份分别讲数据模型与状态机、RPC 层与推送、客户端契约 / robot / 场景跟随 / Java 映射。
> - 分区稿之间互相矛盾、或与代码不符的地方，都回到代码重新核对过。
> - 对分区稿的更正列在 §8.5。
> - 本稿只读代码，没有改任何源文件。
>
> **与 pbmysql 的关系**：
> - 用户要的「pbmysql 的 Java 版」已经由 xm-pbmysql 在批次 4.0 完成（`docs/porting/roadmap.md:57`）。
> - 组队的权威数据全部在 Redis，没有 MySQL 表，所以不适用阶段 4「proto 形状的 MySQL 表接 pbmysql」的约定（`docs/porting/roadmap.md:50-53`）。
> - xm-team 只读 `xm_java.player`（展示资料与 home zone）。这张表不归 xm-team 所有，仍由 MyBatis 侧维护（`docs/design/architecture.md:161`）。

---

## 0 概览与协议

### 0.1 链路与依赖

**基线链路**
- **请求路径**：客户端 → C++ gate（路由模式）→ `client_rpc_router` → match 进程里的 `ClientPlayerTeam` gRPC 服务（`team.proto:21-28`）。
  - team 与 MatchService / MatchInternal 共用同一个 zrpc server（`go/match/match_service.go:136-156`）。
  - team 另以 `TeamNodeService` 做第二次节点注册（`match_service.go:274-300`）。
- **会话拦截器**：外层是 `sessionInterceptor`（`match_service.go:355-382`），它对 team **从不拒绝**。
  - 它只把 `x-session-detail-bin` 解进 ctx；base64 或 protobuf 解不开时只记日志，照常放行（:364-381）。
  - 缺身份由 team 在业务层自己回 in-band 4001（§3.0）。
  - **与 friend 不同**：friend 的拦截器会判坏头，客户端看到的是信封 1003（`docs/porting/friend-spec.md` §7.1）。
- **路由服**：
  - 上游任何 gRPC 错误或超时都翻成**信封** `MessageContent{id, message_id, error_message{1003}}`；成功时把应答字节原样装进 `serialized_message`（`go/client_rpc_router/internal/logic/forwardlogic.go:170-189`、`:225-233`）。
  - 转发超时 `ForwardTimeoutMs = 5000`（见 `server.go:21-22` 的注释）。
- **超时**：match zrpc 顶层 `Timeout: 5000`（`go/match/etc/match_service.yaml:3`；`match_service.go:161`），team 的每个方法再自设 3500 ms 预算（`server.go:21-24`）。

**基线依赖**

| 依赖 | 用途 | 出处 |
|---|---|---|
| SharedRedis（单实例） | 权威记录、投影、玩家索引、邀请反查，四类键只经 Lua 写；另外只读 `player:session:<id>`、`battle:lock:<id>`、`PlayerAllData:<id>`、`player:<id>:location` | `keys.go:5-23`；`presence.go:11-27`；`playercontract.go:42-56` |
| data_service | `BatchGetPlayerHomeZone` 查 home zone | `homezone.go:12-18`、`:53-67` |
| BattleIDGen（snowflake） | 发 team_id，与 battle_id / challenge_id 共用 | `service.go:34-38`、`:105-107` |
| Kafka `gate-cmd_g<N>` | S2C 推送 | `playercontract.go:122-160` |
| Kafka `scene-cmd_g<N>` | scene 刷新信号 `PlayerTeamRefreshEvent` | `notify.go:222-293` |
| 票据域 / gather | 只经 `BattleStarter` 端口使用，仅 StartTeamMatch 需要 | `service.go:40-60`；`team_battle.go` |

**Java 侧的对应**
- **入口**：gate 经 Dubbo 调 `ClientMessageService.handle(ClientCall) → CompletableFuture<ClientReply>`。group 等于 proto 一级目录 `team`（`xm-api/src/main/java/com/game/api/DubboGroups.java:3-19` 的现有写法；`docs/design/architecture.md:75-90`）。
- **进程**：新模块 **xm-team**（Spring Boot + Dubbo Triple），结构照 xm-friend（`architecture.md:350-391`；路线图规定「每个服务一个进程」，`docs/porting/roadmap.md:48`）。
  - 不并进 match：定稿时 Java 还没有 match（批次 6.4，`roadmap.md:84`），见 D1。6.4 落地后 xm-match 也是独立进程，两者经 `MatchTeamService` 往来（§5.5）。
- **存储**：Redis（Redisson）。键经 `com.game.discovery.RedisKeys` 生成，带 `xm:` 前缀（`AGENTS.md` §3；`architecture.md:511`）。
- **在线态与推送**：在线态取 `xm:presence` + `xm:location`，推送走 `PlayerPushes`（`architecture.md:128-147`、`:541-548`）。
- **批次归属**：
  - 4.3 做名册、申请邀请、推送、场景投影与跟随（`roadmap.md:60`）。
  - 整队开战在 6.4（`roadmap.md:84`）；4.3 期间怎么处理见 §5.4。**6.4 已接（2026-10-08）**，现状见 §5.5。

### 0.2 消息号、请求 / 应答形状、gate 限频

**数据来源**
- 消息号只能取自 `MessageIdRegistry`（同步产物 `xm-proto/src/main/resources/contract/message_id.txt:202-216`），代码里不写数字。
- 限频数据：`generated/tables/messagelimiter.json:207-278`。Java gate 读同一份 `config-data/tables/messagelimiter.pb`。表里没有的消息号，按缺省每秒 3 条处理（`xm-gate/.../session/MessageLimit.java:13-14`）。
- 超频时回信封 1008，并计非法包（`ClientDispatcher.java:229-235`）。

| 号 | 方法 | 方向 | 请求（`team.proto`） | 应答 | 限频 |
|---|---|---|---|---|---|
| 214 | CreateTeam | C2S | `{}`（:52） | `TeamResponse` | 3/s |
| 207 | GetMyTeam | C2S | `{bool notify_online=1}`（:54-56） | `TeamResponse` | 5/s |
| 206 | ApplyJoinTeam | C2S | `{uint64 target_player_id=1}`（:62-64） | `TeamResponse` | 3/s |
| 208 | HandleApplication | C2S | `{applicant_id=1; bool approve=2; expected_team_id=3}`（:66-70） | `TeamResponse` | 3/s |
| 201 | InviteToTeam | C2S | `{target_player_id=1; expected_team_id=2}`（:72-75） | `TeamResponse` | 3/s |
| 204 | RespondInvite | C2S | `{team_id=1; bool accept=2}`（:77-80） | `TeamResponse` | 3/s |
| 205 | ListMyInvites | C2S | `{}`（:82） | `ListMyInvitesResponse` | 5/s |
| 210 | LeaveTeam | C2S | `{expected_team_id=1}`（:84-86） | `TeamResponse` | 3/s |
| 202 | KickMember | C2S | `{target_player_id=1; expected_team_id=2}`（:88-91） | `TeamResponse` | 3/s |
| 212 | TransferLeader | C2S | `{target_player_id=1; expected_team_id=2}`（:93-96） | `TeamResponse` | 3/s |
| 209 | DisbandTeam | C2S | `{expected_team_id=1}`（:98-100） | `TeamResponse` | 3/s |
| 211 | StartTeamMatch | C2S | `{uint32 battle_config_id=1; expected_team_id=2}`（:102-105） | `TeamResponse` | 3/s |
| 213 | NotifyTeamSnapshot | **S2C 推送** | `TeamSnapshotS2C{TeamView team=1; TeamChangeReason reason=2; uint64 actor_id=3; TipInfoMessage tip=4}`（:194-199） | `Empty`（:45） | 表中没有，缺省 3/s |
| 215 | NotifyTeamInvite | **S2C 推送** | `TeamInviteS2C{TeamIncomingInviteView invite=1; uint64 server_time_ms=2}`（:201-204） | `Empty`（:46） | 同上 |
| 203 | NotifyTeamEvent | **S2C 推送** | `TeamEventS2C{TeamEventType type=1; uint64 team_id=2; uint64 actor_id=3}`（:213-217） | `Empty`（:47） | 同上 |

**应答与嵌套消息**
- **`TeamResponse{TipInfoMessage error_message=1; TeamView team=2}`**（:109-112）
  - 注释写明：`id≠0` 时 `team` 仍可能带调用者当前视图，客户端照常按排序规则应用。
- **`ListMyInvitesResponse{error_message=1; repeated TeamIncomingInviteView invites=2; uint64 server_time_ms=3}`**（:114-118）
- **`TeamView`**（:161-174）：
  - `team_id=1`（0 = 无队）
  - `leader_id=2`
  - `capacity=3`（恒为 5）
  - `zone_id=4`（建队者的 home zone）
  - `version=5`
  - `membership_epoch=6`（接收者本人的成员关系版本）
  - `match_state=7`（`IDLE=0 / STARTING=1`，:120-123）
  - `members=8`
  - `applications=9`、`pending_invites=10`（这两项只给队长）
  - `application_count=11`（所有人可见）
  - `server_time_ms=12`
- **`TeamMemberView`**（:125-137）：`player_id=1; name=2; level=3; class_id=4; gender=5; is_leader=6; is_online=7; in_battle=8; zone_id=9; join_seq=10; appearance_id=11`
- **其余视图**：
  - `TeamApplicationView{player=1; applied_at_ms=2; expire_at_ms=3}`（:139-143）
  - `TeamOutgoingInviteView{invitee=1; expire_at_ms=2}`（:146-149）
  - `TeamIncomingInviteView{team_id=1; inviter=2; leader_id=3; member_count=4; zone_id=5; expire_at_ms=6}`（:152-159）
- **`TeamChangeReason`**（:176-192）：`UNSPECIFIED=0, CREATED=1, MEMBER_JOINED=2, MEMBER_LEFT=3, MEMBER_KICKED=4, LEADER_TRANSFERRED=5, LEADER_OFFLINE_TRANSFERRED=6, DISBANDED=7, APPLICATION_CHANGED=8, INVITE_CHANGED=9, MEMBER_ONLINE=10, MATCH_STARTED=11, MATCH_ENDED=12, MATCH_FAILED=13, HEALED=14`
- **`TeamEventType`**（:206-210）：`UNSPECIFIED=0, APPLICATION_REJECTED=1, INVITE_REVOKED=2`
  - 第 209 行注释写「解散/满员导致邀请失效」，其中「满员」**没有实现**（§8.4）。
- **存储记录**：`TeamRecord / TeamMemberRecord / TeamApplicationRecord / TeamInviteRecord`（:224-258）不下发客户端。
- **Java 生成类**：
  - 包名 `com.game.proto.team`（`team.proto:2-4`）。
  - 投影 `TeamInfo{team_id=1; leader_id=2; repeated members=3}` 和 scene 组件 `TeamId{team_id=1; membership_epoch=2}` 在包 `com.game.proto`（`xm-proto/src/main/proto/proto/common/component/team_comp.proto:11-24`）。
  - `PlayerTeamRefreshEvent{player_id=1}` 定义在 `xm-proto/src/main/proto/proto/common/event/team_event.proto:16-18`，事件号 48（`xm-proto/src/main/resources/contract/event_id.txt:49`）。

**两条形状纪律**
1. **成功时不设 `error_message`**（`tipOf(0)` 返回 nil，`service.go:816-826`）。
   - 失败时为 `TipInfoMessage{id}`；`param≠0` 时再加 `parameters=[十进制 param]`，`param==0` 时不带 parameters。
   - Java gate 按契约里的应答类型决定是否回包：非 `Empty` 应答一律回，哪怕 0 字节；`Empty` 应答只在 `tip_id≠0` 时回（`xm-gate/.../session/MessageRoutes.java:50-54` `hasResponse`；`ClientDispatcher.java:452-473` `replyToClient`）。
2. **`TeamResponse` 尽量带调用者视图，失败也带**；只有服务端自己读失败时才不带（§3.1）。
   - 客户端靠「有没有视图」来区分「服务端读失败」与「你不在队」（`TeamClient.cs:350-372`、`:411-413`）。

### 0.3 tip 码表（team_error 段，base=4000）

- **Java 写法**：`com.game.table.TeamErrorTip.team_error.kTeamXxx_VALUE`（生成类已存在于 `xm-table/target/classes/com/game/table/TeamErrorTip$team_error.class`）。
- **故障分类**：team 段**只有 4030 是故障**（fault=1）。
  - 基线来源：`go/shared/generated/tip/faults.go:56`；`errors_test.go:83-90` `TestInternalIsTheOnlyFault`；`team-system.md:741`。
  - Java 没有 Tip 表的 fault 列（`config-data/tables/` 里只有 `tip_text.json`），所以 Java 只能写死「team 段故障 = {4030}」，并用测试钉住。
- **写法纪律**：
  - Go 侧一律写 `uint32(table.TeamError_kTeamX)`，不许手写数字（`errors_test.go:92` `TestNoHandWrittenTipCodes`）。
  - 码值唯一，且都落在 team 段内（`errors_test.go:58`、`:71`）。
- **保留不用的码**：4000、4009、4010、4012、4015、4016。
  - 设计稿说明见 `team-system.md:723`；Java 枚举在 `team_error_tip.proto:12,30,32,36,42,44`；文案都是「(保留)」（`tip_text.json:157,166,167,169,172,173`）。

| 值 | Java 枚举（`team_error_tip.proto` 行） | Go 常量（`errors.go` 行） | 文案（`tip_text.json` 行） | 何时产生 | parameters[0] |
|---|---|---|---|---|---|
| 4001 | kTeamPlayerId :14 | ErrPlayerId :15 | 玩家 ID 无效 :158 | 缺 session 或 player_id=0（`server.go:95-99`、`:153-157`）；Apply / Invite 的目标为 0 或自己（服务前置 `service.go:167-170`、`:205-208`；规则 invite `rules.go:532-534`）；Transfer 的目标为 0（`rules.go:649-651`） | 无 |
| 4002 | kTeamMembersFull :16 | ErrMembersFull :17 | 队伍人数已满 :159 | 满员（`len ≥ 5`）时：申请（`rules.go:467-469`）、同意（:514-516）、邀请（:543-545）、接受邀请（:593-595） | 无 |
| 4003 | kTeamMemberInTeam :18 | ErrMemberInTeam :19 | 对方已经在队伍中 :160 | 见本表下方「4003 的发生点」 | 见下方 |
| 4004 | kTeamMemberNotInTeam :20 | ErrMemberNotInTeam :21 | 对方不在你的队伍中 :161 | Kick 的目标不在队（`rules.go:633-635`）；Transfer 的目标不在队（:664-666） | **target**（target=0 时不带） |
| 4005 | kTeamKickSelf :22 | ErrKickSelf :23 | 不能把自己踢出队伍 :162 | `rules.go:629-631` | 无 |
| 4006 | kTeamKickNotLeader :24 | ErrKickNotLeader :25 | 只有队长可以踢人 :163 | `rules.go:625-627` | 无 |
| 4007 | kTeamAppointSelf :26 | ErrAppointSelf :27 | 你已经是队长了 :164 | 转让给自己（`rules.go:653-655`） | 无 |
| 4008 | kTeamAppointLeaderNotLeader :28 | ErrAppointNotLeader :29 | 只有队长可以转让队长 :165 | `rules.go:660-662` | 无 |
| 4011 | kTeamNotInApplicantList :34 | ErrApplicationNotFound :31 | 该申请已失效 :168 | 同意时申请不存在或已过期（`rules.go:506-508`） | 无 |
| 4013 | kTeamHasNotTeamId :38 | ErrNoTeam :33 | 你还没有队伍 :170 | 绑定失败或记录缺失（`service.go:629-637`）；申请目标无队（:183-185）；RespondInvite 的 `team_id=0`（:236-238）；规则层遇到记录为 nil（`rules.go:227-229`、`:273-275`、`:305-306`） | 无 |
| 4014 | kTeamDismissNotLeader :40 | ErrDisbandNotLeader :35 | 只有队长可以解散队伍 :171 | `rules.go:681-683` | 无 |
| 4017 | kTeamPlayerNotFound :46 | ErrTargetOffline :37 | 找不到该玩家 :174 | **只在服务前置**：邀请目标的会话不是 ONLINE（`service.go:214-216`） | 无 |
| 4018 | kTeamNotLeader :48 | ErrNotLeader :42 | 只有队长可以执行此操作 :175 | 审批（`rules.go:489-491`）、邀请（:528-530）、开战（:307-308） | 无 |
| 4019 | kTeamHomeZoneUnknown :50 | ErrHomeZoneUnknown :44 | 角色区服信息异常，请重新登录 :176 | home zone 查不到（`service.go:777-781`）；规则 `zoneCheck` 遇到 zone=0（`rules.go:447-449`）；Create 的 zone=0（:825-826） | 无 |
| 4020 | kTeamCrossZoneDenied :52 | ErrCrossZoneDenied :46 | 不能与其他区服的玩家组队 :177 | `!AllowCrossZone && zone≠队伍 zone`（`rules.go:451-453`） | 无 |
| 4021 | kTeamInviteNotFound :54 | ErrInviteNotFound :48 | 邀请已失效 :178 | 接受时邀请不存在或已过期（`rules.go:581-583`） | 无 |
| 4022 | kTeamInviteLimit :56 | ErrInviteLimit :50 | 对方待处理的邀请过多，请稍后再试 :179 | S_COMMIT 返回 `{-3,i}`（`store.go:251-252`） | **被邀请人**（`InvitesAdded[i-1].InviteeId`） |
| 4023 | kTeamInMatch :58 | ErrInMatch :52 | 队伍正在进入战斗，请稍候 :180 | 开战锁有效时：同意（`rules.go:510-512`）、接受邀请（:585-587）、离队（:615-617）、踢人（:637-639）、转让（:672-674）、解散（:685-687）、再次开战（:309-310） | 无 |
| 4024 | kTeamMemberOffline :60 | ErrMemberOffline :54 | 有队员不在线 :181 | Transfer 的目标会话不是 Online（`rules.go:668-670`）；开战预检（`service.go:426-428`） | **pid** |
| 4025 | kTeamMemberInBattle :62 | ErrMemberInBattle :56 | 有队员正在战斗中 :182 | 只在开战预检（`service.go:429-435`） | **pid** |
| 4026 | kTeamMemberNotReady :64 | ErrMemberNotReady :58 | 有队员暂时无法开战 :183 | 开战预检（`service.go:441-459`）；建票失败（:473-480） | **pid** |
| 4027 | kTeamDungeonNotOpen :66 | ErrDungeonNotOpen :60 | 该副本未开放组队 :184 | `required==0`（`rules.go:319-320`） | 无 |
| 4028 | kTeamSizeExceeded :68 | ErrSizeExceeded :62 | 队伍人数超过该副本上限 :185 | `len(members) > required`（`rules.go:321-322`） | 无 |
| 4029 | kTeamStateChanged :70 | ErrStateChanged :64 | 队伍状态已变化，请重试 :186 | 提交前 ctx 已过期（`store.go:227-229`、`:504-506`）；冲突累计达 3 次（:244-248、:267-271）；修复超过 5 次、修复返回 nil 或出现意外状态（:253-274）；锁内名单与成员集合不等（`rules.go:357-359`）；自由读不稳定（`service.go:758-764`）；开战轮数耗尽（:411） | 无 |
| 4030 | kTeamInternal :72 | ErrInternal :66 | 服务器繁忙，请稍后再试 :187 | 存储层 error（`service.go:618-622`）；自由读故障（:758-764）；home zone 未注入或查询出错（:767-776）；发号失败（:126-130、:785-797）；邀请目标会话读失败（:209-213）；ListMyInvites 读失败（:256-260、:268-272）；新 tid 已有记录（`store.go:200-203`）；applyCreate 的防御分支（`rules.go:823-824`）；未知 OpKind（:255-256）；锁 token 为空或截止已过（:354-356）；开战端口未接线（`service.go:344-347`）；开战锁提交报错（:385-392）；开战预检读会话 / 位置 / 票据失败（:421-425、:436-440、:452-456）。**唯一的 fault** | 无 |

**4003 的发生点（parameters 各不相同）**
- 不带参数的情形：
  - 建队时已在队：服务前置 `service.go:119-121`；规则层 `CallerTeamId≠0`，`rules.go:827-828`。
  - 申请时自己已在队：`service.go:176-178`；规则层 `rules.go:460-462`。
  - 接受邀请时已在别的队：服务前置 `service.go:244-246`；规则层 `rules.go:589-591`。
- param = target 的情形：
  - 邀请目标在任何队：服务前置 `service.go:221-223`。
  - 邀请目标已是本队成员：规则层 `rules.go:536-538`。
- param = 新成员的情形：S_COMMIT 返回 `{-1,i}`，param = `Joined[i-1]`（`store.go:249-250`）。按操作分：
  - 建队时就是 caller；
  - 同意申请时是申请人；
  - 接受邀请时是 caller。

**parameters 的口径**
- 只放十进制的 player_id（`service.go:816-826`）。
- 客户端只对 4024 / 4025 / 4026 和 MATCH_FAILED 解析 `parameters[0]`，用来给成员行着色（`TeamClient.cs:92-93`、`:337-341`、`:435`、`:521`、`:595-597`）。
- **Java 不得往 team 的 tip 里塞文字参数**。friend 的 `failure` 会塞 `"service overloaded"` 这类文字（`xm-friend/.../dispatch/FriendDispatcher.java:75-80`、`:164`），team 不能照抄。

### 0.4 配置与常量

**可配置项（基线）**

| 项 | 默认 | 说明 | 出处 |
|---|---|---|---|
| `Team.AllowCrossZone` | false | 是否允许不同 home zone 的玩家同队；从 true 改回 false 只拦新增 | `go/match/internal/config/config.go:166-174`；`match_service.yaml:126-131` |
| `PveTeamSizeByConfigId` | `{"1": 5}` | 副本组队人数上限，再按 `min(值, 5)` 收口；未配置的 id 回 4027 | `match_service.yaml:82-92`；`matcher.go:361-378` |
| `MatchedTicketTTLSeconds` | 30 | 参与开战锁时长的计算 | `match_service.yaml:68`；`queue.go:367-380` |
| `DataServiceRpc.Timeout` | 1500 ms | home zone 查询 | `match_service.yaml:133-150`；`homezone.go:31-33` |
| zrpc `Timeout` | 5000 ms | 不能改小，否则会截断 WatchBattle | `match_service.yaml:3`；`match_service.go:161` |

**代码常量**（规则常量写死，不做成配置；`rules.go:20-35`）

| 名称 | 值 | 出处 | 说明 |
|---|---|---|---|
| `Capacity` | 5 | `rules.go:23` | 必须等于引擎每队上限 `kMaxBattleTeamSize`。两包各自用测试钉住（`team_battle_test.go:179` `TestMatchLockRules`）。`full()` 的判定是 `len ≥ 5`（`rules.go:441`） |
| `ApplicationTTLMs` / `MaxApplications` | 120 000 ms / 10 | `rules.go:26-27` | 超限时淘汰最早一条，不报错 |
| `InviteTTLMs` / `MaxInvitesPerTeam` | 60 000 ms / 10 | `rules.go:30-31` | 同上 |
| `MaxPendingInvitesPerInvitee` | 10 | `rules.go:34` | 只在 S_COMMIT 里原子判定，作为 ARGV 最后一项传入（`store.go:847`） |
| `TeamIdleTTLSeconds` | 86 400 | `store.go:31` | Lua 参数用字符串 `"86400"`（`store.go:39`） |
| `TouchThresholdSeconds` | 43 200 | `store.go:33` | GetMyTeam 发现记录剩余 TTL 小于它时执行 S_TOUCH |
| `CommitRetries` | 3 | `store.go:35` | 版本冲突 `{0}` 的累计上限，修复提交的冲突也算在内 |
| `freeReadRetries` / `readMembersRetries` | 3 / 3 | `store.go:37-38` | |
| 修复轮数上限 | `Capacity` = 5 | `store.go:255`、`:665` | |
| 邀请反查 ZSET TTL | 3600 s | `scripts.go:122` | 每次 IA 写入后 EXPIRE |
| `teamRPCBudget` | 3500 ms | `server.go:24` | |
| `pushBatchBudget` | 3 s | `notify.go:37` | 推送和 scene 信号整批共用 |
| `matchStartRounds` | 3 | `service.go:68` | |
| `endMatchRoundTimeout` / `endMatchMaxDuration` | 2 s / 110 s | `store.go:453`、`:461` | 110 s 必须 ≥ 5 人锁时长 101 s（`store.go:454-460`） |
| EndMatch 退避 | 50 ms 起翻倍，上限 1 s，±20% | `store.go:463-465`、`:686-689` | |
| 开战锁时长 | `matchedTicketTTLFor(n) + compensationTicketTTLFor(n) + 10` 秒，其中 `compensation(n) = n×3 + 10`；5 人 = 66 + 25 + 10 = 101 s | `team_battle.go:31`、`:60-66`；`queue.go:367-386` | 绝对截止 = 本轮 S_READ 的 nowMs + 时长×1000（`service.go:384`） |
| `teamTicketRollbackBudget` | 3 s | `team_battle.go:26-28` | |

**Java 侧已有的上限**

| 项 | 值 | 出处 |
|---|---|---|
| gate 单包上限 | 整个 ClientRequest 1 KB | `ClientDispatcher.java:76` |
| gate 单会话、单后端域排队上限 | 64，超出即断开 | `xm-gate/src/main/resources/application.yaml:61`；`ClientDispatcher.java:329-341` `enqueueBackend` |
| gate 的 Dubbo consumer 超时 | 5000 ms | `xm-gate/src/main/resources/application.yaml:79` |
| Redisson 单条命令最坏阻塞 | 4.2 s，缺省 `retry-attempts=1` | `architecture.md:474-476` |

### 0.5 全局规则（所有 RPC 适用）

1. **业务结果一律 in-band**。
   - 方法从不返回 error（`service.go:26-28`）；故障也以 in-band 4030 表达。
   - 唯一的例外是外层：请求体解析失败、路由服超时，客户端看到的是信封 1003。
2. **「我」只取自 session**。请求体里没有自己的 player_id（`team.proto:25`；`server.go:63-71`）。
3. **每个方法 3500 ms 预算**。本次请求的全部 Redis / data_service 调用共用这一个 ctx（`service.go:29-30`）；提交之前再检查一次 ctx，过期即放弃（`store.go:227-229`）。
4. **写 RPC 带 `expected_team_id`**。整个 Mutate（含重试）只绑定这一个 tid，绝不换队（`team.proto:58-60`；`store.go:83`）。
5. **唯一时钟源是 SharedRedis TIME**。规则层是纯函数，不读墙钟（`scripts.go:8-10`；`rules.go:13-15`）。
6. **视图同源**：每份视图里的 team_id、version、epoch 和记录出自同一次原子操作（`view.go:14-23`）。
7. **副作用异步执行**：推送、scene 信号、补偿都有独立预算，结果不改变 RPC 回包（`notify.go:26-33`）。
8. **调用者本人不收自己这次提交的快照推送**，以回包为准（`notify.go:32`、`:112-115`）。
9. **会话读失败一律 fail-closed**：既不算在线，也不触发惰性转让（`rules.go:41-47`；`presence.go:25-27`）。
10. **展示数据尽力而为**：名字、等级、在线、战斗中这些字段读失败时填零值，不让 RPC 失败（`presence.go:25-27`）。

---

## 1 数据模型与 Redis 键

### 1.1 `TeamRecord`

`TeamRecord` 存在记录键 hash 的 `pb` 字段里，不下发客户端（`team.proto:219-258`）。

| 字段 | 号 | 写入规则 |
|---|---|---|
| `team_id` | 1 | 来自发号器，返回 0 视为故障（`service.go:785-797`）；必须等于绑定的 tid（`store.go:874-876`） |
| `leader_id` | 2 | 必须是成员（`store.go:880-882`） |
| `zone_id` | 3 | 建队者的 home zone，不为 0（`rules.go:825-826`、`:833`） |
| `created_at_ms` | 4 | 建队那次 S_READ 的 Redis 时钟（`rules.go:834`） |
| `members` | 5 | `TeamMemberRecord{player_id=1, zone_id=2, joined_at_ms=3, join_seq=4}`。集合语义，落盘前按 `(join_seq, player_id)` 升序排（`rules.go:712`、`:778`、`:912-919`） |
| `applications` | 6 | `{player_id, zone_id（申请人的 zone）, applied_at_ms（首次申请时刻）, expire_at_ms}` |
| `invites` | 7 | `{invitee_id, inviter_id（最近一次的邀请人）, zone_id（被邀请人的 zone）, invited_at_ms（首次邀请时刻）, expire_at_ms}` |
| `next_join_seq` | 8 | 建队时为 2（`rules.go:841`）；每次加入后设为 `seq+1`（:711） |
| `match_lock_token` | 9 | 非空表示持有开战锁（uuid，`service.go:383`） |
| `match_lock_expire_at_ms` | 10 | 过了截止即视为无锁 |
| `match_lock_roster` | 11 | 加锁时的名单，集合语义 |

- **版本号 `ver` 不在 pb 里**，存在 hash 字段里（`team.proto:221`）。
- **在线态不进记录**，只在构建视图和做判定时读（`rules.go:37-38`）。

### 1.2 不变量（及其强制点）

1. **成员数在 1 到 5 之间**。
   - 成员清空即解散：`Record=nil`，Lua 删除记录（`rules.go:727-730`；`scripts.go:93-94`）。
   - 提交前校验成员数不超过 5（`store.go:883-885`）。
   - 解散提交不带 J / K（`store.go:869-872`）。
2. **队长是成员**（`store.go:880-882`）。
3. **成员 `player_id` 不重复**：`Joined ∪ Kept` 等于新记录成员集合（按多重集比较）；J / K / L 两两不相交、不含 0（`store.go:857-879`、`:940-955`）。
4. **`join_seq` 唯一且递增**。
   - 取值：新 seq 取 `next_join_seq`；遍历现有成员，遇到 `join_seq ≥ seq` 就改成 `join_seq+1`；最后若为 0 则取 1（`rules.go:696-704`）。所以字段缺失或计数落后都能自愈。
   - 队长建队时 seq 为 1（`rules.go:839`）。
5. **申请人和被邀请人都不是成员**。
   - `addMember` 会同时删掉此人在本队的申请和邀请（`rules.go:713-714`；`rules_test.go:473` `TestJoinClearsBothApplicationAndInvite`）。
   - Apply 和 Invite 都拒绝已是成员的人（`rules.go:460`、`:536`）。
6. **数量上限**：申请按 `player_id` 唯一、最多 10 条；邀请按 `invitee_id` 唯一、最多 10 条。重复申请或邀请都是原地刷新（`rules.go:471-474`、`:548-551`）。
7. **持久化前已清理过期项**：每次提交的记录都按本轮 S_READ 的 `nowMs` 去掉了过期的申请和邀请（`rules.go:234`、`:278`、`:362`、`:380`）。
8. **开战锁**。
   - **有效的定义**：`token≠"" && now < expire`（`rules.go:185-187`）。
   - **有效期间 `match_lock_roster` 等于成员集合**：
     - 加锁时校验（`rules.go:357-359`）；
     - 锁期间所有改名单的操作都回 4023；
     - 修复移出成员时同步从 roster 去掉（`rules.go:286-288`）。
   - **锁期间仍然允许**：申请、邀请、拒绝申请、拒绝邀请、惰性转让队长（这几处都没有 `locked()` 检查，`rules.go:458-485`、`:494-501`、`:526-565`、`:570-576`；`team-system.md:1622`）。
   - **锁自然过期后三个字段不会自动清空**，等下一次加锁覆盖或 Release 清空（`rules.go:382-384`）。过期锁不挡名单变更（`rules_test.go:171` `TestExpiredLockAllowsRosterChange`）。
9. **zone**。
   - 队伍 zone 等于建队者的 zone。
   - 成员 zone 在入队时取申请记录或邀请记录里存的 zone（`rules.go:522`、`:600`）。
   - 跨区校验只拦新增，已有的跨区成员保留（`rules_test.go:193` `TestCrossZoneSwitch`）。
10. **Redis 侧不变量**（由 20×2000 次并发模糊测试的终态检查钉住，`store_test.go:767` `TestConcurrentInvariantsFuzz`）：
    - 每个成员 m 的索引 tid == 本队；
    - 索引 tid≠0 时，对应队伍包含该玩家；
    - 每个玩家的 epoch 单调，且同一个 epoch 只配对一个 tid；
    - 记录里未过期的邀请都在反查索引里；
    - 投影与记录一致；
    - 每个 `(tid, ver)` 只提交一次。

### 1.3 键空间

**基线**：所有键都在 SharedRedis 单实例上，不带 hash tag。一条 S_COMMIT 跨键原子写，所以依赖单实例（`keys.go:5-23`；`team-system.md:458`）。

| 基线键（`keys.go`） | 类型与值 | TTL | 写者 / 读者 | Java 建议（经 `RedisKeys`） |
|---|---|---|---|---|
| `team:rec:<tid>`（:37-40） | hash：`ver` 是十进制，首次为 1，每次提交严格 +1；`pb` 是 TeamRecord 字节（字段名见 :25-29） | 每次提交 EXPIRE 86400（`scripts.go:96-97`）；S_TOUCH 续期（:204）；解散时 DEL（:94） | 写：S_COMMIT / S_TOUCH。读：team；C++ scene 只做 `EXISTS`（`cpp/libs/services/scene/player/system/player_team.cpp:39`）；合服 preflight | `xm:{team}:rec:<tid>` |
| `team:<tid>`（:42-45） | string：`TeamInfo` pb，由 `projectionOf` 生成，members 按 join_seq 升序只为确定性（`store.go:904-911`） | 每次提交 `SET … EX 86400`（`scripts.go:98`）；S_TOUCH 发现缺失时重写（:205-209）；解散时 DEL | 写：S_COMMIT / S_TOUCH。读：C++ scene `GET`（`player_team.cpp:38`） | `xm:{team}:info:<tid>`（改名，避免与其他段混淆） |
| `team:player:<pid>`（:47-50） | hash：`tid` 十进制，无队为 `"0"`；`epoch` 十进制（字段名见 :31-35） | 每次 setIdx 都 EXPIRE 86400（`scripts.go:88`）。**离队不删键，只把 tid 置 0**（`team-system.md:442`） | 写：S_COMMIT / S_HEAL_ORPHAN / S_TOUCH。读：team；C++ scene `HMGET tid epoch`（`player_team.cpp:37`、`:194`） | `xm:{team}:player:<pid>` |
| `team:invite:<pid>`（:52-55） | zset：member = tid 十进制，score = `expire_at_ms` | 每次 ZADD 后 EXPIRE 3600（`scripts.go:120-122`） | 写：S_COMMIT 的 ZADD / ZREM、S_INVITE_LIST、S_INVITE_PRUNE。读：team | `xm:{team}:invite:<pid>` |

**Java 的键设计（D2）**
- **hash tag 统一用 `{team}`**。
  - S_COMMIT 一次要原子写记录、投影、最多 5 名成员加若干移出者的索引、以及若干被邀请人的反查 ZSET，这些是跨玩家的多键写。
  - 统一 tag 之后，将来上 Cluster 时这些 Lua 仍落在同一槽。代价是组队数据全在一个分片上：设计估算 1 万队 × 2 KB ≈ 20 MB（`team-system.md:455`）。
  - Java 现在也是单实例（`architecture.md:511`），所以这一条眼下只是为将来预留，见 §9.3 的待决项。
- **对照**：好友按玩家打 tag（`RedisKeys.java:74-85`），聊天用 `{world}`（:110-113）。组队无法按玩家打 tag，因为 S_COMMIT 会同时碰到任意玩家的键。
- **数字格式**：一律用 `Long.toUnsignedString`（`RedisKeys.java:45-47` 的惯例）。
- **字段名共享**：`ver / pb / tid / epoch` 这几个 hash 字段名，是 xm-team（写者）与 xm-scene（读者）之间的进程间契约。建议把常量与读脚本一起放进 xm-discovery（§6.10）。

### 1.4 epoch 规则

- **只在 tid 变化时变**：setIdx 里 `old ~= v` 才改 epoch（`scripts.go:84-87`）。tid 不变时 epoch 也不变，但 TTL 照样续期（:88）。
  - 测试：`store_test.go:165` `TestEpochSemantics`。
- **怎么变**：
  - 索引存在时 +1。
  - 索引缺失，或 epoch 字段缺失 / 为 0 时，取 Redis `TIME` 毫秒数 +1 起种（`scripts.go:83-85`）。
  - S_HEAL_ORPHAN 用同一口径（`scripts.go:226-233`）。
- **读路径遇到缺失索引**：S_READ 回报 epoch = `nowMs`，只读不写（`scripts.go:144`）。
  - 它大于索引消失前发出的任何 epoch，所以客户端会接受这份空视图（`scripts.go:134-139`；`team-system.md:476`；`service_test.go:519` `TestExpiredTeamEmptyViewIsAcceptedByClient`）。
  - 它严格小于之后的任何起种值 `nowMs+1`（`store_test.go:214` `TestMissingIndexEpochIsMonotonic`）。
- **S_READ_MEMBERS 遇到缺失索引回 `"0"`**（`scripts.go:159`）。这没有问题：它只给 `tid==本队` 的成员推送，这些成员的键必然存在（`team-system.md:478`）。
- **精度**：
  - 所有写回都用 `string.format("%.0f", x)`。
  - id 在 Lua 里只当字符串比较，从不 `tonumber`（`scripts.go:12-14`）。
- **排序键 `(membership_epoch, version)`**：
  - 前提：`(epoch, team_id, version)` 出自同一次原子操作（`team-system.md:484`）。
  - 客户端规则见 §4.2。
  - scene 只在 incoming epoch 更大时才应用（`player_team.cpp:197-205`；`player_team.h:64-76`）。

### 1.5 ver 规则

- **建队**用哨兵 `"new"`：要求记录不存在，`newVer = 0+1 = 1`（`scripts.go:51-52`、`:91`）。
- **每次 S_COMMIT 成功都 +1**，包括只做过期清理或惰性转让的提交。
- **S_TOUCH 不改 ver**（`scripts.go:193-216`；`store_test.go:433` `TestTouchRenewsWithoutBumpingVersion`）。
- **解散时**记录被删，但 `CommitResult.Version` 仍是「最后一版 +1」（`store.go:148`；`store_test.go:267` `TestProjectionAndDisband`）。

### 1.6 Lua 脚本（`scripts.go`）

通用约定：
- **统一模式**：S_READ 一致性读 → Go 里跑规则 → S_COMMIT 按 ver CAS 写（`scripts.go:5`）。
- **时间**：绝对时间一律取 `TIME`。这要求 Redis 7 的 effects replication，调用 TIME 之后仍可写（`scripts.go:8-10`）。
- **返回值**：返回数组里不放 nil，缺失值用 `""` 或 `"0"` 占位（`scripts.go:16`）。

#### 1.6.1 S_COMMIT（唯一的写脚本，`scripts.go:18-125`）

**KEYS**
- `[1]=rec`，`[2]=proj`。
- 其后依次：J、K、L 成员的玩家索引；IA、ID 被邀请人的邀请反查。

**ARGV**
- `[1]` = expectedVer：十进制，或建队用 `"new"`。
- `[2]` = recPb：空串表示解散。
- `[3]` = projPb。
- `[4]` = ttl `"86400"`。
- `[5]` = tid。
- `[6..10]` = nJ, nK, nL, nIA, nID。
- `[11..10+nIA]` = 各条 IA 的 expire_at_ms。
- `[11+nIA]` = 被邀请人的待处理邀请上限 `"10"`。
- Go 侧的组装见 `store.go:815-849`。

**执行顺序**

判定段只读：任何拒绝分支都不产生写入（`scripts.go:39`；`store_test.go:90` `TestCommitRejectBranchesWriteNothing` 用 Dump 前后相等钉住）。

1. **ver CAS**（:50-55）：
   - `"new"` 时记录已存在 → `{0}`。
   - 其余情况，记录不存在或 ver 不等 → `{0}`。所以**绝不复活已解散的队伍**。
2. **取时间**：`TIME` → `nowms` / `nowStr`（:61-63）。
3. **J 判定**（:64-67）：索引 tid 存在、不是 `"0"`、也不是本队 → `{-1,i}`。缺失、`"0"`、本队都放行。
4. **K 判定**（:68-71）：索引 tid 存在且不是本队 → `{-2,i}`。**`"0"` 也算不等**，只有缺失时放行；缺失的索引在下面第 7 步重建。
5. **IA 判定**（:72-80）：`ZSCORE key tid` 为 nil（本队在该 ZSET 里没有条目）时，若 `ZCOUNT key (now +inf ≥ 上限` → `{-3,i}`。
6. **写记录**（:91-99）：`newVer = cur+1`。
   - recPb 为空：DEL 记录和投影。
   - 否则：`HSET ver/pb`、`EXPIRE`、`SET proj EX ttl`。
7. **J∪K**（:100-104）：对每人调 `setIdx(key, tid)`，输出 `(tid, epoch)`。setIdx（:81-90）只在 tid 变化时改 epoch，无论变不变都 EXPIRE。
8. **L**（:105-116）：
   - 索引缺失或 tid == 本队：`setIdx(key, "0")`，输出 `("0", e)`。
   - 否则原样输出 `(别队 tid, 别队 epoch)`，不写、不续期。
9. **ID**（:117）：`ZREM key tid`。
10. **IA**（:118-123）：`ZREMRANGEBYSCORE key -inf now` → `ZADD key expire tid` → `EXPIRE 3600`。
    - 先 ZREM 后 ZADD 是集合重叠时的第二道保险（`store_test.go:508` `TestReinviteAfterExpiryKeepsIndex`）。

**返回值**
- 成功：`{1, newVer, tid_1, epoch_1, …}`，按 J、K、L 的顺序排列。
- 失败：`{0}` / `{-1,i}` / `{-2,i}` / `{-3,i}`，i 从 1 开始（`scripts.go:31-37`）。

**相对设计稿的两处收紧**（`scripts.go:43-48`）
- L 的索引缺失时也置 0，并成对回报 `(tid, epoch)`。
- 写 IA 前先清掉该被邀请人的过期反查项。

#### 1.6.2 其余脚本

| 脚本 | KEYS / ARGV | 语义 | 返回 | 读写 |
|---|---|---|---|---|
| S_READ（`scripts.go:127-147`） | `[player idx, rec]` | 一致性读：玩家索引 + 某队记录 + Redis 时钟 | `{tidNow（"" = 缺失）, epoch（缺失时为 nowStr）, ver（"" = 无记录）, pb, TTL(rec) 整数（-2 不存在 / -1 无 TTL）, nowStr}` | 只读 |
| S_READ_MEMBERS（:149-162） | `[rec, idx_m…]` | 给其他队员构建不经提交的视图 | `{ver, pb, nowMs, tid_i（"" = 缺失）, epoch_i（"0" = 缺失）…}` | 只读 |
| S_INVITE_LIST（:164-177） | `[invite]` | 先 `ZREMRANGEBYSCORE -inf now`，再列出剩余项 | `{nowMs, tid_1, score_1, …}`，score 是 Redis 原字符串 | **会写** |
| S_INVITE_PRUNE（:179-191） | `[invite]`；`ARGV=tid, score` | `ZSCORE == ARGV[2]`（按字符串比较）才 ZREM，因此不会误删队长刚重邀写入的新项 | 1 = 删了 / 0 = 没删 | 写 |
| S_TOUCH（:193-216） | `[rec, proj, idx_m…]`；`ARGV=ver, ttl, projPb, tid` | ver 不等（含记录不存在）→ 0；EXPIRE 记录；投影存在则 EXPIRE，缺失则按该版记录重写；成员索引只在 tid 仍等于本队时续期 | 1 / 0 | 写，不改 ver |
| S_HEAL_ORPHAN（:218-236） | `[idx, rec]`；`ARGV=tid, ttl` | 记录不存在、且索引 tid == ARGV[1] 时：tid 置 `"0"`，epoch +1（为 0 时按 TIME+1 起种），EXPIRE | 1 / 0 | 写 |

### 1.7 存储层（`store.go`）

Store 本身无可变状态，可以并发共享；正确性全靠 ver CAS 与 Lua 原子判定（`store.go:25-27`）。

#### 1.7.1 绑定（`store.go:83-104`）

| 绑定 | 用于 | 未绑定的判定 |
|---|---|---|
| `BindCaller(pid, expected)` | Handle / Invite / Leave / Kick / Transfer / Disband / StartTeamMatch；GetMyTeam 也用它，expected 取自由读得到的 tid | `expected==0`，或调用者索引 tid ≠ expected → `OutcomeNotBound`，不写（`store.go:196-199`） |
| `BindTarget(pid, tid)` | RespondInvite（tid = 请求里的 team_id）、ApplyJoinTeam（tid = 前置读到的目标所在队） | 不校验调用者自己的 tid，交给规则判断 |
| `BindCreate(pid, newTid)` | CreateTeam | `expectedVer="new"`；记录已存在 → `Rejected(4030)`（`store.go:200-203`） |

#### 1.7.2 `Mutate` 循环（`store.go:180-278`）

1. `S_READ(bind.playerId, bind.teamId)`，结果存进 `res.Snapshot`（:187-191）。
2. BindCaller 未绑定 → `NotBound`（:196-199）。
3. 记录检查：
   - BindCreate 但记录已存在 → `Rejected(4030)`（:200-203）。
   - 非建队且记录为 nil → `RecordMissing`。此时若 `teamId≠0` 且调用者索引仍指向它，先执行 `HealOrphan`，结果写进 `HealedOrphan`（:204-214）。
4. `sessions = loadSessions(成员)`；`op.CallerTeamId = snap.PlayerTeamId`（:216-217）。建队时记录为 nil，sessions 也为 nil（:733-738）。
5. `d = Apply(...)`：`Code≠0` → `Rejected(code, param)`；`!Changed` → `Unchanged`（:218-226）。
6. `ctx.Err()≠nil` → `Rejected(4029)`（:227-229）。
7. 用 expectedVer = 本轮 ver（建队用 `"new"`）提交（:231-235），按结果处理：

| 结果 | 处理 |
|---|---|
| OK | `Committed` |
| `{0}` | `Conflicts++`；达到 3 回 4029，否则**立即**回到第 1 步（不退避） |
| `{-1,i}` | 4003，param = `Joined[i-1]` |
| `{-3,i}` | 4022，param = `IA[i-1].InviteeId` |
| `{-2,i}` | `repairs++`；超过 5 或 ctx 已过期 → 4029。否则调 `repairIndexMismatch`：返回 nil → 4029；修复 OK → 记入 `Repairs`，回到第 1 步重算原操作；修复冲突 → `Conflicts++`，达到 3 回 4029；其他结果 → 4029（:253-274） |

8. error 只表示 Redis / 序列化 / 程序缺陷类故障（:181）。服务层把它映射为 4030。

**结局与结果字段**
- 结局枚举：`Committed / Unchanged / Rejected / NotBound / RecordMissing`（:129-143）。
- `MutateResult` 的字段：`Snapshot`（最后一轮 S_READ）、`Decision`、`Commit`、`Repairs`、`HealedOrphan`、`Conflicts`（:159-178）。

#### 1.7.3 `commit` / `validateCommitSets` / 回复解析（`store.go:756-902`）

- **`validateCommitSets`**（:851-902）：违反即视为程序缺陷，返回 error，绝不带着错的集合去写。校验项：
  - tid ≠ 0；
  - J / K / L 不含 0、互不重叠；
  - 解散时不带 J / K；
  - 记录的 team_id 等于绑定的 tid；
  - `J ∪ K` 等于成员集合；
  - 队长在成员里；
  - 成员数不超过 5；
  - IA 不含 0、不重复；
  - IR 不含 0、不重复、不与 IA 重叠。
  - **它不校验 `Left ⊇ 旧 \ 新`**。Decision 的注释（`rules.go:152`）说「store 提交前会校验」，与实现不符。
- **回复解析**（:774-809）：
  - 返回码只认 1 / 0 / -1 / -2 / -3，其他都算错误。
  - 拒绝分支的长度必须是 2；成功分支的长度必须是 `2+2(nJ+nK+nL)`。
  - `Indexes[pid] = (tid, epoch)`。
  - `CommitResult.NowMs` 取的是 **S_READ** 的 nowMs，不是 S_COMMIT 自己的（:153、:804）。
- **`parseUint`**（:922-938）：Lua 回来的空串或非法串一律解析成 0。

#### 1.7.4 `repairIndexMismatch`（`store.go:650-684`）

1. 从 `bad = [Kept[index-1]]` 开始，基于**同一份** `snap.Record`、同一个 expectedVer 生成 `RepairRemoveMembers` 并提交。
2. 修复提交本身又回 `{-2,j}` 时，把 `fix.Kept[j-1]` 并入 `bad`，再来一轮。被拒的修复没有写入，ver 不变。
3. 最多 5 轮；下标越界或不收敛时返回 error（服务层回 4030）。`!fix.Changed` 时返回 nil。
4. 多名成员错位时，一次全部移出（`store_test.go:300` `TestSelfHealing`）。

#### 1.7.5 读、续期、自愈、邀请索引

- **`ReadFree`**（`store.go:286-313`），最多 3 轮：
  1. 先 `HGET tid` 预读；
  2. 再 `S_READ(pid, tid)`，回报的 tid 与预读不符就重来；
  3. `tid≠0` 但记录不存在时执行 HealOrphan，记下 healed，重来。
  - 3 轮用尽返回 `ErrUnstableRead`，服务层回 4029。
  - 预读那一步 `HGET` 的值解析失败会返回 error，服务层回 4030（:722-731）。
- **`NeedsTouch`**（:315-318）：记录存在且 `RecordTTLSeconds < 43200`（含 -1 无 TTL 的情形）。
- **`Touch`**（:320-342）：KEYS = 记录、投影，加上按 `snap.Record` 列出的成员索引；ARGV = ver、ttl、projPb、tid。
- **`HealOrphan`**（:344-354）。
- **`ReadMembers`**（:366-406）：
  - 成员集合与传入的不等时，用新成员表重读，最多 3 次，仍不等返回 `ErrMembersChanged`。
  - 记录不存在时返回 `Record=nil`，error 为 nil。
  - 索引 tid 不等的成员如实回报（`store_test.go:655` `TestReadMembersPairsTidAndEpoch`）。
- **`ListInvites` / `PruneInvite`**（:416-447）：score 用 `ParseFloat` 转成 `ExpireAtMs`，同时保留原字符串，供 PruneInvite 做 CAS（`store_test.go:674` `TestInviteListAndPruneCas`）。

#### 1.7.6 钉版本提交与 EndMatch（开战专用，§5）

- **`CommitMatchLock`**（`store.go:490-508`）：
  - 快照为空或 ver=0 → error。
  - `LockMatch` 拒绝 → 返回 `PinnedResult.Code`。
  - ctx 已过期 → 4029。
  - 否则 `commitPinned`。
  - **不走** Mutate 的「重读重算」：那会在新名单上加锁、却按旧 roster 建票（:490-493）。
- **`commitPinned`**（:620-648）：按 `snap.Version` 钉死提交。
  - OK → Commit；
  - `{0}` → Retry；
  - `{-2}` → Retry，同时做修复（修复成功的记入 Repairs）；
  - `{-1}` / `{-3}` 不可能出现，出现即 error。
- **`EndMatch`**（:537-585）：
  - 每轮新建 2 s 的 ctx；进程内单调时钟超过 110 s → `EndMatchDeadline`。
  - 本轮报错 → 记 `LastErr`，退避后重试。
  - 有修复 → 立即重读；冲突 → `Conflicts++` 后退避。
  - **没有 3 次上限**：锁期间队外玩家的申请、被邀请会持续让 ver+1（:537-538）。
- **`endMatchRound`**（:594-618）：
  - S_READ 的玩家位传 0（读 `team:player:0`，该键恒不存在）。
  - 记录不存在 → RecordMissing；token 不符 → TokenMismatch；锁已不有效 → LockExpired。
  - 否则用 `ReleaseMatchLock` 生成决策，再 `commitPinned`。
- **`ReleaseMatchLockOnce`**（:587-592）：单轮、`ok=false`，受调用方 ctx 约束。

### 1.8 重试与预算一览

| 路径 | 上限 | 用尽后的结果 |
|---|---|---|
| Mutate 冲突 | 累计 3 次，含修复提交的冲突，不退避（`store.go:244-248`、`:267-271`） | 4029 |
| Mutate 修复 | 超过 5 次（:254-256） | 4029 |
| repairIndexMismatch 级联 | 5 轮（:665、:683） | error，服务层回 4030 |
| ReadFree | 3 次 | `ErrUnstableRead`，服务层回 4029 |
| ReadMembers | 3 次 | `ErrMembersChanged`，放弃这次推送 |
| StartTeamMatch 整轮 | 3 轮，且受请求 ctx 约束（`service.go:350`） | 4029 |
| EndMatch | 110 s 单调时钟；每轮 2 s；退避 50 ms → 1 s，±20% | `EndMatchDeadline`，锁靠自然过期 |
| 推送 + scene 信号 | 每批 3 s，独立 ctx（`notify.go:76`） | 剩余收件人记 error |
| home zone | 1500 ms，嵌在请求 ctx 内（`homezone.go:31-33`、`:60-61`） | 4030 |

### 1.9 单键丢失与自愈

| 丢失或矛盾 | 自愈方式 | 出处 |
|---|---|---|
| 在队成员的玩家索引被删 | 本队下一次提交时，K 判定对缺失放行，setIdx 按 TIME+1 重建 | `scripts.go:69-70`、`:100-104` |
| 保留成员的索引指向别队，或为 `"0"` | 回 `{-2}`，生成修复提交（HEALED）移出这些成员，再重算原操作 | `store.go:253-274` |
| 索引指向的记录已不存在（孤儿索引） | ReadFree / Mutate 执行 S_HEAL_ORPHAN：索引置 0、epoch+1 | `store.go:204-213`、`:302-309` |
| 投影缺失 | 下一次提交时 SET，或 S_TOUCH 按该版记录重写 | `scripts.go:98`、`:205-209` |
| 索引指向本队、记录里却没有我 | LeaveTeam 提交 L=[caller]，把索引置 0 | `rules.go:607-613` |
| 整队空闲 24 h 过期 | 不做提交。读路径回报 epoch=nowMs，客户端会接受这份空视图 | `scripts.go:134-139` |

离线队员不会被自动移出，一直留在队里，直到被踢或者队伍空闲 24 h 过期；也没有后台扫描器（`team-system.md:811-816`）。

### 1.10 时钟规则

- **规则层**：纯函数，`now` 就是 S_READ 的 Redis TIME（`rules.go:13-15`；`store.go:218`）。
- **一律取 SharedRedis TIME 的时间**（`scripts.go:8-10`；`team-system.md:486`）：
  - `created / joined / applied / invited_at`；
  - 各 `expire_at`；
  - 开战锁截止（`service.go:384`）；
  - 视图的 `server_time_ms`。
- **S_COMMIT 自己的 TIME 只用于三件事**：epoch 起种、IA 上限计数、写 IA 前清过期项（`scripts.go:61-63`、`:76`、`:120`）。
  - 所以记录里的 expire 来自 S_READ 时刻，而被邀请人的上限按 S_COMMIT 时刻计。
- **进程内单调时钟只用于**：EndMatch 的截止与退避（`store.go:548`、`:557`、`:686-689`），以及请求预算。
- **测试**：
  - miniredis 的时钟固定（`store_test.go:5-6`）；
  - 规则测试用固定的 `ruleNow`（`rules_test.go:15-19`）；
  - 用 Redis 时钟驱动过期的测试见 `store_test.go:611` `TestRedisClockDrivesExpiry`。

---

## 2 规则（状态机）

`rules.go` 全是纯函数：不做 I/O，不读墙钟，冲突后会被重新调用，所以必须幂等、可以重算（`rules.go:11-16`）。

### 2.0 输入

- **`SessionState`**（`rules.go:39-50`），由 `presence.loadSessions` 填（`presence.go:56-82`）：

  | 状态 | 何时 | 规则层怎么用 |
  |---|---|---|
  | `SessionUnknown` | map 里缺项（零值）：MGET 整批失败或单项解析失败 | 不算在线，也不触发惰性转让（fail-closed） |
  | `SessionAbsent` | 键不存在（正常登出或租约到期） | **唯一**触发惰性转让队长的状态 |
  | `SessionPresent` | 键存在但不是 ONLINE（如 DISCONNECTING 的 30 s 重连宽限） | 不算在线，也不算离线 |
  | `SessionOnline` | `SESSION_STATE_ONLINE` | 在线 |

  Store 的 sessions 为 nil 时，全员视为 Unknown（`store.go:69-73`）。
- **`Op`**（`rules.go:79-95`）一律用构造函数创建（:97-137）。
  - `CallerTeamId` 由 Mutate 从同一次 S_READ 的 `tidNow` 填入（`store.go:217`）。
  - `CallerZone` 用于 Create / Apply，`TargetZone` 用于 Invite，`NewTeamId` 用于 Create，`Accept` 用于审批和应邀。
- **`nowMs`** = 本轮 S_READ 的 Redis TIME（`store.go:218`）。
- **`RuleConfig{AllowCrossZone}`**（`rules.go:56-59`）。

### 2.1 公共流水线 `Apply(op, rec, nowMs, sessions, cfg)`（`rules.go:221-259`）

1. `OpCreate` → `applyCreate`：不做清理，也不做惰性转让（:224-226）。
2. 其余操作遇到 `rec == nil` → 4013（:227-229）。
3. 克隆记录，入参永不修改（:230；`rules_test.go:68` `TestApplyRejections` 钉住）。
4. `pruneExpired`：只保留 `expire_at_ms > nowMs` 的申请和邀请，同时记下 `appsExpired` / `invitesExpired`（:234、:854-874）。
5. `lazyTransferLeader`（:235、:876-895）。
6. 分派到具体操作（:237-257）；未知 OpKind → 4030。
7. `finish(orig)`（:258）。

要点：
- **业务拒绝会丢掉这一轮的清理和惰性转让**：`finish` 先判 `code≠0` 就直接 reject（:748-750），Mutate 什么都不写。所以一个被拒的请求不会触发 LEADER_OFFLINE_TRANSFERRED。
- **队长判定基于惰性转让之后的记录**（`isLeader`，:437-439）：新队长在同一次请求里就能执行队长操作（`rules_test.go:222` `TestLeaderHandover`）。
- `fail` 只记第一个错误码（:425-429）。

### 2.2 各操作（判定顺序即代码顺序）

#### Create（`rules.go:821-850`）

**判定顺序**
1. `rec≠nil || NewTeamId==0 || Caller==0` → 4030
2. `CallerZone==0` → 4019
3. `CallerTeamId≠0` → 4003（无 param）

**结果**
- 新记录 `{team_id, leader=caller, zone=CallerZone, created_at=now, members=[{caller, zone, joined=now, seq=1}], next_join_seq=2}`。
- J=[caller]，Reason=CREATED，Actor=caller。提交时 `expectedVer="new"`（`store.go:232-234`）。

#### Refresh（GetMyTeam、StartTeamMatch 第 1 步；`rules.go:238`）

- 没有自身操作，只有发生了清理或惰性转让时才 Changed。
- 「清理完再读」不再变化（`rules_test.go:286` `TestApplicationsFifoRefreshAndExpiry`）。

#### Apply：申请加入（`rules.go:458-485`）

**判定顺序**
1. caller 已是本队成员，或 `CallerTeamId≠0` → 4003
2. `zoneCheck(CallerZone)`：zone=0 → 4019；`!AllowCrossZone && zone≠rec.zone_id` → 4020（:446-456）
3. 满员 → 4002

**写入**
- 已有申请：刷新 `expire = now+120000`、`zone = CallerZone`，`applied_at` 不变。
- 没有申请：追加一条，再淘汰到 10 条（本次刚加的永不淘汰）。
- 提交集合：K = 全员；Reason=APPLICATION_CHANGED，Actor=caller。

**不检查的项**
- 不检查开战锁。
- 被淘汰的申请人不收任何通知。

#### HandleApplication：审批（`rules.go:487-524`）

1. 调用者不是队长（惰性转让之后判）→ 4018。
2. **拒绝**：
   - 申请不存在（已清理过期项）→ 幂等成功、无变化。
   - 否则删除申请，`RejectedApplicant = applicant`；Reason=APPLICATION_CHANGED，Actor=申请人；K = 全员。不检查锁。
3. **同意**，依次判：
   1. 申请人已是成员 → 幂等成功
   2. 申请不存在或已过期 → 4011（`expire==now` 算过期）
   3. 开战锁有效 → 4023
   4. 满员 → 4002
   5. `zoneCheck(申请记录里的 zone)` → 4019 / 4020（开关中途改成 false 也能拦住）
   6. `addMember(applicant, app.zone)`；Reason=MEMBER_JOINED，Actor=申请人。J=[申请人]，K=原成员；申请人在本队若还有邀请，进 InvitesRemoved。

#### Invite：邀请（`rules.go:526-565`）

**判定顺序**
1. 非队长 → 4018
2. `target==0 || target==caller` → 4001
3. target 已是本队成员 → 4003（param=target）
4. `zoneCheck(TargetZone)`
5. 满员 → 4002

**写入**
- `expire = now+60000`。
- 已有邀请：刷新 expire、`inviter_id=caller`、zone，`invited_at` 不变。
- 没有邀请：追加一条，再淘汰到 10 条（本次的 target 永不淘汰）。
- `InvitesAdded += {target, expire}`，**刷新时也加**。
- `InvitedPlayer = target`；Reason=INVITE_CHANGED，**Actor = 被邀请人**。
- 提交集合：K = 全员；IA=[target]；ID = (被淘汰者 ∪ 过期被清者) \ IA（:805-817；`rules_test.go:327` `TestInvitesFifoRefreshAndExpiry`）。

**不检查的项**
- 不检查开战锁。
- 目标是否在线、是否在任何队，由服务前置检查。

#### RespondInvite：应邀（`rules.go:567-602`）

1. 取给自己的邀请（已清理过期项）。
2. **拒绝**：
   - 没有邀请 → 幂等成功。
   - 否则删除邀请；Reason=INVITE_CHANGED，Actor=caller；K = 全员，ID=[caller]。
3. **接受**，依次判：
   1. 已是本队成员 → 幂等成功
   2. 没有邀请或已过期 → 4021
   3. 开战锁有效 → 4023
   4. `CallerTeamId≠0 && ≠本队` → 4003
   5. 满员 → 4002
   6. `zoneCheck(邀请记录里的 zone)`
   7. `addMember`；Reason=MEMBER_JOINED，Actor=caller。J=[caller]，K=原成员，ID=[caller]。

#### Leave：离队（`rules.go:604-621`）

1. caller 不在成员里：
   - 若 `CallerTeamId == 本队`（矛盾态：索引指向本队、记录里没有我）→ `extraLeft=[caller]`，Reason=MEMBER_LEFT，提交 L=[caller]、K=全员，把索引置 0。
   - 否则无变化。走 Mutate 时 BindCaller 保证 `CallerTeamId==本队`，所以「否则」只在纯规则测试里可达。
2. 开战锁有效 → 4023。
3. `removeMember(caller)`；Reason=MEMBER_LEFT，Actor=caller。
   - 最后一人离开即解散：Reason 仍是 MEMBER_LEFT，同时 `Disbanded=true`。
   - 移出的是队长时，按 `pickLeader(onlineOnly=false)` 选新队长。

#### Kick：踢人（`rules.go:623-643`）

1. 非队长 → 4006
2. `target==caller` → 4005
3. target 不在成员里 → 4004（param=target；target=0 时不带参数）
4. 开战锁有效 → 4023
5. `removeMember(target)`；Reason=MEMBER_KICKED，Actor=target。K=其余成员，L=[target]。队长不可能被踢，所以不会触发转让或解散。

#### TransferLeader：转让队长（`rules.go:645-678`）

1. `target==0` → 4001
2. `target==caller` → 4007。有意排在「目标已是队长」之前，否则 4007 永远走不到（:645-646）
3. target 已是队长（惰性转让之后判）→ 幂等成功
4. 调用者不是队长 → 4008
5. target 不是成员 → 4004（param=target）
6. `sessions[target] ≠ Online` → 4024（param=target）。Unknown / Present / Absent 都算失败
7. 开战锁有效 → 4023
8. `leader=target`；Reason=LEADER_TRANSFERRED，Actor=target；K = 全员。

#### Disband：解散（`rules.go:680-692`）

1. 非队长 → 4014
2. 开战锁有效 → 4023
3. 清空成员，调用 `dissolve()`；Reason=DISBANDED，Actor=caller。
   - 结果：Record=nil；L = 全体原成员。
   - ID = 原记录里**全部**被邀请人（含已过期的）。
   - `RevokedInvitees` = 未过期邀请的被邀请人。

### 2.3 辅助函数

- **`addMember`**（`rules.go:694-715`）：
  - 按 §1.2 第 4 条取 seq，追加 `{pid, zone, joined=now, seq}`，`next_join_seq=seq+1`；
  - 排序；
  - 删掉此人在本队的申请和邀请。
- **`removeMember`**（`rules.go:717-734`）：
  - 剩余为空 → `dissolve`；
  - 移出的是队长 → `pickLeader(kept, sessions, false)`。
- **`pickLeader`**（`rules.go:897-910`）：
  - 候选按 `(join_seq, player_id)` 排序，取第一个 Online 的。
  - 没有 Online 时：`onlineOnly=false` 取 join_seq 最小者，`onlineOnly=true` 返回 0。
- **`lazyTransferLeader`**（`rules.go:876-895`）：
  - 只有队长是成员、且 `sessions[leader]==Absent` 时才触发；
  - 在其余成员里取 Online 且 join_seq 最小者；没有就不转。
- **`dissolve`**（`rules.go:736-744`）：
  - 标记解散；
  - `revoked` = 当前（已清理过期项的）邀请的被邀请人；
  - 同时清空邀请**和申请**。
- **`pruneExpired`**：`expire_at_ms <= now` 即视为过期（`rules.go:857`、`:866`）。导出的 `PruneExpired` 不改入参，供视图层使用（:210-219；`rules_test.go:513`）。
- **淘汰**（`evictOldestApplications` / `evictOldestInvites`，`rules.go:978-1028`）：
  - 只在追加新条目时触发，循环直到不超过 10 条；
  - 每轮淘汰 `applied_at` / `invited_at` 最小的一条，同刻按 id 较小者；
  - **永不淘汰 `keep`**（本次刚加的那一条）。

### 2.4 `finish`：汇总成 `Decision`（`rules.go:746-819`）

**Decision 的三种形态**（:146-153）

| 形态 | 含义 |
|---|---|
| `Code≠0` | 业务拒绝，什么都不写 |
| `Code==0 && !Changed` | 成功但无需提交 |
| `Code==0 && Changed` | 需要提交；`Record=nil` 表示解散 |

**Reason / Actor 的优先级**（:761-770）
1. 自身操作有变更 → (操作的 reason, 操作的 actor)
2. 否则发生了惰性转让 → (`LEADER_OFFLINE_TRANSFERRED`, 新队长)
3. 否则有申请过期 → (`APPLICATION_CHANGED`, 0)
4. 否则 → (`INVITE_CHANGED`, 0)

`LeaderOfflineTransferred` 与自身操作同时发生时也会带上（:752），它会把推送范围扩到全员（§4.4）。

**集合怎么算**
- `Kept` / `Joined`：新记录的成员按 join_seq 顺序，按是否在旧记录里分组。
- `Left`：旧成员（按 join_seq 顺序）中不在新记录里的，再追加 `extraLeft`。
- `InvitesAdded = added`。
- `InvitesRemoved`：原记录的 invites 按数组顺序去重，排除仍在新记录里的、以及在 IA 里的。

**集合契约**：`J ∪ K == 新成员`；`L ⊇ 旧 \ 新`；J / K / L 两两不相交；`IR ∩ IA == ∅`（:152-153）。

### 2.5 各操作的 Reason / Actor

| 操作 | Reason（值） | Actor |
|---|---|---|
| Create | CREATED(1) | caller |
| 同意申请 / 接受邀请 | MEMBER_JOINED(2) | 加入者 |
| Leave（含最后一人离开导致解散） | MEMBER_LEFT(3) | caller |
| Kick | MEMBER_KICKED(4) | target |
| TransferLeader | LEADER_TRANSFERRED(5) | target |
| 只有惰性转让 | LEADER_OFFLINE_TRANSFERRED(6) | 新队长 |
| Disband | DISBANDED(7) | caller |
| Apply / 拒绝申请 | APPLICATION_CHANGED(8) | 申请人 |
| 只有申请过期 | APPLICATION_CHANGED(8) | 0 |
| Invite | INVITE_CHANGED(9) | **被邀请人** |
| 拒绝邀请 | INVITE_CHANGED(9) | caller |
| 只有邀请过期 | INVITE_CHANGED(9) | 0 |
| notify_online（不经规则层） | MEMBER_ONLINE(10) | caller |
| LockMatch | MATCH_STARTED(11) | caller |
| ReleaseMatchLock 成功 / 失败 | MATCH_ENDED(12) / MATCH_FAILED(13) | 0 |
| RepairRemoveMembers | HEALED(14) | 第一个实际被移出的成员 |

### 2.6 开战锁规则

- **`MatchLockActive`**（`rules.go:185-187`）：`rec≠nil && token≠"" && now < expire`。`now==expire` 算已过期（`rules_test.go:161` `TestMatchLockActiveBoundary`）。
- **`CheckMatchStart`**（`rules.go:303-313`）：
  1. rec 为 nil → 4013
  2. `caller==0` 或 caller 不是队长 → 4018
  3. 锁有效 → 4023
- **`CheckMatchTeamSize`**（`rules.go:317-325`）：`required==0` → 4027；`|members| > required` → 4028。人数少于 required 允许开战。
- **`MatchRoster`**（`rules.go:329-342`）：队长在前（前提是队长在成员里），其余按 join_seq 升序。
- **`LockMatch`**（`rules.go:350-368`）：
  1. 先跑 `CheckMatchStart`；
  2. token 为空或 `expireAtMs ≤ now` → 4030；
  3. roster 与成员集合不等 → 4029；
  4. 克隆、清理过期项，**不做惰性转让**；
  5. 写入 token、expire、roster；Reason=MATCH_STARTED，Actor=caller；K = 全员。
- **`ReleaseMatchLock`**（`rules.go:374-391`）：
  - 遇到 rec 为 nil、token 为空、token 不符、锁已无效，任一情况都返回零值 Decision（无变化）。
  - 否则：清理过期项、做惰性转让、清空锁的三个字段；Reason 为 `ok ? MATCH_ENDED : MATCH_FAILED`，Actor=0；K = 全员。

### 2.7 `RepairRemoveMembers`（`rules.go:261-297`）

1. rec 为 nil → 4013。
2. 克隆、清理过期项、做惰性转让。
3. 逐个 pid：跳过 0 和不在记录里的；`removeMember(pid)`，并从 `MatchLockRoster` 去掉此人。
4. 至少移出一人时，Reason=HEALED，Actor = 第一个被移出者。
5. `finish(原记录)`：`Left` 始终相对原记录计算。全员被移出时解散（`rules_test.go:482` `TestRepairRemoveMember`）。

注释说「集合里没有一个在记录中时返回 !Changed」（:271），这不完全对：若同时有过期项或惰性转让，会以清理类 reason 返回 Changed。不过在 `repairIndexMismatch` 里错位者必然取自同一份记录，所以这条路径不可达。

### 2.8 状态机速览（单个玩家视角）

| 当前状态 | 事件 | 新状态 | 回包 / 推送要点 |
|---|---|---|---|
| 无队 | CreateTeam | 队长（1 人） | 0 + 视图；不推送 |
| 无队 | ApplyJoinTeam | 无队（有一条 120 s 的申请） | 0 + 自己的空视图；队长收 213 APPLICATION_CHANGED |
| 无队、申请中 | 队长同意 | 成员 | 新人收 213 MEMBER_JOINED |
| 无队、申请中 | 队长拒绝 | 无队 | 申请人收 203 APPLICATION_REJECTED |
| 无队、被邀请（60 s） | 接受 | 成员 | 其余成员收 MEMBER_JOINED |
| 无队、被邀请 | 拒绝 / 过期 / 被淘汰 | 无队 | 拒绝时队长收 INVITE_CHANGED；过期 / 淘汰不通知 |
| 无队、被邀请 | 队伍解散 | 无队 | 收 203 INVITE_REVOKED |
| 成员 | LeaveTeam | 无队（epoch+1） | 其余成员收 MEMBER_LEFT；最后一人离开即解散 |
| 成员 | 被踢 | 无队（epoch+1） | 被踢者收 team_id=0 的空视图 MEMBER_KICKED |
| 成员 | 队长转让给我 / 惰性转让 | 队长 | 全员收 LEADER_TRANSFERRED / LEADER_OFFLINE_TRANSFERRED |
| 队长 | DisbandTeam | 无队（epoch+1） | 其余成员收 DISBANDED 空视图；未过期的被邀请人收 INVITE_REVOKED |
| 队长 | StartTeamMatch 加锁 | 队长（STARTING） | 除发起人外全员收 MATCH_STARTED；锁期间改名单回 4023 |
| STARTING | EndMatch | 队长（IDLE） | 全员（含发起人）收 MATCH_ENDED / MATCH_FAILED |
| 任意 | 索引错位 / 孤儿索引 | 修复后的状态 | HEALED；孤儿索引被治愈后，只发 scene 信号 |

---

## 3 每个 RPC 的处理流程

### 3.0 入口外壳（`server.go`）

- **预算**：每个方法入口 `context.WithTimeout(3500ms)`（`server.go:93`、`:151`）。
  - go-zero 超时之后 handler 仍会跑完。迟到的写入靠 expected_team_id 绑定和「提交前检查 ctx」兜住（:21-23；`team-system.md:783-809`）。
- **身份**：`callerOf` 拿不到 SessionDetails 或 `player_id==0` 时，回 **in-band** `TeamResponse{error_message{4001}}`，**不带 team**。
  - ListMyInvites 回 `ListMyInvitesResponse{4001}`。
  - 指标记 `team_rpc_total{method, outcome=no_session}`（:63-71、:95-99、:153-157）。
  - 测试：`service_test.go:350` `TestMissingSessionFailsClosed`。12 个方法 × {无 session, player_id=0}，都回 4001、无视图、Redis 字节级不变、无推送、无 scene 信号。
- **推送占位方法**：客户端上行 213 / 215 / 203 时直接返回 `Empty{}`：不读、不写、不打指标（:163-175）。路由服会把空字节当成功应答回给客户端。
- **指标外壳**：业务返回后记 `team_rpc_total{method, outcome=rpcOutcome(code)}`（:89-103）。`rpcOutcome` 按码表定性：
  - 0 → ok；
  - fault → internal；
  - 其余已登记的码 → rejected；
  - 码表里没有的码 → unknown_code（:73-87）。

### 3.1 回包视图的四种来源与 `runMutate`

| 记号 | 函数 | 语义 | 出处 |
|---|---|---|---|
| **无视图** | — | 不设 `team` | |
| **[自由读]** | `respond(code,param)` → `freeView` | 对 caller 执行 `readFree` 再 `snapshotView`；读失败（含不稳定）时 team=nil | `service.go:710-723` |
| **[同源快照 snap]** | `snapshotResponse(code,param,snap)` | `snapshotViewable(snap)` 成立就用 snap 构建，否则退回 [自由读] | `service.go:699-708`、`:725-734`；`view.go:150-157` |
| **[提交视图 c]** | `commitResponse(c)` | caller 在 c 的 J/K/L 里、且提交后 tid 为 0 或本队 → 用这次提交构建；否则走 `respond(0)`，即 [自由读] | `service.go:686-697`；`view.go:170-193` |

**辅助规则**
- **`readSelf` 失败**时直接回 `TeamResponse{error_message{readFailureCode}}`，**无视图**（`service.go:736-743`）。
  - `readFailureCode`：`ErrUnstableRead` → 4029，其余 → 4030（:758-764）。
- **`readFree` 的副作用**：S_HEAL_ORPHAN 成功时，记 `team_heal_total{orphan_index}`，并给该玩家发 scene 信号 `publish(0, nil, [pid])`（:745-756）。
- **`snapshotViewable` 的条件**：`PlayerTeamId==0`（空视图），或 `PlayerTeamId==TeamId && Record≠nil`（`view.go:152-157`）。
- **展示缓存只在视图里有队伍时加载**：snapshotView 中 `PlayerTeamId≠0`（:729-732）；commitResponse 中 `Indexes[caller].TeamId≠0`（:691-694）。

**`runMutate` 的结局映射**（`service.go:615-648`）

| 结局 | 回包 |
|---|---|
| `Store.Mutate` 返回 error | 4030 + [自由读]（:618-622）；**不派发副作用**（res 为 nil） |
| Committed | 0 + [提交视图]（:627-628） |
| NotBound / RecordMissing | 4013 + [同源快照 freshSnapshot]。`unboundIsSuccess`（LeaveTeam、GetMyTeam）时回 0。`pruneInviteOnMissing`（RespondInvite）且记录缺失时，先删自己的邀请反查项（:629-637） |
| Unchanged | 0 + [同源快照 freshSnapshot]（:638-639） |
| Rejected 且 `Decision.Code≠0`（规则拒绝） | code + param + [同源快照 freshSnapshot]（:641-647） |
| Rejected 且 `Decision.Code==0` | code + param + [自由读]。包括：Lua `{-1}` / `{-3}`、冲突耗尽、提交前 ctx 过期、修复不收敛、建队遇到新 tid 已有记录 |

- **`freshSnapshot`**：本次 Mutate 落过修复提交、或治过孤儿索引时返回 nil，改走 [自由读]（:677-684）。
- **副作用先于回包派发**（`mutateEffects`，:650-665）：
  - 每次冲突记 `team_commit_retry_total{op=method}`；
  - 每个修复提交记 `team_heal_total{index_mismatch}`（:667-675）；
  - HealedOrphan 记 `{orphan_index}`；
  - 修复提交（在前）、主提交（在后）和 healed 一起交给 `publish(caller, …)`。

### 3.2 home zone 与 team_id 发号

**home zone**
- **接口**：`HomeZones(ctx, ids)`（`homezone.go:20-29`）。契约：
  - map 缺项或值为 0 → **4019**；
  - err≠nil → **4030**；
  - 一次最多 2 个 id。
- **基线实现**：data_service `BatchGetPlayerHomeZone`，自带 1500 ms 超时，请求 ctx 仍能截断它（:53-67）。client 为 nil（DataServiceRpc 未配置）时每次都返回错误 → 4030（:36、:57-59；`service_test.go:305` `TestDataServiceHomeZone`）。
- **`homeZoneOf`**（`service.go:766-783`）：`homeZones==nil` → 4030；err → 4030；`zone==0` → 4019。
- **只有三处查询**：CreateTeam 查自己、ApplyJoinTeam 查自己、InviteToTeam 查目标。审批和接受邀请时用记录里存的 zone 复核（`rules.go:518-521`、`:597-599`）。

**team_id 发号**
- `newTeamId`（`service.go:785-797`）：发号器为 nil、出错、或返回 0 → error → 4030。
- 生产用 `svcCtx.BattleIDGen`，失租被 fence 后返回错误（:34-38）。

### 3.3 CreateTeam（214，`service.go:113-134`）

1. `readSelf` 失败 → 4029 / 4030，无视图（:115-118）。
2. `self.PlayerTeamId≠0` → **4003** + [同源快照 self]，即当前队伍的视图（:119-121）。
   - 这也是重放语义：客户端把「4003 且 `team.leader_id==自己`」当作成功（`TeamClient.cs:416-419`）。
3. `homeZoneOf(caller)` → 4030 / 4019 + 空视图（:122-125）。
4. `newTeamId()` 失败 → 4030 + 空视图（:126-130）。
5. `runMutate(BindCreate(caller,tid), CreateOp)`（:131-132）。Mutate 内可能出现：
   - 新 tid 已有记录 → 4030 + [自由读]；
   - 规则 4003（两次读之间入了队）→ [同源快照] 不可同源，退回 [自由读]；
   - S_COMMIT `{-1,1}` → 4003，param=caller，[自由读]。

- **提交**：J=[caller]，Reason=CREATED，Actor=caller。
- **回包**：0 + [提交视图]。
- **推送**：无（`notify.go:160-161`）。
- **scene**：通知 caller（`service_test.go:423` `TestPushAndSceneRecipients`）。

### 3.4 GetMyTeam（207，`service.go:136-163`）

1. `readSelf` 失败 → 4029 / 4030，无视图。readSelf 会治孤儿索引，并给 caller 发 scene 信号。
2. `self.Record==nil`（ReadFree 保证这等价于无队）→ 0 + 空视图（:142-144）。
   - 空视图的 epoch = 索引 epoch；索引缺失时为 Redis nowMs。
3. `runMutate(BindCaller(caller, self.TeamId), RefreshOp, unboundIsSuccess)`（:146-147）：
   - 只做清理和惰性转让，有变化才提交。
   - 未绑定或记录缺失时回 0 + 当时的视图（`team-system.md:1628`）。
4. 结局为 Unchanged 且 `NeedsTouch` → 用请求 ctx 执行 `store.Touch`；出错只记日志（:148-152）。
5. `notify_online=true` 且回包 `team.team_id≠0` → `publishOnline(caller, tid, 回包视图里的成员 id)`（:153-161；§4.6）。
   - 注意：只要回包视图里有队伍就会触发。回包带 4029 / 4030、但 [自由读] 成功时也一样。

- **提交**（如有）：Reason 为 LEADER_OFFLINE_TRANSFERRED，或 APPLICATION_CHANGED / INVITE_CHANGED（过期清理）。
  - 发生转让时推全员（除 caller）；单纯过期清理只推队长（除 caller）。
- **scene**：无，因为没有人的 tid 变化。
- **可能的码**：0、4029、4030。

### 3.5 ApplyJoinTeam（206，`service.go:165-193`）

1. `target==0 || target==caller` → **4001** + [自由读]（:167-170）。
   - 这个 4001 **带**调用者视图；缺 session 的 4001 不带。
2. `readSelf` 失败 → 4029 / 4030，无视图（:172-175）。
3. `self.PlayerTeamId≠0` → **4003**（无 param）+ [同源快照 self]（:176-178）。
4. `readFree(target)` 失败 → 4029 / 4030 + self 视图（:179-182）。
   - 这一步会顺手治目标的孤儿索引，并给目标发 scene 信号。
5. `other.PlayerTeamId==0` → **4013** + self 视图（:183-185）。
   - 文案「你还没有队伍」对申请人有误导，见 §8.1。
6. `homeZoneOf(caller)` → 4030 / 4019 + self 视图（:186-189）。
7. `runMutate(BindTarget(caller, other.tid), ApplyOp(caller, zone))`（:190-191）：
   - 记录缺失 → 4013；
   - 规则层见 §2.2 Apply（4003 / 4019 / 4020 / 4002）；
   - **不检查开战锁**（`team_battle_test.go:613-614`）。

- **提交**：K=全体成员；Reason=APPLICATION_CHANGED，Actor=caller。
- **回包**：0 + [提交视图]。申请人不在 J/K/L，所以退回 [自由读]，拿到的是申请人自己的视图（一般 team_id=0）。
- **推送**：队长收 213 APPLICATION_CHANGED，视图里带申请；同一次提交若发生了惰性转让，改推全员。
- **scene**：无。
- **重放**：刷新过期时间，成功。

### 3.6 HandleApplication（208，`service.go:195-201`）

服务层**没有前置检查**，`applicant_id=0` 也直接进规则：结果为 4018 / 4011 / 幂等成功之一。

1. 绑定：
   - `expected≠调用者当前 tid` → **4013** + 视图（能同源就同源，否则 [自由读]）；
   - 记录缺失 → 治孤儿后回 4013 + [自由读]。
2. 规则见 §2.2 HandleApplication（4018 / 4011 / 4023 / 4002 / 4019 / 4020）。
3. S_COMMIT `{-1,1}`（申请人已被别的队收走）→ **4003**，param=申请人，[自由读]。

- **回包**：0 + [提交视图]。队长视图带剩余申请（`service_test.go:423`）。
- **推送**：
  - 同意：除 caller 外的 J∪K 收 MEMBER_JOINED。新人的视图里没有申请列表，只有 application_count。
  - 拒绝：队长就是 caller，不收快照；申请人收 203 `{APPLICATION_REJECTED, team_id, actor_id=记录里的队长}`（`notify.go:129-136`）。
- **scene**：同意时通知新人。
- **重放**：同意时已是成员 → 成功；拒绝时申请已不存在 → 成功（`robot/team_smoke_scenario.go:303-316`）。

### 3.7 InviteToTeam（201，`service.go:203-231`）

**前置检查都在绑定之前**，所以调用者不在队时，也可能先拿到下面这些码，而不是 4013。

1. `target==0 || target==caller` → **4001** + [自由读]（:205-208）。
2. `LoadSession(target)` 出错 → **4030** + [自由读]（:209-213）。
3. 会话不是 `SESSION_STATE_ONLINE`（不存在或 DISCONNECTING）→ **4017** + [自由读]（:214-216；`playercontract.go:87-92`）。
4. `readFree(target)` 失败 → 4029 / 4030 + [自由读]（:217-220）。
5. 目标已在任何队 → **4003**，param=target，+ [自由读]（:221-223）。
6. `homeZoneOf(target)` → 4030 / 4019 + [自由读]（:224-227）。
7. `runMutate(BindCaller(caller, expected), InviteOp(caller, target, zone))`（:228-229）：
   - 绑定失败 → 4013；
   - 规则见 §2.2 Invite（4018 / 4001 / 4003[target] / 4019 / 4020 / 4002）；
   - **不检查开战锁**。
8. S_COMMIT `{-3,1}`：被邀请人在**别的队**的未过期邀请已有 10 条（本队已有的条目不算新增）→ **4022**，param=target（`scripts.go:72-80`；`store.go:251-252`）。

- **提交**：K=全体；IA=[target]；ID = 被淘汰者和过期被清者，减去 IA。
- **回包**：0 + [提交视图]，队长视图带 pending_invites。
- **推送**：
  - 队长就是 caller，不收快照。
  - 被邀请人收 215 `TeamInviteS2C{invite=incomingInviteView(记录, target, c.NowMs), server_time_ms=c.NowMs}`（`notify.go:123-128`）。
  - 被淘汰的旧被邀请人**没有任何通知**。
- **scene**：无。
- **重放**：刷新过期时间，成功，并且**再推一次 215**。

### 3.8 RespondInvite（204，`service.go:233-251`）

1. `team_id==0` → **4013** + [自由读]（:235-238）。会读 Redis 取视图，见 §8.4。
2. 仅当 `accept=true` 时：
   - `readSelf` 失败 → 4029 / 4030，无视图；
   - `self.PlayerTeamId≠0 && ≠team_id` → **4003** + self 视图（:239-247）。
3. `runMutate(BindTarget(caller, team_id), RespondInviteOp, pruneInviteOnMissing)`（:248-249）：
   - 记录缺失 → 先执行 `pruneOwnInvite`：`ListInvites`，再对该 tid 按 score 做 CAS 删除，失败只记日志（:799-814）。然后回 **4013** + 视图。
   - 规则见 §2.2 RespondInvite（4021 / 4023 / 4003 / 4002 / 4019 / 4020）。
4. S_COMMIT `{-1,1}` → 4003，param=caller，[自由读]。

- **回包**：
  - 接受成功：[提交视图]（caller ∈ J）。
  - 拒绝成功：caller 不在 J/K/L，所以是 [自由读]，即 caller 自己的视图。
- **推送**：
  - 拒绝：队长收 INVITE_CHANGED（若队长不是 caller）。robot S3 断言其中已没有 D（`team_smoke_scenario.go:334-344`）。
  - 接受：除 caller 外的全体收 MEMBER_JOINED。
- **scene**：接受时通知 caller。
- **客户端**：收到成功、4021、4013 时，都从本地邀请列表移除该条（`TeamClient.cs:429-438`）。

### 3.9 ListMyInvites（205，`service.go:253-300`）

1. `store.ListInvites(caller)`，即 S_INVITE_LIST：按 Redis 时钟删掉过期项（**这个读 RPC 会写**）。
   - 出错 → `{error_message{4030}}`，invites 为空，`server_time_ms=0`（:256-260）。
2. 对每一项执行 `store.read(caller, tid)`。**任一项出错，整个 RPC 回 4030**（:267-272）。
3. 记录里还有给我的未过期邀请 → 保留，并记下 inviter_id。
   - 否则（记录不存在或邀请已失效）→ `PruneInvite(caller, tid, score)`：只在 score 没变时删，失败只记日志（:273-285）。
   - `service_test.go:739` `TestListMyInvitesPruneKeepsReinvitedIndex` 钉住「队长刚重邀写入的新项不会被误删」。
4. 只对 inviter 们加载一次展示缓存（:286）。
5. 每条构建 `incomingInviteView(该队记录, caller, 该队那次读的 nowMs)`（`view.go:113-133`）。
6. 排序：`expire_at_ms` 升序，同值按 team_id 升序（:293-298）。`server_time_ms` 取 S_INVITE_LIST 的 nowMs（:287）。
7. 条数上限由 S_COMMIT 的「每个被邀请人 10 条」保证。**不过滤**「我已在别的队」「对方队已满」的邀请。

- **推送 / scene**：无。

### 3.10 LeaveTeam（210，`service.go:302-307`）

`unboundIsSuccess=true`：
1. 未绑定（expected≠当前 tid，含 expected=0 或无队）→ **0** + 视图。视图可能是调用者的新队 B（`service_test.go:613` `TestLateLeaveDoesNotHurtNewTeam`）。
2. 记录缺失 → 治孤儿 → 0 + [自由读]（空视图，epoch 前进），并给 caller 发 scene 信号。
3. 规则见 §2.2 Leave（矛盾态修复 / 4023 / 解散 / 换队长）。

- **回包**：0 + [提交视图] = 空视图 `{capacity=5, membership_epoch=提交后的新 epoch, server_time_ms=c.NowMs}`。
- **推送**：
  - 剩余成员收 MEMBER_LEFT，若换了队长，视图里能看到。
  - 若解散（最后一人）：没有快照收件人；未过期邀请的被邀请人收 203 `{INVITE_REVOKED, team_id, actor_id=caller}`。
- **scene**：通知 caller。

### 3.11 KickMember（202，`service.go:309-314`）

服务层不检查 target。
1. 绑定失败 → 4013 + 视图。
2. 规则见 §2.2 Kick（4006 / 4005 / 4004[target] / 4023）。

- **回包**：[提交视图]。
- **推送**：
  - 剩余成员（除 caller）收 MEMBER_KICKED；
  - 被踢者收 **team_id=0 的空视图** MEMBER_KICKED，epoch 是提交后的新值。robot S4 断言这个 epoch 严格变大（`team_smoke_scenario.go:362-380`）。
- **scene**：通知 target。
- **重放**：4004 + 视图。

### 3.12 TransferLeader（212，`service.go:316-321`）

- 绑定失败 → 4013。规则见 §2.2 TransferLeader（4001 → 4007 → 幂等 → 4008 → 4004 → 4024 → 4023）。
- **推送**：除 caller 外的全体。
- **scene**：无。C++ 的跟随逻辑读投影里的 leader_id。

### 3.13 DisbandTeam（209，`service.go:323-328`）

- 绑定失败 → **4013** + 当前视图（重放语义；`service_test.go:644` `TestLateDisbandDoesNotHurtNewTeam`）。
- 规则见 §2.2 Disband（4014 / 4023）。
- **提交**：记录和投影 DEL；L = 全体旧成员；ID = 全部邀请。
- **回包**：空视图，带 caller 的新 epoch。
- **推送**：
  - 其余成员收空视图 DISBANDED；
  - 未过期邀请的被邀请人收 203 INVITE_REVOKED，actor=caller。
- **scene**：全体旧成员。

### 3.14 StartTeamMatch（211）

见 §5。

### 3.15 重放与迟到执行（设计稿 `team-system.md:783-809`，已用代码核对）

| RPC | 第二次执行的结果 |
|---|---|
| CreateTeam | 4003 + 当前视图；客户端在 `leader_id==自己` 时视为成功 |
| ApplyJoinTeam / InviteToTeam | 刷新过期时间，成功；Invite 会再推一次 215 |
| 同意申请 / 接受邀请 | 已是成员 → 成功 + 视图 |
| 拒绝申请 / 拒绝邀请 | 申请或邀请已不存在 → 成功 |
| LeaveTeam | 绑定失败 → 成功 + 当前视图 |
| TransferLeader | 目标已是队长 → 成功 |
| KickMember | 4004（param=target）+ 视图 |
| DisbandTeam | 4013 + 当前视图 |
| StartTeamMatch | 4023 + STARTING 视图，客户端视为进行中（`TeamClient.cs:420-426`） |

迟到执行，指旧请求在状态变化之后才落地：

| 迟到的旧请求 | 期间的状态变化 | 结果 |
|---|---|---|
| Leave(A) | 玩家已加入 B | 成功、不写，回 B 的视图 |
| Disband(A) | 玩家已新建 B | 4013 + B 的视图 |
| StartTeamMatch(A) | 玩家已在 B 当队长 | 4013，不给 B 开战（`team_battle_test.go:433`） |
| Kick(A, X) | X 被踢后又被批准回到 A | X 再次被踢。设计上接受：版本号和视图都能看出来 |

**提交结果未知（EVAL 被重发）**：同一段 S_COMMIT 已经落盘、但回复丢失、客户端驱动又重发时，第二次会得到 `{0}`，Mutate 随即重读重算（go-redis 会重发，见 `service.go:404-405`；Redisson 也会，见 `architecture.md:474-476`）。
- 大多数操作按上表的重放语义收敛。
- **CreateTeam 例外**：重读时发现新 tid 已有记录，走「发号器故障」分支，回 **4030**，但 [自由读] 视图里已经能看到建好的队伍（§8.1 第 9 条）。

---

## 4 推送与在线

### 4.1 视图构建（`view.go`）

**同源原则**：一份视图里的 team_id、version、epoch 和记录必须出自**同一次原子操作**。只有三个入口（`view.go:14-23`）：

| 入口 | 原子来源 | 可构建的条件 |
|---|---|---|
| `viewFromSnapshot` | S_READ（调用者索引 + 记录） | `PlayerTeamId==0`，或 `==TeamId` 且记录存在（:159-168） |
| `viewFromCommit` | S_COMMIT 返回的每人 `(tidAfter, epoch)` | pid ∈ J/K/L，且 tid 为 0，或 tid 为本队且记录非 nil（:170-193） |
| `viewFromMembers` | S_READ_MEMBERS（记录 + 各成员索引） | 成员索引 tid == 本队（:195-205） |

不能构建时一律返回 `ok=false`：推送时不推，回包时改走 [自由读]。

**`teamViewFor` 的字段**（`view.go:35-92`）

| 字段 | 取值 |
|---|---|
| team_id / leader_id / zone_id | 取自 `PruneExpired(rec, nowMs)` 之后的副本 |
| capacity | 恒为 5 |
| version | 提交后的新 ver，或读到的 ver |
| membership_epoch | **接收者本人**的索引 epoch，与视图出自同一次原子操作 |
| match_state | `MatchLockActive(live, nowMs)` → STARTING，否则 IDLE（:54-56） |
| members | `MemberIds(live)`，按 join_seq 升序、同值按 player_id 升序；每人一个 `memberView(pid, 成员 zone, join_seq, leader, dc)` |
| application_count | 未过期申请数，**所有成员可见** |
| applications | **只给队长**（`viewer≠0 && viewer==leader`）。按 `applied_at_ms` 升序、同值按 player_id 升序。每条 `TeamApplicationView{player=memberView(pid, 申请 zone, join_seq=0), applied_at_ms, expire_at_ms}` |
| pending_invites | **只给队长**。按 `invited_at_ms` 升序、同值按 invitee_id 升序。每条 `TeamOutgoingInviteView{invitee=memberView(pid, 邀请 zone, 0), expire_at_ms}` |
| server_time_ms | 来源操作的 Redis 时钟；提交时取**该轮 S_READ** 的 nowMs |

**其他视图**
- **空视图** `emptyTeamView(epoch, nowMs)`：只设 capacity=5、membership_epoch、server_time_ms，其余为零（:25-33）。被踢者、被解散者、离队者收到的就是它。
- **`memberView`**（:94-111）：
  - name / level / class_id / appearance_id / gender、is_online、in_battle 取自展示缓存，缺失时为零值；
  - `is_leader = pid==leader`；
  - zone_id 和 join_seq 由调用方传入。
- **`incomingInviteView`**（:113-133）：
  - 邀请人可能已离队，此时 zone 和 join_seq 为 0；
  - `member_count = len(members)`；
  - 记录里没有给该被邀请人的未过期邀请时返回 nil。
- **`rosterIds`**（:135-148）：成员 ∪ 申请人 ∪ 被邀请人 ∪ 邀请人。一次提交或一次读只加载一份展示缓存，所有接收者复用。

### 4.2 客户端怎么应用视图，失败怎么分层（Java 的推送与回包必须满足）

**排序规则**（`TeamViewMapper.cs:135-143`；`team-system.md:1107-1117`）
- incoming 的 epoch 更大 → 接受；更小 → 丢弃。
- epoch 相等但 team_id 不同 → **Conflict**：丢弃并重拉。
- 同队时 `Version >= 当前` 就接受。**相等也接受**，因为 MEMBER_ONLINE 不涨 version。
- Reset 后的第一份视图无条件接受（:138）。

**第二套排序**：`TeamAppearanceTransport.IsAtLeastAsRecent` 依次比较 epoch → version → **`ServerTimeMs >=`**（`TeamAppearanceTransport.cs:87-93`）。所以 `server_time_ms` 必须是单一时钟源（Redis TIME）。用各实例自己的墙钟会有偏差，会把同 version 的 MEMBER_ONLINE 误判为旧视图。

**客户端映射**（`TeamViewMapper.cs:22-69`）
- 成员按 JoinSeq 稳定排序；
- 队长以 `LeaderId` 为准；
- `Capacity=0` 时按 5 处理；
- `TeamRole.IsOnline` 的类默认值是 true，必须用服务端的值覆盖（`TeamModels.cs:21`）。

**失败分层**（`TeamClient.cs`）

| 线上形态 | 客户端动作 |
|---|---|
| in-band tip，带 `team` | 按排序规则应用视图，状态栏写 DescribeTip 的文案（:411-440） |
| in-band tip，**不带** `team`（4029 / 4030 / 缺身份的 4001） | 不清本地快照：`RefreshQueued=true`，3 s 后重拉（:350-372、:413） |
| GetMyTeam 带 `notify_online` 但回包没有视图 | 下次重拉时再带一次 `notify_online`（:359） |
| 信封 1008（限频） | 读请求退避 3 s 后重排，写请求提示「操作太快」；**不停用**（:446-458） |
| 其他信封（1003 等） | `Suspended`：本连接内不再发任何 team 消息（:459-462） |
| 探针（首个 GetMyTeam）超时 | `Suspended` 且 `RequiresReconnect`（:464-470） |
| 其他超时 / 断线 | `RequiresReconnect`（:472-474） |

**特判**
- 重复建队：回 4003 且视图 `leader_id==自己` → 按成功处理（:416-419）。
- 重复开战：回 4023 且视图 STARTING → 提示「正在进入战斗」（:420-426）。
- RespondInvite：成功、4021、4013 都删掉本地邀请（:429-438）。

**对服务端的隐含要求**
1. 每个 TeamResponse 尽量带调用者视图，失败也带。
2. 视图同源，`server_time_ms` 取 Redis TIME。
3. **被移出者一定要收到 epoch 更大的空视图**，否则迟到的旧队视图会盖过空视图（`TeamViewMapper.cs:18-21`）。
4. 客户端本地的前置检查只是体验，服务端必须全部复核。例如客户端不拦「转让给离线成员」，由服务端回 4024（`TeamClient.cs:621-671`）。
5. 推送与回包不保序，客户端按 `(epoch, version)` 排序。邀请没有排序键，客户端用 `_pushedInvites` / `_revokedInvites` 修补（:66-67、:379-405、:534-574）。

**客户端节奏**：同一 message_id 在 2.5 s 窗口内读最多 5 次、写最多 3 次；相邻发送间隔 ≥ 0.4 s；面板打开时每 30 s 静默 GetMyTeam 一次；首个 GetMyTeam 带 `notify_online=true`（`TeamClient.cs:22-34`、:134、:204、:289）。

### 4.3 会话状态与展示缓存（基线 `presence.go`）

**`loadSessions`**（`presence.go:56-82`）：对成员 MGET `player:session:{id}`。
- 键不存在 → Absent；ONLINE → Online；其他状态 → Present；
- MGET 整批失败、或单项解析失败 → 不填，即 Unknown。
- 每轮 Mutate 都对**当前记录的成员**重读一次。

**`loadDisplay(ids)`**（`presence.go:84-105`）：去掉 0、去重，然后做三次 MGET。

| 字段 | 来源 | 失败语义 |
|---|---|---|
| Online | 同 loadSessions，`==SessionOnline` | 失败 → false |
| InBattle | `battle:lock:{id}` 值非空（咨询性） | MGET 失败 → false |
| Level / ClassId / Name / AppearanceId / Gender | `PlayerAllData:{id}` 的等级组件、class、profile（:128-147） | 缺失、解析失败、blob 里的 player_id 与 key 不符 → 全部零值 |

MGET 失败或返回长度不符时只记日志，按「全部缺失」处理，**不让 RPC 失败**（:107-126）。

**其他在线判定**
- InviteToTeam 的目标：单 GET `LoadSession`。出错 → 4030；不是 ONLINE → 4017。
- 推送：`PushToPlayer` 先读会话，不是 ONLINE → `ErrPlayerOffline`，不推（`playercontract.go:135-142`）。
- notify_online 的接收者：用展示缓存的 Online 筛选（`notify.go:192`）。

### 4.4 推送管道（`notify.go`）

**`publish(caller, commits, healed)`**（`notify.go:69-93`）
- commits 按落盘顺序传入：修复提交在前，主提交在后。commits 和 healed 都为空时直接返回。
- 整批在 `safego.Go("match.team.publish")` 里异步执行。用独立 ctx，预算 3 s，**不继承请求 ctx**。
- 单 goroutine 串行：先按 commits 顺序逐个 `pushCommit`，再对 `uniqueIds(全部提交的 Joined ∪ Left ∪ healed)` 逐个 `refreshScene`。
- 失败只记日志和指标；投递语义是至多一次。
- **caller 排除**：`pushCommit` 跳过 `pid==caller` 的快照和 INVITE_REVOKED（:112-115、:137-140）；RejectedApplicant 也排除 caller（:129）。修复提交同样排除 caller。
  - EndMatch、清锁补偿、开战结果走 `publish(0, …)`，不排除任何人（`service.go:508`、`:536`、`:562`）。

**`pushCommit`**（`notify.go:104-148`）
1. **快照 213**：收件人 = `snapshotRecipients(c)`（:150-173）。
   - 若发生了惰性转让（LOT）：J∪K∪L 全员。
   - 否则：CREATED → 无；APPLICATION_CHANGED / INVITE_CHANGED → 只有队长（记录为 nil 时无）；其余 reason → J∪K∪L。
   - 对每个收件人（排除 caller）构建 `viewFromCommit`，不可构建的跳过。
   - 消息：`TeamSnapshotS2C{team, reason=d.Reason, actor_id=d.Actor, tip=c.PushTip}`。
2. **邀请 215**：`d.InvitedPlayer≠0` 且 `incomingInviteView` 非 nil → `TeamInviteS2C{invite, server_time_ms=c.NowMs}`。
3. **203 APPLICATION_REJECTED**：`d.RejectedApplicant≠0 && ≠caller` → `{type=1, team_id=c.TeamId, actor_id=d.Record.leader_id}`。
4. **203 INVITE_REVOKED**：对 `uniqueIds(d.RevokedInvitees)` 中排除 caller 的每人 → `{type=2, team_id, actor_id=d.Actor}`。
   - **只在解散（`dissolve`）时产生**，包括显式解散、最后一人离队、修复移出全员。只含解散时未过期的邀请。
- 展示缓存只在 `d.Record≠nil` 且（有快照收件人或有被邀请人）时加载（:108-111）。

**Reason × 收件人 × scene 矩阵**

| Reason | 213 收件人（均排除 caller） | actor_id | 其他推送 | scene 信号 |
|---|---|---|---|---|
| CREATED | 无 | caller | — | caller |
| APPLICATION_CHANGED | 队长（有 LOT 时全员） | 申请人；过期清理时为 0 | 拒绝时申请人收 203 REJECTED | 无 |
| INVITE_CHANGED | 队长（有 LOT 时全员） | 被邀请人 / caller；过期清理时为 0 | Invite 时目标收 215 | 无 |
| MEMBER_JOINED | J∪K | 新人 | — | 新人 |
| MEMBER_LEFT | K（解散时无） | caller | 解散时被邀请人收 REVOKED | caller |
| MEMBER_KICKED | K ∪ {被踢者：空视图} | target | — | target |
| LEADER_TRANSFERRED | 全员 | target | — | 无 |
| LEADER_OFFLINE_TRANSFERRED | 全员 | 新队长 | — | 无 |
| DISBANDED | L（空视图） | caller | 被邀请人收 REVOKED | 全体旧成员 |
| HEALED | J∪K∪L 中可同源构建的人；被移出者的索引在别队，跳过 | 第一个被移出者 | 若因此解散，被邀请人收 REVOKED | 被移出者 |
| MATCH_STARTED | 全员（除发起人） | 发起人 | — | 无 |
| MATCH_ENDED / MATCH_FAILED | **全员，含发起人**；建票失败时带 `tip={4026,[pid]}` | 0 | — | 无 |
| MEMBER_ONLINE | 见 §4.6 | caller | — | 无 |

### 4.5 单条推送（`notify.go:208-220`）

`pushFn = playercontract.PushToPlayer`（`notify.go:62`；`playercontract.go:122-160`）：
1. 读 `player:session`，不是 ONLINE → `ErrPlayerOffline`；
2. 序列化；
3. 发 Kafka `gate-cmd_g<N>`。`gate_instance_id` 为空时 fail-closed。

结局记 `team_push_total{kind=snapshot|invite|event, outcome=ok|offline|error}`；offline 不算错误。每次推送是一次会话 GET 加一次 Kafka 写，逐人串行，共用 3 s 批预算。

### 4.6 在线态刷新 MEMBER_ONLINE（`notify.go:95-102`、`:175-206`）

1. 异步执行（`safego.Go("match.team.online")`），独立 3 s 预算。
2. `ReadMembers(tid, knownMembers)`：
   - 仍不等 → `ErrMembersChanged`，记 `team_push_total{members_changed, skipped}`，放弃；
   - 其他错误 → 记 `{snapshot, error}`；
   - 记录不存在 → 静默返回。
3. 对新记录的 `rosterIds` 加载展示缓存。
4. 对 `MemberIds(rec)` 里**排除 caller、`dc.Online` 为真、且索引 tid==本队**的成员，推 `TeamSnapshotS2C{view, reason=MEMBER_ONLINE, actor_id=caller}`。
   - version 不变；epoch 取自这次 S_READ_MEMBERS（`service_test.go:704` `TestGetMyTeamNotifyOnlineSkipsForeignIndex`）。

### 4.7 scene 刷新信号（基线 `notify.go:222-293`）

信号只表示「去 SharedRedis 读一下」，不带权威数据，乱序、重复、丢失都安全（`notify.go:33`）。
1. `LoadLocation(pid)`：出错记 `error`；没有位置记 `no_location`，此时玩家不在场景，下次进场会自己拉。
2. `SceneNodes` 为 nil，或节点查不到 → `no_node`。
3. 节点 uuid 为空 → `empty_uuid`，按防僵尸约束不发（`service_test.go:556` `TestSceneRefreshFailsClosed`）。
4. 组 `SceneCommand{DispatchEvent, player_id, target_scene_id=node_id, payload=PlayerTeamRefreshEvent{player_id}, target_instance_id=uuid, event_id=48}`，写 Kafka `scene-cmd_g<N>`：分区 = node_id % P，key = player_id。成功记 `ok`。

### 4.8 场景侧消费方（基线 C++ `player_team.{h,cpp}`）

**读的键**（`player_team.cpp:37-42`）
- `team:player:<pid>`（HMGET tid epoch）
- `team:<tid>`（GET）
- `team:rec:<tid>`（只 EXISTS）
- `player:<pid>:location`
- `battle:lock:<pid>`

**入口**（全部在 scene loop 线程上执行）
- `OnEnteredScene`：模式 FollowLeaderAndFanout（:121-124）。调用点是进游戏 / 重连 / 跨节点加载 / 同节点换图之后。刻意放在 `HandleEnterScene` 之外，避免同场景重连漏刷新（`player_team.h:114-117`）。
- `OnRefreshEvent`：模式 RefreshOnly（:126-137）。本节点找不到该玩家时丢弃；**只刷新 TeamId，不跟随**，所以入队不拉人。
- `OnBattleFreezeCleared`：模式 FollowLeader（:139-147）。

**RefreshMembership**（:149-195、:197-228）
- HMGET 结果分三态：
  - 形状不对 → Unknown，组件不动；
  - 两个 NIL → KeyMissing，无条件按 tid=0 清除；
  - 两个合法十进制 → Present。
- `ShouldApplyMembership`：KeyMissing 或无组件 → 应用；否则只在 `incomingEpoch > currentEpoch` 时应用（`player_team.h:64-76`）。
- 应用后 tid 变了，就刷新队友 AOI 优先级。
- 非 RefreshOnly 模式下，以组件的最终状态继续 `LoadTeamInfo`。

**LoadTeamInfo**（:230-348）
- GET 投影出错 → 返回。
- 投影为 NIL → `EXISTS rec`：
  - 记录存在 → 不清组件、不跟随；
  - 记录不存在 → 按无队清除。
- 投影解析失败、`team_id≠tid`、自己不在 members 里、或 `leader_id==0` → 返回。
- 队长不是自己 → `CheckFollowLeader`。
- 队长是自己且模式为 Fanout → 对 members 里**本节点上**的其他成员逐个 `RefreshAndFollow`（不再扇出）。

**CheckFollowLeader**（:350-498）
- 依次检查：战斗中 → 归属交接在途 → 换图在途 → 会话不活 → Redis 未连接。
- 然后一跳读 `MGET player:<leader>:location battle:lock:<self>`。回调里：
  - 先重新核对上述状态；
  - 战斗锁非 NIL → fail-closed；
  - 队长 zone 不同 → `cross_zone`；node_id 不同 → `cross_node`；
  - 队长 scene_id 为 0 或与自己相同 → 返回；
  - 否则发 `EnterSceneRequest{scene_id=队长的 scene_id,…}` 给 scene_manager，**不设 request_id**（:448-484）。
- 跳过原因只打 `metric=team_follow_skipped reason=…` 日志行，不是 Prometheus 指标。

**玩家可见的涌现行为**（Java 应当复现）
1. 队长换图或重新进场 → 同节点的队员在秒级内被拉到队长**同一个场景实例**（robot S6，`team_smoke_scenario.go:421-437`）。
2. 队员自己换图 → 进场后检查跟随，队长在另一个场景就**立刻被拉回**。
3. 入队、转让、踢人都不拉人。
4. 跨节点、跨 zone 不跟随（X2）。
5. 战斗中不跟随，冻结解除后补一次检查。

另外：
- **AOI 优先级**：同队双方的兴趣条目标为 kTeammate（`player_team_aoi.cpp:50-91`）。这只在兴趣表满、发生挤出时才有可见效果（`docs/reference/mmorpg-client-contract-aoi.md:87`、`:328`）。
- **跨 zone 传送**：在队时拒绝，回 `kZoneTravelInTeam`。

---

## 5 开战（StartTeamMatch，211）与 Java 首批的处理

### 5.1 基线流程（`service.go:330-412`）

0. **端口未接线**：`starter==nil` → `matchReject(4030)` + [自由读]（:344-347）。生产中由 `logic.NewTeamBattleStarter` 注入（`match_service.go:143-145`）。

整轮循环条件：`round < 3 && ctx.Err()==nil`（:350）。每一轮：

1. **刷新**：`Mutate(BindCaller(caller, expected), RefreshOp)`，结果先走 `mutateEffects`（:351-356）。
   - error → 4030 + [自由读]；
   - Committed（惰性转让或过期清理已落盘）→ `continue`，**会占用一轮**；
   - NotBound / RecordMissing → **4013** + [同源快照]；
   - Rejected → code + [自由读]；
   - Unchanged → 进入下一步。
2. **`CheckMatchStart`**：非队长 → **4018**；锁有效 → **4023**。附 [同源快照]，此时 STARTING，客户端视为进行中（:368-370）。
3. **`CheckMatchTeamSize(rec, starter.TeamSizeFor(configId))`**：`required==0` → **4027**；超员 → **4028**（:371-373）。
   - `TeamSizeFor` = `PveTeamSizeByConfigId[config]`，再收口到 5（`team_battle.go:54-58`；`matcher.go:361-378`）。
4. **`roster = MatchRoster(rec)`**（:374）。
5. **`preflightMatch`**（:414-463）：按 roster 逐人检查，第一个失败即返回，失败时附 [同源快照]。
   1. `LoadSession` 出错 → 4030；不是 ONLINE → **4024**[pid]。
   2. `IsBattleLocked` 出错按「已锁」处理 → **4025**[pid]。
   3. `LoadLocation` 出错 → 4030；为 nil → **4026**[pid]；`node_id==""`（跨 zone 交接中）→ **4026**[pid]。
   4. `starter.TicketBlocked` 出错 → 4030；票据仍在途 → **4026**[pid]。
   - 通过后记下 `zones[pid] = location.zone`。
6. **加锁**：`token = uuid`，`expireAtMs = snap.NowMs + MatchLockTTLSeconds(n)×1000`（:383-384），然后 `CommitMatchLock`：
   - 提交报错 → `releaseLockInBackground`（后台按 token 执行 EndMatch(false)，只有清锁提交成功才推送）+ 4030 + [自由读]（:385-392、:525-542）。
   - 修复提交和锁提交一起 `publish(caller)`：除发起人外推 MATCH_STARTED（:393-397）。
   - `lock.Code≠0` → code + [自由读]（:398-400）。
   - 未提交（Retry）→ 记 `team_commit_retry_total{StartTeamMatch}`，然后 `settleUnconfirmedLock`：用请求 ctx 跑一轮 `ReleaseMatchLockOnce`，确认不了就转后台；再 `continue`（:401-408、:544-566）。
7. **`launchMatch`**（:465-495）：用**锁内名单**调 `starter.CreateMatchedTickets`。
   - 失败 → 记 `team_match_total{ticket_failed}`；后台 `finishMatch(false, tip={4026,[failed]})`；回 **4026**[failed] + [自由读]。此时回包视图可能仍是 STARTING，随后到达的 MATCH_FAILED 版本更高。
   - 成功 → 回包 = 0 + 加锁提交的视图（STARTING）。后台 `RunGather`，记 `success | gather_failed`，再 `finishMatch(ok, nil)`。

轮数耗尽或 ctx 过期 → 4029 + [自由读]（:411）。

### 5.2 收尾

- **`finishMatch`**（:497-523）：
  - `store.EndMatch`；
  - 清锁提交成功 → `publish(0)`，全员（含发起人）收 MATCH_ENDED，或 MATCH_FAILED（带 tip）；
  - 没有提交（token 不符 / 锁已过期 / 记录缺失 / 截止）→ `pushMatchView`。
- **`pushMatchView`**（:568-595）：独立 3 s 预算，`ReadMembers` 之后给索引 tid==本队的**当前**成员（含发起人）推当前视图，reason 同上，actor_id=0。
- **`matchReject`**（:597-602）记 `team_match_total{rpcOutcome(code)}`。
  - 所以 `team_match_total` 的全部取值是：rejected、internal、unknown_code、success、gather_failed、ticket_failed。
- **测试**：`team_battle_test.go:231-671`，覆盖加锁前拒绝、建票失败释放锁、roster 顺序、锁过期后重开、并发只锁一次、迟到请求、整轮重来、提交结果未知的补偿、EndMatch 冲突。

### 5.3 对 match / battle 的依赖（Java 目前都没有）

`BattleStarter` 端口（`service.go:40-60`）：

| 方法 | 依赖 |
|---|---|
| TeamSizeFor | `PveTeamSizeByConfigId`；`DungeonTable.max_team_size` 是另一个候选来源，是否改用它待决（`docs/porting/inventory/scene-manager-match.md:389`） |
| MatchLockTTLSeconds | matched 票 TTL 公式与 gather 超时常量（`queue.go:367-386`） |
| TicketBlocked | 票据读取与自愈 |
| CreateMatchedTickets | 票据写入与回滚，回滚有独立 3 s 预算 |
| RunGather | scene PrepareBattle / CancelBattlePrepare、battle CreateBattle、观战记录 |

预检还需要 `battle:lock:{id}` 和位置的 `node_id`。

Java 现状：
- 首批不做战斗（`architecture.md:597-603`），整队开战排在 6.4（`roadmap.md:83`）。
- Java 有位置记录 `xm:location`（`PlayerLocationDirectory`；`scene_node_id` 是 uint32，没有「空串」语义，见 `xm-discovery/src/main/proto/xm/discovery/location.proto:14-23`），但没有 battle lock，也没有票据。
- **以上是 4.3 定稿时的现状**。之后：战斗锁随 6.3 落地（`RedisKeys.battleLock`，经 `BattleLockReader` 读），票据、gather 与整队开战端口随 6.4 落地（xm-match），见 §5.5。

### 5.4 Java 4.3 批次的处理（D11）

> 本节是 4.3 到 6.4 之间的占位处理，留作记录。批次 6.4（2026-10-08）已把占位端口 `NoTeamBattle` 换成真端口，211 不再恒回 4027，现状见 §5.5。

**推荐**：保留 §5.1 第 1–3 步的真实逻辑，注入一个「没有任何副本开放组队」的端口，`teamSizeFor(any) → 0`。

- **行为**：
  1. Refresh Mutate：惰性转让或过期清理照常提交并推送，整轮重来，最多 3 轮。
  2. 绑定失败 → 4013 + 视图。
  3. 非队长 → 4018。
  4. 4023 不可达，因为占位版永不加锁。
  5. 其余一律回 **4027 kTeamDungeonNotOpen** + 同源视图（IDLE）。
- **指标**：`xm_team_matches_total{outcome=rejected}`。不加锁、不建票、不推 MATCH_*。
- **理由**：
  - 这正是基线在「该副本未配置组队人数」时的真实路径（`team_battle_test.go:240`）。
  - 客户端只弹「该副本未开放组队」，不停用组队（`TeamClient.cs:327`、`:411-440`）。
  - 6.4 接入真端口时，入口代码不用改。
- **配置**：4.3 **不开放**组队人数的配置项，以免有人配了之后走进尚未实现的预检 / 加锁路径。6.4 再加入，并与 mmorpg 同源同值（§9.3 第 4 条）。

**不建议的做法**
- **4030**（基线 `starter==nil` 的分支）：这是 fault，会触发告警、计入 internal，文案「服务器繁忙」对玩家也有误导。
- **信封 1006 / 1003**：客户端遇到非限流的信封错误会停用整条连接上的组队（`TeamClient.cs:459-462`）。
- **in-band 1006**：team 码轴之外的码，客户端只显示一句通用文案（`TeamClient.cs:332`）。

开战锁三字段在 Java 记录里照常保留，名单规则里的 `locked()` 判断照写。6.4 只需补端口、`CommitMatchLock`、EndMatch 和补偿。

### 5.5 6.4 要补齐的清单（已补齐，2026-10-08）

原清单五条都已落地；设计与跨进程契约的正文在 `docs/porting/match-spec.md` §7.3–§7.6，这里只记 xm-team 一侧的现状（以代码为准）。

**1. 开战端口：`BattleStarter` 的五个方法 → xm-match 的四方法 Dubbo 接口**

- 跨进程接口 `com.game.api.MatchTeamService`（xm-api，group `match`，xm-match 提供），消息在 `xm/api/match_control.proto`。基线的 `TeamSizeFor` + `MatchLockTTLSeconds` + `TicketBlocked` 合成一次 `checkTeamMatch`
  （人数检查 + 逐成员预检 + 回开战锁时长），`CreateMatchedTickets` → `createTeamTickets`，`RunGather` → `runTeamGather`，另加一个基线没有的 `releaseTeamTickets`（跨进程后建票的回包可能丢失，要能按票号回滚）。
- xm-team 一侧的端口 `match.TeamBattlePort`（四个方法，与上面一一对应）：
  - `checkTeamMatch(configId, roster, deadline) → Check(code, param, zones, lockTtlSeconds)`：`code` 已翻译成 team 段 tip（0 / 4027 / 4028 / 4024 / 4025 / 4026 / 4030）；
  - `createTeamTickets(configId, teamId, roster, zones, ticketIds, deadline) → Created | Failed(playerId) | Unknown(why)`：票号由 xm-team 每人生成一个 UUID；
  - `releaseTeamTickets(ticketIds, deadline) → boolean`：只用于建票结果不明、且还没发出 gather 的情形；
  - `runTeamGather(configId, teamId, roster, ticketIds, lockTtlSeconds) → CompletionStage<Gather(ok, outcome, battleId)>`：长挂调用，不阻塞 worker。
  前三个阻塞且从不抛（调不通、超出预算、应答缺字段都折成返回值里的「故障 / 结果不明」）。
- 生产实现 `match.MatchTeamBattle`（Dubbo 客户端）；占位实现 `NoTeamBattle` 已删。引用的装配在 `TeamDubboConfiguration`：`group = match`、`retries = 0`（Dubbo 缺省的 failover 会重发建票与 gather）、`check = false`
  （xm-match 不在时本进程照常启动，211 回 4030）、直连 `xm.dubbo.match-url`（缺省 `tri://127.0.0.1:20888`，nacos profile 置空走注册中心）。
- **调用纪律**：前三个方法每跳的调用级超时 = min(3000 ms, 剩余请求预算)，**同一个值**经附件 `xm-budget-ms` 带给 xm-match 作它的本地截止（带整请求的剩余预算时，迟到的建票会在本端已判「结果不明」并回滚之后照常写票）；
  剩余预算 ≤ 0 时不发。`runTeamGather` 的调用级超时 = `lock_ttl_seconds × 1000`，不带预算附件，另加晚 2 s 的本地兜底。应答枚举的 `UNSPECIFIED`、不认识的值、空应答、future 异常完成一律按传输失败；
  `checkTeamMatch` 回 OK 时还要校验 zones 覆盖名单、锁时长在 [1, 110] 秒。

**2. 预检顺序与分工**

- 顺序不变：按名单顺序逐人「在线 → 战斗锁（读失败按在战斗中）→ 位置（`xm:location`，状态 `o` 且节点号 ≠ 0）→ 票据（先按排队的规则自愈）」，第一个不满足的人即为结论。
- **预检整个在 xm-match 做**（`MemberPrecheck`），xm-team 只映射结论、不另读战斗锁：拆开做会改变「谁先被报出来」这个客户端可见结果（例：成员 1 有在途票、成员 2 离线，基线回 4026[成员 1]）。
  所以 4025 由 xm-match 读战斗锁（`BattleLockReader`，咨询性读，权威在 scene 的备战写锁）给出；`TeamConfiguration` 里的 `BattleLockReader` bean 仍只给视图的 `in_battle` 用。
- 映射：`DUNGEON_NOT_OPEN → 4027`、`SIZE_EXCEEDED → 4028`、`MEMBER_OFFLINE → 4024[pid]`、`MEMBER_IN_BATTLE → 4025[pid]`、`MEMBER_NOT_READY → 4026[pid]`；`INTERNAL`、调不通、超出请求预算、应答不可信 → 4030。
  一律带本轮 S_READ 的同源视图。组队人数的配置（`pve-team-size-by-config-id`）在 xm-match，xm-team 不配。

**3. 开战锁：钉版本提交与「提交结果未知」的补偿**

- `TeamStore.commitMatchLock`（钉本轮 S_READ 的版本，`expire = snap.nowMs + lock_ttl_seconds × 1000`）。提交报错 → 后台按 token 清锁（`releaseLockInBackground`）、回 4030；
  未提交 → 重来之前按 token 同步确认一轮（`settleUnconfirmedLock`，Redisson 会重发同一段 EVAL，「未提交」也可能已经落锁）再整轮重来；最多 3 轮（`MATCH_START_ROUNDS`），耗尽回 4029 + 自由读。
- 建票（加锁之后）：`Failed(pid)` → 回 4026[pid]（自由读），后台清锁，全员 MATCH_FAILED 带同一 tip（计 `ticket_failed`）；`Unknown` → 回 4030，后台先退票（独立 3 s 预算）再清锁，全员 MATCH_FAILED 不带 tip（计 `internal`）。
- 加锁之前的拒绝用本轮 S_READ 的同源视图；加锁之后的失败用自由读，请求预算已用完时不带视图。

**4. EndMatch 与收尾**

- `TeamStore.endMatch`：110 s 单调截止（`MatchBudgets.TEAM_END_MATCH_DEADLINE_SECONDS`）、每轮 2 s 的独立预算、50 ms 起翻倍到 1 s 的退避加 ±20% 抖动；终止原因 `EndMatchStop`（`RELEASED / RECORD_MISSING / TOKEN_MISMATCH / LOCK_EXPIRED / DEADLINE`，
  另有 Java 独有的 `INTERRUPTED`：停机时等不完的清锁被中断即停，锁靠自然过期）。
- `TeamService.finishMatch`：清锁提交成功 → 按提交推全员（含发起人）MATCH_ENDED / MATCH_FAILED；没有提交（锁已被清 / 重新加锁 / 自然过期 / 截止）→ 仍尽力给当前队员推一次当前视图与结果原因（`TeamPushes.publishMatchView` → `pushMatchView`），保证客户端不停在 STARTING。
- 收尾都在新增的有界线程池 `team-match-end` 上跑（`xm.team.match-end-threads = 4`、`xm.team.match-end-queue-capacity = 1024`，启动校验必须为正）；一次收尾最坏阻塞 110 s；队列满时放弃这次收尾并记 ERROR，锁靠自然过期。
  关闭次序：请求池 → 收尾池 → 推送池，各等 10 s。gather 的回调在 Dubbo 的线程上只做定性、计数与投递。
- **gather 结果不明**（xm-match 中途退出、网络分区、调用超时）：计 `gather_unknown`，按失败收尾（推 MATCH_FAILED），**不退票**——gather 可能仍在跑，票据由它收尾或按 matched TTL 自愈。
- **本进程优雅停机时**（`TeamShutdown`：`ContextClosedEvent` 监听器，次序排在 Dubbo 的监听器之前，置一个停机标志）：之后才异常完成的 gather 结果只计 `gather_unknown`、记 WARN，不清锁、不推 MATCH_FAILED，
  锁靠自然过期（最长 101 s）——与进程崩溃一致（§5.2）。正常到达的结果在停机中照常收尾。

**5. `in_battle` 与跟随的战斗守卫**：批次 6.3 已做（视图的 `in_battle` 与 scene 组队跟随的战斗守卫，见 §6.7、§6.10 第 5 条与 D10）；整队开战预检的 4025 随 6.4 由 xm-match 给出（上面第 2 点）。

**客户端可见的推送**（§2.5 的 Reason / Actor 与 §4.4 的收件人表不变）：除发起人外的成员收 MATCH_STARTED；gather 成功后全员（含发起人）收 MATCH_ENDED；失败收 MATCH_FAILED——建票失败带 tip 4026[pid]，gather 失败与结果不明不带 tip。

**指标**：`xm_team_matches_total{outcome}` 在原有的 rejected / internal / unknown_code 之外新增 `success / gather_failed / ticket_failed / gather_unknown`，一次 211 恰好计一次；线程池标准指标 `executor_*` 新增 `name=team-match-end`。

**测试与 robot**：`TeamServiceTest`（用例在 `TeamMatchScenarios`，内存后端）与 `TeamMatchRedisIntegrationTest`（同一批用例跑真 Redis）、`MatchTeamBattleTest`、`MatchTeamBattleLoopbackTest`（真 Triple 回环）、`TeamDubboConfigurationTest`、
`TeamBudgetConstraintTest`、`TeamConfigurationTest`、`TeamStoreTest` / `TeamStoreIntegrationTest` 里开战锁与 EndMatch 的用例。robot `team` 场景的开战段（拒绝码 4018 / 4027 / 4026[B]、S7、S8）见 match-spec §15.5。
残余风险与遗留登记在 match-spec 文末的实现记录里（xm-team 一段）。

---

## 6 Java 落地映射

### 6.1 进程与模块

| 组件 | Java | 依据 / 先例 |
|---|---|---|
| 进程 | `xm-team`（Spring Boot + Dubbo Triple）。Dubbo 端口 **20885**，管理端口 **18109**。已占用：20881 login、20882 scene-manager、20883 friend、20884 chat；18101–18108 | `xm-*/src/main/resources/application.yaml` 的端口；`tools/local/start-slice.sh:49-55` |
| Dubbo group | `DubboGroups.TEAM = "team"` | `DubboGroups.java:7-19` |
| 提供方 | `TeamClientMessageService implements ClientMessageService`，`@DubboService(group=TEAM)`。`sessionClosed` / `abandonEnter` 直接回 Ack（进程无状态） | `xm-friend/.../FriendClientMessageService.java:18-40` |
| 鉴权 | xm-team 必须要求 `XM_DUBBO_SECRET`，缺失即拒绝启动（调用方鉴权过滤器） | `architecture.md:91-102` |
| 派发 | `dispatch/TeamDispatcher` + `TeamWorkerPool`（固定线程 + 有界队列 + AbortPolicy） | `FriendDispatcher.java:93-126`、`:138-194`；`FriendWorkerPool.java:23-38` |
| 预算 | `Deadline`（受理时刻 + 3500 ms）。建议把 `com.game.friend.support.Deadline` 抽到 xm-common，team 是第二个用户 | `xm-friend/.../support/Deadline.java:12-58` |
| 规则 | `rules/TeamRules`：纯函数，输入 `(Op, TeamRecord, nowMs, Map<Long,SessionState>, RuleConfig)`，输出 `Decision`。不读墙钟。用领域对象 + `XxxService` 命名，不用 ECS 风格 | `rules.go`；`architecture.md:436-437` |
| 存储 | `store/TeamStore` 接口 + `RedissonTeamStore`（七段 Lua，`evalAsync`）；Mutate 循环、修复、自由读照 §1.7 | `store.go`；`scripts.go` |
| 视图 | `view/TeamViews`：纯函数，三个同源入口 | `view.go` |
| 在线 / 资料 | `presence/TeamSessions`（规则用的四态，§6.6）、`presence/TeamDisplay`（展示缓存，§6.7） | `presence.go` |
| 推送 | `push/TeamPushes`：包一层 `PlayerPushes`，按批串行 | `xm-discovery/.../presence/PlayerPushes.java:56-108` |
| 发号 | `id/TeamIds`：`Snowflake` + `NodeIdLease`，新增 `NodeTypes.TEAM="team"`，作用域 0（全服） | `NodeTypes.java:8-24`；`PlayerIdGenerator.java:14-32`；`SceneNode.java:480-491` |
| 指标 | `metrics/TeamMetrics`（§7） | `FriendMetrics` |
| 开战端口 | `match/TeamBattlePort`；4.3 实现为 `NoTeamBattle`，`teamSizeFor → 0`。**6.4 起**是四个方法的端口，生产实现 `match/MatchTeamBattle`（调 xm-match 的 `MatchTeamService`），装配在 `TeamDubboConfiguration`；另有停机标志 `TeamShutdown` 与收尾池 `team-match-end` | §5.4、§5.5 |
| scene 侧 | xm-discovery 新增 `team/TeamMembershipReader`（只读）；xm-scene 新增 `team/TeamFollow` | §6.10 |

### 6.2 gate 接入（照 friend / chat）

- **路由**：`MessageRoutes.SERVICE_BACKENDS` 加一行 `"ClientPlayerTeam" → DubboGroups.TEAM`（`MessageRoutes.java:28-31`）。203 / 213 / 215 的应答类型是 Empty，`hasResponse=false`（:50-54）。
- **Dubbo 引用**：`GateConfiguration` 加 `@DubboReference(group=TEAM, check=false, url="${xm.dubbo.team-url:}", methods=@Method(name="handle", retries=0))`（先例 `GateConfiguration.java:77-97`），并放进 backends Map（:126-127）。
  - 写路径不幂等，必须 `retries=0`。
- **配置**：
  - `xm-gate/src/main/resources/application.yaml:50-54` 加 `team-url: tri://127.0.0.1:20885`；nacos profile 置空。
  - `start-slice.sh` / `stop-slice.sh` 的 `SERVICES` 加 `"xm-team 20885"`。
- **按会话、按域的在途队列**是现成机制（`ClientDispatcher.java:257-261`、`:329-350`）：
  - team 请求之间串行，保证写后读；
  - 不占 login / scene 的 `inFlight`；
  - 队列满 64 → 断开连接；
  - 身份取**入队时**的会话快照（`ClientSession.java:158`）。
- **调用失败或超时**（Dubbo 5000 ms）→ gate 推 23 {1003}（`ClientDispatcher.java:382-397` `onBackendCompleted`）。这是已有差异（D14）。
- **热关停**（工作区未提交的新功能）：gate 在转发前按方法检查热关停规则，命中时回信封 1003（`ClientDispatcher.java:247-256`）。
  - 它对 team 消息号同样生效，而基线 match 没有接 killswitch（mmorpg `go/` 下用 killswitch 的服务不含 match）。
  - 命中时客户端会停用本连接的组队。这是运维止血手段，登记即可（D17）。

### 6.3 准入与错误映射（`TeamDispatcher`）

| 情形 | 基线客户端所见 | Java 返回 | 说明 |
|---|---|---|---|
| 12 个 C2S | 应答体 | `ClientReply{body}`，`tip_id=0` | gate 回显 id |
| 上行 213 / 215 / 203 | 服务端空操作回 Empty，路由服回一个空体成功应答（`server.go:163-175`） | `ClientReply{tip_id=0, body 空}`，gate 按 Empty 规则**不回包**（`ClientDispatcher.java:456-459`） | D13。指标计 `forbidden`（基线不计，指标不是客户端契约） |
| 请求体解析失败 | gRPC 解码错误 → 路由服信封 1003 | `tip_id=1003`（信封） | 与 friend 一致（`FriendDispatcher.java:172-178`）。基线解码发生在拦截器和业务之前，所以**解析应先于身份检查** |
| 会话 `player_id=0` | **in-band** `{4001}`，不带视图 | body = `TeamResponse{error_message{4001}}`，ListMyInvites 回 `ListMyInvitesResponse{4001}`；`tip_id=0`；指标 `unauthenticated` | **与 friend 不同**：friend 回信封 1003（`FriendDispatcher.java:151-156`），team 照搬会让客户端停用组队（`TeamClient.cs:459-462`） |
| 工作队列满 / 排队已超预算 / 处理器抛异常 | 无直接对应（基线依赖故障是 in-band 4030） | **in-band 4030**，不带视图、不带 parameters；指标 `overloaded` / `internal_error` | D12。客户端按「读失败」处理，3 s 后重拉；不用 1003，因为 team 有自己的故障码 |
| 不认识的号 | — | 信封 1013（契约里没有）/ 1006（契约里有，但不归 team） | 照 friend（`FriendDispatcher.java:206-214`）；gate 只把 `ClientPlayerTeam` 的号路由过来，正常走不到 |

**启动校验**（照 `FriendDispatcher.java:114-126`）：
- 15 个方法名都要在 `message_id.txt` 里；
- 请求原型与处理器类型一致；
- 12 个 C2S 应答都有 `error_message` 字段；
- 缺号即启动失败。

### 6.4 线程、预算、重试

- **线程**：
  - Dubbo 线程只投递，处理在 `team-worker` 有界池上执行（缺省 16 线程 / 1024 队列，同 friend，`xm-friend/src/main/resources/application.yaml:73-74`）。
  - 所有 Redis 调用都用 async，并在工作线程上 `deadline.await`。
  - MySQL（资料、home zone）只在工作线程上执行（`AGENTS.md` §3）。
- **预算**：
  - `request-budget` 缺省 3500 ms，启动时校验必须在 [500, 3500] ms 内，即必须小于 gate 的 5000 ms（照 `FriendProperties.java:44-63`）。
  - 提交前 `deadline.expired()` → 4029（对应基线的 `ctx.Err()`）。
  - Redis 等待超时 / 失败 → `DependencyException` → 4030（对应基线的「存储层 error」）。
  - 预算耗尽后，回包的 [自由读] 也会失败，于是不带视图。这与基线 ctx 过期后 freeView 失败一致。
- **Redisson 重发**：响应超时后会重发同一段 EVAL（缺省 `retry-attempts=1`，`architecture.md:474-476`）。
  - S_COMMIT 靠 ver CAS 收敛（§3.15）。
  - S_HEAL_ORPHAN 被重发时第二次回 0，「已治愈」的标记会丢，只影响指标。
  - S_INVITE_LIST / S_TOUCH / S_INVITE_PRUNE 是幂等的。
  - 6.4 的开战锁必须保留「按 token 确认」的补偿。
- **推送执行器**：
  - 提交后的推送整批交给独立的 `team-push` 执行器，预算 3 s，不继承请求的 Deadline。
  - 批内按落盘顺序串行，与基线一致。展示资料要读 MySQL，所以这个执行器允许阻塞。
  - 执行器拒绝时整批放弃，按 `kind` 记 error 并打日志。
  - **不能用 `pushToPlayers` 批量推**：每个收件人的视图不同（epoch 不同、只给队长的列表不同）。

### 6.5 Redis 键与 Lua 的落地

- **键**：在 `RedisKeys` 新增 `teamRecord(tid)` / `teamInfo(tid)` / `teamPlayer(pid)` / `teamInvite(pid)`，形如 `xm:{team}:rec:<tid>`（§1.3，D2）。
  - hash 字段名 `ver / pb / tid / epoch` 不变。
  - TTL 与阈值同基线：空闲 86400 s、续期阈值 43200 s、邀请反查 ZSET 3600 s。
- **脚本**：七段 Lua 逐字照搬语义，包括 J / K 判定的 `"0"` 差异与 IA 上限的判定时机（§8.1 第 1、2 条）。
- **编解码**：
  - recPb / projPb 是任意字节。好友脚本用的是 `StringCodec`（`xm-friend/.../directory/RedissonDirectoryRedis.java:33`），按 UTF-8 处理会破坏这些字节。
  - 组队脚本一律用 `ByteArrayCodec`：参数全部是 `byte[]`，数字写成 ASCII 十进制。
  - 返回值按 MULTI 解码：整数是 `Long`，字符串是 `byte[]`；空串解析为 0，同 `parseUint`。
  - 先例：`PlayerPresenceDirectory` 的脚本用的就是 ByteArrayCodec（`PlayerPresenceDirectory.java:105-107`）。
- **调用方式**：
  - 单条 `evalAsync`，遇到 NOSCRIPT 会自动重载；不用 RBatch（`architecture.md:132-136`）。
  - S_INVITE_LIST、S_TOUCH、S_HEAL_ORPHAN、S_COMMIT、S_INVITE_PRUNE 必须是 `READ_WRITE` 模式。
  - 要求 Redis 7（脚本里先调 TIME 再写）。
- **无符号**：
  - player_id / team_id 一律用 `Long.toUnsignedString` / `Long.parseUnsignedLong`。
  - 排序中的 id 比较用 `Long.compareUnsigned`，join_seq 用 `Integer.compareUnsigned`，否则在大值上会与 Go 的顺序不同。
- **记录与投影的 proto**（D18）：
  - 建议用 Java 自有的 proto：`TeamRecord` 及三个子记录放 xm-team，`TeamInfo` 投影放 xm-discovery（写者 xm-team 与读者 xm-scene 都依赖它）。
  - 形状与同步来的 `team.proto:224-258` / `team_comp.proto:19-24` 相同，但不依赖契约同步产物。
  - 先例：friend 的表定义用的是 Java 自有 proto（`PARITY.md:97` 的 ⑧）。

### 6.6 在线态映射（替代 `player:session`，D3）

Java 的 `xm:presence:{id}` 只表示「此刻在游戏里」，TTL 60 s，gate 在断线时就删掉，没有 DISCONNECTING 状态（`architecture.md:132-140`）。

| Java 观察到的情况 | 映射为 |
|---|---|
| `xm:presence:{pid}` 存在、能解码、玩家号一致 | **Online** |
| presence 不存在，且 `xm:location:{pid}` 的 `s` 为 `l`（30 s 重连租约）或 `o`（scene 认为在线，但 presence 还没写或已丢） | **Present** |
| presence 不存在，且 location 不存在或 `s=x`（登出墓碑） | **Absent** |
| 该玩家任一读失败、条目损坏、身份不符 | **Unknown**，逐成员 fail-closed |

- location 的状态字段见 `RedisKeys.java:49-56`、`PlayerLocationDirectory.java:20-44`。
- 直接把「presence 不存在」当 Absent，会在 30 s 重连宽限内错误地转让队长（基线把这段宽限当转让宽限，`team-system.md:811-813`）。
- **与 friend 不同**：friend 是「有一个读失败整体回 1003」（`architecture.md:376-377`）。组队必须逐成员 fail-closed，不让 RPC 失败。
- **需要新增的接口**：
  - `PlayerPresenceDirectory.findAllStrictAsync` 只回计数，不说出错的是谁（`PlayerPresenceDirectory.java:181-215`）。
  - `PlayerLocationDirectory.find` 是阻塞调用，不区分 o / l，x 当作没有（:104-110、:183-200）。
  - 所以需要新增两个按玩家给结果的异步读：presence 严格批量读，返回 `Map<pid, Online|Absent|Error>`；location 的 `HGET s` 批量读。
  - 不要用一段 Lua 同时读两类键：它们没有共同的 hash tag，将来上 Cluster 会跨槽。
- **其它在线判定**：
  - **邀请目标**：`findStrictAsync`（:142-159）。异常 → 4030；空 → 4017。
  - **转让目标**：要求 Online，Unknown / Present / Absent 都回 4024。
  - **视图里的 is_online**：宽松批量读（`findAllAsync`，:162-174），读失败按离线处理。
  - **推送前的在线判定**：在 `PlayerPushes` 里做（`pushToPlayer` 用的是严格单查，:56-61）。
  - **MEMBER_ONLINE 的收件人筛选**：用展示缓存的 is_online。
- **已知偏差**：
  - scene 节点崩溃后，location 的 `o` 最多残留 60 s，这段时间该玩家被判为 Present，惰性转让被推迟 ≤ 60 s。
  - gate 崩溃后 presence 最多残留 60 s，这段时间算 Online。
  - 基线的会话键没有 TTL，可能永远停在 ONLINE（`architecture.md:138-139`）。

### 6.7 展示资料与 home zone（D4、D5）

**展示资料**
- 读 `xm_java.player`：`SELECT player_id, name, level, class_id, gender, appearance_id, zone_id … WHERE player_id IN (...)`，每批 64 人（`xm-friend/.../profile/PlayerProfiles.java:33-36`、`:92-117`）。
- 失败语义取 `load`：某批失败时记 ERROR、填零值，**不让 RPC 失败**，与 `presence.go:25-27` 一致。
- 与基线的差别：name 恒非空；新角色的 level 是 1；level 到存盘才更新（同 friend D4）。
- 建议把 `PlayerProfiles` 迁到 xm-player-store（它拥有 player 表），由 friend 和 team 共用。
- 一次视图构建只读一份，覆盖 `rosterIds`：最多 5 名成员 + 10 名申请人 + 10 名被邀请人 + 邀请人。

**in_battle**：Java 在 6.3 之前没有战斗锁，**恒为 false**（D10）。**批次 6.3 已接上**（2026-10-06，D10 收口）：

- **口径**：回合制战斗锁 `xm:battle:{<pid>}:lock`（`RedisKeys.battleLock`，scene 写；键与脚本见 scene-battle-spec §7.2）**存在即为 true**，只看锁在不在，不看锁里的阶段也不看 battle_id。
  锁从备战起就在，到结算销账（或锁过期 / reaper 判废）才没——备战中、战斗中、已结算待销账（含结算后续锁的 180 s）都显示为在战斗；只有待结算记录或已销账墓碑、没有锁时为 false。
- **读法**：`TeamDisplay` 多一个依赖 `battleLocks`（生产 = `BattleLockReader::existsAll`，`TeamConfiguration` 里的 bean）。一次视图构建只读一次，范围同展示资料——全体 `rosterIds`
  （成员、申请人、被邀请人、邀请人，最多 26 人；基线 `loadDisplay` 的 InBattle MGET 也是对全体 id 做的，每个 `TeamMemberView` 都带 `in_battle`）；空名单不读。
  **批量读是每人一条 `EXISTS` 并发发出，不是 MGET**：锁是 Hash；对 Hash 键 `MGET` 回 nil、**不报错**（报 `WRONGTYPE` 的是 `GET`），照搬基线 `presence.go:92-97`「MGET 的值非空即在战斗」的写法
  会让 `in_battle` 静默地恒为 false——比报错更隐蔽。`TeamDisplayRedisIntegrationTest` 有一条真 Redis 用例把这个前提钉住。
- **次序与预算**：在线读与战斗锁读都在阻塞的资料读之前发出，最后在调用线程（工作线程或推送线程）上依次限时等；没有单独的超时键，与在线读共用调用方传入的 `Deadline`
  （请求预算 3500 ms，推送批预算 3 s）。预算被前一路用尽时，已完成的那一路仍取得到结果。
- **失败语义**：咨询性字段。任何一条 `EXISTS` 失败，`existsAll` 整体异常完成，**本次视图全体按 false**（不是只把失败的那个人按 false；同基线「MGET 失败 → 全 false」，§4.3）。
  同步抛出、返回 null、异常完成、被取消、超时、被中断、以 null 完成、结果里缺这个人或值为 null，全部只记一行 WARN（`[team] 读战斗锁失败，N 人的 in_battle 按 false 显示`，带原始原因）、视图照常，`load()` 不抛出。
  被中断时立刻返回（不把剩余预算等完）并保留中断标志。没有新增指标或配置键。
- **读路由**：`BattleLockReader.exists` 走 Redisson 的普通读，主从部署下可能读到从库的旧值；`in_battle` 只用于显示，可接受（权威在 scene；部署约束见 scene-battle-spec §10.5）。
- **测试**：`TeamDisplayTest`、`TeamDisplayRedisIntegrationTest`（真 Redis、用 `BattleRedis` 的真脚本写锁放锁：备战 → 确认 → 落库加续锁 → 销账全程的 `in_battle`）、`TeamConfigurationTest`、`TeamViewsTest`；
  运行证据见 scene-battle-spec 末尾的实现记录。robot 的端到端验证放在 `battle-settle` 第 12 步（不在 `team` 场景：它没有 battle 管理口客户端）；写本段时还没有切片证据，切片上的结论见 scene-battle-spec 末尾的「最终验证」。

**home zone**
- 取 `player.zone_id`，即建角时 login 所在的 zone（`xm-player-store/src/main/resources/db/xm-player-schema.sql:14`；`xm-login/.../CreatePlayerHandler.java:262`）。
- 查不到行或 `zone_id=0` → 4019；SQL 出错或预算用完 → 4030。
- 查询超时取 `min(1500 ms, 剩余预算)`。每次最多查 2 个 id。

### 6.8 推送（替代 Kafka gate-cmd，D6）

- **下发形态**：`MessageContent{message_id=213 | 215 | 203, serialized_message}`，id=0。消息号取 `registry.requireId("ClientPlayerTeam", "NotifyTeamSnapshot" | "NotifyTeamInvite" | "NotifyTeamEvent")`。
- **收件人、内容、caller 排除、不可构建则跳过**：全部照 §4.4–§4.6。
- **结局映射**（照 `xm-friend/.../service/FriendService.java:415-425`）：

  | `PlayerPushes` 结局 | 指标 outcome |
  |---|---|
  | `SENT` | ok |
  | `OFFLINE` | offline |
  | `GATE_UNREACHABLE` / 异常 / 超时 | error |

- **玩家栅栏**：gate 侧有玩家栅栏，防止推错人（`architecture.md:141-145`）。基线没有这一层。

### 6.9 team_id 发号（D15）

- 雪花号，worker 取 xm-team 的全服租约（`NodeTypes.TEAM`，作用域 0；理由同 `NodeTypes.SCENE_GUID`，`NodeTypes.java:16-20`）。
- 每次发号前检查 `isValid()`，无效即拒绝；时钟回拨也拒绝（`PlayerIdGenerator.java:26-31`；`xm-common/.../id/Snowflake.java:5-14`）。
- 发号失败或返回 0 → CreateTeam 回 4030 + 空视图（同 `service.go:126-130`）。

### 6.10 场景跟随（xm-scene，批次 4.3，`roadmap.md:60`）

**Java 现状**
- 场景节点都是主世界，没有战斗。
- 同节点换场景是**同步**的：`ClientRequestHandler.java:233` 调 `SceneWorld.switchScene`，后者在 `SceneWorld.java:445-461`；目标与当前相同时直接返回（:447-449）。
- 断线即移出内存，不复用实例（`roadmap.md:44`）。
- 场景状态只在逻辑线程读写，Redis 的结果投递回逻辑线程（`AGENTS.md` §3）。

**设计（D7、D8、D9）**
1. **读者**：xm-discovery 的 `TeamMembershipReader.readAsync(pid)`。用一段只读 Lua，一次往返原子读出：
   - `HMGET xm:{team}:player:<pid> tid epoch`；
   - tid≠0 时再 `GET xm:{team}:info:<tid>`；
   - 返回 `{tid, info 字节或 nil}`。
   - 这比基线的两跳少一跳，而且同源。两个键同在 `{team}` 槽。
   - 不需要 `EXISTS rec`：Java 不缓存 TeamId，「投影缺失」和「无队」的处理一样，都是不跟随。
2. **钩子**：`SceneWorld` 注入 `TeamFollow`，缺省 `NONE`（照 `PlayerLocations.NONE` 的写法，`SceneWorld.java:140-150`）。在两处 `enterScene` 之后调用 `onEnteredScene(player)`：
   - 进场：`SceneWorld.java:366-368`；
   - 换场景：`SceneWorld.java:458-459`。
   - 这两处覆盖登录、重连、玩家自己换图、被跟随换图，与基线的两个调用点等价。
3. **回调**（回到逻辑线程上执行）：
   - `playersById.get(pid) != 当初那个实例` → 丢弃（对应 `IsSamePlayer`）。
   - tid=0、键缺失、info 缺失或解析失败、`info.team_id≠tid`、自己不在 members 里、`leader_id=0` → 不跟随。
   - 队长不是自己 → `followLeader(self, leader)`。
   - 队长是自己、且这次是自己进场（不是被跟随触发的）→ 对 members 里在 `playersById` 中的其他成员，各自再读一次自己的成员关系，按「只跟随、不扇出」处理。
4. **`followLeader(p, leaderId)`**：
   - 队长不在本节点（跨节点、跨 zone、离线都落在这里）→ 不跟随。
   - 与 p 在同一场景 → 什么都不做。
   - 否则 `world.switchScene(p, leader.scene())`：精确到场景实例，与基线一致。
   - 用内存里的队长场景，不读 `xm:location`，因为同节点时内存就是权威。
5. **基线守卫在 Java 的对应**：
   - 战斗中 / 战斗锁：6.3 补，届时加上「冻结解除后补一次」。**批次 6.3 已做**（scene-battle-spec §7.13 世界内部第 3 条）：读成员关系的同时并行发一条战斗锁的 `EXISTS`；
     回调里自己有内存里的战斗冻结 → 不跟随（`team_follow{in_battle}`），锁存在或读锁失败 → 不跟随（`battle_lock`，fail-closed，同基线 `player_team.cpp:378-402`）；
     `TeamFollow.onBattleFreezeCleared` 在解冻且删锁完成之后、以及销账脚本确实放掉锁之后各补一次跟随（只跟随、不扇出）。删锁 / 销账的结局回来时发起它的实例已经换掉
     （同 epoch 重进），或者那次删除本来就不跟着一次解冻（备战失败 / 过期后的尽力删锁、离线取消）的，只要锁确实是这一次删掉 / 放掉的，就补给**现任实例**（它没有战斗冻结时）——
     它进场那次检查读到的还是这把锁、已经放弃，删完之后没有别的触发点（scene-battle-spec §7.4「删锁结局回来之后补给谁」、§7.12）。与基线写法的一处出入：基线在第二跳
     （读队长位置与自己的锁）之前、回调之后各判一次内存冻结；Java 把锁读并进了成员关系那一跳，只在回调后判一次。
   - 归属交接在途：5.2 前不存在。
   - 换图在途：同步换图，不存在。（批次 5.2 起这两条对应 Java 的跨节点换图在途：`SceneWorld.switchInFlight` 为真不跟随，计 `team_follow{switching}`，见 scene-handoff-spec §5.5；
     6.3 起判定经这个入口而不是直接看 `switchPhase`，过了期限的 RESOLVING 槽在这次判定里就地作废、不再挡。）
   - 会话不活：断线即移出，不存在。
6. **不做的部分**：
   - 不发、不收 `PlayerTeamRefreshEvent`（事件号 48 在 Java 不用）。
   - 不做 kTeammate AOI 优先级：Java 的 `ViewIndex` 表满时不挤人（`xm-scene/.../world/ViewIndex.java:15-25`）。
   - 跨 zone 传送（5.4）落地时，用同一个 Reader 判断「在队就拒 kZoneTravelInTeam」。
7. **涌现行为**：§4.8 的第 1–4 条自然成立。被跟随者换图也会触发钩子，但它已经和队长同场景，所以什么都不做，不会循环。
8. **指标**：`xm_scene_team_follow_total{result=followed / same_scene / not_in_team / projection_missing / not_member / leader_not_on_node / stale / read_error}`，启动即注册。

### 6.11 配置项（`xm.team.*`）

| 配置项 | 缺省 | 校验 / 依据 |
|---|---|---|
| `allow-cross-zone` | false | `match_service.yaml:126-131` |
| `request-budget` | 3500 ms | 必须在 [500, 3500] ms 内 |
| `push-batch-budget` | 3 s | `notify.go:37` |
| `home-zone-timeout` | 1500 ms | `homezone.go:31-33` |
| `worker-threads` / `worker-queue-capacity` | 16 / 1024 | 同 friend |
| `push-threads` / `push-queue-capacity` | 4 / 1024 | Java 自有，§6.4 |
| （6.4 再加）`pve-team-size-by-config-id` | — | 4.3 不开放，§5.4。**6.4 落地**：这项配置放在 xm-match（`xm.match.pve-team-size-by-config-id`，缺省 `{1: 5}`），xm-team 不配，经 `checkTeamMatch` 取结论 |
| `match-end-threads` / `match-end-queue-capacity` | 4 / 1024 | 6.4 新增：整队开战收尾池 `team-match-end`；必须为正（§5.5） |
| `xm.dubbo.match-url`（不在 `xm.team.*` 下） | `tri://127.0.0.1:20888` | 6.4 新增：调 xm-match 的直连地址；nacos profile 置空走注册中心 |

规则常量**不做成配置**（§0.4）。

---

## 7 指标

### 7.1 基线（`go/match/internal/metrics/metrics.go:158-211`、`:374-413`）

label 只允许固定枚举，禁止 player_id 和 team_id。

| 指标 | label 取值 |
|---|---|
| `team_rpc_total` | method = 12 个 RPC 名（`server.go:27-40`）；outcome = ok / rejected / internal / unknown_code / no_session |
| `team_commit_retry_total` | op = RPC 名（Mutate 的冲突次数，含修复冲突；开战锁 Retry 也计） |
| `team_heal_total` | kind = orphan_index / index_mismatch |
| `team_push_total` | kind = snapshot / invite / event / members_changed；outcome = ok / offline / error / skipped |
| `team_scene_refresh_total` | outcome = ok / no_location / no_node / empty_uuid / error |
| `team_match_total` | outcome = rejected / internal / unknown_code / success / gather_failed / ticket_failed |
| `team_cross_zone_allowed`（gauge） | 无 label；启动时上报 AllowCrossZone（`match_service.go:87`） |

此外还有 `safego_panic_total{point}`，点位包括 `match.team.publish`、`match.team.online`、`match.team.end_match`、`match.gather.pve_team`。

### 7.2 Java 建议

照 `FriendMetrics` 的模式：前缀 `xm_team_`，启动时注册全部 label 组合（`architecture.md:661-671`）。

| 指标 | 类型 | label | 对应基线 |
|---|---|---|---|
| `xm_team_requests_seconds` | Timer | method = `ClientPlayerTeam.<方法>` / unrouted；result = ok / business_error / internal_error（tip==4030）/ overloaded / bad_request / unauthenticated / forbidden / unsupported | `team_rpc_total` |
| `xm_team_commit_retries_total` | Counter | op | `team_commit_retry_total` |
| `xm_team_heals_total` | Counter | kind = orphan_index / index_mismatch | `team_heal_total` |
| `xm_team_pushes_total` | Counter | kind = snapshot / invite / event / members_changed；outcome = ok / offline / error / skipped | `team_push_total` |
| `xm_team_matches_total` | Counter | outcome（4.3 只会出现 rejected） | `team_match_total` |
| `xm_team_cross_zone_allowed` | Gauge | — | `team_cross_zone_allowed` |
| `xm_scene_team_follow_total` | Counter | result（§6.10） | 基线只有 `metric=team_follow_skipped` 日志行 |
| `executor_*{name="team-worker"}`、`{name="team-push"}` | 线程池 | — | — |

- `team_scene_refresh_total` **不移植**，因为没有 scene 信号（D7）。
- Java 拿不到 Tip 表的 fault 列，所以 internal_error 的判据写死为 tip==4030，并用测试钉住。

---

## 8 隐患与边界

### 8.1 基线自身的隐患（移植时要么照搬，要么显式偏离并登记 PARITY）

1. **邀请上限可超出 1 条**（`scripts.go:75`）。
   - 本队在被邀请人的 ZSET 里**有一条已过期、还没清掉**的旧项时，`ZSCORE` 非空，于是跳过上限判定；随后先清过期项再 ZADD，结果对方可能持有 11 条有效邀请。
   - 每次 IA 写入都会清掉该键的全部过期项，所以上界是 11。现有测试没有覆盖。
   - 修正见 D19（需要两版同改）。
2. **K 判定把索引 `"0"` 当作错位**（`scripts.go:68-71`）：保留成员的索引是 `"0"` 时，该成员会被 HEALED 移出，只有索引缺失时才放行重建。语义是「玩家自认无队，就移出」，Java 照搬。
3. **业务拒绝会丢掉这一轮的惰性转让和过期清理**（`rules.go:748-750`）：例如非队长踢人被拒，同一次读到的「队长已离线」不会落盘。
4. **被淘汰者不收通知**。
   - 淘汰的申请人、被邀请人都不收通知；被淘汰的被邀请人只被 ZREM 掉反查项。
   - `INVITE_REVOKED` 只在解散时发；proto 注释里的「满员导致邀请失效」**没有实现**（`team-system.md:1621`）。
5. **过期的开战锁字段会残留在记录里**，直到下次加锁覆盖或 Release 清空。视图只按 `MatchLockActive` 判断，不受影响（`view.go:54-56`）。
6. **`validateCommitSets` 不校验 `Left ⊇ 旧 \ 新`**，只靠规则层构造来保证（`store.go:851-902`）。
7. **S_INVITE_LIST 会写**：做读写分离时不能路由到只读副本（`scripts.go:172`）。
8. **Mutate 返回 error 时，前几轮已落盘的修复提交不会推送，也不会发 scene 信号**。
   - `store.go:187-189`、`:236-237`、`:259-261` 都是 `return nil, err`，`res.Repairs` 随之丢掉；`runMutate` 在 error 时不调用 `mutateEffects`（`service.go:618-622`）。
   - 后果有界：被移出者靠下次 GetMyTeam 或下次进场自愈。
9. **建队提交结果未知、EVAL 被重发**时：第二次回 `{0}`，重读发现新 tid 已有记录，走发号器故障分支，回 **4030**；但 [自由读] 视图里已能看到建好的队伍（`store.go:200-203`）。
   - 客户端会显示「服务器繁忙」，下次刷新后看到队伍。修正见 D20。
10. **缺身份的 4001 不带视图**，而目标为 0 或自己的 4001 **带** [自由读] 视图（`server.go:95-99` 对比 `service.go:167-170`、`:205-208`）。
11. **调用者不收自己提交的快照推送**，包括修复提交和惰性转让。回包丢失只能靠 GetMyTeam 自愈（`team-system.md:1626`）。
12. **notify_online 在错误回包上也会触发**，只要回包视图里有队伍（`service.go:153-161`）。
13. **TransferLeader 遇到会话读失败回 4024**（fail-closed），不是 4030（`rules.go:668-671`）。
14. **InviteToTeam 的前置检查早于绑定**：调用者没有队伍时，也可能先拿到 4017 / 4003 / 4019 / 4030，而不是 4013（`service.go:205-228`）。
15. **ListMyInvites 只要一条记录读失败，整体回 4030**；而且它会写 Redis（`service.go:256-285`）。
16. **ApplyJoinTeam 目标无队时回 4013，文案是「你还没有队伍」**，对申请人有误导（`service.go:183-185`；`tip_text.json:170`）。两版保持一致，不要私改码。
17. **推送逐人串行，共用 3 s 批预算**：下游慢时，排在后面的收件人会记 error 被跳过。
18. **StartTeamMatch 里 Committed 也占用一轮**：惰性转让加两次过期清理，最坏会耗尽 3 轮，回 4029（`service.go:350-359`）。
19. **建票失败的回包视图可能仍是 STARTING**（`service.go:476-480`），客户端靠随后到达的 MATCH_FAILED 收敛。
20. **锁期间惰性转让照常发生**：Refresh / Release 会做，LockMatch 不做。队长可能在 STARTING 期间变更，但 roster 不变。
21. **离线队员不会被自动移出**；队伍只在空闲 24 h 后过期，没有后台扫描器（`team-system.md:811-816`）。
22. **多实例 AllowCrossZone 取值不一致**时，结果依实例而定（`team-system.md:1434` 的 J-13a），用 gauge 观察。

### 8.2 Java 移植时会踩的

1. **照抄 friend 的信封 1003**（player_id=0、过载时）会让客户端停用本连接的组队（`TeamClient.cs:459-462`）。team 一律用 in-band 4001 / 4030（§6.3）。
2. **往 tip 里塞文字参数**会破坏 `parameters[0]` 是玩家号的口径（§0.3）。
3. **用 StringCodec 传 pb 字节**会把字节弄坏（§6.5）。
4. **把「presence 缺失」直接当 Absent**，会在重连宽限内错误转让队长（§6.6）。
5. **`findAllStrictAsync` 不给出错的是谁**，`PlayerLocationDirectory.find` 是阻塞的、不区分状态，都需要新接口（§6.6）。
6. **id 排序用有符号比较**，在大值上与 Go 的顺序不同（§6.5）。
7. **S_INVITE_LIST 用 READ_ONLY 模式**，在读写分离时会写不进去（§6.5）。
8. **用 `pushToPlayers` 批量推快照**会把同一份视图推给所有人；每人的 epoch 和只给队长的列表都不同（§6.4）。
9. **请求体解析放在身份检查之后**：在「坏包 + 未进游戏」的组合上，会与基线回的码不同（§6.3）。这是边缘情形。
10. **gate 热关停对 team 生效**，基线没有这一层（§6.2，D17）。
11. **player 表的 level 到存盘才更新**，在线玩家的等级可能滞后（§6.7）。

### 8.3 边界速查

| 场景 | 结果 |
|---|---|
| `expire_at_ms == now` | 已过期（申请、邀请；`rules.go:857`、`:866`） |
| 锁 `now == expire` | 已无效（`rules.go:186`） |
| 成员数 == 5 | 满员（`rules.go:441`） |
| 第 11 条申请 / 邀请 | 淘汰最早的一条，本次的不淘汰，不报错 |
| 被邀请人在别的队已有 10 条未过期邀请 | 4022[被邀请人]；本队已有条目的刷新不受限 |
| 人数少于副本上限 | 允许开战（`rules.go:315-325`） |
| Kick target=0 | 4004，不带参数 |
| Transfer target=0 / target=自己 | 4001 / 4007（排在「已是队长」之前） |
| HandleApplication `applicant_id=0` | 4018 / 4011 / 成功（无前置检查） |
| RespondInvite `team_id=0` | 4013 + [自由读] |
| LeaveTeam `expected=0` | 成功 + 当前视图 |
| 其他写操作 `expected=0` | 4013 + 当前视图 |
| 队伍空闲 24 h | 记录、投影、索引都过期；读路径回 epoch=nowMs 的空视图 |
| 邀请人已离队后看到的邀请 | inviter 的 zone / join_seq 为 0 |
| StartTeamMatch 在 Java 4.3 | 非队长 4018；其余 4027 + IDLE 视图（6.4 起走真实开战，只有未配置组队人数的副本仍是 4027 + IDLE，§5.5） |

### 8.4 设计稿与注释同代码不一致的地方（以代码为准）

| 位置 | 文档 / 注释怎么说 | 代码实际 |
|---|---|---|
| `team-system.md:765`（D.5 GetMyTeam） | 推全员 LEADER_OFFLINE_TRANSFERRED 或 APPLICATION_CHANGED | 单纯过期清理只推队长，reason 也可能是 INVITE_CHANGED；只有转让时才推全员（`notify.go:156-173`；`rules.go:761-770`） |
| `team-system.md:766`、`:768`（D.5 Apply / Invite） | 跨区校验在 pre 阶段 | pre 只查 home zone，跨区判定在规则里；Invite 的规则顺序是：非队长 → 0 / 自己 → 本队成员 → zone → 满员（`rules.go:526-546`） |
| `team-system.md:773`（D.5 Transfer） | 「目标已是队长 → 成功」排在最前 | 顺序是 4001 → 4007 → 幂等 → 4008 → 4004 → 4024 → 4023（`rules.go:647-678`） |
| `team-system.md:767`、`:775`、`:1101` | 「全员（含新人）」「全员 MATCH_STARTED」「调用者也在收件人里」 | 一律排除 caller（`notify.go:112-115`；R.3 已记在 `team-system.md:1626`） |
| `team-system.md:1041`、`:1043`（G.2） | name 恒为空、gender 恒为 0 | 从 PlayerAllData 的 profile 读取（`presence.go:144-146`；`team.proto:127`、`:130`） |
| `team-system.md:1630` | RespondInvite 的 team_id=0「直接回 4013，不读 Redis」 | 回 4013 + [自由读]，会读调用者视图（`service.go:236-237`、`:710-713`） |
| `errors.go:16` | 4002 在「开战时队伍已满」也会出现 | StartTeamMatch 不产生 4002，超员是 4028 |
| `rules.go:152` | 集合契约「store 提交前会校验」 | 不校验 `Left ⊇ 旧 \ 新` |
| `rules.go:271` | 「集合里没有一个在记录中时返回 !Changed」 | 同时有过期项或惰性转让时，会以清理类 reason 返回 Changed（在调用路径上不可达） |
| `team.proto:209` | INVITE_REVOKED 用于「解散/满员」 | 只在解散时发 |
| `TeamViewMapper.cs:145` | 「服务端 name 尚未接线」 | 已接线（同上一行 G.2） |
| `mmorpg-client/Docs/team-ui.md:40` | 「组队暂未开放」 | 实际已走 `TeamClient`（据分区稿三，未复核 UI 代码） |

### 8.5 对三份分区规格与 inventory 的勘误

**对分区稿**
1. 分区三的 tip 表：
   - 4004 实际带 param=target，原稿写「无」；
   - 4022 实际带 param=被邀请人，原稿写「无」；
   - 4003 除了邀请前置之外，规则层 invite 也带 target，`{-1}` 带新成员，原稿只列了一处；
   - 4002 写「开战时满员」，照抄了 `errors.go:16` 的注释，代码不产生。
2. 分区三 §1.4 写 INVITE_REVOKED 发给「因解散或满员而失效的被邀请人」：只有解散会发（`rules.go:736-744`）。
3. 分区三引用的邀请 4003[target] 位置应为 `service.go:221-223`，不是 `:219-221`。
4. **键名统一**：三份稿分别写成 `xm:team:rec:<tid>`、`xm:team:rec:{tid}`、`xm:{team}:rec:<tid>`。统一为 `xm:{team}:rec|info|player|invite:<id>`，并说明 `{team}` 是 hash tag（§1.3）。
5. **指标名统一**：`xm_team_match_total` 与 `xm_team_matches_total` 统一为后者。scene 刷新信号不移植（分区二曾建议记 `no_consumer`），见 D7。
6. 分区一说「被邀请人上限可被绕过一次、最多 11 条」，核对属实，补上了上界的论证（§8.1 第 1 条）。
7. **补充三条分区稿都没有的隐患**：Mutate 出错时丢失修复推送（§8.1 第 8 条）；建队结果未知时回 4030（第 9 条）；`findAllStrictAsync` 不能逐成员给结果（§8.2 第 5 条）。
8. **补充**：xm-team 需要 `XM_DUBBO_SECRET`（§6.1）；Java 没有 Tip fault 列（§0.3）；4.3 不开放组队人数配置（§5.4）。

**对 `docs/porting/inventory/scene-manager-match.md`**
1. `:316` 写「解散 / 满员时被邀请人收 INVITE_REVOKED」：只有解散会发。
2. `:303` 的转让规则没提 4004 / 4024 的 param，也没提 SessionUnknown 按离线处理。以本稿 §2.2 为准。
3. `:315` 写申请时「跨区 → CrossZoneDenied」在满员之前：确实如此。但审批、接受邀请时的判定顺序见本稿 §2.2，满员在 zone 复核之前。

---

## 9 建议的有意差异

### 9.1 建议采纳

| 编号 | 差异 | 理由 | 客户端可见？ | 两版同改？ |
|---|---|---|---|---|
| D1 | 独立进程 xm-team（Dubbo group `team`，端口 20885，管理端口 18109），不与 match 同进程 | Java 还没有 match；路线图要求每个服务一个进程。整队开战在 6.4 经 Dubbo 接入（已接：`MatchTeamService`，match-spec M1 / M20） | 否 | 否 |
| D2 | 键空间 `xm:{team}:rec / info / player / invite:<id>`，统一 hash tag `{team}`；投影键改名为 info | `AGENTS.md` §3 要求经 `RedisKeys` 生成、带 `xm:` 前缀；多玩家 Lua 将来在 Cluster 下仍同槽 | 否 | 否 |
| D3 | 会话四态由 `xm:presence` 加 `xm:location.s` 组合得出，代替 `player:session` | Java 没有 player_locator；用 location 的 l / o 保住「断线宽限内不惰性转让」 | 轻微：崩溃后的残留窗口 ≤ 60 s（§6.6） | 否 |
| D4 | 展示资料读 `xm_java.player`，代替 PlayerAllData 缓存 | Java 没有这份缓存；同 friend D4 | 是：name 恒非空；level 到存盘才更新；新角色 level 为 1 | 否，登记即可 |
| D5 | home zone = `player.zone_id`，代替 data_service 的 `player:zone` 映射 | Java 没有 data_service；合服重映射等 7.3 | 否 | 否 |
| D6 | 推送走 `PlayerPushes`（Redis pub/sub + 玩家栅栏），代替 Kafka gate-cmd；GATE_UNREACHABLE 记 error | `architecture.md` §4.3；排序契约 `(epoch, version)` 不变 | 否 | 否 |
| D7 | 不发 `PlayerTeamRefreshEvent`，scene 不缓存 TeamId，进场时现读；不移植 `team_scene_refresh_total` | Java scene 里 TeamId 的消费方只有跟随；少一个 Kafka 依赖 | 否 | 否 |
| D8 | 不做队友 AOI 优先级（kTeammate） | Java 的 `ViewIndex` 表满时不挤人，优先级没有可见效果；以后引入挤出时再加 | 否（眼下） | 否 |
| D9 | 跟随用内存里同节点队长的场景，同步 `switchScene`；不读 location，不经 scene-manager | 基线也只做同节点跟随；Java 同节点换图是同步的，没有在途槽，也没有 60 s 去重 | 否（行为等价） | 否 |
| D10 | `in_battle` 恒为 false；跟随与开战预检没有战斗锁（6.3 前）。**已收口（批次 6.3，2026-10-06）**：`in_battle` 由 `BattleLockReader.existsAll` 批量 EXISTS 算出（全体 rosterIds，任何失败整批按 false，§6.7）；scene 组队跟随读锁并有内存冻结守卫（§6.10 第 5 条）。只剩整队开战预检的 4025 随 6.4（现在恒回 4027，D11）。**6.4 已接（2026-10-08）**：4025 由 xm-match 的成员预检读战斗锁给出（§5.5 第 2 点） | Java 还没有战斗（6.3 起有了） | 收口后与基线相同 | 否 |
| D11 | 4.3 的 StartTeamMatch 走规则路径回 4027（端口恒为 0）。**已关闭（批次 6.4，2026-10-08）**：端口换成 `MatchTeamBattle`，211 走真实的预检 → 加锁 → 建票 → gather → EndMatch（§5.5）；4027 只在该副本未配置组队人数时出现。跨进程带来的新故障面（xm-match 调不通 / 过载 / 租约无效时回 4030）登记在 match-spec M20 | 基线未接线时回 4030（fault）；4027 文案真实、不触发告警，6.4 换真端口零改动 | 关闭后与基线相同（只在 xm-match 故障时多 4030 与一条 MATCH_FAILED） | 否，按批次登记 |
| D12 | 过载（队列满 / 排队超预算 / 处理器异常）回 in-band 4030，不带视图和参数 | 基线没有对应；用 team 自己的故障码，客户端按「读失败」重拉 | 是（只在过载时） | 否 |
| D13 | 上行 213 / 215 / 203 时 gate 不回包 | 基线服务端回 Empty，路由服回一个空应答体；客户端不发这三个号 | 否 | 否 |
| D14 | team 后端调用失败或超时时，gate 推 23{1003}（已有差异） | 与 login / friend / chat 同形；基线是信封 1003 → 客户端 Suspended。Java 下客户端要等 15 s 超时 → RequiresReconnect，结果也是本连接停用组队 | 是（只在故障时） | 否 |
| D15 | team_id 来自 xm-team 自己的全服雪花租约，不与 battle_id / challenge_id 共用 | 客户端契约只要求非 0、唯一 | 否 | 否 |
| D16 | robot 用 run-tag 新账号，不用固定的 9301–9304；跨区步骤只在配了第二个 zone 时跑；开战步骤 6.4 再跑（6.4 已加：拒绝码 4018 / 4027 / 4026[B]、S7、S8，match-spec §15.5） | Java robot 惯例（`xm-robot/.../RobotOptions.java:70`）；本机切片只有单 zone | 否 | 否 |
| D17 | gate 热关停对 team 消息号生效（依赖工作区里未提交的热关停功能） | Java 在 gate 统一做按方法关停；基线 match 没有接 killswitch | 是（只在运维关停时：客户端看到信封 1003，停用组队） | 否 |
| D18 | 记录与投影用 Java 自有 proto（形状同 `TeamRecord` / `TeamInfo`），不依赖同步来的契约 proto | friend 的先例（`PARITY.md:97` 的 ⑧）：存储格式不该随契约同步漂移 | 否 | 否 |

### 9.2 列出但不建议首批采纳（或需要两版同改）

- **D19：邀请上限改为「先清过期项再判」**，修掉 §8.1 第 1 条的 11 条绕过。
  - 客户端可见：4022 出现的时机会变。必须先改 mmorpg 再两版同步，首批照搬基线。
- **D20：建队结果未知时**（重读发现新 tid 已有记录、且 caller 的索引指向它）**按已提交处理，回 0**，修掉 §8.1 第 9 条。
  - 客户端可见，需要两版同改，首批照搬基线。
- **D21：被淘汰的被邀请人、或接受时遇到满员，推 INVITE_REVOKED**，兑现 proto 注释的意图。
  - 需要两版同改；客户端已经靠 ListMyInvites / 4021 / 4002 自愈，收益小，不建议。
- **D22：ApplyJoinTeam 目标无队时改回别的码**：不改。两版保持 4013，文案问题留给 mmorpg 决定。
- **D23：请求体解析失败回 1014（login 惯例）**：不采纳。跟 friend 一样回 1003，这就是基线客户端所见。
- **D24：notify_online 只在成功回包时触发**：不采纳，照搬基线，副作用无害。
- **D25：`validateCommitSets` 补上 `Left ⊇ 旧 \ 新` 的校验**：内部、只会更严，可以在 Java 加，不算客户端差异，不必登记 PARITY。

### 9.3 待用户拍板 / 开放问题

1. **xm-team 与将来 xm-match 的边界**（inventory 开放问题 9，`scene-manager-match.md:392`）。本稿按独立进程写。到 6.4 要定：`BattleStarter` 做成 xm-match 的 Dubbo 接口，还是把 team 并入 xm-match。**已定（6.4）**：做成 xm-match 提供的 Dubbo 接口 `MatchTeamService`，两个进程分开（§5.5）。
2. **`{team}` 单槽的代价**：将来上 Cluster 且组队写量大时，要改按队伍打 tag，同时放弃多玩家索引同槽，并拆分 Lua。另一个选择是在 `architecture.md` 登记「组队要求 Redis 单实例」。推荐先用 `{team}`。
3. **location 为 `o`、presence 缺失时按 Present 处理是否合适**。这是偏保守的选择（不转让）。代价是 scene 崩溃后最多推迟 60 s 转让。
4. **PvE 组队人数的来源**：yaml `PveTeamSizeByConfigId`，还是 `DungeonTable.max_team_size`（表里有 10 的历史行，`scene-manager-match.md:389`；Java 已同步 `config-data/tables/dungeon.pb`）。6.4 时两版必须同源同值。**已定（6.4）**：照基线用配置（xm-match 的 `pve-team-size-by-config-id`，缺省 `{1: 5}`），不查表——查表会额外开放 id 2 和 3，客户端可见，须先改 mmorpg（match-spec Q11）。
5. **共用件抽取**：`Deadline` 抽到 xm-common，`PlayerProfiles` 迁到 xm-player-store。推荐在 xm-team 落地时一并做。
6. **D19 / D20 是否推动 mmorpg 先改**：如果要改，Java 首批就可以直接按修正后的语义实现。

---

## 10 测试计划

### 10.1 规则单测（纯函数，照搬 `rules_test.go`）

- **拒绝分支**：每条的错误码、参数、不提交、不改入参（`TestApplyRejections` :68）。
- **锁的边界**与过期锁不挡名单变更（:161、:171）。
- **建队**（:177）；**跨区开关**中途切换（:193）。
- **队长交接与惰性转让**：只有 Absent 触发；Unknown / Present 不触发；新队长可以在同一请求里执行队长操作（:222）。
- **淘汰、刷新、过期**；ID \ IA（:286、:327）。
- **重放安全表**（`TestRetrySafety` :375，对应 §3.15）。
- **加入时同时清申请和邀请**；修复，含全员移出导致解散（:473、:482）；`PruneExpired` 不改入参（:513）。
- **开战锁规则**（`team_battle_test.go:179` `TestMatchLockRules`），包括 `Capacity == 5`。
- **Java 增项**：
  - id 排序用无符号比较；
  - `finish` 的 Reason 优先级；
  - `Left ⊇ 旧 \ 新`（若采纳 D25）；
  - tip 段与 fault 集合 = {4030} 的钉子（对应 `errors_test.go`）。

### 10.2 存储（真 Redis，`-Dxm.it.redis=redis://127.0.0.1:6379`，缺省跳过）

逐个对应 `store_test.go`：
- 拒绝分支零写入（:90，Dump 前后相等）。
- epoch 语义与单调性：只在 tid 变化时 +1；缺失时按 TIME+1 起种；S_READ 回 nowMs（:165、:214）。
- 投影与解散：DEL；`Version` = 最后一版 +1（:267）。
- 自愈：成员索引缺失后重建；`{-2}` 级联修复多名错位成员；孤儿索引（:300）。
- Touch 不涨 ver；重写缺失的投影；不给已去别队的索引续期（:433）。
- 建队哨兵；解散与接受邀请交错（:472）。
- 过期后重邀；ID \ IA；先 ZREM 后 ZADD（:508）。
- 被邀请人上限的原子性（:548），**外加**一个用例钉住 §8.1 第 1 条的 11 条行为（照搬基线；采纳 D19 后改为断言 10）。
- Redis 时钟驱动过期（:611）。
- ReadMembers 的 tid / epoch 配对；邀请列表的 CAS 删除（:655、:674）。
- 并发模糊测试的不变量（:767）。规模可以按 CI 预算缩小，但六条终态不变量都要检查。
- **Java 增项**：
  - ByteArrayCodec 往返任意字节的 pb（含 0x00、非法 UTF-8）；
  - 重放同一段 S_COMMIT（模拟 Redisson 重发）回 `{0}`，不重复写；
  - S_INVITE_LIST 以 READ_WRITE 执行；
  - `TeamMembershipReader` 的三种情形：tid=0、键缺失、投影缺失。

### 10.3 服务与派发（单测，假 Redis 或嵌入式 Redis，假时钟）

- **每个 RPC** 的检查顺序与回包视图来源（§3），逐条对应 `service_test.go`：
  - home zone fail-closed（:284、:295、:331）；
  - 缺 session（:350）：12 个方法 × 2 种情形，都回 in-band 4001、无视图、无写、无推送；
  - 推送与 scene 收件人矩阵（:423）；
  - 过期整队的空视图被客户端规则接受（:519）；
  - 冲突耗尽回 4029（:580）；
  - 迟到的 Leave / Disband 不伤新队（:613、:644）；
  - ctx 过期跳过提交（:673）；
  - notify_online 跳过索引在别队的成员（:704）；
  - ListMyInvites 的 CAS 删除（:739）。
- **StartTeamMatch（4.3）**：
  - 绑定失败 → 4013 + 视图；
  - 非队长 → 4018；
  - 队长 → 4027 + IDLE 视图；
  - Refresh 有提交时整轮重来，最多 3 轮；
  - 不加锁、不推 MATCH_*。
- **StartTeamMatch（6.4，取代上一条里「队长 → 4027」「不加锁」两项）**：用例在 `TeamMatchScenarios`，由 `TeamServiceTest`（内存后端）与 `TeamMatchRedisIntegrationTest`（真 Redis）共跑——加锁之前就拒绝（带同源视图）、
  建票失败释放锁并带 tip 4026[pid]、roster 顺序、锁过期后可重开、并发只锁一次、整轮重来、提交结果未知时的补偿、EndMatch 冲突、xm-match 调不通 / 应答不可信 → 4030、建票结果不明 → 退票 + MATCH_FAILED（无 tip）+ 4030、
  gather 传输失败 → MATCH_FAILED 且不退票、停机中的 gather 结果不明不清锁（清单见 §5.5 末）。
- **TeamDispatcher**：
  - 12 个 C2S 都有路由；203 / 213 / 215 回 `tip=0`、空 body；
  - `player_id=0` → 两种应答类型都回 in-band 4001，不带视图；
  - 解析失败 → 信封 1003，且先于身份检查；
  - 队列满、排队超预算、处理器异常 → in-band 4030，没有 parameters；
  - 指标 result 正确；契约缺号时启动失败。
- **在线状态映射**：presence × location 状态矩阵（存在 / 缺失 / 损坏 / 读失败 × o / l / x / 缺失 / 读失败），覆盖 Online / Present / Absent / Unknown 四种结果。
- **推送**：
  - 收件人规则：CREATED 无人；申请 / 邀请类只推队长，有 LOT 时推全员；其余推 J∪K∪L，移出者收空视图；
  - caller 排除；被拒申请人收 203 REJECTED（actor = 队长）；
  - REVOKED 只在解散时发；
  - 邀请推 215 带 server_time；
  - MEMBER_ONLINE 只推在线、非 caller、tid==本队的成员；
  - 批内按落盘顺序推送；执行器拒绝时记 error。
- **`MessageRoutesTest`**：`ClientPlayerTeam` → `team`；203 / 213 / 215 `hasResponse=false`，其余为 true。gate 的按域队列测试沿用 friend 的。
- **Properties 校验**：request-budget 在 [500, 3500] ms 内；`XM_DUBBO_SECRET` 缺失时拒绝启动。

### 10.4 场景跟随（xm-scene `TeamFollow`，假 Reader，在逻辑线程上执行）

- 非队长进场 → 跟随到队长的**场景实例**。
- 队长进场 → 扇出给本节点成员（每个成员再读自己的成员关系），不循环。
- 队长不在本节点 → 不跟随；已同场景 → 什么都不做。
- 回调前玩家重新进场或已离开 → 丢弃。
- tid=0、键缺失、投影缺失、自己不在 members、`leader_id=0` → 不跟随。
- 队员自己换图后被拉回（§4.8 第 2 条）；入队（没有进场事件）不拉人。
- 指标 result 正确。

### 10.5 robot `team`（`xm-robot` 新增 `TeamScenario`，`RobotOptions.Scenario` 加 `TEAM`）

- **结构**：
  - 三个机器人 A / B / D，同 zone，run-tag 账号（`RobotOptions.java:70`）。
  - 用 `Inbox` 的到达序做 mark 和扫描（`xm-robot/.../client/Inbox.java:20-34`）；相邻请求间隔 ≥ 400 ms。
  - 换图候选从 World 表取（照 `ReconnectScenario.java:99-109`）。
- **步骤**（对应基线 `team_smoke_scenario.go`）：

| 步 | 内容 | 4.3 是否跑 |
|---|---|---|
| S0 | GetMyTeam → 在队就 Disband 或 Leave（预清理） | 跑 |
| S1 | A 建队（zone == 本区）；B 申请；A 看到申请且 version 增长；A 同意；B 收 MEMBER_JOINED，version ≥ 回包、B 恰好出现一次（:241-298） | 跑 |
| S2 | 重放同意：成员不变，join_seq 不变（:304-316） | 跑 |
| S3 | 邀请 D；D 列表里有；D 拒绝，A 收 INVITE_CHANGED 且其中没有 D；再邀请；D 接受（:319-358） | 跑 |
| S4 | 踢 D：D 收空视图且 epoch 变大；A → B → A 转让（:362-396） | 跑 |
| S5 | D 申请、A 拒绝 → D 收 203 APPLICATION_REJECTED（:401-418） | 跑 |
| S6 | A 换图 → B 在 10 s 内收到 79，且 scene_id 等于 A 的新 scene_id（:421-437） | 跑（单 scene 节点天然满足同节点前提） |
| S7 / S8 / X2 开战部分 | 整队开战、战斗中拒绝 | **6.4 再跑**。4.3 改为断言：A StartTeamMatch(1) → **4027**，带视图，`match_state=IDLE`。**6.4 已打开**：S7（STARTING、发起人不收 MATCH_STARTED、同一局的 177 / 143、150、两人 MATCH_ENDED）与 S8（4025[B]），外加拒绝码 4018 / 211(2) → 4027 / 4026[B]；原「211(1) 回 4027」的断言已删。X2 的跨区部分仍只在配了第二个 zone 时跑 |
| X1 / X2 跨区 | 需要第二个 zone 的 gateway；只在配置了第二个 zone 时跑 | 本机单 zone 时跳过并在报告里注明；4020 由 §10.3 用不同 zone_id 的预置玩家覆盖 |
| S9 | 解散，其余成员收 DISBANDED 且 team_id==0（:534-567） | 跑 |

- **Java 增项**（钉住契约细节）：
  - A 重复建队 → 4003 + 视图，且 `leader_id==A`；
  - B 用旧的 expected 离队 → 成功 + 当前视图；
  - A 踢自己 → 4005；A 转让给自己 → 4007；B（非队长）踢人 → 4006；
  - A 邀请一个已登出的账号 → 4017；
  - B 带 `notify_online=true` 拉取 → A 收 213 MEMBER_ONLINE，且 version 不变；
  - A 上行 213 → 收不到任何回包；
  - D 拒绝不存在的邀请 → 幂等成功；RespondInvite(team_id=0) → 4013。
- **输出**：沿用 `CheckReport`，在报告里引用 PARITY「组队」行。
