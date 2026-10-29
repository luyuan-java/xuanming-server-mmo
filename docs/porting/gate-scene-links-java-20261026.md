# gate / scene 链路生命周期（2026-10-26）

工作树 `wt-port-next`，实现对应 `scene-drain-spec.md` §5.7–5.8。C++ 证据固定为
`92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5`，通过 `git show` 读取，未依赖活动工作区。

## 固定源端与 Java 边界

- 固定 C++ `cpp/nodes/gate/handler/event/scene_route_helper.h` 的 `CompareSceneNode` 比较节点实体身份，
  同一业务节点号重启不是同一进程；`client_session_handler.cpp` 的 `OnNodeRemoveEventHandler` 清除旧会话指向。
  Java 用显式不可变实例 ID、Redis 单调租约代次、链路 generation 三层身份表达，保留 Java 独立内部协议。
- Java 采用规格已定案的断开会话行为：普通 scene 链路断开先推 `23 {1003}`，再关客户端；不留无场景绑定的哑连接。
  交出进场尚未确认的 `transferEntering` 维持 `scene-handoff-spec` F11/K34：仅关闭、不自行放弃已发出的 E+1。
- 应用层 Ping/Pong、协商读空闲和退场宽限属于 Java 5.5 设计，不声称 C++ 有相同内部帧结构。
- 固定源端还具有 CPP-2 `AttemptPendingSceneEntry`：20 秒补发预算、3 秒节点发现预算、退避与玩家围栏。
  本次继续移植已据此补上 Java 会话级限时补发；下面单列它与“已发送或写结果未知”的界线。

## 实现

1. 自有 `node_link.proto` 只追加 Ping/Pong、Hello 心跳间隔、Ack 当前 scene 租约代次与回显间隔。
   `LinkIdentity` 构造注入 `LongSupplier`，原号复占后握手取当前代次；旧三参数构造器保持零值兼容。
2. gate 使用 Netty EventLoop 固定周期 Ping；业务写不会延后探活。双方协商后才装 `IdleStateHandler`，
   任意解析后的入站帧都重置读空闲；旧 Ack 不回显则不启用。scene 直接在链路 I/O 线程回 Pong，
   不排逻辑线程、不增加业务背压计数。出站不可写时关闭，避免心跳不断堆积。
3. scene 接受的间隔范围为 1000–30000 毫秒，越界按旧版本零值并 WARN。
   业务背压暂停读取期间忽略读空闲；恢复读取时重装计时器，从完整预算开始，消除恢复到下一次 readComplete 之间的竞态。
4. 真连接解析目录后固定目标实例与代次，Ack 必须命中实例；两侧代次均非零时 Ack 不能落后。
   `SceneLinkManager` 保留每节点见过的最大无符号代次，迟到旧目录不能用于新建连接或触发换代。
5. `SupersessionSweep` 的周期触发器只提交一笔目录读取到现有 `gate-link-resolve` 执行器，不在 I/O 线程读 Redis。
   目录缺席或失败不踢当前连接；同实例更高代次仅更新记录；不同实例且更高代次取代。
   尚无非零围栏的旧版本需要连续两次同一新实例证据，缺席、失败或候选变化打断连续证据。
6. 被取代连接先发布到 `(node, generation)` 旧表，再禁止新绑定，避免并发摘当前表造成旧绑定短暂无路可发。
   新 `PlayerEnter` 使用当前代次；已有 `ClientForward` / `PlayerLeave` 严格按会话绑定代次发送，绝不自动改绑。
   旧连接仍允许下行与 `PlayerTransfer`，会话原四元组校验保持不变。
7. 旧连接默认 30 秒宽限。周期触发器独立做纯内存到期清理，目录 I/O 阻塞也不会无限续命；
   旧绑定主动发送时再次检查截止时刻。到期只关闭仍绑定旧代次的会话，记 `scene_link_superseded`。
   清理精度受扫描周期影响，默认额外不超过 5 秒；不是实时调度保证。
8. 生产 GateNode 已接扫描、协商配置和停止撤销；SceneNode 由主代理接入当前租约 supplier。
   配置校验 `idle >= 3 * ping`、`superseded-grace >= 20s`；部署应让宽限覆盖 scene 疏散与最终存盘预算。
9. `GateNodeInfo.lease_epoch = 9` 追加当前节点号租约代次，生产每次发布现取 `lease.leaseEpoch()`。
   NodeDirectory v2 的永久 epoch / instance 发布围栏由另一分工实现；这里的生产工厂与 publish 原件测试覆盖字段确实送入目录。

