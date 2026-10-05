package com.game.battle.room;

import com.game.battle.BattleIdentity;
import com.game.battle.engine.BattleData;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.port.ActivityResultSink;
import com.game.battle.port.BattleResultSink;
import com.game.battle.port.SceneBattleEvents;
import com.game.battle.port.SettlementSink;
import com.game.battle.protocol.BattleMessageIds;
import com.game.battle.push.LobbyAnnouncer;
import com.game.common.token.BattleTickets;
import java.util.Objects;

/**
 * {@link BattleRoomServiceImpl} 的全部依赖（装配方 {@code BattleConfiguration} 构造；测试用假实现逐项替换）。不可变。
 *
 * @param data             战斗配表（生产 {@code TableBattleData}；引擎 {@code TurnBattleEngine.start(request, data)} 用）
 * @param tableFingerprint 本节点的战斗配表指纹（{@code TableBattleData.fingerprint()}），指纹闸与 1006 文案用
 * @param fingerprintMode  指纹闸模式（{@code xm.battle.table-fingerprint-mode}）
 * @param scheduler        逻辑线程执行器与计时器（回合 / 期限 / 确认补发；{@link BattleScheduler#assertInLoop()}）
 * @param clock            墙钟（action_deadline_ms、房间期限、finished_at_ms）
 * @param identity         本进程身份（签票的 node_id / 实例 UUID / 通告地址）
 * @param tickets          票据签名器（与直连面验签共用同一个实例 / 同一把密钥）
 * @param messageIds       推送的 7 个消息号
 * @param lobby            大厅公告的 gate 回落出口（177 / 143，无活直连时）
 * @param sceneEvents      确认事件出站端口
 * @param settlements      结算出站端口（dev 房间不调）
 * @param activityResults  活动局结果出站端口（dev 房间不调）
 * @param results          普通局结果出站端口（dev 房间不调）
 * @param metrics          指标
 */
public record RoomDependencies(
        BattleData data,
        String tableFingerprint,
        FingerprintMode fingerprintMode,
        BattleScheduler scheduler,
        BattleClock clock,
        BattleIdentity identity,
        BattleTickets tickets,
        BattleMessageIds messageIds,
        LobbyAnnouncer lobby,
        SceneBattleEvents sceneEvents,
        SettlementSink settlements,
        ActivityResultSink activityResults,
        BattleResultSink results,
        BattleMetrics metrics) {

    public RoomDependencies {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(tableFingerprint, "tableFingerprint");
        Objects.requireNonNull(fingerprintMode, "fingerprintMode");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(tickets, "tickets");
        Objects.requireNonNull(messageIds, "messageIds");
        Objects.requireNonNull(lobby, "lobby");
        Objects.requireNonNull(sceneEvents, "sceneEvents");
        Objects.requireNonNull(settlements, "settlements");
        Objects.requireNonNull(activityResults, "activityResults");
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(metrics, "metrics");
    }
}
