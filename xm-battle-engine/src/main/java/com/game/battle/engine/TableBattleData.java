package com.game.battle.engine;

import com.game.table.BuffTable;
import com.game.table.ConfigTables;
import com.game.table.DungeonTable;
import com.game.table.ItemTable;
import com.game.table.MonsterTable;
import com.game.table.SkillPermissionTable;
import com.game.table.SkillTable;
import com.game.table.load.TableLoadException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * {@link BattleData} 的生产实现：绑定<strong>一份</strong> {@link ConfigTables} 快照（基线 {@code table_battle_data_provider.cpp}，
 * 规格 §9.1、§9.4）。
 *
 * <p>与基线的差别：
 * <ul>
 *   <li>基线每次调用都读全局表管理器，热重载会让进行中的战斗中途读到新表；这里整局绑定开局时的快照，热更只影响之后新开的局（D3）。</li>
 *   <li>构造时就算好这份快照的战斗配表指纹（{@link #fingerprint()}），表有问题提前暴露。</li>
 *   <li>战斗公式里的 {@code random()} 在基线走 C 的 {@code rand()}，破坏确定性；这里构造时发现 {@code Skill.damage} 或
 *       {@code Buff.health_regeneration} 调用了 {@code random()} 就抛 {@link TableLoadException}，求值时传入的随机源一被调用就抛
 *       {@link IllegalStateException}（D4）。{@code Buff.bonus_damage} 引擎不读，不检查。</li>
 * </ul>
 *
 * <p>表达式求值按公式原文从左到右（{@code TableExpression} 的左结合语法树），例如 {@code 0.013*level*health} 是
 * {@code (0.013*level)*health}。若 exprtk 把它改写成 {@code 0.013*(level*health)}，个别输入两版会差 1 ulp，
 * 进而在 ApplyHeal 的截断边界上差 1 点气血；尚未用 C++ 金样核对（规格 §12.3 Q7）。
 *
 * <p>实例不可变、线程安全：一份快照建一个实例，多场战斗共享即可（构造要序列化七张表，不宜每局重建）。
 */
public final class TableBattleData implements BattleData {

    /**
     * 公式调用 {@code random()} 的检测口径，与 {@code TableExpression} 的词法一致：函数名大小写不敏感，前面不能紧挨名字字符
     * （{@link Character#isLetterOrDigit} 或下划线），名字与左括号之间允许空白（{@link Character#isWhitespace}）。
     * 对能编译通过的公式，它命中当且仅当语法树里有 {@code random} 调用。
     */
    private static final Pattern RANDOM_CALL =
            Pattern.compile("(?<![\\p{javaLetterOrDigit}_])random\\p{javaWhitespace}*\\(", Pattern.CASE_INSENSITIVE);

    /** 引擎求值时传给表达式的随机源：一被调用就抛异常（构造期的闸已保证公式里没有 {@code random()}，这是第二道保险）。 */
    static final RandomGenerator FORBID_RANDOM = new RandomGenerator() {
        @Override
        public long nextLong() {
            throw forbidden();
        }

        @Override
        public double nextDouble() {
            throw forbidden();
        }

        private IllegalStateException forbidden() {
            return new IllegalStateException("回合制战斗公式不得调用 random()：引擎要求同种子同输入逐字节可回放");
        }
    };

    private final ConfigTables tables;
    private final String fingerprint;

    /**
     * @throws TableLoadException 战斗公式（{@code Skill.damage}、{@code Buff.health_regeneration}）里调用了 {@code random()}
     */
    public TableBattleData(ConfigTables tables) {
        this.tables = Objects.requireNonNull(tables, "tables");
        rejectRandomFormulas(tables);
        this.fingerprint = BattleTableFingerprint.compute(tables);
    }

    /** 绑定的配置表快照。 */
    public ConfigTables tables() {
        return tables;
    }

    /** 这份快照的战斗配表指纹（{@link BattleTableFingerprint#compute(ConfigTables)}，构造时算好）。 */
    public String fingerprint() {
        return fingerprint;
    }

    @Override
    public Optional<SkillTable> skill(int skillTableId) {
        return tables.skill().find(skillTableId);
    }

    @Override
    public Optional<BuffTable> buff(int buffTableId) {
        return tables.buff().find(buffTableId);
    }

    @Override
    public Optional<SkillPermissionTable> skillPermission(int combatStateId) {
        return tables.skillPermission().find(combatStateId);
    }

    @Override
    public Optional<DungeonTable> dungeon(int dungeonTableId) {
        return tables.dungeon().find(dungeonTableId);
    }

    @Override
    public Optional<MonsterTable> monster(int monsterTableId) {
        return tables.monster().find(monsterTableId);
    }

    @Override
    public Optional<ItemTable> item(int itemTableId) {
        return tables.item().find(itemTableId);
    }

    /** {@code CooldownTable.duration} 是 uint32 毫秒，按无符号扩成 uint64；缺行返回 0。 */
    @Override
    public long cooldownDurationMs(int cooldownTableId) {
        return tables.cooldown().find(cooldownTableId).map(row -> Integer.toUnsignedLong(row.getDuration())).orElse(0L);
    }

    /** {@code DungeonTable.monster} 按表内顺序、跳过 0（表内跨列留空补的 0）；缺行返回空列表。 */
    @Override
    public List<Integer> dungeonMonsterIds(int dungeonTableId) {
        Optional<DungeonTable> row = tables.dungeon().find(dungeonTableId);
        if (row.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>(row.get().getMonsterCount());
        for (int monsterId : row.get().getMonsterList()) {
            if (monsterId != 0) {
                ids.add(monsterId);
            }
        }
        return List.copyOf(ids);
    }

    /** 缺行返回 0.0（同基线生成代码的 {@code GetDamage}）；引擎只在查到行之后调用。 */
    @Override
    public double skillDamage(int skillTableId, double casterLevel) {
        return tables.skill().find(skillTableId)
                .map(row -> tables.skill().evalDamage(row, casterLevel, FORBID_RANDOM))
                .orElse(0.0);
    }

    /** 缺行返回 0.0；第二个参数按位置传给公式的 {@code health}（引擎传已损失气血）。 */
    @Override
    public double buffHealthRegeneration(int buffTableId, double level, double lostHealth) {
        return tables.buff().find(buffTableId)
                .map(row -> tables.buff().evalHealthRegeneration(row, level, lostHealth, FORBID_RANDOM))
                .orElse(0.0);
    }

    /** 公式原文是否调用了 {@code random()}（口径见 {@link #RANDOM_CALL}）。 */
    static boolean callsRandom(String formula) {
        return formula != null && RANDOM_CALL.matcher(formula).find();
    }

    private static void rejectRandomFormulas(ConfigTables tables) {
        for (SkillTable row : tables.skill().all()) {
            if (callsRandom(row.getDamage())) {
                throw randomRejected("Skill", "damage", row.getId(), row.getDamage());
            }
        }
        for (BuffTable row : tables.buff().all()) {
            if (callsRandom(row.getHealthRegeneration())) {
                throw randomRejected("Buff", "health_regeneration", row.getId(), row.getHealthRegeneration());
            }
        }
    }

    private static TableLoadException randomRejected(String sheet, String column, int rowId, String formula) {
        return new TableLoadException("配置表 " + sheet + " 的表达式列 " + column + "（行 " + Integer.toUnsignedString(rowId)
                + "）调用了 random()：回合制战斗要求同种子同输入逐字节可回放，战斗公式不得用随机数，公式 \"" + formula + "\"");
    }
}
