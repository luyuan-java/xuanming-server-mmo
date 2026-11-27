package com.game.common.id;

import java.util.OptionalLong;

/**
 * 永久业务 ID 预留记录的单份独立存储接口（主记录、副记录或全局登记处水位）。
 * <p>语义铁律：水位单调不减；底层存储不可用时必须抛异常，绝不静默返回 0 伪装成全新号源。
 */
public interface IdReservationStore {

    /** 存储唯一标识（例如主/副 etcd 集群标识或数据库实例标识），用于全局登记处绑定校验。 */
    String storeId();

    /** 读取指定地域码当前已确认的预留上界（未初始化返回 empty；存储故障抛异常）。 */
    OptionalLong readWatermark(int regionCode);

    /**
     * 将指定地域码的预留上界单调抬升至 {@code Math.max(current, newUpperBound)}。
     * @return 写入后该存储确认的水位（必须 >= newUpperBound）
     */
    long raiseWatermark(int regionCode, long newUpperBound);
}
