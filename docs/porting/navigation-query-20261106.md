# 地表最近点与射线查询（2026-11-06）

本段在 `game-navigation` 的既有不可变 MSET 模型上新增纯 Java 21 查询代码，未增加依赖、未修改 C++、同步地图/表/协议或场景移动逻辑。源码已写入独立 worktree；**未编译或运行测试**。不能据此登记 C++ 导航完整对齐。

## 固定事实与旧规格复核

对照根提交 `92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5` 和 `993b6da9d4ea51d5e4a27cc6ec17004af0fb97c1`。两者的 `cpp/libs/services/scene/spatial/system/{nav_query.cpp,nav_query.h,navigation.cpp,recast.cpp}` 和 UE 导航 gitlink 没有差异；子模块固定为 `aacf2f82af2e24c56474855fce057018928735bb`。所有参考源码均从固定 git 对象读取。

| 固定来源 | 本段采用或明确留后的事实 |
|---|---|
| 根 `nav_query.cpp:16-54`、`nav_query.h:11-17` | 参考封装使用导航 Y-up 米，服务器坐标换轴为 `(server.y,server.z,server.x)`；吸附候选范围 `(2,4,2)`。本段 API 直接使用模型 Y-up，不隐式换轴。 |
| 子模块 `Private/Detour/DetourNavMeshQuery.cpp:707-802` | ground 最近点先做水平投影/夹边，再从细节三角形取高度。不是斜坡面上三维正交投影的最短距离。 |
| 同文件 `:1074-1116` | 候选结果按三维距离比较，并明确要求 `abs(center.y-height) < extents.y`；128 候选截断属于原生实现限制。旧规格中“很远下方仍能吸附”的推断不作为本段合同。 |
| 同文件 `:3105-3332` | 地表 raycast 检查 X/Z 凸面与邻接，跳过 off-mesh；不做三维遮挡检测。固定 flags 默认 include=0xffff、exclude=0，至少一个 include 位通过且 exclude 优先。 |
| 子模块 `Private/Detour/DetourNavMesh.cpp:907-973,1027-1104` | 原生装载时重建跨 tile links，并把部分 portal 范围压缩到字节。本段依据模型的静态 portal 方向和真实几何重叠查询，不导入 serialized links。 |
| 根 `nav_query.cpp:86-198` | 起点吸附、终点再吸附、0.05 米回缩、服务器换轴、A* 与拉直路径是上层功能；本段尚未接入这些行为。 |

既有 `NavMesh`/`MsetReader` 已校验凸 ground、detail 覆盖、内部反向共享边和静态 portal 平面；足以支撑本段独立查询。读取层保留的 off-mesh 数据不会自动成为地表可走面。

## Java API 与数据组织

`NavMeshQuery` 持有不可变的地面几何视图、引用索引和 portal 索引；`NavMeshGeometry` 负责凸面裁剪、水平投影及三角形高度。没有原生内存布局访问、运行时链表、节点池、服务定位器或进程级查询工作区。`records`、标准集合与 `List.copyOf/Map.copyOf` 表达值及不可变关系，每次查询自己的预算和访问集合互不共享。

```java
NavMesh mesh = new MsetReader().read(path);
NavMeshQuery query = new NavMeshQuery(mesh);
var filter = NavMeshQuery.Filter.walkable();
var nearest = query.nearestPoint(point, new NavMesh.Vec3(2, 4, 2), filter);
if (nearest.status() == NavMeshQuery.NearestStatus.FOUND) {
    var start = nearest.surface().orElseThrow();
    var ray = query.raycast(start.polygonReference(), start.position(), target, filter);
    // 调用方必须检查状态；LIMIT_REACHED 和 INVALID_START 不能当作可达。
}
```

输入是导航坐标 Y-up 米，finite 且每轴绝对值不超过 1e9，与固定读取器默认范围一致。使用更大自定义读取范围的网格不能直接套用本查询器。坐标换轴、场景注册和数据源选择由后续接入层处理。

`Filter` 是 uint16 include/exclude flags。flags=0、include=0、命中 exclude 均不可行走；area 保留在模型里，本段不引入 area 费用或路径策略。

## 最近点合同

