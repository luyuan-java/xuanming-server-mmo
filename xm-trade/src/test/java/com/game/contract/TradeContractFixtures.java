package com.game.contract;

import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 测试专用：造一份缺了某个键的消息号注册表（与 {@link MessageIdRegistry} 同包，才能调用包内可见的 {@code build}；同 xm-guild 的
 * GuildContractFixtures），用来钉住 TradeDispatcher「契约缺号即启动失败」。只在 xm-trade 的测试 classpath 上。
 */
public final class TradeContractFixtures {

    private TradeContractFixtures() {
    }

    /** classpath 上的契约去掉 {@code key}（如 {@code ClientPlayerJubaozhaiBrowseListings}）那一行。 */
    public static MessageIdRegistry registryWithout(String key) {
        ClassLoader loader = MessageIdRegistry.class.getClassLoader();
        try (InputStream ids = loader.getResourceAsStream(MessageIdRegistry.MESSAGE_ID_RESOURCE);
             InputStream desc = loader.getResourceAsStream(MessageIdRegistry.DESCRIPTOR_SET_RESOURCE)) {
            String filtered = new String(ids.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.trim().endsWith("=" + key))
                    .collect(Collectors.joining("\n"));
            return MessageIdRegistry.build(new ByteArrayInputStream(filtered.getBytes(StandardCharsets.UTF_8)),
                    FileDescriptorSet.parseFrom(desc), loader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
