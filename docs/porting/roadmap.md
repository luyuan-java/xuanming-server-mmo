# 移植路线图（mmorpg → Java 版）

> 2026-10-02 按 mmorpg `26ceb70ca` 全量盘点（约 350 个功能点，明细见 [inventory/](inventory/)，盘点是参考快照，以代码为准）。
> 每一批 = 一次提交：编译通过、单测通过、PARITY.md 登记、提交并推送。批次按依赖排序；完成后在本表「状态」列打勾并写提交号。
> 规则：客户端契约逐字节兼容；服务端内部按 Java 惯用方式重做；第三方库优先 ≥2 万 star、同类取最高（tech-stack.md）。

## 阶段 0：基线

| 批次 | 内容 | 盘点 id | 状态 |
|---|---|---|---|
| 0.1 | 契约同步到 `26ceb70ca`；gate 独立通告端口 | contract-sync、agones-client-endpoint | ✅ `7ab4d9b` |
| 0.2 | 配置表工具重做：权威 schema + 编译期生成 `ConfigTables` + manifest / 外键校验；ContractSync `--check` | table-typed-access-codegen、table-load-validation、table-named-constants | ✅ `3a52935` |

## 阶段 1：平台底座（后面几乎所有玩法都依赖）

| 批次 | 内容 | 盘点 id | 状态 |
|---|---|---|---|
| 1.1 | 玩家数据按玩法分段持久化（`player_state` 玩法数据 blob，带 owner_epoch 围栏）+ 在线周期存盘 + 脏比对跳过（「最终写回失败后持续重试直到成功」仍待做，见 PARITY） | player-persistent-data-model、player-data-record、periodic-dirty-save、periodic-autosave、save-failure-durability | ✅ `9103f78` |
| 1.2 | 玩家在线目录（player → gate / session / scene）+ 服务端向在线玩家推送通道（tip / 踢线 / 业务推送） | player-presence-directory、player-push-channel、gate-command-channel、sm-gate-command-channel、server-push-tip-kick-redirect、kick-player | ✅ `832c0a3`（场景 / 全服广播随首个用到的功能做） |
| 1.3 | 非 scene 客户端服务的后端路由（gate 路由表 → 各 Dubbo 服务）+ 按方法热关停 | other-backend-routing、social-backend-routing、contract-service-backend-routing、rpc-killswitch、killswitch | 后端路由并入 4.1 ✅；按方法热关停 ✅ `8a4726f` |
| 1.4 | 请求字段规模校验、客户端 GM 指令闸（gate + scene） | request-field-sanity-check、gm-client-message-gate、client-gm-gate | ✅ `a8ccc0d`（并入 2.1：GM 闸两道锁；字段规模与负数校验做在 scene 分发入口，对全部 scene 客户端请求生效） |
| 1.5 | 公共件：游戏日 / 游戏周切点、永久 GUID 号段、表达式列求值、表内 tip 引用校验 | game-day、game-day-periods、guid-segment-alloc、id-segment-allocator、table-expression-columns、table-tip-ref-validation | ✅（提交见 git log「批次 1.5」；号段不移植——Java 用雪花 + 节点号租约，PARITY「永久身份号段」行）|

## 阶段 2：角色成长（scene 内玩法）

