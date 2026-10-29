# 场景疏散运维面（Java 5.5 §5.9，2026-10-26）

`SceneEvacuationAdminController` 复用 game-data 的 `/admin/*` 过滤器、共享令牌与必填操作人；写操作记录
目标 zone / node / instance、固定结局、TTL、force 与操作人。请求指标只使用固定 `scenes` 标签。
Redis 懒加载；存储、坏标记或目录身份异常在请求时返回 503，不阻止 game-data 启动。

| 接口 | 结果 |
|---|---|
| `POST /admin/scenes/evacuate` | JSON `zone_id,node_id,ttl_sec?,force?`。TTL 缺省 3600 秒，范围 `[game.data.scene-evacuate.min-ttl（缺省 30m）,86400]`。参数错 400、目录无目标 404、最后节点或同实例坏标记 409。成功返回 `marked`、当前实例与起点。 |
| `GET /admin/scenes/{zone}` | 目录叠加标记，包含 `node_id,instance_id,lease_epoch,evacuating,evacuating_since,stale_mark,player_count,channels,evacuated`。频道列出配置、人数、kind、ACTIVE / DRAINING；uint64 场景号和租约代次使用无符号十进制字符串。 |
| `DELETE /admin/scenes/evacuate/{zone}/{node}` | 删除标记，存在与否均为 204。读写失败仍为 503。 |

`evacuated` 仅在标记属于当前实例、节点自报 evacuating、总在线人数为零时为真。标记旧实例不会误判已排完。
后备节点必须来自同区目录、实例合法、链路地址可用、没有自报疏散；空节点仍能承接新频道，不因当前场景列表为空而排除。

`SceneEvacuationMarks` 位于 game-discovery，所有键由注入的 `RedisKeys.sceneEvacuating(zone,node)` 生成，
值为 `Redis TIME 秒:实例`，带 TTL。单节点 Redis Lua 同时检查其他节点的实例标记并写入目标标记，
防止多个管理端副本同时把同一目录快照的所有节点标满；force 才略过最后节点保护。已经属于同实例的有效标记
不刷新起点或 TTL；无 TTL 或起点损坏返回 `EXISTING_INVALID`，要求先撤销再打。节点号换实例时旧标记可被覆盖。

Lua 在同一拍读取目标与后备节点的 `NodeIdLease` 原件：目标租约已失效或换实例回 409 `target_changed`，
后备仅在租约仍等于目录所读实例时计入。它阻断两份新旧目录快照交错把新实例标记当 stale 而标满全区的竞态。
同脚本先检查 `RedisKeys.worldRetirement(zone)`，存在永久退休原件即回 409 `zone_retired`；force 不绕过
目标实例围栏与永久退休闸。脚本不解析、续期或删除退休原件。这些键跨槽，仅声明单 Redis 部署能力，不能称 Redis Cluster 可用。

scene 使用 `read(zone,node)` 返回的 `Mark.appliesTo(instance)`；不存在返回 null，损坏或 I/O 异常上抛，
异常不等于撤销。它必须在 I/O 线程调用，把结果投回逻辑线程；标记消失仅取消 ADMIN 原因的疏散。
没有新增 scene HTTP 管理端点，也没有让 SM 直接消费此标记。

源码新增了真 Redis 并发打标、TTL 不续期、旧实例与强制覆盖用例，以及离线解析、管理端参数 / 目录 / 503 / 审计用例。
现有 Tomcat 鉴权用例追加了编码路径、POST、DELETE 场景疏散入口，保留原断言；命名空间金样新增独立键样例。
本代理未运行任何构建或测试；真 Redis 用例需显式 `-Dgame.it.redis=...`，不能把默认跳过记作已验收。

该运维主动疏散入口是 Java 规格的明确扩展；固定 C++ `92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5`
没有同名场景疏散标记管理面，不能据此宣称原版也提供此接口。
