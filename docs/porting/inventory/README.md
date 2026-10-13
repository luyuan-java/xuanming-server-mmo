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
| [delta-20261010.md](delta-20261010.md) | **2026-10-10 增量**：mmorpg `e8e6ed1c2`（旧基线 `26ceb70ca` 的代码等价点）→ `73b852f3` 的行为 / 运维 / 文档增量，按领域分节，每条带建议批次（批次 0.3，2026-10-12 盘点） |

## 2026-10-12：基线变化

- mmorpg 的历史整理过，旧基线 `26ceb70ca` 已不在其中；代码等价点是 `e8e6ed1c2`（2026-09-30），新基线 `73b852f3`（2026-10-10）。
  上面各领域文件仍是 `26ceb70ca` 的快照，**没有改写**；增量只记在 [delta-20261010.md](delta-20261010.md)，两者冲突时以增量文件与代码为准。
- mmorpg 完整克隆在 `D:\luyuan\xuanming-server-mmo-cpp`；各文件里写的 `D:\work\mmorpg`、`D:\work\xuanming-server-mmo` 是盘点时的路径，已不存在。

### mmorpg 目录整理后的路径对照（2026-10-07，`a8e9ffb1f` / `a8944d80a` / `dcbb83419`）

本目录与 `docs/porting/*-spec.md` 里引用的 mmorpg 文档路径在 `73b852f3` 下按此改读（代码路径与 ContractSync 读的 `proto/`、`data/`、`generated/` 等目录不受影响；
出处 mmorpg `docs/handoff/repo-layout-20261007.md` §1.1 / §1.2）：

| 原路径 | 现路径 |
|---|---|
| 根目录 `PROGRESS.md` | `docs/PROGRESS.md` |
| 根目录 `AUDIT.md` | `docs/archive/cross-zone-merge-rollback-audit-2026-05.md` |
| `run/README.md` | `docs/ops/run-directory.md` |
| `docs/design/stress-*`、`docs/design/prev-summary*.txt` | `docs/stress/` |
| `docs/design/handoff-*`、`docs/design/friend-handoff-*`、会话小结 | `docs/handoff/` |
| `docs/design/slg-*`、`docs/design/moba-*` | `docs/notes/slg-moba/` |
| `docs/design/centre_decommission_*` | `docs/archive/` |
| `docs/architecture*.md` | `docs/notes/event-priority*.md` |
| `bin/` 下的 k8s 机器人压测清单、一次性脚本（如 `debug_gate.sh`）、旧 Lua 脚本 | `tools/archived/` |
| `output/teaching*` | `docs/teaching/` |
| `启动服务器.cmd` / `启动服务器并打开游戏.cmd` | `start-server.cmd` / `start-game.cmd` |

`lib/`、`run/`、`output/` 不再进版本库；`docs/README.md` 是生成的文档索引（`tools/scripts/gen_docs_index.py`）。
