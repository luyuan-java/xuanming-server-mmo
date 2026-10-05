package com.game.battle.engine;

import com.game.common.math.Unsigned;
import com.game.proto.BattleBuffEntry;
import com.game.proto.BattleBuffEntryOrBuilder;
import com.game.proto.eBattleEventType;
import com.game.table.BuffTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * buff 的挂载、免疫、驱散、叠层、移除、回合末 tick 与开局快照清洗（基线 {@code engine.cpp:1037-1167}、{@code :1245-1285}、
 * {@code :1402-1564}；规格 §5）。
 *
 * <p>buff 条目存在单位的 {@code BattleActorState.buffs} 里（{@code BattleBuffEntry}：实例号、表 id、层数、剩余回合（0 = 无限）、
 * 施法者）。条目在递归挂子 buff、tick、驱散时会增删，<strong>不持有条目 builder 跨过任何可能增删的调用</strong>，之后一律按实例号
 * 重新查下标（规格 §5.2、§11.3 第 8 条）。
 *
 * <p>实例号计数器（{@code nextBuffInstanceId}）：开局时按全部快照 buff_id 推高（在清洗之前），之后只有新建条目才取号；
 * 叠层、驱散、免疫拦截、纯驱散 buff 都不消耗实例号。uint64，无符号比较，允许回绕。
 */
final class BuffEngine {

    private final BattleContext ctx;
    /** uint64；基线 {@code nextBuffInstanceId = 1}（{@code engine.h:230}）。 */
    private long nextBuffInstanceId = 1;

    BuffEngine(BattleContext ctx) {
        this.ctx = ctx;
    }

    /**
     * 开局拷快照 buff 时推高实例号计数器（{@code engine.cpp:155-157}）：{@code buff_id >= next}（无符号）时 {@code next = buff_id + 1}；
     * {@code buff_id == UINT64_MAX} 时回绕成 0（基线行为，照搬）。在清洗之前执行，所以被清洗丢掉的条目也会推高计数器。
     */
    void reserveSnapshotBuffId(long buffId) {
        if (Long.compareUnsigned(buffId, nextBuffInstanceId) >= 0) {
            nextBuffInstanceId = buffId + 1;
        }
    }

    // ---- 挂载（engine.cpp:1402-1564） ----

