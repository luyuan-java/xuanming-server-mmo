package com.game.scene.world;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;

/**
 * 客户端请求的字段规模与负数校验（基线 {@code ProtoFieldChecker::CheckFieldSizes} / {@code CheckForNegativeInts}，
 * 阈值 {@code kProtoFieldCheckerThreshold} = 20）。与具体方法无关，按描述符反射遍历：
 * <ul>
 *   <li>任一 repeated / map 字段元素数 &gt; 20；</li>
 *   <li>任一有符号整数字段（int32 / sint32 / sfixed32 / int64 / sint64 / sfixed64，含 repeated）为负——
 *       uint32 / uint64 / fixed* 不算（Java 里它们高位置位时也读成负数，按声明类型区分，同基线的 CPPTYPE）；</li>
 *   <li>两项都只递归进<b>非 repeated</b> 的子消息（repeated 子消息、map 的值不展开，同基线）。</li>
 * </ul>
 */
final class RequestFieldCheck {

    /** 基线 {@code kProtoFieldCheckerThreshold}。 */
    static final int MAX_REPEATED = 20;

    private RequestFieldCheck() {
    }

    /** @return 违规说明（记日志用）；没有违规为 null */
    static String violation(Message message) {
        StringBuilder out = new StringBuilder();
        checkSizes(message, "", out);
        checkNegatives(message, "", out);
        return out.isEmpty() ? null : out.toString();
    }

    private static void checkSizes(Message message, String path, StringBuilder out) {
        for (FieldDescriptor field : message.getDescriptorForType().getFields()) {
            if (field.isRepeated()) {
                int size = message.getRepeatedFieldCount(field);
                if (size > MAX_REPEATED) {
                    out.append(path).append(field.getName()).append(" 元素数 ").append(size).append(" > ")
                            .append(MAX_REPEATED).append("; ");
                }
            } else if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE && message.hasField(field)) {
                checkSizes((Message) message.getField(field), path + field.getName() + ".", out);
            }
        }
    }

    private static void checkNegatives(Message message, String path, StringBuilder out) {
        for (FieldDescriptor field : message.getDescriptorForType().getFields()) {
            if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                if (!field.isRepeated() && message.hasField(field)) {
                    checkNegatives((Message) message.getField(field), path + field.getName() + ".", out);
                }
            } else if (isSigned(field.getType())) {
                if (field.isRepeated()) {
                    for (int i = 0, n = message.getRepeatedFieldCount(field); i < n; i++) {
                        appendIfNegative(field, ((Number) message.getRepeatedField(field, i)).longValue(), path, out);
                    }
                } else {
                    appendIfNegative(field, ((Number) message.getField(field)).longValue(), path, out);
                }
            }
        }
    }

    private static boolean isSigned(FieldDescriptor.Type type) {
        return switch (type) {
            case INT32, SINT32, SFIXED32, INT64, SINT64, SFIXED64 -> true;
            default -> false;
        };
    }

    private static void appendIfNegative(FieldDescriptor field, long value, String path, StringBuilder out) {
        if (value < 0) {
            out.append(path).append(field.getName()).append(" 为负 ").append(value).append("; ");
        }
    }
}
