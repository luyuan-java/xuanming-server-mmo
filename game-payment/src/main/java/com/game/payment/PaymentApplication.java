package com.game.payment;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 充值支付微服务启动入口（Dubbo group {@code payment} + HTTP 回调 {@code /notify/{channel}}）。 */
@SpringBootApplication
@EnableDubbo(scanBasePackages = "com.game.payment.rpc")
public class PaymentApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
