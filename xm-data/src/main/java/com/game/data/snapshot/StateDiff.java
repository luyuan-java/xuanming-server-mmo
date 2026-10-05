package com.game.data.snapshot;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * protobuf 反射的逐字段差异（data-ops-spec §3.6 第 5 项：Java 的 protobuf 没有 C++ 的 MessageDifferencer，自己写）。
 * 给出路径级的变化 {@code (path, snapshot, current)}，至多 {@link #MAX_CHANGES} 条，超出置 {@link #truncated()}。
 *
 * <ul>
 *   <li>标量逐值比较；uint32 / uint64 / fixed64 按无符号输出，bytes 按 base64，枚举按名字（不认识的按数值）；</li>
 *   <li>repeated 按下标比较，多出来的元素对侧记 {@code <absent>}；map 按键比较（键排序后逐个，键对侧没有记 {@code <absent>}）；</li>
 *   <li>子消息有无不同（一侧未设置）先记一条 {@code <unset>} / {@code <set>}，再与默认实例逐字段比较；</li>
 *   <li>本版本不认识的字段按字节比较，路径后缀 {@code .<unknown>}。</li>
 * </ul>
 * 不是线程安全的（一次比较一个实例）。
 */
public final class StateDiff {

    public static final int MAX_CHANGES = 500;
    static final String ABSENT = "<absent>";
    static final String UNSET = "<unset>";
    static final String SET = "<set>";

    /** 一处差异。值是可读字符串（uint64 等按无符号十进制）。 */
    public record Change(String path, String snapshot, String current) {
    }

    private final List<Change> changes = new ArrayList<>();
    private boolean truncated;

    public List<Change> changes() {
        return changes;
    }

    public boolean truncated() {
        return truncated;
    }

    /** 比较同类型的两个消息（{@code snapshot} 在前、{@code current} 在后），差异追加到本实例。 */
    public StateDiff compare(String path, Message snapshot, Message current) {
        if (!snapshot.getDescriptorForType().equals(current.getDescriptorForType())) {
            throw new IllegalArgumentException("类型不同：" + snapshot.getDescriptorForType().getFullName() + " vs "
                    + current.getDescriptorForType().getFullName());
        }
        compareMessage(path, snapshot, current);
        return this;
    }

    private void add(String path, String snapshot, String current) {
        if (changes.size() >= MAX_CHANGES) {
            truncated = true;
            return;
        }
        changes.add(new Change(path, snapshot, current));
    }

    private boolean full() {
        if (changes.size() >= MAX_CHANGES) {
            truncated = true;
            return true;
        }
        return false;
    }

    /**
     * 只比较两个同类型消息上的一个字段（路径 = {@code path}.字段名），差异追加到本实例。用于逐段比较根消息
     * （SnapshotDiffService 按描述符遍历 PlayerState 的非资产段）。
     */
    public StateDiff compareField(String path, FieldDescriptor fd, Message snapshot, Message current) {
        if (!snapshot.getDescriptorForType().equals(current.getDescriptorForType())
                || fd.getContainingType() != snapshot.getDescriptorForType()) {
            throw new IllegalArgumentException("字段 " + fd.getFullName() + " 不属于 "
                    + snapshot.getDescriptorForType().getFullName() + " / " + current.getDescriptorForType().getFullName());
        }
        if (!full()) {
            compareOne(path, fd, snapshot, current);
        }
        return this;
    }

    private void compareMessage(String path, Message a, Message b) {
        for (FieldDescriptor fd : a.getDescriptorForType().getFields()) {
            if (full()) {
                return;
            }
            compareOne(path, fd, a, b);
        }
        ByteString ua = a.getUnknownFields().toByteString();
        ByteString ub = b.getUnknownFields().toByteString();
        if (!ua.equals(ub)) {
            add(join(path, "<unknown>"), ua.size() + " bytes", ub.size() + " bytes");
        }
    }

    /** 一个字段：map 按键、repeated 按下标、子消息先比有无再逐字段、标量逐值。 */
    private void compareOne(String path, FieldDescriptor fd, Message a, Message b) {
        String child = join(path, fd.getName());
        if (fd.isMapField()) {
            compareMap(child, fd, a, b);
        } else if (fd.isRepeated()) {
            compareRepeated(child, fd, a, b);
        } else if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
            boolean hasA = a.hasField(fd);
            boolean hasB = b.hasField(fd);
            if (hasA != hasB) {
                add(child, hasA ? SET : UNSET, hasB ? SET : UNSET);
            }
            if (hasA || hasB) {
                compareMessage(child, (Message) a.getField(fd), (Message) b.getField(fd));
            }
        } else {
            Object va = a.getField(fd);
            Object vb = b.getField(fd);
            if (!Objects.equals(va, vb)) {
                add(child, render(fd, va), render(fd, vb));
            }
        }
    }

    private void compareRepeated(String path, FieldDescriptor fd, Message a, Message b) {
        int na = a.getRepeatedFieldCount(fd);
        int nb = b.getRepeatedFieldCount(fd);
        for (int i = 0; i < Math.max(na, nb); i++) {
            if (full()) {
                return;
            }
            String child = path + "[" + i + "]";
            Object va = i < na ? a.getRepeatedField(fd, i) : null;
            Object vb = i < nb ? b.getRepeatedField(fd, i) : null;
            if (va != null && vb != null && fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                compareMessage(child, (Message) va, (Message) vb);
            } else if (!Objects.equals(va, vb)) {
                add(child, va == null ? ABSENT : render(fd, va), vb == null ? ABSENT : render(fd, vb));
            }
        }
    }

    private void compareMap(String path, FieldDescriptor fd, Message a, Message b) {
        Map<String, Message> ma = entries(fd, a);
        Map<String, Message> mb = entries(fd, b);
        TreeMap<String, Boolean> keys = new TreeMap<>();
        ma.keySet().forEach(k -> keys.put(k, true));
        mb.keySet().forEach(k -> keys.put(k, true));
        FieldDescriptor valueField = fd.getMessageType().findFieldByName("value");
        for (String key : keys.keySet()) {
            if (full()) {
                return;
            }
            String child = path + "{" + key + "}";
            Message ea = ma.get(key);
            Message eb = mb.get(key);
            if (ea == null || eb == null) {
                add(child, ea == null ? ABSENT : render(valueField, ea.getField(valueField)),
                        eb == null ? ABSENT : render(valueField, eb.getField(valueField)));
                continue;
            }
            Object va = ea.getField(valueField);
            Object vb = eb.getField(valueField);
            if (valueField.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                compareMessage(child, (Message) va, (Message) vb);
            } else if (!Objects.equals(va, vb)) {
                add(child, render(valueField, va), render(valueField, vb));
            }
        }
    }

    /** map 字段的条目：键渲染成字符串（无符号整数按无符号；同一个 map 里键的类型相同，排序只为输出稳定）。 */
    private static Map<String, Message> entries(FieldDescriptor fd, Message m) {
        FieldDescriptor keyField = fd.getMessageType().findFieldByName("key");
        Map<String, Message> out = new LinkedHashMap<>();
        for (int i = 0; i < m.getRepeatedFieldCount(fd); i++) {
            Message entry = (Message) m.getRepeatedField(fd, i);
            out.put(render(keyField, entry.getField(keyField)), entry);
        }
        return out;
    }

    private static String join(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    /** 标量的可读形式。 */
    static String render(FieldDescriptor fd, Object value) {
        if (value == null) {
            return "null";
        }
        return switch (fd.getType()) {
            case UINT32, FIXED32 -> Integer.toUnsignedString((Integer) value);
            case UINT64, FIXED64 -> Long.toUnsignedString((Long) value);
            case BYTES -> "base64:" + Base64.getEncoder().encodeToString(((ByteString) value).toByteArray());
            case ENUM -> {
                EnumValueDescriptor e = (EnumValueDescriptor) value;
                yield e.getIndex() >= 0 ? e.getName() : Integer.toString(e.getNumber());
            }
            case MESSAGE, GROUP -> SET;
            default -> String.valueOf(value);
        };
    }
}