## CPP-2：本地未发送的进场补发

`PendingSceneEntry` 是每会话一个 Java 值对象，持有不可变的原 `PlayerEnter`、原目标节点、截止时间与有限尝试计数；
调度复用该客户端 EventLoop，不建全局重试队列。20 秒从收到原进场指令起算，最多 16 次；250ms 起步、翻倍到 2s。
目录缺席把截止收紧到原起点 + 3s，后续连接故障不拉长它。正常首登与跨节点交出均使用这一机制。

- 只对已由链路摘出、确认从未调用 `Channel.write` 的临时寻址/连接故障及握手未完成超时重试。
  非法路由、鉴权拒绝、队列满、gate 租约无效保持既有拒绝流程。
- `SceneLink.cancelQueuedEnter` 与 flush 共用同一对象锁。flush **先摘出队列再调用 write**；取消成功才可带原 E abandon。
  取消输掉竞态即为“已发或未知”，不重试、不 abandon，不额外引入等待进场结果的超时。
- 首次 write 通知 `onEnterDispatched`，无论 write future 以后成功还是失败都撤销补发资格。
  连接随后断开按既有 F11 / 普通断链收尾，归属由目标 scene 或 SQL 租约处理。
- 重试只重解析原 target node；从不另选 sceneId、另夺 ownerEpoch 或补造 account / ownerRevision / loginGeneration。
  临时连接上的 generation 可以变化，旧尝试重复回调不能释放新尝试仍使用的同一 E。
- LeaveGame、断线、玩家身份替换时撤销定时器；若原件仍未 write，则取消并按原玩家 / E 放弃。
  预算用尽时同样先取得未发送证明，再推 `23 {3023}` 关闭。Java 沿用现有 tip 后关闭约定，不另发 C++ 的 34 踢出帧。
- `game.gate.scene.entry.retries{result}` 只有 deferred/recovered/exhausted/cancelled 四个有限值；
  普通进场补发耗尽断开原因为 `scene_entry_failed`，交出进场仍为 `transfer_failed`。

## 验证证据

本分工未启动 Maven、Java 或任何编译器；下列是新增/扩展的测试源码，不能据此声称测试通过。

- `LinkLifecycleTest`：握手实例/无符号代次、旧版零值、异常回显、固定心跳、读空闲、同实例原号复占、
  新旧代次并存分流、旧链路改绑帧、到期仅清旧连接、旧目录回退防护、双扫描兼容、目录失败、单飞扫描、
  I/O 卡住时宽限仍清理、关闭撤销任务。
- `NodeLinkHandlerTest`：Ping 不入逻辑队列且不占背压、原样回显 uint64 序号、边界协商、背压时忽略空闲、
  恢复后完整预算、租约 supplier 动态值。原鉴权、背压及关闭断言均保留。
- `SceneLinkServiceTest` / `LinkHellosTest`：真实生产握手路径回显字段、原 HMAC 契约及旧构造器兼容。
- `ClientDispatcherTest`：普通断链先推 1003、独立换代断开原因、不影响未绑定和已改绑会话、F11 原语义。
- `GatePropertiesTest`：真实 Spring Binder 默认值、有效边界与拒绝配置。原测试未删断言。
- `GateNodeDirectoryEntryTest`：生产 publish 两次真实捕获原件，租约代次 101 → 102，启动时刻/实例/人数保留。
- `SceneEntryRetryTest`：真实 Dispatcher + SceneLinkManager + EventLoop 组合，覆盖缺席恢复、握手前断链恢复、
  原身份逐字段不变、3s/20s/16 次预算、重复/旧尝试回调、断线/LeaveGame/换角色取消、已 write 结果未知不重发、
  跨节点交出的 E 与 loginGeneration 区分、截止事件先于未发送回调/派发通知的两个竞态。
  `LinkLifecycleTest` 另覆盖取消与首次 write 的两个顺序，以及失败 write future。

新增指标无玩家或节点高基数标签：`game.gate.link.idle.timeouts`、`game.gate.scene.links.superseded`、
`game.scene.link.idle.closes`、`game.scene.link.idle.ignored`；已有帧计数自然覆盖 Ping/Pong。

待主线程统一构建、运行测试并记录结果；多进程网络分区与实际 Redis 故障注入仍需集成验收。
