package com.game.robot.client;

/**
 * 探针流程里的一次失败：服务端行为不符合契约、超时、连接被关、HTTP 出错等。消息是给人看的中文，
 * 直接进最终汇总，所以要写清「哪一步、期望什么、实际是什么」。
 */
public final class RobotException extends Exception {

    public RobotException(String message) {
        super(message);
    }

    public RobotException(String message, Throwable cause) {
        super(message, cause);
    }
}
