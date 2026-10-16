package com.game.mall;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class UpstreamProvenanceTest {
    @Test
    void 固定来源许可与修改后源码哈希随包保留() throws Exception {
        Properties provenance = new Properties();
        try (var input = getClass().getResourceAsStream("/META-INF/UPSTREAM.properties")) {
            assertThat(input).isNotNull();
            provenance.load(input);
        }
        assertThat(provenance.getProperty("upstream.commit")).isEqualTo("dcaa93b3150352e5044708d7211b5bed0af4509f");
        try (var input = getClass().getResourceAsStream("/META-INF/LICENSE")) {
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).contains("Apache License", "Version 2.0");
        }
        for (String model : new String[]{"OmsOrder", "PmsSkuStock"}) {
            String source = Files.readString(Path.of(provenance.getProperty("model." + model + ".adapted.path")), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertThat(source).contains("https://github.com/macrozheng/mall/blob/", provenance.getProperty("upstream.commit"), "Swagger");
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))))
                    .isEqualToIgnoringCase(provenance.getProperty("model." + model + ".adapted.sha256"));
        }
        assertThat(provenance.getProperty("model.OmsOrder.upstream.sha256")).isEqualToIgnoringCase("70516FEC9A0D6E51B13AFBBCC2D9E0E9200AD93B968666E570CEAA36C760A87E");
        assertThat(provenance.getProperty("model.PmsSkuStock.upstream.sha256")).isEqualToIgnoringCase("B08D9C5CC9BDC559E846703AE39E6F6D610E0EE586B417E791F6F9BA5D796509");
    }
}
