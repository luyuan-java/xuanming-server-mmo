package com.game.common.team;

import com.game.common.player.PlayerRouteAuthority;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;

/** Team与Match共用同一SQL策略原件；硬规则、allowCrossZone与pvpRatingGapMax不能各造一份配置真相。 */
public final class TeamPolicyAuthority {
    private static final String GAP_MARKER = "|pvp-rating-gap-max=";
    private TeamPolicyAuthority(){}

    public record Contract(byte[] original, boolean allowCrossZone, int pvpRatingGapMax) {
        public Contract(byte[] original, boolean allowCrossZone) {
            this(original, allowCrossZone, TeamPolicyContract.DEFAULT_PVP_RATING_GAP_MAX);
        }
        public Contract {
            original = original.clone();
            if (pvpRatingGapMax < 0) {
                throw new IllegalArgumentException("pvpRatingGapMax 不能为负: " + pvpRatingGapMax);
            }
        }
        @Override public byte[] original() { return original.clone(); }
    }

    public static byte[] canonicalContract(boolean allow) {
        return canonicalContract(allow, TeamPolicyContract.DEFAULT_PVP_RATING_GAP_MAX);
    }

    public static byte[] canonicalContract(boolean allow, int pvpRatingGapMax) {
        if (pvpRatingGapMax < 0) {
            throw new IllegalArgumentException("pvpRatingGapMax 不能为负: " + pvpRatingGapMax);
        }
        return (TeamPolicyContract.hardRules() + "|allow-cross-zone=" + allow + GAP_MARKER + pvpRatingGapMax)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] digest(byte[] raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int parseRatingGap(byte[] raw) throws SQLException {
        String text = new String(raw, StandardCharsets.UTF_8);
        int idx = text.indexOf(GAP_MARKER);
        if (idx < 0) {
            throw new SQLException("Team策略缺少 pvp-rating-gap-max 指纹段");
        }
        try {
            int gap = Integer.parseInt(text.substring(idx + GAP_MARKER.length()));
            if (gap < 0) {
                throw new SQLException("Team策略 pvp-rating-gap-max 为负");
            }
            return gap;
        } catch (NumberFormatException e) {
            throw new SQLException("Team策略 pvp-rating-gap-max 格式无效", e);
        }
    }

    public static Contract load(Connection c, int timeout) throws SQLException {
        try (var q = c.prepareStatement("SELECT contract,allow_cross_zone,contract_digest FROM team_policy WHERE id=1")) {
            q.setQueryTimeout(timeout);
            try (var r = q.executeQuery()) {
                if (!r.next()) throw new SQLException("缺少Team初次SQL准入策略原件");
                String flag = r.getString(2);
                byte[] raw = r.getBytes(1), hash = r.getBytes(3);
                if (!"0".equals(flag) && !"1".equals(flag) || raw == null || hash == null || hash.length != 32) {
                    throw new SQLException("Team策略typed投影或摘要缺失");
                }
                boolean allow = "1".equals(flag);
                int gap = parseRatingGap(raw);
                if (!MessageDigest.isEqual(hash, digest(raw)) || !Arrays.equals(raw, canonicalContract(allow, gap))) {
                    throw new SQLException("Team策略与唯一硬规则/实际跨区/评分差投影不符");
                }
                return new Contract(raw, allow, gap);
            }
        }
    }

    /** 仅Team正规初始化首次插入；有原件只能逐字核对，不覆盖、不替缺typed投影补权。 */
    public static void ensure(Connection c, boolean actualAllow, int timeout) throws SQLException {
        ensure(c, actualAllow, TeamPolicyContract.DEFAULT_PVP_RATING_GAP_MAX, timeout);
    }

    public static void ensure(Connection c, boolean actualAllow, int actualRatingGapMax, int timeout) throws SQLException {
        if (c.getAutoCommit()) throw new SQLException("Team初次策略需要事务");
        if (actualRatingGapMax < 0) throw new IllegalArgumentException("pvpRatingGapMax 不能为负: " + actualRatingGapMax);
        PlayerRouteAuthority.requireMeta(c, timeout, true);
        byte[] raw = canonicalContract(actualAllow, actualRatingGapMax);
        try (var q = c.prepareStatement("INSERT INTO team_policy(id,contract,allow_cross_zone,contract_digest) VALUES(1,?,?,?) ON DUPLICATE KEY UPDATE id=id")) {
            q.setQueryTimeout(timeout);
            q.setBytes(1, raw);
            q.setInt(2, actualAllow ? 1 : 0);
            q.setBytes(3, digest(raw));
            q.executeUpdate();
        }
        var original = load(c, timeout);
        if (original.allowCrossZone() != actualAllow || original.pvpRatingGapMax() != actualRatingGapMax
                || !Arrays.equals(original.original(), raw)) {
            throw new SQLException("Team副本实际配置与初次准入策略不同");
        }
    }
}
