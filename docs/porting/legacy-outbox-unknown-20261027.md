# 旧 Redis 发件箱写入未知恢复（2026-10-27，已集成待验证）

本批处理战斗库存 `inventory/full-migration-battle-20261026.md` §4 的旧 Redis 发件箱 store UNKNOWN。Java 基线为 `03a0708d35b1e938d59f51c4e868b45f88ed9da5` 的工作树文件快照，实际文件摘要以候选 manifest 为准；固定 C++ / Go 原件为 `92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5`。本文不宣称全量迁移、构建或测试完成。

第二版候选已独立复审，并按 manifest `b252beeb…` 的完整摘要核验接入 13 个路径。初版遗漏了“较新 store 已返回、旧 store 仍未执行”的在途约束，现保留全部未完成回调计数，最新身份只授予登记权；两个新增反例覆盖登记过龄/销账后旧写入才真正执行的情况。共新增 15 个确定性测试与 2 个真实 Redis gated 用例，均未运行；仓外候选与集成回执保留。下一次构建绑定 v5 的 2820 项输入，不能引用旧测试结果。

## 1. 源码复核与本批语义

固定 C++ `cpp/nodes/battle/logic/battle_room_manager.cpp` 的 `DispatchSettlementDurably`（2633 起）与 `DispatchActivityResultDurably`（3047 起）均在写入回包未知时登记原 ID / 原字节；仅在连接未就绪或明确命令未发出时降为一次投递。Java 端口没有“确定未发出”的结果类型，异常、null、空 future 或非法成功码只能保守归入 UNKNOWN。

C++ 的探测缺失也可能意味着从未写入；它不能作为严格销账证据。Java 候选据已有 Redis 结构实现更保守的恢复，不照抄 C++ 容器、回调或 NIL 判定：

| store 结局 / 后续证据 | Java 行为 |
| --- | --- |
| 结算返回字段数 ≥ 1；活动返回 1 | 保留既有成功登记、首投与探测流程。 |
| 结算返回已有脚本定义的 -1 | 保留既有墓碑命中行为，不写、不登记、不投递。本批没有新增墓碑。 |
| 异常、null、空 future、其他非法码 | 计 `not_durable`，登记原 ID / 不可变原字节，保留一次尽力首投；活动 `dispatchCommitted` 不重复首发。 |
| UNKNOWN + EXISTS / HEXISTS = false | 保留 UNKNOWN，不记 ACK，不重建记录，不重投。 |
| UNKNOWN + 存在 | 结算用已有 `readSettlement`，活动用新增只读 `readActivityResult`；两者均走现有主库脚本入口和 ByteArrayCodec。只有完整字节相同才能继续本轮重投。 |
| 原件读取失败、缺失、不同字节 | 保留 UNKNOWN，计 `probe_error`，不消费重投次数。每轮重新核对，第一次读到原件不会永久解除 UNKNOWN。 |
| UNKNOWN + 结算玩家离线 | 本轮计 `skip_no_target`，不调用 `ACK_IF_SUPERSEDED`。新局锁不能证明旧局已结算。 |
| 超过年龄或次数上限 | 停止本地重投并报警，不删原件、不记 ACK，也不声称记录仍在 Redis。探测 future 悬挂也不能绕过年龄检查。 |

原有周期 10 秒、结算 12 次、活动 30 次、登记过龄 10 分钟保持。原件比对失败只消耗年龄，不消耗重投次数；年龄检查在下一次周期生效。没有新增阻塞 I/O、数据库、线程或依赖。

## 2. 同 ID 重入与迟到回包

每个发件箱在自己的调度线程内保存同键全部未完成 store 的共同原 bytes、在途计数，以及最新调用的身份。同进程 pending 或登记期间，同 ID 不同字节被拒绝，计固定枚举 `payload_conflict`，不再写 Redis / 首发。相同字节重入保持兼容；只有最新 store 调用的回包才能创建登记。最新回包完成、登记 ACK / 耗尽 / 过龄都不能清除其它未完成写调用的约束；直到全部 store future 的结局归还才移除 pending 约束。旧 store 回包不能重复首投、重置新条目的年龄或次数，旧原件读取也必须核对登记对象身份。

