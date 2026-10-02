package com.game.table.codegen;

import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * 配置表代码生成器（javac 注解处理器）。
 *
 * <p>为什么是注解处理器而不是 protoc 插件：Maven 多模块构建里，protoc 的 JVM 插件要按坐标解析成 jar，
 * {@code ./mvnw test} 这类不打包的构建解析不到同一 reactor 里的插件模块；注解处理器只需在编译 classpath 上，
 * 任何阶段都可用。
 *
 * <p>只在设置了 {@value #DESCRIPTOR_SET_OPTION} 选项的编译里工作（xm-table 的 pom 配置，指向 protoc 输出的描述符集），
 * 其余模块即使 classpath 上有它也什么都不做。是「通配」处理器（支持 {@code *}），所以不需要在源码里放触发注解；
 * 只在第一轮生成一次，不认领任何注解。
 */
@SupportedOptions(ConfigTableProcessor.DESCRIPTOR_SET_OPTION)
public final class ConfigTableProcessor extends AbstractProcessor {

    /** 描述符集文件路径（protoc {@code --descriptor_set_out} 的产物，需含 import）。 */
    public static final String DESCRIPTOR_SET_OPTION = "xm.table.descriptorSet";

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
        if (descriptorSet == null || generated || roundEnv.processingOver()) {
            return false;
        }
        generated = true;
        try {
            FileDescriptorSet set = FileDescriptorSet.parseFrom(Files.readAllBytes(Path.of(descriptorSet)));
            TableSchema schema = TableSchemaReader.read(set);
            if (schema.tables().isEmpty()) {
                error("描述符集里没有任何配置表（找不到带 cfg_sheet 的消息）: " + descriptorSet);
                return false;
            }
            schema = resolveAccessors(schema);
            for (Map.Entry<String, String> file : TableSourceGenerator.generate(schema).entrySet()) {
                String qualified = schema.javaPackage() + "." + file.getKey();
                JavaFileObject source = processingEnv.getFiler().createSourceFile(qualified);
                try (Writer writer = source.openWriter()) {
                    writer.write(file.getValue());
                }
            }
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "xm-table-codegen: 已生成 " + schema.tables().size() + " 张配置表的访问代码");
        } catch (TableSchemaReader.SchemaException e) {
            error("配置表 schema 不合法: " + e.getMessage());
        } catch (IOException e) {
            error("读不了配置表描述符集 " + descriptorSet + ": " + e);
        }
        return false;
    }

    /**
     * 访问器名以 protoc 实际生成的行类为准：protoc 生成的源码与本次编译同在，按类型元素查它的无参方法。
     * 候选依次是 {@code SkillType}（常规）、{@code Class_}（禁用词）、{@code Monster5}（同消息里与 {@code monster_count}
     * 等冲突时 protoc 追加字段号）；都不存在就报编译错误，不生成调不通的代码。
     */
    TableSchema resolveAccessors(TableSchema schema) {
        Elements elements = processingEnv.getElementUtils();
        Map<String, Set<String>> methodsByRow = new HashMap<>();
        return schema.mapFields((table, field) -> {
            Set<String> methods = methodsByRow.computeIfAbsent(table.rowClass(), row -> {
                TypeElement type = elements.getTypeElement(schema.javaPackage() + "." + row);
                if (type == null) {
                    throw new TableSchemaReader.SchemaException("找不到 protoc 生成的行类 " + schema.javaPackage() + "." + row);
                }
                Set<String> names = new HashSet<>();
                for (ExecutableElement m : ElementFilter.methodsIn(type.getEnclosedElements())) {
                    if (m.getParameters().isEmpty()) {
                        names.add(m.getSimpleName().toString());
                    }
                }
                return names;
            });
            String base = chooseAccessorBase(field, methods);
            if (base == null) {
                throw new TableSchemaReader.SchemaException(table.sheet() + "." + field.name() + ": 在 " + table.rowClass()
                        + " 上找不到 protoc 生成的访问器 get" + field.accessorBase() + field.accessorSuffix() + "()");
            }
            return field.withAccessorBase(base);
        });
    }

    /** 在行类的无参方法里挑出该字段的访问器名；找不到返回 null。 */
    static String chooseAccessorBase(TableSchema.Field field, Set<String> noArgMethods) {
        String cap = JavaNames.underscoresToCamelCase(field.name(), true);
        for (String base : List.of(field.accessorBase(), cap, cap + "_", cap + field.number())) {
            if (noArgMethods.contains("get" + base + field.accessorSuffix())) {
                return base;
            }
        }
        return null;
    }

    private void error(String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message);
    }
}
