# 移植路线图（mmorpg → Java 版） ✅ `3557267`（完成后的自动领奖 / 链式接取 / 完成事实同步执行，基线线上不派发——PARITY 有意差异；完成 / 领奖成功的端到端随 6.3 击杀来源） |

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
| 1.3 | 非 scene 客户端服务的后端路由（gate 路由表 → 各 Dubbo 服务）+ 按方法热关停 | other-backend-routing、social-backend-routing、contract-service-backend-routing、rpc-killswitch、killswitch | ➡ 并入 4.1（gate 侧每接一个后端只加一行，与首个社交服务一起做，免得空转） |
| 1.4 | 请求字段规模校验、客户端 GM 指令闸（gate + scene） | request-field-sanity-check、gm-client-message-gate、client-gm-gate | ✅ `a8ccc0d`（并入 2.1：GM 闸两道锁；字段规模与负数校验做在 scene 分发入口，对全部 scene 客户端请求生效） |
| 1.5 | 公共件：游戏日 / 游戏周切点、永久 GUID 号段、表达式列求值、表内 tip 引用校验 | game-day、game-day-periods、guid-segment-alloc、id-segment-allocator、table-expression-columns、table-tip-ref-validation |

## 阶段 2：角色成长（scene 内玩法）

| 批次 | 内容 | 盘点 id | 状态 |
|---|---|---|---|
| 2.1 | 货币：加 / 扣 / 余额、列表与 GM 指令（54/37/49/94/95）、客户端 GM 闸（gate + scene，运行模式）、scene 请求分发改为按功能注册、获取封禁（属性洗点 / 方案要扣金币，所以货币在属性之前） | currency-core、currency-client-and-gm、currency-debt-clawback、gain-block | ✅ `a8ccc0d`（玩家级 GM 封禁已做；补缴债务 currency-debt-clawback 移到 2.9 资产通道（基线目前没有生产调用方挂债），全服产出封禁 gain-block 移到 2.3 与异常检测一起） |
| 2.2 | 属性：二级属性重算、属性面板（167/170）、加点 / 洗点 / 自动加点（168/172/173）、方案（174/171/169）、GM 设等级（175）、等级 | derived-attribute-recalc、attribute-panel、attribute-allocate-reset-auto、attribute-schemes、gm-set-player-level、player-level；robot attribute-smoke | ✅ `787f497`（行为互斥表 actor-action-state 移到 2.6、运行时属性重算位 actor-attribute-calculator 移到 2.7：当前表数据下两者都没有客户端可见效果；当前气血 / 法力持久化随 2.7） |
| 2.3 | 资产流水与审计（Kafka）、获取异常检测、全服产出封禁、玩家快照 | transaction-log、kafka-client-infra、kafka-audit-pipeline、anomaly-detector、gain-block、player-snapshot | 2.3a 资产流水管线 ✅ `c7939d2`；2.3b 玩家快照 ✅ `b9519f0`；2.3c 全服产出封禁 + 获取异常检测 ✅ `625f10a` |
| 2.4 | 背包：容器（堆叠 / 格子 / 四个包）、编排、持久化、读取 / 整理（191/192）、装备栏规则 | bag-core-container、bag-orchestration-service、bag-persistence、bag-client-get-sort、equip-slot-rules | ✅ `580e2cd` |
| 2.5 | 条件 + 奖励 + 任务（193/194/195）+ 活动列表（190）；robot features-smoke | condition-eval、mission-*、activity-list、activity-schedule-list |
| 2.6 | 技能：冷却、施法阶段与打断（33）、伤害结算、行为互斥表（放技能前的状态检查） | skill-cooldown、skill-cast-phases-interrupt、realtime-skill-damage、combat-damage-rules、actor-action-combat-state、actor-action-state |
| 2.7 | buff 核心与效果、运行时属性重算位与战斗状态（66 combat_state_flags）、死亡 / 复活、新号初始化与登录回满（当前气血 / 法力持久化） | realtime-buff-core、realtime-buff-effects、actor-attribute-calculator、death-revive、new-player-init-and-revive |
| 2.8 | 宝宝系统；robot pet-smoke | pet-system-core |
| 2.9 | 通用资产通道（跨服务发放 / 扣除，幂等账本）、补缴债务（加币先抵扣） | asset-channel、asset-op-channel、asset-op-ledger-read、currency-debt-clawback |

## 阶段 3：登录与网关补全