    /**
     * 挂 buff（{@code AddBuffToActor}，{@code engine.cpp:1402-1480}；规格 §5.2），严格按顺序：
     * <ol>
     *   <li>{@code depth > 8} 或目标已死 → 返回（深度 0..8 共 9 层都生效）；</li>
     *   <li>表行缺失 → 返回；</li>
     *   <li>免疫 → 返回，不出任何事件；</li>
     *   <li>驱散（新 buff 的 dispel_tag 命中现存条目的 tag）；</li>
     *   <li>纯驱散(35) → 返回：不落地、不出 ADD；</li>
     *   <li>叠层 / 刷新命中 → 返回；</li>
     *   <li>新建条目：layer 1、施法者 casterId；剩余回合 = 无限 ? 0 : (duration &gt; 0 ? 秒换回合 : 1（瞬时）)；</li>
     *   <li>BUFF_ADD（source = casterId，value = 1）；</li>
     *   <li>sub_buff 按表序递归挂给持有者本人（深度 + 1）；</li>
     *   <li>target_sub_buff 非空、施法者存在且<strong>未死</strong>（不查是否已逃）时，按表序递归挂给施法者，方向对调：
     *       施法者记为本 buff 的持有者（自己对自己施放时就挂回自己）；</li>
     *   <li>瞬时条目：按新实例号重查下标，还在就移除并出 BUFF_REMOVE。</li>
     * </ol>
     */
    void addBuffToActor(BattleUnit target, int buffTableId, long casterId, int depth) {
        if (Integer.compareUnsigned(depth, BattleConstants.MAX_SUB_BUFF_DEPTH) > 0 || target.isDead()) {
            return;
        }
        Optional<BuffTable> buffRow = ctx.data().buff(buffTableId);
        if (buffRow.isEmpty()) {
            return;
        }
        BuffTable row = buffRow.get();
        if (isImmuneToBuff(target, row)) {
            return;
        }
        dispelBuffsByTag(target, row);
        if (row.getBuffType() == BattleConstants.BUFF_TYPE_DISPEL) {
            return;
        }
        if (stackOrRefreshExistingBuff(target, row, casterId)) {
            return;
        }

        long newBuffId = nextBuffInstanceId++;
        int remainRounds;
        if (row.getInfiniteDuration() != 0) {
            remainRounds = 0;
        } else if (row.getDuration() > 0) {
            remainRounds = BattleRules.roundsFromSeconds(row.getDuration());
        } else {
            remainRounds = 1; // 瞬时 buff：驱散 / 子 buff 照样生效，条目本身随后立即移除
        }
        // 只经由 add 建条目、不留 builder 引用：下面的递归可能把它驱散掉，之后一律按 newBuffId 重查（engine.cpp:1448-1451）
        target.state().addBuffs(BattleBuffEntry.newBuilder()
                .setBuffId(newBuffId)
                .setBuffTableId(buffTableId)
                .setLayer(1)
                .setRemainRounds(remainRounds)
                .setCasterId(casterId));

        ctx.events().append(eBattleEventType.BATTLE_EVENT_BUFF_ADD, casterId, target.actorId())
                .setBuffTableId(buffTableId)
                .setValue(1);

        for (int subBuffId : row.getSubBuffList()) {
            addBuffToActor(target, subBuffId, casterId, depth + 1);
        }

        if (row.getTargetSubBuffCount() > 0) {
            BattleUnit caster = ctx.findActor(casterId);
            if (caster != null && !caster.isDead()) {
                for (int targetSubBuffId : row.getTargetSubBuffList()) {
                    addBuffToActor(caster, targetSubBuffId, target.actorId(), depth + 1);
                }
            }
        }

        if (row.getInfiniteDuration() == 0 && row.getDuration() <= 0) {
            int index = target.findBuffIndex(newBuffId);
            if (index >= 0) {
                removeBuffAt(target, index);
            }
        }
    }

