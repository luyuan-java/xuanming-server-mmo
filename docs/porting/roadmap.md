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
| 2.5 | 条件 + 奖励 + 任务（193/194/195）+ 活动列表（190）；robot features-smoke | condition-eval、mission-*、activity-list、activity-schedule-list | ✅ `3557267`（完成后的自动领奖 / 链式接取 / 完成事实同步执行，基线线上不派发——PARITY 有意差异；完成 / 领奖成功的端到端随 6.3 击杀来源） |
| 2.6 | 技能：冷却、施法阶段与打断（33）、伤害结算、行为互斥表（放技能前的状态检查） | skill-cooldown、skill-cast-phases-interrupt、realtime-skill-damage、combat-damage-rules、actor-action-combat-state、actor-action-state | ✅ `a79a469`（冷却 / 后摇 / 引导按设计意图生效，基线线上只有前摇——PARITY 有意差异；伤害只移植纯公式 CombatDamageRules，实时技能命中两版都不生效、不接；66 combat_state_flags 随 2.7） |
| 2.7 | buff 核心与效果、运行时属性重算位与战斗状态（66 combat_state_flags）、死亡 / 复活、新号初始化与登录回满（当前气血 / 法力持久化） | realtime-buff-core、realtime-buff-effects、actor-attribute-calculator、death-revive、new-player-init-and-revive | ✅ `f368901`（当前气血 / 法力落 player_state.vitals、加载复活；实时 buff / 属性重算位 / 66 combat_state_flags 基线线上不可达，两版都未生效、登记不移植；结算复活与 0 血拒绝开战随 6.3） |
| 2.8 | 宝宝系统；robot pet-smoke | pet-system-core | ✅ `6ff0e34`（写闸随 5.2 / 6.3，战斗接缝随 6.3，交易原语随聚宝斋资产托管） |
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
| 5.3 | 副本（Dungeon 表）与镜像场景、空闲回收 | dungeon-instance、mirror-scene、sm-mirror-instance、sm-instance-lifecycle |
| 5.4 | 跨 zone 传送（226）与重定向（124）、归属区路由；robot travel-smoke | zone-travel、cross-zone-redirect、sm-cross-zone-redirect、home-zone-mapping、sm-home-zone-routing |
| 5.5 | 场景排空 / 节点疏散、死节点判定与接管 | scene-drain-relocate、sm-dead-node-recovery、scene-node-loss-handling |

## 阶段 6：匹配与回合制战斗

| 批次 | 内容 | 盘点 id | 状态 |
|---|---|---|---|
| 6.1 | 回合制战斗引擎（纯库：回合、技能、buff、道具、掉落） | turn-battle-engine-core、turn-battle-skills-buffs、turn-battle-items-drops-rewards、battle-table-fingerprint | ✅（提交见 git log「批次 6.1」；新纯库模块 xm-battle-engine，逐位照搬基线、有意差异 D1–D8；跨语言金样待 mmorpg——PARITY「回合制战斗确定性引擎」「战斗配表指纹」行）|
| 6.2 | battle 节点：房间生命周期、客户端直连、票据、推送、准入 | battle-room-lifecycle、battle-direct-connect-edge、battle-ticket-assignment、battle-client-actions-push、battle-node-admission-ops |
| 6.3 | scene 侧战斗冻结与结算应用、结算 outbox | scene-battle-freeze、scene-battle-settlement-apply、battle-settlement-outbox、in-battle-gates、pet-battle-integration |
| 6.4 | 匹配：排队、凑单、开局、评分、切磋、帮会活动开战、整队开战；robot battle-smoke | match-*、team-match |
| 6.5 | 观战；跨区 1V1 | battle-spectate、match-spectate；robot battle-smoke-cross-zone |

## 阶段 7：运维、数据与工具

| 批次 | 内容 | 盘点 id | 状态 |
|---|---|---|---|
| 7.1 | 本地编排（docker compose）、镜像构建、CI（构建 + 单测 + 契约 `--check`） | deploy-local-compose、deploy-container-images、ci-workflows、container-image-build | 7.1a ✅（提交见 git log「批次 7.1a」；三个 workflow、`deploy/compose/infra.yaml`、`tools/TestReport.java`、mvnw 可执行位，另把 build-info 与 Dockerfile 提前做了；compose / 镜像只在 CI 上执行，结论以首轮 CI run 为准——规格 docs/porting/deploy-ci-spec.md §12，PARITY「本地编排」「服务镜像与构建信息」「CI 门禁」行）；7.1b 待做（整栈 `stack.yaml`、各进程地址占位符与探针 / info、CI stack job） |
| 7.2 | 流水 / 快照落库、GM 快照差异、回档、批量回收 | txlog-ingest、player-snapshot-ingest、gm-snapshot-diff、gm-rollback、gm-batch-recall | 7.2a ✅（提交见 git log「批次 7.2a」；流水筛选查询与三条索引、物品追溯、快照新原因与读路径、手工快照 / 详情 / 结构化差异、回收 dry-run、保留期按原因分、兜底日志回灌、运维作业表（pbmysql）、102–117 回归测试——规格 docs/porting/data-ops-spec.md §13，PARITY「GM 快照与差异」等行）；7.2b 待做（作业框架、归属夺权栅栏、回档、帮会检查、整区维护前快照）；7.2c 待做（回收执行、欠款、精确回收） |
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
