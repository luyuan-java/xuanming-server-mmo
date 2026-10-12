package com.game.guild.service;

/**
 * 服务层判定的故障（基线 {@code status.Error(codes.Internal | Unavailable, reason)}，guild-spec §0.6 第 1 条）：配表缺行、
 * 双存储矛盾（{@code LEADER_MISMATCH} / {@code LEVEL_CONFIG_MISSING}）。派发器把它与其它运行期异常（依赖故障、存储内部错误）一样回
 * <b>信封 1003</b>（不带 parameters）并记 ERROR，客户端随之停用帮会模块直到重连——这些都不是玩家重试能修的。
 *
 * <p>消息是基线的 gRPC 错误文案（如 {@code "GuildRule row 1 missing"}），只进日志。
 */
public final class GuildFaultException extends RuntimeException {

    public GuildFaultException(String message) {
        super(message);
    }
}
