# 双版本对账本（PARITY）

本仓库是 [mmorpg（C++ 节点 + Go 微服务）](https://github.com/luyuan-cpp/xuanming-server-mmo) 的 Java 版。
两个版本并行演进，**任何功能两边都要做**（mmorpg `AGENTS.md` §12）。本文件是唯一对账处：

- 「版本基线」表：Java 版本号 ↔ 已对齐到的 mmorpg commit。
- 「功能对齐」表：逐功能记录两边状态。任一边完成一个功能都在这里登记一行（或更新已有行）。
- 只追加、只更新状态，不删旧行。

## 版本基线

| Java 版本 | 对齐的 mmorpg commit | 日期 | 说明 |
|---|---|---|---|
| 0.1.0-SNAPSHOT | `766cb037c` | 2026-09-29 | 移植起点：仓库骨架 + 登录进场景竖切（进行中） |

## 功能对齐

状态取值：`已对齐` / `Java 待做` / `mmorpg 待做` / `Java 进行中` / `不适用（写原因）`。

| 功能 | mmorpg 位置 | mmorpg commit | Java 模块 | Java 版本 | 状态 | 备注 |
|---|---|---|---|---|---|---|
| 客户端协议（帧格式 + 客户端可见 proto） | `cpp/nodes/gate`、`proto/` | `766cb037c` | — | — | Java 进行中 | 两版共享的唯一契约 |
| 登录 → 进场景竖切 | `go/login`、`cpp/nodes/gate`、`go/scene_manager`、`cpp/nodes/scene` | `766cb037c` | — | — | Java 进行中 | 首批里程碑 |
