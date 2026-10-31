package com.game.match.spectate;

import com.game.match.proto.BattlePlacement;
import com.game.proto.BattleActivityContext;
import com.game.proto.CreateBattleRequest;
import com.game.proto.eBattleActivityKind;
import com.game.proto.eSpectateVisibility;
import com.game.proto.match.MatchMode;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 「这局能不能被观战」的唯一权威（mmorpg {@code docs/design/battle-spectate-tiered-fanout.md} §10 第 6 条；与 mmorpg Go 版
 * {@code spectateVisibilityFor} 同一张表）。建房时调用一次 {@link #decide}，结果冻结进 {@code CreateBattleRequest.spectate_visibility}
 * （全球模式随 formation 持久的 create_request 一起冻结）；之后落点记录、可观战索引、两条 163 路径、164 列表与 battle 的 AddObserver
 * 都只读这份冻结值，不再按模式重算——策略以后改了，也不会让已经开打的局在中途变得可看或不可看。
 *
 * <p><b>默认拒绝</b>：只有表里显式登记为 PUBLIC 的模式可观战。活动局（帮会历练等）一律私密；计分局 1V1 / 5V5（mmorpg
 * {@code global-match-hierarchical-pools.md} D7：计分局禁止观战）、点名切磋、人数档真人 PVP、未开放与未填的模式都私密；表外的值
 * （proto 的 {@code UNRECOGNIZED}、将来新增而没进表的模式）同样私密。新增 {@link MatchMode} 必须在 {@link #BY_MODE} 里显式表态，
 * {@code SpectateVisibilityPolicyTest} 遍历枚举钉住，没进表就红。
 *
 * <p>纯函数、无状态、线程安全。
 */
public final class SpectateVisibilityPolicy {

    /** 每个匹配模式的可见性（平铺的显式决策表；不在表里的一律私密）。 */
    private static final Map<MatchMode, eSpectateVisibility> BY_MODE = decisionTable();

    private SpectateVisibilityPolicy() {
    }

    private static Map<MatchMode, eSpectateVisibility> decisionTable() {
        Map<MatchMode, eSpectateVisibility> table = new EnumMap<>(MatchMode.class);
        // 漏填模式的老发送方：没有任何公开依据
        table.put(MatchMode.MATCH_MODE_UNSPECIFIED, eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE);
        // 计分局禁止观战（D7）
        table.put(MatchMode.MATCH_MODE_5V5, eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE);
        // 未开放的模式
        table.put(MatchMode.MATCH_MODE_3V3, eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE);
        // 计分局禁止观战（D7）
        table.put(MatchMode.MATCH_MODE_1V1, eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE);
        table.put(MatchMode.MATCH_MODE_PVE_SOLO, eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC);
        table.put(MatchMode.MATCH_MODE_PVE_TEAM, eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC);
        // 点名切磋是两人之间的私事，没有公开依据
        table.put(MatchMode.MATCH_MODE_PVP_CHALLENGE, eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE);
        // 人数档真人 PVP：是否计分、能否公开都没有拍板，按默认拒绝；要开放须先改设计再改这一行
        table.put(MatchMode.MATCH_MODE_PVP_TEAM, eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE);
        return Collections.unmodifiableMap(table);
    }

    /**
     * 建房时决定这局的观战可见性（只在组 {@code CreateBattleRequest} 时调用一次，结果随建房原件冻结）。
     *
     * @param mode     这局的匹配模式；{@code null} 与 {@code UNRECOGNIZED}（数值不在契约里）都按私密
     * @param activity 建房原件里的活动上下文；{@code null} 或 kind 为 NONE 表示普通对局。活动局一律私密，与模式无关
     * @return 永不为 {@code UNSPECIFIED}：调用方据此填进建房请求，battle 与 match 不必再区分「没填」与「私密」
     */
    public static eSpectateVisibility decide(MatchMode mode, BattleActivityContext activity) {
        if (activity != null && activity.getKind() != eBattleActivityKind.BATTLE_ACTIVITY_KIND_NONE) {
            return eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE;
        }
        if (mode == null) {
            return eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE;
        }
        // 表外的值（UNRECOGNIZED）回私密，不是放行的缺省
        return BY_MODE.getOrDefault(mode, eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE);
    }

    /** 决策表里显式表态过的模式（只读；测试据此钉住「每个枚举都在表里」）。 */
    static Set<MatchMode> decidedModes() {
        return BY_MODE.keySet();
    }

    /** 只认 PUBLIC：{@code UNSPECIFIED}（漏填 / 老发送方）、PRIVATE 与认不出的值一律不可观战（fail-closed）。 */
    public static boolean isPublic(eSpectateVisibility visibility) {
        return visibility == eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC;
    }

    /** 落点记录里冻结的可见性（{@code BattlePlacement.spectate_visibility} 存的是枚举数值，口径同 {@link #isPublic(eSpectateVisibility)}）。 */
    public static boolean isPublic(BattlePlacement placement) {
        Objects.requireNonNull(placement, "placement");
        return placement.getSpectateVisibility() == eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC_VALUE;
    }

    /**
     * 全球模式 formation 持久的建房原件（{@code create_request} 字节）是否公开：空（还没组好建房请求）或解析不了一律 false（fail-closed）。
     * 全球模式没有另存一份可见性，读的就是建房时冻结的那一份。
     */
    public static boolean createRequestPublic(ByteString createRequest) {
        if (createRequest == null || createRequest.isEmpty()) {
            return false;
        }
        try {
            return isPublic(CreateBattleRequest.parseFrom(createRequest).getSpectateVisibility());
        } catch (InvalidProtocolBufferException corrupt) {
            return false;
        }
    }
}
