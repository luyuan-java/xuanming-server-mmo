package com.game.robot.scenario;

import com.game.robot.client.RobotException;

/** 某个服务管理端口的 Prometheus 文本（配合 {@code AdminClient.sum} 取值）。抓不到抛出。 */
@FunctionalInterface
public interface MetricsSource {

    String scrape() throws RobotException;
}
