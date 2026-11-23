package com.game.scene.skill;

import com.game.battle.engine.ClassSkillRules;
import com.game.battle.engine.SupportEffectRules;
import com.game.proto.SupportEffectBreakdown;
import com.game.proto.SupportEffectSource;
import com.game.proto.SupportEffectSummary;
import com.game.scene.player.PlayerLevels;
import com.game.scene.world.ScenePlayer;

/** 从同一场景线程上的穿戴投影和已学技能计算人物治疗与独立容量盾。 */
public final class CharacterSupport {
    private CharacterSupport() {}

    private static final class Selection {
        private double gain, loss;
        private int gainIndex = -1, lossIndex = -1;
        void consider(double value, int index) {
            if (Double.isFinite(value) && value > gain) { gain = value; gainIndex = index; }
            if (Double.isFinite(value) && value < loss) { loss = value; lossIndex = index; }
        }
        double total() { return gain + loss; }
        void project(SupportEffectBreakdown.Builder output) {
            for (int index : new int[] {gainIndex, lossIndex}) if (index >= 0) {
                var source = output.getSourcesBuilder(index);
                source.setEffectiveBonusBps(source.getRawBonusBps()).clearReason();
            }
        }
    }

    public static SupportEffectSummary build(ScenePlayer player) {
        var equipment = player.attributes().equipment().effects();
        var summary = SupportEffectRules.begin(equipment).setInBattle(player.inBattle());
        var catalog = player.skillCatalog();
        var owned = player.learnedSkills();
        int level = PlayerLevels.effective(player.level());
        boolean complete = equipment.getSupportSourcesComplete() && !player.inBattle();
        var healing = new Selection();
        var shield = new Selection();
        var mana = new Selection();
        double legacyHeal = 0;
        for (var entry : owned.entries().stream().sorted((a,b) -> Integer.compareUnsigned(a.getSkillId(), b.getSkillId())).toList()) {
            int id = entry.getSkillId();
            var skill = catalog.row(id);
            if (skill == null) {
                if (entry.getRank() != 0 && !SupportEffectRules.retiredSkill(id)) complete = false;
                continue;
            }
            int rank = catalog.effectiveRank(player.classId(), level, owned, id);
            if (rank == 0) continue;
            if (ClassSkillRules.parsePassiveStat(skill.getPassiveStat()) == ClassSkillRules.PassiveStat.OUTGOING_HEAL) {
                double value = skill.getPassivePercent() * ClassSkillRules.passiveRankFactor(rank);
                legacyHeal += value;
                summary.getHealingBuilder().addSources(SupportEffectSource.newBuilder().setKind(2).setEffectKind(1)
                        .setSkillTableId(id).setName(skill.getName()).setRawBonusBps(value * 10000)
                        .setEffectiveBonusBps(value * 10000).setInfinite(true));
            }
            if (skill.getBattleModel() != 1 || skill.getAbilityKind() != 2) continue;
            for (int buffId : skill.getInitEffectList()) {
                var buff = catalog.buff(buffId);
                if (buff == null) { complete = false; continue; }
                if (buff.getBattleOp() != 6 || buff.getTriggerEvent() != 0) continue;
                int stat = buff.getModifierStat();
                if (stat == 9) { mana.consider(buff.getRatio(), -1); continue; }
                if (stat != 8 && stat != 17) continue;
                var part = stat == 17 ? summary.getShieldBuilder() : summary.getHealingBuilder();
                (stat == 17 ? shield : healing).consider(buff.getRatio(), part.getSourcesCount());
                part.addSources(SupportEffectSource.newBuilder().setKind(2).setEffectKind(stat == 17 ? 2 : 1)
                        .setName(skill.getName()).setSkillTableId(id).setRawBonusBps(buff.getRatio() * 10000)
                        .setInfinite(buff.getInfiniteDuration() != 0).setReason("同类更强修正优先生效"));
            }
        }
        healing.project(summary.getHealingBuilder());
        shield.project(summary.getShieldBuilder());
        double healBonus = legacyHeal + healing.total() + equipment.getOutgoingHealBps() / 10000.0;
        double shieldBonus = shield.total() + equipment.getShieldCapacityBps() / 10000.0;
        var rows = catalog.classRows(player.classId()).stream().filter(r -> ClassSkillCatalog.forSubject(r, 1)).toList();
        for (var skill : rows) for (int id : skill.getEffectList()) if (catalog.buff(id) == null) complete = false;
        summary.getHealingBuilder().setTotalBonusBps(healBonus * 10000).setComplete(complete);
        summary.getShieldBuilder().setTotalBonusBps(shieldBonus * 10000).setComplete(complete);
        for (var skill : rows) {
            int rank = catalog.effectiveRank(player.classId(), level, owned, skill.getId());
            int category = SupportEffectRules.manaCategory(skill, catalog::buff);
            double discount = equipment.getManaReductionBpsOrDefault(category, 0) - mana.total() * 10000;
            for (int id : skill.getEffectList()) {
                var buff = catalog.buff(id);
                if (buff == null) continue;
                var preview = SupportEffectRules.preview(skill, buff, level, rank, player.attributes().derived().maxMana(),
                        buff.getBattleOp() == 9 ? shieldBonus : healBonus, discount, complete);
                if (preview.isPresent()) { summary.addPreviews(preview.get()); break; }
            }
        }
        return summary.build();
    }
}
