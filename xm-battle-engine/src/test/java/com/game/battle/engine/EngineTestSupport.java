package com.game.battle.engine;

import com.game.proto.BattleActorState;
import com.game.proto.BattleEventItem;
import com.game.proto.BattleStateS2C;
import com.game.proto.TurnResultS2C;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Message;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 引擎单测（移植用例、轨迹、线上字节、不变量）共用的小工具：事件的紧凑文本形式、确定性序列化、hex。
 *
 * <p>事件文本形式（{@link #describe}）只写非零字段，与 proto3 线上只带非零字段的口径一致，形如
 * {@code g1 h2 DAMAGE 5001->M0 skill101 v69 crit hp231 mp70}：
 * <ul>
 *   <li>{@code g} 后是 group_id，{@code h} 后是 hit_index（为 0 时省略）；</li>
 *   <li>actor_id：怪物写 {@code M<局内序号>}（M0 = {@code 0x8000000100000000}），宝宝写 {@code P<局内序号>}，其余按无符号十进制；</li>
 *   <li>其后依次是 skill / buff / item 表 id、value（v）、is_critical（crit）、success、target_health_after（hp）、
 *       target_mana_after（mp），都只在非零时出现。</li>
 * </ul>
 * 与规格 §13.5 的轨迹记法一一对应，断言失败时可以直接对着规格读。
 */
final class EngineTestSupport {

    private static final long LOCAL_RANGE = 1L << 32;

    private EngineTestSupport() {
    }

    /** actor_id 的可读形式：M0 / M1 / P0 / 无符号十进制。 */
    static String actorName(long actorId) {
        long monsterOffset = actorId - BattleConstants.MONSTER_ACTOR_ID_BASE;
        if (Long.compareUnsigned(monsterOffset, LOCAL_RANGE) < 0) {
            return "M" + monsterOffset;
        }
        long petOffset = actorId - BattleConstants.PET_ACTOR_ID_BASE;
        if (Long.compareUnsigned(petOffset, LOCAL_RANGE) < 0) {
            return "P" + petOffset;
        }
        return Long.toUnsignedString(actorId);
    }

    /** 事件的紧凑文本形式（见类注释）。 */
    static String describe(BattleEventItem event) {
        StringBuilder text = new StringBuilder();
        text.append('g').append(Integer.toUnsignedString(event.getGroupId()));
        if (event.getHitIndex() != 0) {
            text.append(" h").append(Integer.toUnsignedString(event.getHitIndex()));
        }
        String typeName = event.getEventType() == com.game.proto.eBattleEventType.UNRECOGNIZED
                ? "TYPE" + event.getEventTypeValue()
                : event.getEventType().name().substring("BATTLE_EVENT_".length());
        text.append(' ').append(typeName)
                .append(' ').append(actorName(event.getSourceId()))
                .append("->").append(actorName(event.getTargetId()));
        if (event.getSkillTableId() != 0) {
            text.append(" skill").append(Integer.toUnsignedString(event.getSkillTableId()));
        }
        if (event.getBuffTableId() != 0) {
            text.append(" buff").append(Integer.toUnsignedString(event.getBuffTableId()));
        }
        if (event.getItemTableId() != 0) {
            text.append(" item").append(Integer.toUnsignedString(event.getItemTableId()));
        }
        if (event.getValue() != 0) {
            text.append(" v").append(Long.toUnsignedString(event.getValue()));
        }
        if (event.getIsCritical()) {
            text.append(" crit");
        }
        if (event.getSuccess()) {
            text.append(" success");
        }
        if (event.getTargetHealthAfter() != 0) {
            text.append(" hp").append(Long.toUnsignedString(event.getTargetHealthAfter()));
        }
        if (event.getTargetManaAfter() != 0) {
            text.append(" mp").append(Long.toUnsignedString(event.getTargetManaAfter()));
        }
        return text.toString();
    }

    /** 一回合全部事件的文本形式，按结算顺序。 */
    static List<String> describeAll(TurnResultS2C result) {
        List<String> lines = new ArrayList<>(result.getEventsCount());
        for (BattleEventItem event : result.getEventsList()) {
            lines.add(describe(event));
        }
        return lines;
    }

    /** 确定性序列化（map 按键排序），用于快照这类含 map 字段的消息的字节比对（规格 §7.4）。 */
    static byte[] deterministicBytes(Message message) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(buffer);
        out.useDeterministicSerialization();
        try {
            message.writeTo(out);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    /** 小写 hex。 */
    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** 快照里某单位的气血（单位不存在时抛异常，让用例在定位处失败）。 */
    static long health(BattleStateS2C state, long actorId) {
        BattleActorState actor = TestBattles.stateActor(state, actorId);
        if (actor == null) {
            throw new AssertionError("快照里没有单位 " + actorName(actorId));
        }
        return actor.getAttributes().getHealth();
    }
}
