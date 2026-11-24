package com.game.gm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class MailCampaignManifestTest {

    @Test
    void 去重按uint64升序分片并生成与邮件服一致的v1摘要() throws Exception {
        MailCampaignManifest.Plan plan = MailCampaignManifest.build(
                List.of("100", "2", "18446744073709551615", "2", "10"));
        assertThat(plan.recipientCount()).isEqualTo(4);
        assertThat(plan.shards()).hasSize(1);
        assertThat(plan.shards().get(0)).containsExactly("2", "10", "100", "18446744073709551615");

        String shardHash = sha256("2\n10\n100\n18446744073709551615\n");
        String expectedManifest = sha256("v1\n0:" + shardHash + ":4\n");
        assertThat(plan.manifestHash()).isEqualTo(expectedManifest);
    }

    @Test
    void 超过1000人自动分片且拒绝零值与越界ID() {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 1005; i++) {
            ids.add(Integer.toString(i));
        }
        MailCampaignManifest.Plan plan = MailCampaignManifest.build(ids);
        assertThat(plan.recipientCount()).isEqualTo(1005);
        assertThat(plan.shards()).hasSize(2);
        assertThat(plan.shards().get(0)).hasSize(1000);
        assertThat(plan.shards().get(1)).hasSize(5);

        assertThatThrownBy(() -> MailCampaignManifest.build(List.of("0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MailCampaignManifest.build(List.of("18446744073709551616")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