| 批次 | 内容 | 盘点 id | 状态 |
|---|---|---|---|
| 2.1 | 货币：加 / 扣 / 余额、列表与 GM 指令（54/37/49/94/95）、客户端 GM 闸（gate + scene，运行模式）、scene 请求分发改为按功能注册、获取封禁（属性洗点 / 方案要扣金币，所以货币在属性之前） | currency-core、currency-client-and-gm、currency-debt-clawback、gain-block | ✅ `a8ccc0d`（玩家级 GM 封禁已做；补缴债务 currency-debt-clawback 移到 2.9 资产通道（基线目前没有生产调用方挂债），全服产出封禁 gain-block 移到 2.3 与异常检测一起） |
| 2.2 | 属性：二级属性重算、属性面板（167/170）、加点 / 洗点 / 自动加点（168/172/173）、方案（174/171/169）、GM 设等级（175）、等级 | derived-attribute-recalc、attribute-panel、attribute-allocate-reset-auto、attribute-schemes、gm-set-player-level、player-level；robot attribute-smoke | ✅ `787f497`（行为互斥表 actor-action-state 移到 2.6、运行时属性重算位 actor-attribute-calculator 移到 2.7：当前表数据下两者都没有客户端可见效果；当前气血 / 法力持久化随 2.7） |
| 2.3 | 资产流水与审计（Kafka）、获取异常检测、全服产出封禁、玩家快照 | transaction-log、kafka-client-infra、kafka-audit-pipeline、anomaly-detector、gain-block、player-snapshot | 2.3a 资产流水管线 ✅ `c7939d2`；2.3b 玩家快照 ✅ `b9519f0`；2.3c 全服产出封禁 + 获取异常检测 ✅ `625f10a` |
| 2.4 | 背包：容器（堆叠 / 格子 / 四个包）、编排、持久化、读取 / 整理（191/192）、装备栏规则 | bag-core-container、bag-orchestration-service、bag-persistence、bag-client-get-sort、equip-slot-rules | ✅ `580e2cd` |
| 2.5 | 条件 + 奖励 + 任务（193/194/195）+ 活动列表（190）；robot features-smoke | condition-eval、mission-*、activity-list、activity-schedule-list | ✅ `3557267`（完成后的自动领奖 / 链式接取 / 完成事实同步执行，基线线上不派发——PARITY 有意差异；完成 / 领奖成功的端到端随 6.3 击杀来源——6.3 已接：战斗结算的击杀事实，端到端写进 robot `battle-settle`） |
| 2.6 | 技能：冷却、施法阶段与打断（33）、伤害结算、行为互斥表（放技能前的状态检查） | skill-cooldown、skill-cast-phases-interrupt、realtime-skill-damage、combat-damage-rules、actor-action-combat-state、actor-action-state | ✅ `a79a469`（冷却 / 后摇 / 引导按设计意图生效，基线线上只有前摇——PARITY 有意差异；伤害只移植纯公式 CombatDamageRules，实时技能命中两版都不生效、不接；66 combat_state_flags 随 2.7） |
| 2.7 | buff 核心与效果、运行时属性重算位与战斗状态（66 combat_state_flags）、死亡 / 复活、新号初始化与登录回满（当前气血 / 法力持久化） | realtime-buff-core、realtime-buff-effects、actor-attribute-calculator、death-revive、new-player-init-and-revive | ✅ `f368901`（当前气血 / 法力落 player_state.vitals、加载复活；实时 buff / 属性重算位 / 66 combat_state_flags 基线线上不可达，两版都未生效、登记不移植；结算复活与 0 血拒绝开战随 6.3——6.3 已做） |
| 2.8 | 宝宝系统；robot pet-smoke | pet-system-core | ✅ `6ff0e34`（写闸随 5.2 / 6.3，战斗接缝随 6.3——写闸（交出冻结 1005、战斗在途 26008）与战斗接缝都已随 5.2 / 6.3 接上；交易原语随聚宝斋资产托管） |
| 2.9 | 通用资产通道（跨服务发放 / 扣除，幂等账本）、补缴债务（加币先抵扣） | asset-channel、asset-op-channel、asset-op-ledger-read、currency-debt-clawback | ✅ `eab1952`（scene 侧：账本 / 验签 / 扣发 / durable / 进程内入口、接管守卫；补缴欠款两版都休眠。跨进程传输与调用方 asset-channel、已落盘账本查询 asset-op-ledger-read 随 4.5 帮会经济） |

## 阶段 3：登录与网关补全

