package com.game.gate.session;

import com.game.table.ConfigTables;
import com.game.table.MessageLimiterRows;
import com.game.table.MessageLimiterTable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 从 MessageLimiter 表（与 mmorpg 同一份配表）构建 {@link MessageLimits}：
 * 表里有的消息号按表（{@code max_requests} 条 / {@code time_window} 秒），其余用 C++ 缺省每秒 3 条。
 * 启动时读一次，之后只读。
 */
public final class TableMessageLimits {

    private TableMessageLimits() {
    }

    /**
     * 加载配置表目录（整体校验：manifest、sha256、外键，见 {@link ConfigTables#load}）并取 MessageLimiter 表。
     *
     * @throws com.game.table.load.TableLoadException 目录不存在 / 数据不完整或被改动：拒绝启动（fail-fast）
     * @throws IllegalStateException                  表里有非法行
     */
    public static MessageLimits load(Path tableDir) {
        return from(ConfigTables.load(tableDir).messageLimiter());
    }

    static MessageLimits from(MessageLimiterRows table) {
        Map<Integer, MessageLimit> overrides = new HashMap<>();
        for (MessageLimiterTable row : table.all()) {
            if (row.getMaxRequests() <= 0 || row.getTimeWindow() <= 0) {
                throw new IllegalStateException("MessageLimiter 表行非法 id=" + row.getId()
                        + " max_requests=" + row.getMaxRequests() + " time_window=" + row.getTimeWindow());
            }
            overrides.put(row.getId(), new MessageLimit(row.getMaxRequests(), Duration.ofSeconds(row.getTimeWindow())));
        }
        return MessageLimits.of(overrides);
    }
}
