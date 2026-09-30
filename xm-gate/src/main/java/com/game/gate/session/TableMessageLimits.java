package com.game.gate.session;

import com.game.table.MessageLimiterTable;
import com.game.table.MessageLimiterTableData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 从导表器产物 {@code messagelimiter.pb}（MessageLimiter 表，与 mmorpg 同一份配表）构建 {@link MessageLimits}：
 * 表里有的消息号按表（{@code max_requests} 条 / {@code time_window} 秒），其余用 C++ 缺省每秒 3 条。
 * 只读这一个文件，不碰全局表管理器单例；启动时读一次，之后只读。
 */
public final class TableMessageLimits {

    static final String FILE_NAME = "messagelimiter.pb";

    private TableMessageLimits() {
    }

    /**
     * @throws IllegalStateException 文件不存在 / 读不了 / 解析失败 / 行非法：拒绝启动（fail-fast）
     */
    public static MessageLimits load(Path tableDir) {
        Path file = tableDir.resolve(FILE_NAME).toAbsolutePath().normalize();
        MessageLimiterTableData data;
        try {
            data = MessageLimiterTableData.parseFrom(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IllegalStateException("读不了 MessageLimiter 表: " + file
                    + "（进程需从仓库根目录启动，或设置 xm.table-dir）", e);
        }
        Map<Integer, MessageLimit> overrides = new HashMap<>();
        for (MessageLimiterTable row : data.getDataList()) {
            if (row.getMaxRequests() <= 0 || row.getTimeWindow() <= 0) {
                throw new IllegalStateException("MessageLimiter 表行非法 id=" + row.getId()
                        + " max_requests=" + row.getMaxRequests() + " time_window=" + row.getTimeWindow());
            }
            overrides.put(row.getId(), new MessageLimit(row.getMaxRequests(), Duration.ofSeconds(row.getTimeWindow())));
        }
        return MessageLimits.of(overrides);
    }
}