第二轮独立复核修正了初稿仅保留最新调用的问题：A1 写命令悬挂，A2 同字节先完成且登记随后摘除时，B3 异字节仍须拒绝，不能先让 B3 写成再被迟到的 A1 SET 覆盖。新增回归实际延迟执行旧模型写入，并核对所有 store 完成后才允许新字节。活动通道没有墓碑，所以迟到旧 SET 仍可能在消费删除后复现旧原件；本次不把它误称为已解决的永久销账问题。

这些只是进程内围栏。Redis 现有 STORE 仍是 HSET / SET，未改为跨写者 CAS：其他进程、登记过期后的重入、客户端内部迟到重放，仍可能改写同 ID 原件。逐轮读取能挡住本轮已观察到的异值，不能原子地排除“读成功后到投递前”又被另一写者覆盖。不能把 callback 身份当作 Redis 原件身份的永久证明。

## 3. 测试源码与验证状态

新增 `OutboxUnknownStoreTest` 15 个确定性用例，覆盖两种 store 已执行后丢回包、异字节/读失败/缺失、UNKNOWN 经成功读取后再次缺失、离线不取代销账、同 ID 异字节拒写、旧 store/read 回包、登记已摘除但旧写尚未执行、读悬挂过龄、非法码、broker 已提交和次数上限、未实现原件读取的旧端口。

`OutboxRedisIntegrationTest` 新增 2 个真实 Redis gated 用例，先等实际 store 成功，再人为丢弃成功回复，以现有生产适配器读取真实原件并重投；随后验证异字节拒投、记录删除不能 ACK。它们还检查实际存储字节及 store 调用次数。默认不开 Redis 开关时不会执行这两条。

现有 Settlement / Activity / Redis 集成测试保留原断言，只改未知写入的预期登记数、定时器与探测序列；指标测试加入新增枚举，脚本清单和只读断言加入 `READ_ACTIVITY_RESULT`。没有删用例或降低断言数量。

当前候选仅完成静态审查。未编译、未执行单测、未连接 Redis / TiDB / Kafka，不能登记“测试通过”。统一验证至少应运行 `OutboxUnknownStoreTest`、`SettlementOutboxTest`、`ActivityResultOutboxTest`、`OutboxMetricsTest`、`BattleRedisConstantsTest`、既有假件测试与真实 Redis `OutboxRedisIntegrationTest`，随后按主控范围跑全量构建。

## 4. 明确不覆盖的边界

- SQL v1 的全球 COMPLETE、永久全局 / 个人来源、Inbox 水位与 APPLIED 查询没有改动，也没有降级为 Redis TTL。
- 兼容记录的 7 天 TTL、既有结算墓碑 10 分钟、活动无真实永久销账证明、进程重启后 UNKNOWN 内存记录丢失均未解决。无原件时不会擅自重建；只保留到本地上限后报警，不能保证补奖。
- 已确认写入的既有 `exists=false → ACK` 及 `ACK_IF_SUPERSEDED` 路径未重写；旧 `PlayerBattleService` 的锁证据和分步 `applyOnce` 属于库存 368 的独立批次，不能据本候选声明整条兼容资产链具备永久恢复语义。
- 保留旧 UNKNOWN 尽力首投，不承诺“首投前一定有耐久原件”。真正原子发布语义由 SQL v1 保证。明确未发送证明、跨写者原件不可变协议、跨重启恢复索引和永久回执需要另行设计与闭合，不能靠虚构墓碑补齐。
- 重试仍依赖现有 Redis 命令超时。登记后探测有本地年龄上限；尚未完成的首个 store 回调未新增取消机制。原有结算 drain 有界等候保留，不能将其当作所有存储命令已经回滚。

本候选只闭合“写入已执行但回包丢失，原件仍可精确读取时恢复有界重投”的最小链路。库存总表和 PARITY 应由主控在集成、运行验证后追加事实，保留上述剩余项。
