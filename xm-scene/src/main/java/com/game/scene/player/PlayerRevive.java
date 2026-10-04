package com.game.scene.player;

/**
 * 基础复活的纯规则（基线 {@code player_revive.h} ApplyClassInitialAttributesOrRevive / ReviveBaseAttributesIfDead 的气血法力部分）：
 * 活着（气血 ≠ 0，含残血）一律不动；阵亡（气血 0）按上限回满。进场加载用它（新号与 2.7 之前的存档没有记录，等同气血 0），
 * 回合制战斗结算（路线图 6.3）把人打到 0 血后同样用它。
 *
 * <p>基线还会在上限传 0 时退回职业 1 级初值、给全新号写一套成长属性（力量 / 护甲 / 抗性 / 暴击 / 速度）。Java 不存成长属性
 * （用到时由职业表即时算出），气血上限恒 ≥ 1，所以只保留「上限为 0 的那一项不动」：回合制结算复活（6.3）时法力上限为 0 的职业
 * 不会回到初值 800（基线会）；进场加载两版都是 0（基线先回 800、随后 RescaleCurrent 按上限 0 归零）。现表各职业法力上限都 &gt; 0，走不到。
 * 不要在这里加 800 兜底：Java 是先夹再复活，加了会让登录给出基线没有的 800。
 */
public final class PlayerRevive {

    /** 规则的结果。 */
    public record Result(long health, long mana, boolean revived) {
    }

    private PlayerRevive() {
    }

    /**
     * @param health    当前气血（uint64，调用方已饱和到 long）
     * @param maxHealth 气血上限（二级属性）
     * @param maxMana   法力上限（二级属性）
     */
    public static Result reviveIfDead(long health, long mana, long maxHealth, long maxMana) {
        if (health != 0) {
            return new Result(health, mana, false);
        }
        return new Result(maxHealth, maxMana > 0 ? maxMana : mana, true);
    }
}
