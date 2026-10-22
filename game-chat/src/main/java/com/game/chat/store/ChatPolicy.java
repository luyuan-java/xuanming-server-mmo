package com.game.chat.store;

import com.game.chat.ChatProperties;
import com.game.common.deadline.Deadline;
import com.game.discovery.sql.AuthorityJdbc;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Base64;
import javax.sql.DataSource;

/** 一条正规SQL策略原件固定全部副本实际使用的规则，不从Redis或旧进程补建历史权限。 */
public final class ChatPolicy {
    private final byte[] original;

    public ChatPolicy(ChatProperties rules) {
        var text = new StringBuilder("chat-sql-v2\n")
                .append(ChatProperties.MAX_PULL_MESSAGES).append('\n')
                .append(rules.maxContentBytes()).append('\n').append(rules.rateLimitPerSecond()).append('\n')
                .append(rules.historyMaxEntries()).append('\n').append(rules.historyTtl().toSeconds()).append('\n')
                .append(rules.historyDefaultLimit()).append('\n').append(rules.historyMaxLimit()).append('\n')
                .append(rules.requestBudget().toMillis()).append('\n');
        // 敏感词保持原顺序；无分隔符的字节编码防词内换行/特殊字符产生指纹歧义。
        for (String word : rules.sensitiveWords()) {
            text.append(Base64.getEncoder().encodeToString(word.getBytes(StandardCharsets.UTF_8))).append('\n');
        }
        original = text.toString().getBytes(StandardCharsets.UTF_8);
    }

    public void initialize(DataSource source) throws SQLException {
        new AuthorityJdbc(source).tx(Deadline.after(3000), transaction -> {
            byte[] saved = transaction.one("SELECT original_contract FROM chat_policy WHERE id=1", row -> row.getBytes(1));
            if (saved == null) {
                if (transaction.one("SELECT player_id FROM chat_send_authority LIMIT 1", row -> row.getString(1)) != null) {
                    throw new SQLException("已有聊天历史却缺策略原件，不能用当前配置补权");
                }
                transaction.update("INSERT INTO chat_policy(id,original_contract) VALUES(1,?) ON DUPLICATE KEY UPDATE id=id", original);
            }
            require(transaction);
            return null;
        });
    }

    public void require(AuthorityJdbc.Session transaction) throws SQLException {
        byte[] saved = transaction.one("SELECT original_contract FROM chat_policy WHERE id=1", row -> row.getBytes(1));
        if (!Arrays.equals(original, saved)) throw new SQLException("聊天副本的实际策略与SQL永久原件不符");
    }
}
