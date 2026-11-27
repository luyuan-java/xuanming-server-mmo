package com.game.gateway.admin;

import java.util.Map;
import java.util.Set;

/** 热关停规则存储与在线节点核对边界（生产经 Redis 哈希 {@code <ns>:killswitch} 与节点目录实现）。 */
public interface KillSwitchRuleStore {

    /** 列出全部规则（相对键 → 原始值）。 */
    Map<String, String> list();

    /** 写入或覆盖一条规则（相对键 → 规范 JSON 值）。 */
    void put(String relativeKey, String value);

    /** 删除一条规则，返回删除条数（0 或 1）。 */
    long delete(String relativeKey);

    /** 返回当前在线注册在指定 {@code nodeType}（如 {@code SceneNodeService}）下的节点 UUID 集合（小写）。 */
    Set<String> registeredNodeUuids(String nodeType);
}
