package com.game.guild.store;

import java.util.Arrays;
import java.util.Locale;

/**
 * 数据库版本下限（基线 server_version.go:44-87；guild-spec §1.1）。纯函数，任意线程可调。
 *
 * <p>本包的「不成环」推演只对两类库成立：TiDB 悲观事务 + RC，以及 MySQL 8.0.29 起的 InnoDB + RC。MySQL 的下限来自审批通过在
 * guild_member 上重插删除标记的成员行时的 S → X 升级：8.0.29 修了 InnoDB Bug #11745929（已持 S|REC_NOT_GAP 的事务申请 X 时可以越过
 * 他人排队中的 X），之前的版本这一处升级会成环。代码改不了，只能在启动期拒绝更老的版本。
 *
 * <p>判定：含 {@code tidb}（不分大小写）放行（它的 "8.0.11-TiDB-…" 前缀只是兼容协议号）；含 {@code mariadb} 拒绝（InnoDB 分叉已久，
 * 推演不适用）；否则取开头连续的「数字与点」，至少三段、前三段都是十进制数，低于 8.0.29 或解析不出一律拒绝（fail-closed）。
 */
public final class ServerVersion {

    /** MySQL 的最低版本（主, 次, 修订）。 */
    static final int[] MIN_MYSQL = {8, 0, 29};

    private static final String WHY = "审批通过重新插入删除标记的成员行时要做 S → X 升级，不成环依赖 InnoDB Bug #11745929 的修复"
            + "（8.0.29 起，已持 S|REC_NOT_GAP 的事务申请 X 可越过他人排队中的 X）";

    private ServerVersion() {
    }

    /**
     * 校验 {@code SELECT VERSION()} 的结果。
     *
     * @return null = 通过；否则是拒绝原因（含版本下限 8.0.29，调用方据此拒启）
     */
    public static String rejection(String version) {
        String raw = version == null ? "" : version;
        String trimmed = raw.strip();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains("tidb")) {
            return null;
        }
        String min = MIN_MYSQL[0] + "." + MIN_MYSQL[1] + "." + MIN_MYSQL[2];
        if (lower.contains("mariadb")) {
            return "数据库版本 \"" + raw + "\" 是 MariaDB，帮会服务的取锁推演只对 MySQL " + min + "+ 与 TiDB 成立，拒绝启动";
        }
        int[] got = parseMySqlVersion(trimmed);
        if (got == null) {
            return "无法从数据库版本 \"" + raw + "\" 解析出 MySQL 的主.次.修订号，按低于 " + min + " 处理并拒绝启动（fail-closed）：" + WHY;
        }
        if (Arrays.compare(got, MIN_MYSQL) < 0) {
            return "MySQL 版本 \"" + raw + "\" 低于 " + min + "，拒绝启动：" + WHY;
        }
        return null;
    }

    /**
     * 取版本串开头连续的「数字与点」那一段，要求至少三段、前三段都是十进制数；其后的后缀（-0ubuntu0.22.04.1、-log 等）忽略。
     *
     * @return {主, 次, 修订}；解析不出返回 null
     */
    static int[] parseMySqlVersion(String version) {
        int end = 0;
        while (end < version.length()) {
            char ch = version.charAt(end);
            if (ch != '.' && (ch < '0' || ch > '9')) {
                break;
            }
            end++;
        }
        String[] parts = version.substring(0, end).split("\\.", -1);
        if (parts.length < 3) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 9) {
                return null;
            }
            out[i] = Integer.parseInt(parts[i]);
        }
        return out;
    }
}
