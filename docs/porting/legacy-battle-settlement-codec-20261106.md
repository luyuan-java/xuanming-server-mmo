# 旧结算信封兼容修复（2026-11-06）

本次真实 clean 构建暴露 `game-scene` / `game-battle` 仍引用已删除的 `com.game.proto.BattleSettlementEvent`。这不是 Java 包名移动：同步 proto 与干净生成目录都不存在该类型，旧 target 生成类掩盖了引用未迁移的问题。

删除前的 Java `b2036888^:game-proto/src/main/proto/proto/common/event/battle_event.proto` 与 C++ `65c69f4a9^:proto/common/event/battle_event.proto` 定义均只有 `BattleSettlementData settlement = 1`。固定 C++ 993b6da9 已删除这个事件，终局由 match SQL 与 player_inbox 承接；本修复不恢复共享 proto、Kafka 事件或旧生产者。

Java 已有 Redis 兼容通道仍有读写和回归，不能直接把信封替换成裸 `BattleSettlementData` 字节。新增 `game-api` 的 `LegacyBattleSettlementCodec` 使用项目已有 protobuf `DynamicMessage` 和只在 Java 内构造的历史描述符，封装字段 1；未修改任何 `.proto`、同步产物或依赖。

`SettlementOutbox` 编码、`SceneBattleProvider` 解码、`PlayerBattleService.parseRecord` 恢复以及所有相关测试夹具使用这一边界。原 Redis 保存/重读/原字节一致性检查与 Dubbo 传递仍操作完整原字节，不经过解码再编码。空信封仍在 parseRecord 返回 null，在 RPC 路径得到默认空结算后交给既有校验；坏字节仍抛 `InvalidProtocolBufferException` 进入原丢弃路径。

嵌套结算的未知字段、uint64 位模式、重复 message 字段合并交给 protobuf 标准实现。信封外层未知字段在提取内部结算时不转交业务，与原生成类 `getSettlement()` 一致。编码只用于新建结算，不用于重写历史原件。

新增 5 个测试方法用固定十六进制字节核对旧格式、空 message 存在性、重复字段合并、未知字段及高位 uint64、坏包拒绝。源码和静态差异检查已完成；编译与测试由根任务统一执行，此文不声称运行通过。
