package com.game.battle;

import com.game.battle.engine.BattleData;
import com.game.battle.protocol.BattleMessageIds;
import com.game.net.limit.MessageLimits;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * battle 进程启动时加载一次、之后只读的数据（battle-node-spec §6.4、§7.11 第 3 步）：战斗配表、本节点的配表指纹、按消息号限频表、11 个消息号。
 * 不可变，线程安全。
 *
 * @param data          战斗配表（生产 {@code TableBattleData}，构造失败即拒启，engine-spec D4）
 * @param fingerprint   本节点的战斗配表指纹（指纹闸与 1006 文案、目录、启动日志用）
 * @param messageLimits 按消息号限频的上限（MessageLimiter 表，每条直连一份限频器）
 * @param messageIds    直连面白名单与推送的消息号（缺号即拒启）
 */
public record BattleTables(BattleData data, String fingerprint, MessageLimits messageLimits, BattleMessageIds messageIds) {

    /** 启动日志里打行数的七张表（同基线 {@code main.cpp:184-198}）。 */
    static final List<String> BATTLE_SHEETS = List.of("Skill", "Buff", "Cooldown", "SkillPermission", "Dungeon", "Monster", "Item");
    /** 任一为空时打 ERROR（回合引擎无法开局），但<b>不拒启</b>（同基线 {@code main.cpp:205-214}）。 */
    static final List<String> CRITICAL_SHEETS = List.of("Skill", "Buff", "Dungeon", "Monster");
    /** 为空时只打 WARN（只影响战斗内用药）。 */
    static final String ITEM_SHEET = "Item";

    public BattleTables {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(messageLimits, "messageLimits");
        Objects.requireNonNull(messageIds, "messageIds");
    }

    /**
     * 七张战斗表的行数报告（纯函数，启动日志用）。
     *
     * @param rows          七张表 → 行数（缺的表按 0）
     * @param emptyCritical 为空的关键表（Skill / Buff / Dungeon / Monster），非空即打 ERROR
     * @param itemEmpty     Item 表为空（打 WARN）
     */
    record RowReport(Map<String, Integer> rows, List<String> emptyCritical, boolean itemEmpty) {

        static RowReport of(Map<String, Integer> rowCounts) {
            Map<String, Integer> rows = new LinkedHashMap<>();
            for (String sheet : BATTLE_SHEETS) {
                rows.put(sheet, rowCounts.getOrDefault(sheet, 0));
            }
            List<String> empty = new ArrayList<>();
            for (String sheet : CRITICAL_SHEETS) {
                if (rows.get(sheet) == 0) {
                    empty.add(sheet);
                }
            }
            return new RowReport(Map.copyOf(rows), List.copyOf(empty), rows.get(ITEM_SHEET) == 0);
        }

        /** {@code skill=… buff=… cooldown=… skill_permission=… dungeon=… monster=… item=…}（同基线日志的写法）。 */
        String summary() {
            StringBuilder out = new StringBuilder();
            for (String sheet : BATTLE_SHEETS) {
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(snake(sheet)).append('=').append(rows.get(sheet));
            }
            return out.toString();
        }

        private static String snake(String sheet) {
            return sheet.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        }
    }
}
