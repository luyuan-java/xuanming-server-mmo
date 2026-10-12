package com.game.discovery.namespace;

import com.game.common.GameNamespace;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * 部署命名空间拒启的标准报告（Spring Boot 的 {@code APPLICATION FAILED TO START} 格式：Description 写原值与来源，Action 写怎么改）。
 * 登记在 game-discovery 的 {@code META-INF/spring.factories}（键 {@code org.springframework.boot.diagnostics.FailureAnalyzer}）。
 * EnvironmentPostProcessor 阶段的失败发生在日志系统初始化之前，靠它把信息带出来。
 */
public class GameNamespaceFailureAnalyzer extends AbstractFailureAnalyzer<InvalidGameNamespaceException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidGameNamespaceException cause) {
        return new FailureAnalysis(description(cause), action(cause), cause);
    }

    static String description(InvalidGameNamespaceException cause) {
        return cause.getMessage();
    }

    static String action(InvalidGameNamespaceException cause) {
        if (cause.isConflict()) {
            return "本进程的部署命名空间在 Spring 解析配置之前就已经定下了，或同一进程里出现了两个不同的值。让系统属性 " + GameNamespace.PROPERTY
                    + "、环境变量 " + GameNamespace.ENV + " 与配置文件取同一个值（建议只经环境变量 " + GameNamespace.ENV + " 注入，不写进 yaml）；"
                    + "main() 在 SpringApplication.run 之前不得触碰 RedisKeys / KafkaNames / AuditTopics 等名字类。";
        }
        return "把 " + GameNamespace.PROPERTY + "（环境变量 " + GameNamespace.ENV + "）改成合法值：匹配 " + GameNamespace.PATTERN_TEXT
                + "（小写字母开头，只含小写字母与数字，至多 " + GameNamespace.MAX_LENGTH + " 个字符），且不是 mysql / sys / public；不设则为缺省 "
                + GameNamespace.DEFAULT + "。全部进程与命令行工具必须同值；compose 里写 ${" + GameNamespace.ENV + ":-" + GameNamespace.DEFAULT
                + "}，不要写 ${" + GameNamespace.ENV + ":-}（空串会被拒绝）。";
    }
}
