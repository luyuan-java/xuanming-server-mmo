package com.game.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MallPropertiesTest {
    @Test
    void 缺密钥相同密钥与越界配置拒绝() {
        assertThatThrownBy(() -> new MallProperties(null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MallProperties(false, "short", MallTestDatabase.PAYMENT_KEY, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MallProperties(false, MallTestDatabase.GATEWAY_KEY, MallTestDatabase.GATEWAY_KEY, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MallProperties(false, MallTestDatabase.GATEWAY_KEY, MallTestDatabase.PAYMENT_KEY, "bad/subject", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MallProperties(false, MallTestDatabase.GATEWAY_KEY, MallTestDatabase.PAYMENT_KEY, null, 101)).isInstanceOf(IllegalArgumentException.class);
        MallProperties defaults = new MallProperties(null, MallTestDatabase.GATEWAY_KEY, MallTestDatabase.PAYMENT_KEY, null, null);
        assertThat(defaults.requireTidb()).isTrue();
        assertThat(defaults.maxUnpaidOrders()).isEqualTo(5);
        assertThat(defaults.paymentSubject()).isEqualTo("payment-adapter");
    }
}
