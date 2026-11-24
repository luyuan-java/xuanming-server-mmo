package com.game.proto.codegen;

import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * 协议契约编译期代码生成器（javac 注解处理器，模式与 {@code ConfigTableProcessor} 一致）。
 *
 * <p>只在设置了 {@value #DESCRIPTOR_SET_OPTION}、{@value #MESSAGE_IDS_OPTION}、{@value #EVENT_IDS_OPTION}
 * 三个选项的编译里运行（即 {@code game-proto} 的 {@code default-compile}），其余模块即使 classpath 上有它也什么都不做。
 */
@SupportedOptions({
        ContractProtoProcessor.DESCRIPTOR_SET_OPTION,
        ContractProtoProcessor.MESSAGE_IDS_OPTION,
        ContractProtoProcessor.EVENT_IDS_OPTION
})
public final class ContractProtoProcessor extends AbstractProcessor {

    public static final String DESCRIPTOR_SET_OPTION = "game.proto.descriptorSet";
    public static final String MESSAGE_IDS_OPTION = "game.proto.messageIds";
    public static final String EVENT_IDS_OPTION = "game.proto.eventIds";

    private boolean generated;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        String descriptorSet = processingEnv.getOptions().get(DESCRIPTOR_SET_OPTION);
        String messageIds = processingEnv.getOptions().get(MESSAGE_IDS_OPTION);
        String eventIds = processingEnv.getOptions().get(EVENT_IDS_OPTION);
        if (descriptorSet == null || messageIds == null || eventIds == null || generated || roundEnv.processingOver()) {
            return false;
        }
        generated = true;
        try {
            FileDescriptorSet set = FileDescriptorSet.parseFrom(Files.readAllBytes(Path.of(descriptorSet)));
            ContractModel.ContractSpec spec;
            try (InputStream msgIn = Files.newInputStream(Path.of(messageIds));
                 InputStream evtIn = Files.newInputStream(Path.of(eventIds))) {
                spec = ContractDescriptorReader.read(set, msgIn, evtIn);
            }
            if (spec.methods().isEmpty()) {
                error("契约描述符集与 message_id.txt 未解析出任何活跃 RPC 方法: " + descriptorSet);
                return false;
            }
            Map<String, String> generatedFiles = ContractSourceGenerator.generate(spec);
            for (Map.Entry<String, String> file : generatedFiles.entrySet()) {
                JavaFileObject source = processingEnv.getFiler().createSourceFile(file.getKey());
                try (Writer writer = source.openWriter()) {
                    writer.write(file.getValue());
                }
            }
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "game-proto-codegen: 已生成 " + spec.methods().size() + " 条消息号路由、"
                            + spec.services().size() + " 个服务接口与派发器、" + spec.events().size() + " 条事件号常量");
        } catch (ContractDescriptorReader.ContractSchemaException e) {
            error("契约协议元数据不合法: " + e.getMessage());
        } catch (IOException e) {
            error("读取协议契约输入失败: " + e);
        }
        return false;
    }

    private void error(String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message);
    }
}