| 批次 | 内容 | 盘点 id | 状态 |
|---|---|---|---|
| 3.1 | access / refresh token、HTTP `/api/login`、`/api/refresh-token`、设备数上限 | login-access-refresh-token、http-login、gateway-http-login、gateway-refresh-token、login-device-limit；robot e2e-http | ✅ `6922bc4`（robot `token` 覆盖 e2e-http 链路；登录限流排队随 3.4） |
| 3.2 | 生产口令认证（Argon2id）与第三方认证 | login-production-password、login-third-party-auth、satoken-auth-service | ✅ `5294cc1`（另含存量口令迁移工具 PasswordAdmin；satoken-auth-service（基线 java/springboot_satoken_auth_starter）本身就是 Java 进程，Java 版当外部组件复用、只读它写的 Redis，不另行移植） |
| 3.3 | 短线重连：30s 断线租约、回原节点复用实体、租约到期收口 | short-reconnect-lease、reconnect-resume、lease-expired-zombie-close | ✅ `ff1797b`（玩家位置记录 + 30 s 重连租约；不复用内存实例、租约到期无收口动作——PARITY 有意差异；首登改为同基线落默认主世界；更正参考契约里基线「重连回原场景」的错误描述）|
| 3.4 | 登录排队、开服限流、区服目录 / 健康探测 / 运维接口 / 公告 / 白名单 | login-queue、login-queue-dispatcher、gateway-rate-limit、gateway-zone-*、admin-api-auth、gateway-announcement | 3.4a 区服目录 / 健康探测 / 运维接口 / 公告 / 白名单 ✅ `46e65ec`（运维接口放在 xm-data 的运维面）；3.4b 登录排队 ✅ `554405f`（弹出与放行同一段 Lua、取走后占位再留 15 s、放行摊到各 gate——PARITY 有意差异）；3.4c 开服限流 ✅ `cf9fb96`（Redis Lua 令牌桶、先 IP 桶后区桶、冷却按身份哈希 + IP、IPv6 按 /64——PARITY 有意差异）|
| 3.5 | GM 签名停机、gate 排空与滚动替换 | gm-graceful-shutdown、gm-graceful-shutdown-rpc、gate-drain、gate-drain-ops | ✅ `3f7ec8f`（签名绑「区:节点号:实例」、只收本机；排空标记绑实例、运维入口在 xm-data——PARITY 有意差异；K8s 滚动替换编排随部署批次）|

## 阶段 4：社交服务（每个服务一个 Spring Boot 进程模块）

> 存储约定（2026-10-03 用户要求）：从这一阶段起凡是新增的、形状由 proto 消息决定的 MySQL 表（好友 / 邮件 / 帮会 / 交易等），
> 接 pbmysql——用户自有的 proto2mysql（Go 版 `luyuan-cpp/proto2mysql`，mmorpg go/db 用它从 proto 描述推导表结构、upsert 落库）的 Java 实现，
> 不手写逐表 DDL 与 Mapper；首次接入时把选择写进 tech-stack.md（自有库，不受 star 门槛约束）。玩家主记录目前是 `player` 行 + `player_state` blob，
> 是否改走 pbmysql 在 7.x 数据层批次一并评估。

| 批次 | 内容 | 盘点 id | 状态 |
|---|---|---|---|
| 4.0 | xm-pbmysql：proto2mysql 的 Java 实现（建表 DDL、只扩不缩同步、按消息 CRUD） | — | ✅ `0fae75f`（DDL 与 Go 逐字节相同；updateByPk 写整行——PARITY 有意差异）|
| 4.1 | 好友（申请 / 同意 / 删除 / 列表 / 黑名单 / 推荐 / 在线目录 / 推送）；robot friend-smoke | friend-* | ✅ 4.1a `85f7aef`（核心与推送）、4.1b `c0b3cd1`（推荐 / 在线目录 / 清理）；规格 docs/porting/friend-spec.md，有意差异见 PARITY「好友」行 |
| 4.2 | 聊天（世界 / 私聊、历史、限速）；robot chat-smoke | chat-send、chat-history | ✅ `dd7b80b`（独立进程 xm-chat；处理全程异步——PARITY「聊天」行）|
| 4.3 | 组队（名册 / 申请邀请 / 推送 / 场景投影与跟随）；robot team-smoke | team-roster、team-apply-invite、team-notify、team-scene-follow | ✅ `05ffdf9`（独立进程 xm-team；在线四态取 presence + location、跟随走本节点内存、整队开战恒回 4027 待 6.4——PARITY「组队」行）|
| 4.4 | 帮会核心（建 / 查 / 退 / 解散 / 公告 / 任免 / 踢人 / 申请审批 / 推送 220 / 排行）；robot guild-smoke | guild-* 核心段 | ✅（提交见 git log「批次 4.4」；独立进程 xm-guild、过载回 in-band 14021、经济 / 活动号暂回 1006——PARITY「帮会核心」行）|
| 4.5 | 帮会经济（资产指令账本与投递、捐献、升级、商店）；robot guild-economy | guild-asset-*、guild-donate、guild-upgrade、guild-shop | ✅（提交见 git log「批次 4.5」；资产通道跨进程传输 scene Dubbo 提供方、离线读已落盘账本——PARITY「帮会经济」行；也补上了 2.9 留下的 asset-channel 传输与 asset-op-ledger-read）|
| 4.6 | 帮会活动（公共底座、列表、灯会、团圆、同道历练 239–243） | guild-activity-*、guild-lantern、guild-reunion、guild-trial |
| 4.7 | 聚宝斋只读面（浏览 / 详情 / 收藏 / 货架）；robot trade-smoke | trade-browse-listings、trade-listing-detail、trade-favorite、trade-my-shelf | ✅（提交见 git log「批次 4.7」；独立进程 xm-trade、dev 播种走管理口 HTTP、listing_id 用雪花——PARITY「聚宝斋只读面」行）|
| 4.8 | 聚宝斋写侧（上架托管、下单、支付、交付、回退；trade 侧资产托管通道 P2 与 P3–P6 同批） | trade-asset-escrow-channel、trade-p3-orders-payment、trade-merge-zone-rewrite | 等 mmorpg 定下 P3 契约（消息号 / tip / proto）后两版同批做（trade-spec §4.6） |