    /**
     * 免疫（{@code IsImmuneToBuff}，{@code engine.cpp:1482-1497}）：目标任一现存条目（其表行存在）的 immune_tag 包含新 buff 任一 tag 的
     * <strong>键</strong>（忽略 bool 值）。靠的是任意类型 buff 的 immune_tag，类型 34 本身没有特殊逻辑。
     */
    private boolean isImmuneToBuff(BattleUnit target, BuffTable row) {
        int count = target.state().getBuffsCount();
        for (int index = 0; index < count; index++) {
            Optional<BuffTable> existingRow = ctx.data().buff(target.state().getBuffsOrBuilder(index).getBuffTableId());
            if (existingRow.isEmpty()) {
                continue;
            }
            for (String tag : row.getTagMap().keySet()) {
                if (existingRow.get().containsImmuneTag(tag)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 驱散（{@code DispelBuffsByTag}，{@code engine.cpp:1499-1523}）：新 buff 的 dispel_tag 非空时，先按下标升序收集表行存在、且其 tag
     * 含 dispel_tag 任一键的现存条目，再<strong>按下标降序</strong>逐个移除，所以 BUFF_REMOVE 按插入序的逆序产出。不分施法者。
     */
    private void dispelBuffsByTag(BattleUnit target, BuffTable row) {
        if (row.getDispelTagCount() == 0) {
            return;
        }
        int count = target.state().getBuffsCount();
        int[] dispelIndexes = new int[count];
        int found = 0;
        for (int index = 0; index < count; index++) {
            Optional<BuffTable> existingRow = ctx.data().buff(target.state().getBuffsOrBuilder(index).getBuffTableId());
            if (existingRow.isEmpty()) {
                continue;
            }
            for (String dispelTag : row.getDispelTagMap().keySet()) {
                if (existingRow.get().containsTag(dispelTag)) {
                    dispelIndexes[found++] = index;
                    break;
                }
            }
        }
        for (int i = found - 1; i >= 0; i--) {
            removeBuffAt(target, dispelIndexes[i]);
        }
    }

    /**
     * 叠层 / 刷新（{@code StackOrRefreshExistingBuff}，{@code engine.cpp:1525-1551}）：按列表顺序找<strong>第一个</strong>表 id 相同、并且
     * （no_caster ≠ 0 或施法者相同）的条目。找到后：{@code layer < max_layer}（uint32）时加一层（max_layer 为 0 时永不加层）；
     * 有限条目且表 duration &gt; 0 时剩余回合重挂满；出 BUFF_ADD（source = 本次 casterId，value = 加层后的层数）。
     * 不触发子 buff，也不改条目原来的施法者。
     */
    private boolean stackOrRefreshExistingBuff(BattleUnit target, BuffTable row, long casterId) {
        int count = target.state().getBuffsCount();
        for (int index = 0; index < count; index++) {
            BattleBuffEntryOrBuilder existing = target.state().getBuffsOrBuilder(index);
            if (existing.getBuffTableId() != row.getId()) {
                continue;
            }
            if (row.getNoCaster() == 0 && existing.getCasterId() != casterId) {
                continue;
            }
            BattleBuffEntry.Builder entry = target.state().getBuffsBuilder(index);
            if (Integer.compareUnsigned(entry.getLayer(), row.getMaxLayer()) < 0) {
                entry.setLayer(entry.getLayer() + 1);
            }
            if (entry.getRemainRounds() != 0 && row.getDuration() > 0) {
                entry.setRemainRounds(BattleRules.roundsFromSeconds(row.getDuration()));
            }
            ctx.events().append(eBattleEventType.BATTLE_EVENT_BUFF_ADD, casterId, target.actorId())
                    .setBuffTableId(row.getId())
                    .setValue(Integer.toUnsignedLong(entry.getLayer()));
            return true;
        }
        return false;
    }

    /** 删除该下标的条目并出 BUFF_REMOVE（source = 条目的施法者，不填 value）（{@code RemoveBuffAt}，{@code engine.cpp:1553-1564}）。 */
    private void removeBuffAt(BattleUnit target, int buffIndex) {
        if (buffIndex < 0 || buffIndex >= target.state().getBuffsCount()) {
            return;
        }
        BattleBuffEntryOrBuilder entry = target.state().getBuffsOrBuilder(buffIndex);
        int buffTableId = entry.getBuffTableId();
        long casterId = entry.getCasterId();
        target.state().removeBuffs(buffIndex);
        ctx.events().append(eBattleEventType.BATTLE_EVENT_BUFF_REMOVE, casterId, target.actorId())
                .setBuffTableId(buffTableId);
    }

    // ---- 回合末 tick（engine.cpp:1037-1167） ----

    /** 按单位插入序（玩家 → 宝宝 → 怪物）逐个 tick，只处理存活未逃的单位（{@code TickBuffsAtRoundEnd}，{@code engine.cpp:1037-1045}）。 */
    void tickAtRoundEnd() {
        for (BattleUnit unit : ctx.units()) {
            if (!unit.isActive()) {
                continue;
            }
            tickActorBuffs(unit);
        }
    }

    /**
     * 单个单位的回合末 tick（{@code TickActorBuffs}，{@code engine.cpp:1047-1116}；规格 §5.5）：
     * <ol>
     *   <li>先拍下全部实例号；列表为空就返回、<strong>不开组</strong>，否则开一组（即使这一组最终没有事件）；</li>
     *   <li>按快照里的实例号逐个：找不到下标跳过；复制条目、查表行，行缺失跳过且<strong>不递减</strong>；</li>
     *   <li>周期效果（interval &gt; 0，全部 uint32 运算）：k = 秒换回合(interval)；有限条目 elapsed = 表时长回合 ≥ remain ?
     *       表时长回合 − remain + 1 : 1（用<strong>表时长</strong>，不是条目的初始剩余）；无限条目 elapsed = 全局回合号；
     *       {@code elapsed % k == 0} 且（interval_count 为 0 或 {@code elapsed / k <= interval_count}）时结算周期效果；</li>
     *   <li>结算后单位已死：本单位 tick 立即结束（死亡已清空 buff）；</li>
     *   <li>按实例号重查：remain 0 不动；1 移除（出 BUFF_REMOVE）；否则减 1。</li>
     * </ol>
     * 有意差异 D5：k 被截成 0（秒换回合的结果 ≥ 2^32）时 C++ 是 {@code % 0}（x86 上 SIGFPE）；Java 不结算周期效果、递减照常。
     */
    private void tickActorBuffs(BattleUnit actor) {
        int count = actor.state().getBuffsCount();
        if (count == 0) {
            return;
        }
        long[] buffIds = new long[count];
        for (int index = 0; index < count; index++) {
            buffIds[index] = actor.state().getBuffsOrBuilder(index).getBuffId();
        }
        ctx.events().beginGroup();

        for (long buffId : buffIds) {
            int index = actor.findBuffIndex(buffId);
            if (index < 0) {
                continue; // 本轮 tick 中已被移除
            }
            BattleBuffEntry entrySnapshot = actor.state().getBuffs(index);
            Optional<BuffTable> buffRow = ctx.data().buff(entrySnapshot.getBuffTableId());
            if (buffRow.isEmpty()) {
                continue;
            }
            BuffTable row = buffRow.get();

            if (row.getInterval() > 0) {
                int intervalRounds = BattleRules.roundsFromSeconds(row.getInterval());
                int elapsedRounds;
                int remain = entrySnapshot.getRemainRounds();
                if (remain != 0) {
                    int totalRounds = BattleRules.roundsFromSeconds(row.getDuration());
                    elapsedRounds = Integer.compareUnsigned(totalRounds, remain) >= 0 ? totalRounds - remain + 1 : 1;
                } else {
                    elapsedRounds = ctx.roundIndex();
                }
                if (intervalRounds != 0) { // D5
                    boolean onIntervalBoundary = Integer.remainderUnsigned(elapsedRounds, intervalRounds) == 0;
                    int ticksDone = Integer.divideUnsigned(elapsedRounds, intervalRounds);
                    boolean underTickLimit = row.getIntervalCount() == 0
                            || Integer.compareUnsigned(ticksDone, row.getIntervalCount()) <= 0;
                    if (onIntervalBoundary && underTickLimit) {
                        applyBuffIntervalEffect(actor, entrySnapshot, row);
                    }
                }
            }

            if (actor.isDead()) {
                return;
            }

            index = actor.findBuffIndex(buffId);
            if (index < 0) {
                continue;
            }
            int remainRounds = actor.state().getBuffsOrBuilder(index).getRemainRounds();
            if (remainRounds == 0) {
                continue;
            }
            if (remainRounds == 1) {
                removeBuffAt(actor, index);
            } else {
                actor.state().getBuffsBuilder(index).setRemainRounds(remainRounds - 1);
            }
        }
    }

    /**
     * 周期效果（{@code ApplyBuffIntervalEffect}，{@code engine.cpp:1118-1167}；规格 §5.6）：
     * <ul>
     *   <li>40 / 42 回血：损血 = (double)(max_health − health)（uint64 减法，当前超上限时回绕成巨大值），
     *       回血量 = 公式(表 id, (double) 等级, 损血)，经 {@link BattleUnit#applyHeal}；实回 ≠ 0 才出 BUFF_TICK；</li>
     *   <li>50 / 51 毒、灼烧：interval_effect 为空无效果；raw = interval_effect[0] × (double) max(1, layer)，不乘 PvP 系数、
     *       不掷暴击、不过伤害公式；经 {@link BattleUnit#applyDamage}（防御中减半）；实扣 ≠ 0 才出 BUFF_TICK；然后只要气血为 0
     *       （包括本来就是 0）就按条目的施法者结算死亡；</li>
     *   <li>其余类型没有效果。</li>
     * </ul>
     * BUFF_TICK 的 source 是条目的施法者，buff_table_id 取表行 id。
     */
    private void applyBuffIntervalEffect(BattleUnit actor, BattleBuffEntry entry, BuffTable row) {
        switch (row.getBuffType()) {
            case BattleConstants.BUFF_TYPE_HEALTH_REGENERATION,
                 BattleConstants.BUFF_TYPE_HEALTH_REGENERATION_BASED_ON_LOST_HEALTH -> {
                double lostHealth = Unsigned.toDouble(actor.maxHealth() - actor.health());
                double healAmount = ctx.data().buffHealthRegeneration(row.getId(),
                        (double) Integer.toUnsignedLong(actor.level()), lostHealth);
                long healed = actor.applyHeal(healAmount);
                if (healed != 0) {
                    ctx.events().append(eBattleEventType.BATTLE_EVENT_BUFF_TICK, entry.getCasterId(), actor.actorId())
                            .setBuffTableId(row.getId())
                            .setValue(healed)
                            .setTargetHealthAfter(actor.health())
                            .setTargetManaAfter(actor.mana());
                }
            }
            case BattleConstants.BUFF_TYPE_POISON, BattleConstants.BUFF_TYPE_BURN -> {
                if (row.getIntervalEffectCount() == 0) {
                    return;
                }
                int layer = entry.getLayer();
                // std::max(1u, layer)：0 层按 1 层算（uint32）
                double rawDamage = row.getIntervalEffect(0) * (double) Integer.toUnsignedLong(layer == 0 ? 1 : layer);
                long dealt = actor.applyDamage(rawDamage);
                if (dealt != 0) {
                    ctx.events().append(eBattleEventType.BATTLE_EVENT_BUFF_TICK, entry.getCasterId(), actor.actorId())
                            .setBuffTableId(row.getId())
                            .setValue(dealt)
                            .setTargetHealthAfter(actor.health())
                            .setTargetManaAfter(actor.mana());
                }
                if (actor.health() == 0) {
                    ctx.handleDeath(actor, entry.getCasterId());
                }
            }
            default -> {
                // 其余 buff 类型的周期语义一期不启用
            }
        }
    }

    // ---- 开局快照清洗（engine.cpp:1245-1285） ----

    /**
     * 快照 buff 清洗（{@code SanitizeSnapshotBuffs}，{@code engine.cpp:1245-1285}；规格 §5.8），只对玩家、在全部单位就位之后执行。
     * 按快照顺序处理，保留的条目保持相对顺序：
     * <ol>
     *   <li>表行缺失 → 丢弃；类型为眩晕 / 冰冻 / 沉默 → 丢弃；瞬时（非无限且 duration ≤ 0）→ 丢弃；</li>
     *   <li>施法者非 0 且本局查不到（查找范围含怪物与宝宝）→ 施法者置 0；</li>
     *   <li>有限条目：remain 为 0 或大于表时长回合（uint32）→ 改成表时长回合；无限条目保留快照里的 remain；</li>
     *   <li>layer 不夹（0 层在毒伤里按 1 层算，超过 max_layer 的层数原样保留）。</li>
     * </ol>
     */
    void sanitizeSnapshotBuffs(BattleUnit actor) {
        List<BattleBuffEntry> kept = new ArrayList<>(actor.state().getBuffsCount());
        for (BattleBuffEntry buff : actor.state().getBuffsList()) {
            Optional<BuffTable> buffRow = ctx.data().buff(buff.getBuffTableId());
            if (buffRow.isEmpty()) {
                continue;
            }
            BuffTable row = buffRow.get();
            int buffType = row.getBuffType();
            if (buffType == BattleConstants.BUFF_TYPE_STUN || buffType == BattleConstants.BUFF_TYPE_FREEZE
                    || buffType == BattleConstants.BUFF_TYPE_SILENCE) {
                continue;
            }
            if (row.getInfiniteDuration() == 0 && row.getDuration() <= 0) {
                continue;
            }
            BattleBuffEntry.Builder entry = buff.toBuilder();
            if (entry.getCasterId() != 0 && ctx.findActor(entry.getCasterId()) == null) {
                entry.setCasterId(0);
            }
            if (row.getInfiniteDuration() == 0) {
                int tableRounds = BattleRules.roundsFromSeconds(row.getDuration());
                if (entry.getRemainRounds() == 0 || Integer.compareUnsigned(entry.getRemainRounds(), tableRounds) > 0) {
                    entry.setRemainRounds(tableRounds);
                }
            }
            kept.add(entry.build());
        }
        actor.state().clearBuffs();
        actor.state().addAllBuffs(kept);
    }
}