| 批次 | 内容 | 盘点 id | 状态 |
|---|---|---|---|
| 3.1 | access / refresh token、HTTP `/api/login`、`/api/refresh-token`、设备数上限 | login-access-refresh-token、http-login、gateway-http-login、gateway-refresh-token、login-device-limit；robot e2e-http |
| 3.2 | 生产口令认证（Argon2id）与第三方认证 | login-production-password、login-third-party-auth、satoken-auth-service |
| 3.3 | 短线重连：30s 断线租约、回原节点复用实体、租约到期收口 | short-reconnect-lease、reconnect-resume、lease-expired-zombie-close |
| 3.4 | 登录排队、开服限流、区服目录 / 健康探测 / 运维接口 / 公告 / 白名单 | login-queue、login-queue-dispatcher、gateway-rate-limit、gateway-zone-*、admin-api-auth、gateway-announcement |
| 3.5 | GM 签名停机、gate 排空与滚动替换 | gm-graceful-shutdown、gm-graceful-shutdown-rpc、gate-drain、gate-drain-ops |

## 阶段 4：社交服务（每个服务一个 Spring Boot 进程模块）

| 批次 | 内容 | 盘点 id | 状态 |
|---|---|---|---|
| 4.1 | 好友（申请 / 同意 / 删除 / 列表 / 黑名单 / 推荐 / 在线目录 / 推送）；robot friend-smoke | friend-* |
| 4.2 | 聊天（世界 / 私聊、历史、限速）；robot chat-smoke | chat-send、chat-history |
| 4.3 | 组队（名册 / 申请邀请 / 推送 / 场景投影与跟随）；robot team-smoke | team-roster、team-apply-invite、team-notify、team-scene-follow |
| 4.4 | 帮会核心（建 / 查 / 退 / 解散 / 公告 / 任免 / 踢人 / 申请审批 / 推送 220 / 排行）；robot guild-smoke | guild-* 核心段 |
| 4.5 | 帮会经济（资产指令账本与投递、捐献、升级、商店）；robot guild-economy | guild-asset-*、guild-donate、guild-upgrade、guild-shop |
| 4.6 | 帮会活动（公共底座、列表、灯会、团圆、同道历练 239–243） | guild-activity-*、guild-lantern、guild-reunion、guild-trial |
| 4.7 | 聚宝斋只读面（浏览 / 详情 / 收藏 / 货架）；robot trade-smoke | trade-browse-listings、trade-listing-detail、trade-favorite、trade-my-shelf |

## 阶段 5：场景拓扑

| 批次 | 内容 | 盘点 id | 状态 |
|---|---|---|---|
| 5.1 | 场景实例登记、主世界多频道与自动扩缩容 | scene-instance-registry、world-channels-from-tables、sm-world-channel-* |
| 5.2 | 跨节点换图 + 归属交接 | scene-switch-cross-node、ownership-handoff、sm-cross-node-scene-switch、sm-player-location |
| 5.3 | 副本（Dungeon 表）与镜像场景、空闲回收 | dungeon-instance、mirror-scene、sm-mirror-instance、sm-instance-lifecycle |
| 5.4 | 跨 zone 传送（226）与重定向（124）、归属区路由；robot travel-smoke | zone-travel、cross-zone-redirect、sm-cross-zone-redirect、home-zone-mapping、sm-home-zone-routing |
| 5.5 | 场景排空 / 节点疏散、死节点判定与接管 | scene-drain-relocate、sm-dead-node-recovery、scene-node-loss-handling |

## 阶段 6：匹配与回合制战斗

| 批次 | 内容 | 盘点 id | 状态 |
|---|---|---|---|
| 6.1 | 回合制战斗引擎（纯库：回合、技能、buff、道具、掉落） | turn-battle-engine-core、turn-battle-skills-buffs、turn-battle-items-drops-rewards、battle-table-fingerprint |
| 6.2 | battle 节点：房间生命周期、客户端直连、票据、推送、准入 | battle-room-lifecycle、battle-direct-connect-edge、battle-ticket-assignment、battle-client-actions-push、battle-node-admission-ops |
| 6.3 | scene 侧战斗冻结与结算应用、结算 outbox | scene-battle-freeze、scene-battle-settlement-apply、battle-settlement-outbox、in-battle-gates、pet-battle-integration |
| 6.4 | 匹配：排队、凑单、开局、评分、切磋、帮会活动开战、整队开战；robot battle-smoke | match-*、team-match |
| 6.5 | 观战；跨区 1V1 | battle-spectate、match-spectate；robot battle-smoke-cross-zone |

## 阶段 7：运维、数据与工具

| 批次 | 内容 | 盘点 id | 状态 |
|---|---|---|---|
| 7.1 | 本地编排（docker compose）、镜像构建、CI（构建 + 单测 + 契约 `--check`） | deploy-local-compose、deploy-container-images、ci-workflows、container-image-build |
| 7.2 | 流水 / 快照落库、GM 快照差异、回档、批量回收 | txlog-ingest、player-snapshot-ingest、gm-snapshot-diff、gm-rollback、gm-batch-recall |
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
