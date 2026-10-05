package com.game.battle;

import com.game.battle.port.ActivityResultSink;
import com.game.battle.port.BattleResultSink;
import com.game.battle.port.SceneBattleEvents;
import com.game.battle.port.SettlementSink;
import com.game.battle.push.LobbyAnnouncer;
import java.util.Objects;

/**
 * 房间的全部出站口（battle-node-spec §7.7、§7.9）：大厅公告的 gate 回落与四个出站端口。6.2 的端口是只记日志的缺省实现，
 * 6.3 / 6.4 换成真实传输时只替换 Spring bean，{@link BattleNode} 与房间不变。不可变。
 *
 * @param lobby           大厅公告 177 / 143 经 gate 回落（{@code PresenceLobbyAnnouncer}）
 * @param sceneEvents     确认事件 → scene
 * @param settlements     结算 → scene（dev 房间不调）
 * @param activityResults 活动局结果（dev 房间不调）
 * @param results         普通局结果 → match（dev 房间不调）
 */
public record OutboundPorts(
        LobbyAnnouncer lobby,
        SceneBattleEvents sceneEvents,
        SettlementSink settlements,
        ActivityResultSink activityResults,
        BattleResultSink results) {

    public OutboundPorts {
        Objects.requireNonNull(lobby, "lobby");
        Objects.requireNonNull(sceneEvents, "sceneEvents");
        Objects.requireNonNull(settlements, "settlements");
        Objects.requireNonNull(activityResults, "activityResults");
        Objects.requireNonNull(results, "results");
    }
}