## 阶段 5：场景拓扑

| 批次 | 内容 | 盘点 id | 状态 |
|---|---|---|---|
| 5.1 | 场景实例登记、主世界多频道与自动扩缩容 | scene-instance-registry、world-channels-from-tables、sm-world-channel-* | ✅（提交见 git log「批次 5.1」；频道计划在 Redis、scene-manager 分 zone 领导者维护、scene 节点拉取收敛，缺省每节点每图一个频道、自动扩缩容缺省关；跨节点改派随 5.2 / 5.5——PARITY「场景实例与主世界频道」行）|
| 5.2 | 跨节点换图 + 归属交接 | scene-switch-cross-node、ownership-handoff、sm-cross-node-scene-switch、sm-player-location | ✅（提交见 git log「批次 5.2」；一笔 MySQL 交出事务（写回冻结快照 + epoch 加一，剩余租约安全边际 + 加锁读探测）、scene-manager 只选目标、gate 经链路帧 PlayerTransfer 改绑、冻结闸集中在入口缺省拒绝；per-node 覆盖保持缺省，本机双 scene 切片 + robot cross-node——PARITY「跨节点换图与归属交接」行）|
| 5.3 | 副本（Dungeon 表）与镜像场景、空闲回收 | dungeon-instance、mirror-scene、sm-mirror-instance、sm-instance-lifecycle | ✅（提交见 git log「批次 5.3」，契约与 scene-manager 部分随 `db585b4`；实例由承载节点自有、节点目录是唯一登记，scene-manager 只发全服 scene_id、镜像恒与源同节点；空闲回收按精确变空时刻 + 30 s 宽限（宽限内在途进场复活）、级联在节点本地同图改派；选频道 / 改派只认主世界频道；副本只有 dev / test 管理口入口；有意差异 D1–D19——规格 docs/porting/dungeon-mirror-spec.md §13，PARITY「副本 / 镜像场景与实例空闲回收」行）|
| 5.4 | 跨 zone 传送（226）与重定向（124）、归属区路由；robot travel-smoke | zone-travel、cross-zone-redirect、sm-cross-zone-redirect、home-zone-mapping、sm-home-zone-routing | 待做（双 zone 本机切片 `XM_ZONES=2` 与 robot 的 `--visit-zone` 已由 6.5 提前落地——只有切片脚本与 robot 选项，226 / 124、GO-5、X16 等生产代码都还没有做；见 6.5 行与 zone-travel-spec §5.13 的「落地状态」）|
| 5.5 | 场景排空 / 节点疏散、死节点判定与接管 | scene-drain-relocate、sm-dead-node-recovery、scene-node-loss-handling |

## 阶段 6：匹配与回合制战斗

