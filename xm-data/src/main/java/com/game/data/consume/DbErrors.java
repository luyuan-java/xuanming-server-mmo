package com.game.data.consume;

import java.sql.SQLException;

/**
 * 落库异常分类。只有明确是「这一行的数据有问题」（SQLState 22 数据异常 / 23 完整性约束）才算数据错误、可以逐行隔离后跳过；
 * 其余一律当可恢复故障（库不可达、锁超时、死锁、表还没建、配置错……）：暂停消费、不提交位点、原批次重试——宁可积压不丢。
 */
public final class DbErrors {

    private DbErrors() {
    }

    public static boolean isDataError(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                if (state.startsWith("22") || state.startsWith("23")) {
                    return true;
                }
            }
        }
        return false;
    }
}
