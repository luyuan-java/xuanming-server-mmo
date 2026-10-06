package com.game.match;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * xm-match 进程入口（批次 6.4，docs/porting/match-spec.md §9.1）：对外提供 Dubbo 服务（Triple，缺省端口 20888，group {@code match}）——
 * gate 转来的 {@code MatchService} 的 10 个消息号（排队 157 / 148 / 153、切磋 152 / 151 / 156 / 154、补签 179、观战 163 / 164 的临时应答）、
 * xm-team 调的 {@code MatchTeamService}（整队开战）、xm-guild 调的 {@code MatchInternalService}（帮会活动开战）；管理端口（缺省 18113，只绑本机）上
 * 是 actuator 与 dev / test 专用的 {@code /admin/match/dev/*}。全服一份、可多副本：排队与票据在 Redis（一个 hash tag {@code {match}}），
 * 评分在 MySQL {@code xm_java} 的两张表，对局结果经 Kafka topic {@code xm-battle-result-g<代次>} 从 xm-battle 回流。
 *
 * <p>包结构按职责分（各包自带自己的 {@code XxxConfiguration}，{@link MatchConfiguration} 只放基础设施）：
 * {@code dispatch}（客户端入口派发）、{@code queue} / {@code ticket}（排队与票据）、{@code matcher}（凑单）、{@code gather} / {@code placement} /
 * {@code reissue}（开局管线、落点记录、补签）、{@code rating}（评分与结果消费）、{@code challenge}（切磋）、{@code precheck} / {@code team} /
 * {@code activity}（点名开局入口）。包与包之间只经各包的接口往来（清单见 {@code MatchConfiguration} 的类注释）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableDubbo(scanBasePackages = "com.game.match")
public class MatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatchApplication.class, args);
    }
}
