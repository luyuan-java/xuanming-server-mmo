# 场景导航最小接入方案（2026-11-06，只读评估）

本轮没有接入生产导航、复制网格、生成资源或改地图合同。评估结论：现有 `game-navigation.ServerNavMeshQuery` 足以承接场景 4、17–21 的进场、移动上报和帧外推；三图及其复用场景继续使用 `CurrentMapNavigation`，不必重烘焙或替换 mask。具体落位仍须运行真实产物探针和生产路径回归后才能验收。

## 已核对的来源

- C++ 固定 `993b6da9d4ea51d5e4a27cc6ec17004af0fb97c1` 的 `cpp/libs/services/scene/spatial/system/{navigation,nav_query,recast,movement}.cpp`、移动 handler、`tools/navmesh_baker/sync_current_maps.py` 与 `data/scene_nav_bin`，全部只读。
- Java 当前 `config-data/tables/basescene.pb` 按现有 schema 只读解码，21 行绑定见下表；不是从旧设计稿推测。
- `config-data/navmesh/current_maps` 与移动客户端契约：150×150、2 米格、MSB-first、源坐标 Unity Y-up；服务端坐标为 `(unity.z, unity.x, unity.y)`。原三图 300 米布局和保守 mask 的约束不变。
- 客户端 `CurrentMapCatalog` 只声明 main / island / village；旧网格不能冒充当前三图 mask 的替代品。
- 993b6da9 与旧导航固定源 92952c84 的三个待接网格 git blob 相同，另已直接读取固定 git 对象重新计算大小和 SHA256。

| BaseScene | 现有完整源路径尾部 | 当前出生点（服务器） | 接入策略 |
|---|---|---|---|
| 1、5–16 | `data/scene_nav_bin/main_scene.bin` | (133,199,0) | 原 main mask |
| 2 | `data/scene_nav_bin/penglai_scene.bin` | (173,195,0) | 原 island mask |
| 3 | `data/scene_nav_bin/donghai_scene.bin` | (147,201,0) | 原 village mask |
| 4 | `data/scene_nav_bin/lanxian_scene.bin` | (180,200,0) | mesh |
| 17–19 | `data/scene_nav_bin/dungeon_scene.bin` | (180,200,0) | mesh |
| 20–21 | `data/scene_nav_bin/mirror_scene.bin` | (180,200,0) | mesh |

| 文件 | 字节 | SHA256 |
|---|---:|---|
| lanxian_scene.bin | 102088 | `191afe6382d9ec7b3ad94eca572cc05f865e6d93a9347600ff01cee3363ef2da` |
| dungeon_scene.bin | 175024 | `07f11490a9de3fd25ed5fc2b251a1d64b0c5ccf57ab4d5e0eec83880a9a239a4` |
| mirror_scene.bin | 175024 | `07f11490a9de3fd25ed5fc2b251a1d64b0c5ccf57ab4d5e0eec83880a9a239a4` |

`tianyong_scene.bin` 没被这 21 行引用，不应顺手绑定。dungeon 与 mirror 几何相同，仍保留两条源文件身份。

## 资源与装配范围

建议新增独立 `config-data/navmesh/mset`，保存三个源文件及带固定 commit、源路径、字节数、SHA256 的清单。由一个 JDK 工具用 `git show <固定提交>:<路径>` 的原始二进制流导出并支持 `--check`；不通过 shell 文本重定向复制二进制。无需 C++ 烘焙、源表导出或修改 C++ 仓库。现有 scratch 导出 `tools/review-port-next/fixed-nav-92952c84` 可作只读比对，不能作为没有来源清单的生产依赖。

`config-data/tables/**`、`config-data/navmesh/current_maps/**`、`contract/SOURCE.properties` 均不写。现 `ContractSync` 只负责 current_maps；如以后统一资源同步，应作为单独设计扩展，不能让本次接入误改已有地图来源基线。

最小文件范围：

1. `game-scene/pom.xml` 引入已有内部 `game-navigation` 模块；没有新第三方依赖。
2. 新 `MeshSceneNavigation` 封装 `ServerNavMeshQuery.Position` 与场景 `Vec3`，转译明确的落位/移动状态；文件加载不放进每帧查询。
3. 新 `MeshNavigationAssets` 读取有界文件、核对清单摘要并交给 `MsetReader`；相同源路径只建一个不可变查询对象，各行出生点分别探针。
4. `ConfigSceneTables` 的导航 map 改为 `SceneNavigation`，准确匹配完整合法源路径，按表行绑定策略并保存已验证的投影出生点。当前仅看 basename 的规则不能直接扩展成任意磁盘路径读取。
5. `SceneNodeConfiguration` 从同一 `game.table-dir` 根装配 current_maps 与 mset；任何已声明资源缺失、损坏、未知非空路径或出生点无可验证落点都拒绝发布这个配置快照。仅真正未声明导航的地图返回 NONE。
6. `SceneNavigation` / `SceneWorld` 给上报恢复与外推保留不同入口，下节说明；无需重写已有移动广播、视野或 MoveGuard。

Docker 已 `COPY config-data/navmesh/`，`.dockerignore` 已放行该目录；离线 `ReleaseSource.included` 也包含整个目录。新增 mset 子目录不需要另建部署传送链，仍须在打包回归中证明资源实际在镜像/原件里。

## 运行边界

- 进场：同图合法存档使用 `place` 的投影位置。换图、无效存档使用启动时已验证并投影的出生点，不能只验证后又返回未经投影的原坐标。现 `resolveEnterPosition` 的裸出生点返回需要照顾这一点。
- 上报：先走原 MoveGuard，再查询整段；非 CLEAR 清速度并更新最终锚点，不退移动额度。`INVALID_START` 应有明确恢复：固定 C++ 上报先尝试吸附上报点，失败再出生点；Java 可沿用这一恢复，但必须使用 MoveGuard 已裁决的点且记述其语义。
- 外推：`INVALID_START` 保留原位置、清速，不能因为每帧失去起点就跳出生点。可新增默认 `traceReported`，现 mask 默认继续委托 trace，mesh 上报单独恢复；普通 trace 用于外推。
- `LIMIT_REACHED` 是预算不足，不能解释成确定撞墙或确定没有网格。只接受已证实的起点；没有证明的位置则保留原坐标并停速，不降级 NONE。
- mesh 保留实际地面高度，速度仍按现三维信任上限处理；三图 mask 继续将高度与垂直速度归零。上报纯高度纠偏是否推 137 不在这次默改：既有规则只有水平偏差严格大于 0.5 米才发 137。
- 已有外推撞墙当帧 133、后续不重复停步、MoveGuard 锚点更新与不退额度均继续使用现主链并补 mesh 用例。
- 管理瞬移当前明确只验证世界范围，操作者选择落点；不在本批悄悄改变。下一条上报恢复与非法起点外推须覆盖此情形。
- `findPath`、机器人 AI、off-mesh 与部分路径不属于本次最小场景闭环；现库不返回 C++ 的部分路径，不能宣传全量 Detour 行为等价。

## 必须完成的运行验证

固定三文件摘要和六个 BaseScene 出生探针；共享文件复用、完整路径拒绝、缺文件/错摘要/非法出生点拒启；三图旧断言保持；mesh 进场投影、旧档恢复、换图、整段撞墙、纯高度输入、预算耗尽、管理瞬移后恢复、外推当帧停步广播、137 和移动额度。当前没有执行这些接入测试，也没有改动生产。
