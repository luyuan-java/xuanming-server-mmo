# mmorpg 功能盘点（参考快照）

2026-10-02 按 mmorpg `26ceb70ca` 逐子系统盘点，对照当时的 Java 版标注状态（done / partial / missing / not_applicable）。
用途：移植每一批之前先读对应条目（客户端可见行为、tip 码、配表、依赖、基线里的坑）。

- 这是**快照**，不随代码更新；以 mmorpg 源码与本仓库代码为准，发现不符以代码为准并在 PARITY.md 记录。
- 批次顺序见 [../roadmap.md](../roadmap.md)，两版对齐状态见根目录 PARITY.md。

| 文件 | 范围 |
|---|---|
| [gate.md](gate.md) | C++ gate 节点 |
| [scene-core.md](scene-core.md) | C++ scene 节点核心与玩家生命周期 |
| [combat.md](combat.md) | 实时技能 / buff / 战斗状态、回合制战斗与 battle 节点、宝宝 |
| [modules.md](modules.md) | 背包、货币、任务、奖励、条件、流水、快照、号段 |
| [data.md](data.md) | Go data_service / db / 迁移、合服、一致性巡检 |
| [login.md](login.md) | Go login、client_rpc_router、player_locator、go/shared |
| [scene-manager-match.md](scene-manager-match.md) | Go scene_manager、match、team、battle 服务 |
| [guild.md](guild.md) | Go guild |
| [social.md](social.md) | Go friend、chat、trade |
| [java-infra.md](java-infra.md) | mmorpg 的 Java 网关 / 配置节点 / 认证、Kafka、Agones、部署、CI |
| [tools.md](tools.md) | 全部工具 |
| [contract-robot.md](contract-robot.md) | 客户端服务覆盖矩阵与 robot 验收场景 |