| 批次 | 内容 | 盘点 id | 状态 |
|---|---|---|---|
| 6.1 | 回合制战斗引擎（纯库：回合、技能、buff、道具、掉落） | turn-battle-engine-core、turn-battle-skills-buffs、turn-battle-items-drops-rewards、battle-table-fingerprint | ✅（提交见 git log「批次 6.1」；新纯库模块 xm-battle-engine，逐位照搬基线、有意差异 D1–D8；跨语言金样待 mmorpg——PARITY「回合制战斗确定性引擎」「战斗配表指纹」行）|
| 6.2 | battle 节点：房间生命周期、客户端直连、票据、推送、准入；观战的房间侧（规格 Q1 并入） | battle-room-lifecycle、battle-direct-connect-edge、battle-ticket-assignment、battle-client-actions-push、battle-node-admission-ops、battle-spectate（房间侧） | ✅ `db585b4`（评审修复 `3deff9b`；独立进程 xm-battle：单逻辑线程兼直连面 EventLoop、Dubbo 控制面按节点直连、大厅公告经 `PlayerPushes` 一条 `MessageBatch` 保序、dev / test 管理口建房；确认 / 结算 / 对局结果只定义出站端口、缺省只记日志，传输随 6.3 / 6.4；观战房间侧（AddObserver / RemoveObserver、161 / 158 / 166、165）已做，match 侧留给 6.5（2026-10-08 已落地，见 6.5 行）；限频器挪到 xm-net、解码改逐帧分发；有意差异 N1–N21——PARITY「battle 节点」行）|
| 6.3 | scene 侧战斗冻结与结算应用、结算 outbox | scene-battle-freeze、scene-battle-settlement-apply、battle-settlement-outbox、in-battle-gates、pet-battle-integration | ✅ `4f3f345`（主代码最早随 `7dff75c` 入库，之后经审计 → 修复 → 补测试 → 评审，第一轮的修复与测试在 `4f3f345`，CI 上的一处测试竞态修在 `d6b6b1e`；第二轮修正与文档见其后的提交，git log「批次 6.3 收尾」；scene 在资产 RPC 端口上再导出 `SceneBattleService`（备战 / 取消 / 确认 / 结算，Dubbo 按节点直连）；战斗锁是承重的：一个 Hash、独占、写成功才回 match，全部 Lua 在 `BattleRedis`；在途闸按方法声明 `BattlePolicy`、缺省拒绝，与 5.2 的交出冻结始终互斥；结算链路：battle 发件箱先落库后投递、未销账就 10 s × 12 重投（每局一个字段），scene 幂等应用，账本 `player_state.battle_ledger = 9` 与资产同一次围栏写，落盘后销账并留已销账墓碑；进场恢复闸（代际 + 本实例已销账集合）、reaper 判废前 10 s 宽限 + rescue；活动结果持久通道做了 battle 侧（两版都不可达）；宝宝参战快照与结算回写；gate 的战斗上行回到基线位置当场拒绝、xm-team 的 `in_battle` 与组队跟随读锁、xm-data 回档前查战斗锁一并收口；dev / test 管理口 `dev/gather` + robot `battle-settle` 与两个故障变体供 6.4 之前验收；有意差异 D1–D36、照搬的基线怪癖 B1–B17；match 的调用方、对局结果的传输与整队开战预检留给 6.4——规格 docs/porting/scene-battle-spec.md 末尾两段实现记录（运行结论见其中「最终验证」），PARITY「scene 侧战斗冻结与在途闸」「回合制战斗结算链路」「战斗活动结果持久通道」行）|
| 6.4 | 匹配：排队、凑单、开局、评分、切磋、帮会活动开战、整队开战；robot battle-smoke | match-*、team-match | ✅（提交见 git log「批次 6.4」：先行件 → 十个工作包 → 集成 → 八路评审与修正；新进程模块 xm-match（Dubbo 20888 / 管理 18113，group `match`）：全部 match 键共用一个 hash tag `{match}`，票据与队列 15 段可重放的 Lua；凑单按队列加锁、锚点 + 评分容差、原子弹组；开局管线每次一个虚拟线程，步骤、补偿矩阵、各跳超时与 matched TTL 公式同基线，建房之前写落点记录；补签 179 先直拨落点地址、三条证据才判房间已死；评分落 MySQL 两张表、一局一笔事务，对局结果走 Kafka `xm-battle-result-g<代次>`（xm-battle 生产、xm-match 消费）；切磋的发起与消费原子；帮会活动开战做了 match 侧与 dev 管理口，调用方与结果消费随 4.6；整队开战 211 由 xm-team 经 `MatchTeamService` 接上（team-spec D11 关闭）；gate 把 `MatchService` 的 10 个号路由到 xm-match；发号租约真正丢失时健康检查 DOWN、拒收排队与切磋发起；robot `battle-smoke` / `match-activity` / `match-5v5` 与 `team` 的开战段（S7 / S8），本机切片 13 个服务进程；有意差异 M1–M33；观战的 match 侧（6.4 期间 163 / 164 回 1006 / 空列表）与跨区 1V1 留给 6.5（2026-10-08 已落地，见 6.5 行），后端重启后约 60 s 的重连恢复留给 7.6——规格 docs/porting/match-spec.md 末尾「实现记录」（运行结论见其中「最终验证」），PARITY「匹配」「切磋」「战斗票据补签 179」「帮会活动开战」行与「组队」「battle 节点」行的 6.4 段）|
| 6.5 | 观战的 match 侧（163 / 164、观战索引、开局前清退观众；房间侧已在 6.2）；跨区 1V1 | battle-spectate（match 侧）、match-spectate；robot battle-smoke-cross-zone | ✅（提交见 git log「批次 6.5」：先行件 → 六个工作包 → 集成 → 六路评审与修正；battle-spectate 的房间侧（AddObserver / RemoveObserver、161 / 158 / 166、165）已在 6.2，本批只做 match 侧，代码在 xm-match 的 `com.game.match.spectate` 包，没有新进程、没有新依赖；163 / 164 换成真实现，6.4 的临时应答 M22 关闭；两个新键——观战标记 `xm:{match}:watching:<pid>`（值带 nonce、按值删）与可观战索引 `xm:{match}:watchable`——与票据、落点记录同在 `{match}` tag，观战记录复用 6.4 的落点记录，11 段读主库的 Lua，抢标记与查票据原子完成；163 的判定顺序、16004 / 16014–16019 与九条 `parameters[0]` 同基线，跑在处理器自带的执行器上（每请求一条虚拟线程、在途上限 128、4.5 s 预算，`MatchMethodHandler.executor()`）；观众 RPC 按落点记录的地址直拨 battle，判死与 179 共用三条证据，带硬截止的重载到点不判死；164 在工作池上，懒剔除不补齐；开局钩子 `SpectateGatherHooks`（开局前逐人清退、每人至多 3 s、永不阻断开局，开局后按 attempt 公开）；清扫 10 s 一轮；停机时并行等在途 163、当场中断清扫，第 3–5 步的设计预算仍是 20 s；跨区 1V1 没有新的生产代码路径，交付的是不变量 Z1–Z12 的对照与组件层的测试钉（Z1–Z3、Z5–Z7、Z11、Z12）、同号节点跨 zone 碰撞的寻址审计、双 zone 本机切片 `XM_ZONES=2`（区 2 的 `xm-scene-z2` / `xm-gate-z2`，共 15 个进程，区 2 的区服状态跟随 `XM_ZONES`）与 robot 新场景 `battle-cross-zone`（`--visit-zone`）——这两样原是 5.4 的交付物，由本批提前落地，不含 5.4 的任何生产代码；robot `battle-smoke` 加观战段（S0–S13），同号第二局 PVE 不再断言胜负；有意差异 W1–W20、X1–X8，照搬的基线怪癖 BW1–BW10，两版同改候选 C1 / C2（ready 残留自愈、已结束的战斗及时出索引）没有做；双 zone 进 CI 与 Go robot 跨版本验收留给 7.1b 之后，xm-match 多副本与连接治理留给 7.6——规格 docs/porting/spectate-spec.md 末尾「实现记录」（运行结论见其中「最终验证」），PARITY「观战」「跨区 1V1 匹配」行与「匹配」「battle 节点」「战斗票据补签 179」行的 6.5 段）|