1. 用真实细节几何 AABB 与调用方搜索盒选候选面，不用 BV 量化夹取；搜索盒是候选面范围，不是球形半径。
2. 对每个候选做水平投影或夹到最近水平边，再求细节高度；严格垂直差 `< halfExtents.y`。
3. 比较投影结果的三维距离，等距选无符号 polygon reference 较小者；不受 tile 输入顺序或 Java signed long 顺序影响。
4. `FOUND` 返回引用、点及距离平方；`NOT_FOUND` 没有结果；工作上限耗尽返回 `LIMIT_REACHED` 并丢弃部分最优结果。

这是固定参考的地面投影语义，以清晰的 Java 查询合同实现。搜索候选的精确范围、并列顺序和取消 128 项截断均与原生实现存在差异，不能宣称逐位一致。

## 地表射线合同

- 起点引用必须来自本 mesh、为 filter 允许的 ground，起点 X/Z 位于该面。引用非零但 salt/slot 不同或为 off-mesh 时仍 `INVALID_START`。起点 Y 不选择另一层；高度投影到指定引用的细节面。
- 沿整条水平线段裁剪凸面并推进互反内部边；终点 Y 不影响能否穿墙，结果高度始终取地表。因此本 API 不是跳跃/飞行的三维碰撞检测。
- 跨 tile 只接受相邻格、反向 portal、同平面及真实正长度重叠；多层候选按无符号引用稳定选择，在实际穿越点验证双方细节高度差不超过 climb（含 1e-8 数值容差）。未找到邻 tile 或反向边即为墙。
- 部分 portal 两端保留 1e-8 的保守边界；恰好穿过端点/只接触顶点不通过，不能从一个格的角跳到对角格。这个策略比原生字节压缩 portal 更保守，接缝/角点差异须在后续客户端验收中确认。
- 终点在当前闭合凸面内或恰好落在其边界上返回 `CLEAR`，fraction=1；没有阻挡法向。此闭边界约定也不承诺与 Detour 返回极大 t 的边界行为相同。
- `HIT_WALL` 返回最后可证明地表点、参数 fraction 及可确定时的水平外法向；四轴法向的零分量规范为 +0.0，保持 record 的值相等语义。不能确定离开边的保守阻挡结果法向为零。
- `LIMIT_REACHED` 是查询未完成，不是 CLEAR。若已经取得合法地表点，返回最后证明点与已访问引用；起点投影前就耗尽则没有地表点。结果列表不可修改。

默认最多 1,048,576 个工作单元和 4,096 个经过面；工作单元包括扫描候选、detail 三角形和邻接候选。可由构造器收紧，硬上限分别为 16,777,216 / 65,536。所有几何有限且受读取层总量限制；循环重访也保守结束，不依赖无限原生链表遍历。最近点目前线性扫描真实地面视图；尚无性能压测或微秒级延迟承诺。

## 测试源码与待验项

新增 `NavMeshQueryTest` 23 个测试：平面/边缘投影、严格高度窗口、多层及无符号引用并列、flags、细节峰顶、顺逆时针和四轴法向、内部邻接、墙、过滤邻面、部分/缺失 portal、portal epsilon 边界、climb、多层 portal 稳定顺序、零水平长度、沿边/闭端点、角点不跳格、非法起点、工作/访问限额、原生格式读取后的查询、并发查询工作区隔离。合成测试不通过修改原读取器测试夹具获得预期，全部原断言保持。

新增 `FixedNavMeshQueriesTest` 2 个测试：通过 `game.it.navmesh.fixedDir` 显式提供固定 929 的六文件，先核字节数与 SHA-256，再检查每个 ground 的细节三角形内部最近点/局部射线，并为每份文件实际检查一条墙与一个跨 tile portal。未提供目录时明确跳过，不能算固定资产通过；有目录但缺文件或 hash 不同则失败。这是固定几何自洽回归，不是 C++ 查询金样。

本轮仅写源码并做静态差异检查，**25 个新增测试均未运行**。按用户要求，构建和测试继续等其他重型构建空闲。后续还需实际单测、固定六产物运行、C++ 对照金样、性能与并发容量、实际场景/客户端验收。

本段之后已补入地表 A* 与走廊内路径拉直源码，18 个新增测试尚待运行，见 [路径查询续接](navigation-path-20261106.md)。仍未实现 off-mesh 跳跃、移动回缩裁决、服务器换轴封装、scene 进场/上报/外推接入和剩余配置地图接入。本项未改现有三图位图导航或同步地图，整体进度见根 PARITY。
