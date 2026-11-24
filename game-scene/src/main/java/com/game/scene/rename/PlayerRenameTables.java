package com.game.scene.rename;

import com.game.common.text.GoSpaces;
import com.game.table.ConfigTables;
import com.game.table.RoleNameRuleTable;
import java.text.Normalizer;
import java.util.Objects;

/**
 * 角色改名配置表视图（RoleNameRule 表 id=1 + Item 表存在性核验）与纯角色名归一化校验。
 * 与 C++ {@code player_rename_rules.h} 及 {@code game-login} {@code PlayerNames} 保持同一口径。
 */
public final class PlayerRenameTables {

    public static final int ROW_ID = 1;
    public static final int STRUCTURAL_MAX_CHARS = 32;
    public static final long MAX_DURATION_SECONDS = 365L * 86_400L;

    private static final int IDEOGRAPHIC_ZERO = 0x3007;
    private static final int CJK_EXT_A_FIRST = 0x3400;
    private static final int CJK_EXT_A_LAST = 0x4DBF;
    private static final int CJK_BASIC_FIRST = 0x4E00;
    private static final int CJK_BASIC_LAST = 0x9FFF;

    public record Rule(
            int minChars,
            int maxChars,
            long cooldownSeconds,
            long oldNameHoldSeconds,
            int renameItemId,
            long renameItemCount) {

        public long cooldownMs() {
            return cooldownSeconds * 1_000L;
        }

        public long oldNameHoldMs() {
            return oldNameHoldSeconds * 1_000L;
        }
    }

    private final Rule rule;

    private PlayerRenameTables(Rule rule) {
        this.rule = Objects.requireNonNull(rule, "rule");
    }

    /** 从 ConfigTables 载入并做 fail-closed 校验（缺表、越界或改名卡道具不存在即拒启）。 */
    public static PlayerRenameTables from(ConfigTables tables) {
        Objects.requireNonNull(tables, "tables");
        RoleNameRuleTable row = tables.roleNameRule().find(ROW_ID).orElseThrow(
                () -> new IllegalStateException("RoleNameRule 表缺少 id=" + ROW_ID + " 的规则行"));
        long minChars = Integer.toUnsignedLong(row.getMinChars());
        long maxChars = Integer.toUnsignedLong(row.getMaxChars());
        if (minChars < 1 || maxChars > STRUCTURAL_MAX_CHARS || minChars > maxChars) {
            throw new IllegalStateException("RoleNameRule min_chars/max_chars 越界: " + minChars + ".." + maxChars);
        }
        long cooldownSec = Integer.toUnsignedLong(row.getRenameCooldownSeconds());
        long holdSec = Integer.toUnsignedLong(row.getOldNameHoldSeconds());
        if (cooldownSec > MAX_DURATION_SECONDS || holdSec > MAX_DURATION_SECONDS) {
            throw new IllegalStateException("RoleNameRule 冷却或保护时长超过 365 天: cooldown="
                    + cooldownSec + " hold=" + holdSec);
        }
        int itemId = row.getRenameItemId();
        long itemCount = Integer.toUnsignedLong(row.getRenameItemCount());
        if (itemId == 0 || itemCount < 1) {
            throw new IllegalStateException("RoleNameRule 改名卡配置非法: item_id=" + itemId + " count=" + itemCount);
        }
        if (tables.item().find(itemId).isEmpty()) {
            throw new IllegalStateException("RoleNameRule.rename_item_id=" + itemId + " 在 Item 表中不存在");
        }
        return new PlayerRenameTables(new Rule((int) minChars, (int) maxChars, cooldownSec, holdSec, itemId, itemCount));
    }

    public Rule rule() {
        return rule;
    }

    /**
     * 按与建角一致的口径归一化角色名（NFKC → 去首尾空白 → 长度在 [minChars, maxChars] → 字符集白名单）。
     * 不合规返回 {@code null}。
     */
    public String normalizeAndValidate(String raw) {
        if (raw == null || hasUnpairedSurrogate(raw)) {
            return null;
        }
        String trimmed = GoSpaces.trim(Normalizer.normalize(raw, Normalizer.Form.NFKC));
        if (trimmed.isEmpty()) {
            return null;
        }
        int chars = trimmed.codePointCount(0, trimmed.length());
        if (chars < rule.minChars() || chars > rule.maxChars()) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); ) {
            int cp = trimmed.codePointAt(i);
            if (!isAllowedCodePoint(cp)) {
                return null;
            }
            i += Character.charCount(cp);
        }
        return trimmed;
    }

    public static boolean isAllowedCodePoint(int cp) {
        return (cp >= '0' && cp <= '9')
                || (cp >= 'A' && cp <= 'Z')
                || (cp >= 'a' && cp <= 'z')
                || cp == IDEOGRAPHIC_ZERO
                || (cp >= CJK_EXT_A_FIRST && cp <= CJK_EXT_A_LAST)
                || (cp >= CJK_BASIC_FIRST && cp <= CJK_BASIC_LAST);
    }

    private static boolean hasUnpairedSurrogate(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }
}