## 阶段 7：运维、数据与工具

| 批次 | 内容 | 盘点 id | 状态 |
|---|---|---|---|
| 7.1 | 本地编排（docker compose）、镜像构建、CI（构建 + 单测 + 契约 `--check`） | deploy-local-compose、deploy-container-images、ci-workflows、container-image-build | 7.1a ✅（提交见 git log「批次 7.1a」；三个 workflow、`deploy/compose/infra.yaml`、`tools/TestReport.java`、mvnw 可执行位，另把 build-info 与 Dockerfile 提前做了；compose / 镜像只在 CI 上执行，结论以首轮 CI run 为准——规格 docs/porting/deploy-ci-spec.md §12，PARITY「本地编排」「服务镜像与构建信息」「CI 门禁」行）；7.1b 待做（整栈 `stack.yaml`、各进程地址占位符与探针 / info、CI stack job） |
| 7.2 | 流水 / 快照落库、GM 快照差异、回档、批量回收 | txlog-ingest、player-snapshot-ingest、gm-snapshot-diff、gm-rollback、gm-batch-recall | 7.2a ✅（提交见 git log「批次 7.2a」；流水筛选查询与三条索引、物品追溯、快照新原因与读路径、手工快照 / 详情 / 结构化差异、回收 dry-run、保留期按原因分、兜底日志回灌、运维作业表（pbmysql）、102–117 回归测试——规格 docs/porting/data-ops-spec.md §2.4 / §3 / §5.4 / §6（该稿 §13 只有 7.2b 的实现记录），architecture.md §4.5，PARITY「GM 快照与差异」等行）；7.2b ✅ `7dff75c`（与 6.3 主代码同一提交；作业框架：202 + jobId、全集群单飞 `ops_active`、心跳 / 清扫 / 幂等键 / 取消，不自动续跑；离线栅栏 = 归属夺权 `AdminOwnership`：缺省拒绝在线、显式 kick 走顶号通路、续约、释放前写位置墓碑；回档 `POST /admin/rollbacks`：单人 / 多人 / 整区 / 全服，FULL / SECTIONS，每人一个事务写安全快照 + 覆盖写 + TX_ROLLBACK_RESTORE 流水 + 明细；三道资产分歧检查：帮会（Dubbo）、账本差集、回收逆转（已接线，7.2c 起才有数据）；整区维护前快照 `POST /admin/zone-snapshots`；`idx_player_zone`（db-migrations M9）；robot `rollback` 10 项；回档前查战斗锁已随 6.3 落地（`4f3f345`；夺权之后批量读锁，锁在记 `in_battle`、读不到记 `battle_lock_unknown`，都无条件不写），同一轮补齐了 7.2b 登记时留下的测试缺口、robot `rollback` 的检查项扩到 19 项（运行结论见规格 §13.4「最终验证」）——规格 docs/porting/data-ops-spec.md §13.1–§13.4，PARITY「GM 回档」行）；7.2c 待做（回收执行、欠款、精确回收） |
| 7.3 | 合服（围栏、玩家数据、跨系统步骤、预检 / 审计 / 撤销、合服后提示） | merge-* |
| 7.4 | 一致性巡检、存盘压测、玩家数据排障工具、压测机器人 AI | data-consistency-check、data-stress-verifier、player-data-debug-tools、robot-stress-ai |
| 7.5 | 契约变更报告与兼容性闸；导航网格查询 | contract-change-report、navmesh-bake-and-query、navmesh-queries |
| 7.6 | K8s 开区编排、发布制品与门禁、日志 / 告警 | deploy-k8s-zone-orchestration、release-packaging、release-preflight、observability-logs-alerts |

## 不移植（及原因）

- 导表主流程与客户端各语言表产物：表数据是两版共享的契约，只在 mmorpg 导出一次，Java 消费同一份产物。
- 消息号 / 事件号发号：契约在 mmorpg 定，Java 只同步。
- C++ ECS 组件生成、C++/Go 内部 proto 产物、仓库卫生补丁：Java 没有对应物。
- 战斗美术程序化生成（battle_art_gen）：客户端资源工具，与服务端无关。
- 存储落点多库路由 / 在线搬库、跨区 KV 代理：mmorpg 的 TiDB 分库方案；Java 版存储方案另行设计，到合服 / 扩容批次时再定。
- 会话级消息签名密钥、遗留 gate RPC、心跳：mmorpg 未启用或已废弃。
