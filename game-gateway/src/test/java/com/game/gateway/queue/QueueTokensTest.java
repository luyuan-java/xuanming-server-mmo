package com.game.gateway.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** 排队令牌：往返、过期、篡改、换密钥、格式不对都验不过。 */
class QueueTokensTest {

    private final QueueTokens tokens = new QueueTokens("gate-secret".getBytes(StandardCharsets.UTF_8));
    private final String queueId = QueueTokens.newQueueId();

    @Test
    void 往返_到期那一秒仍有效_之后无效() {
        String token = tokens.sign(queueId, 3, 1_000);
        assertThat(tokens.verify(token, 1_000)).contains(new QueueTokens.Claims(queueId, 3, 1_000));
        assertThat(tokens.verify(token, 1_001)).isEmpty();
    }

    @Test
    void 篡改_换密钥_格式不对都无效() {
        String token = tokens.sign(queueId, 3, 1_000);
        String body = token.substring(0, token.indexOf('.'));
        String forgedBody = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("v1." + queueId + ".4.1000").getBytes(StandardCharsets.US_ASCII));
        assertThat(tokens.verify(forgedBody + token.substring(token.indexOf('.')), 0)).as("改区").isEmpty();
        assertThat(new QueueTokens("other".getBytes(StandardCharsets.UTF_8)).verify(token, 0)).as("换密钥").isEmpty();
        assertThat(tokens.verify(body, 0)).as("缺签名").isEmpty();
        assertThat(tokens.verify(token + ".x", 0)).isEmpty();
        assertThat(tokens.verify("", 0)).isEmpty();
        assertThat(tokens.verify(null, 0)).isEmpty();
        assertThat(tokens.verify("!!!.???", 0)).isEmpty();
        assertThat(tokens.verify("a".repeat(300), 0)).isEmpty();
        assertThat(tokens.verify(tokens.sign("not-a-uuid", 3, 1_000), 0)).isEmpty();
        assertThat(tokens.verify(tokens.sign(queueId, 0, 1_000), 0)).as("区号 0").isEmpty();
    }
}
