package com.game.data.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.player.store.state.PlayerState;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把快照里的 {@code player_state} 字节转成 JSON（快照详情 {@code includeState=true}，data-ops-spec §3.5）：
 * protobuf-java-util 的 {@link JsonFormat}（proto 字段名原样；64 位整数按 proto3 JSON 规则输出成十进制字符串）。
 * JsonFormat 会丢掉本版本不认识的字段，所以另列 {@code unknownFields}：每个带未知字段的子消息给出路径、字节数与 base64 原样内容
 * （更新版本 scene 写下的段不会在详情里「消失」）。字节解不出来时给 {@code parseError} 与原样 base64。
 */
public final class StateJson {

    /** 列出的未知字段段数上限（每段是一个子消息上的全部未知字段）。 */
    static final int MAX_UNKNOWN_ENTRIES = 200;

    private StateJson() {
    }

    public static Map<String, Object> render(byte[] data, ObjectMapper json) {
        Map<String, Object> out = new LinkedHashMap<>();
        PlayerState state;
        try {
            state = PlayerState.parseFrom(data);
        } catch (InvalidProtocolBufferException e) {
            out.put("parseError", e.getMessage());
            out.put("rawBase64", Base64.getEncoder().encodeToString(data));
            return out;
        }
        try {
            String text = JsonFormat.printer().preservingProtoFieldNames().print(state);
            out.put("state", json.readTree(text));
        } catch (InvalidProtocolBufferException | JsonProcessingException e) {
            out.put("parseError", "转 JSON 失败：" + e.getMessage());
            out.put("rawBase64", Base64.getEncoder().encodeToString(data));
            return out;
        }
        List<Map<String, Object>> unknown = new ArrayList<>();
        collectUnknown("", state, unknown);
        out.put("unknownFields", unknown);
        return out;
    }

    /** 深度优先收集未知字段（路径形如 {@code bag.items[3]}；根上的未知字段路径为空串）。 */
    static void collectUnknown(String path, Message message, List<Map<String, Object>> out) {
        if (out.size() >= MAX_UNKNOWN_ENTRIES) {
            return;
        }
        if (!message.getUnknownFields().asMap().isEmpty()) {
            byte[] bytes = message.getUnknownFields().toByteArray();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", path);
            entry.put("fieldNumbers", message.getUnknownFields().asMap().keySet());
            entry.put("bytes", bytes.length);
            entry.put("base64", Base64.getEncoder().encodeToString(bytes));
            out.add(entry);
        }
        for (Map.Entry<FieldDescriptor, Object> field : message.getAllFields().entrySet()) {
            FieldDescriptor fd = field.getKey();
            if (fd.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
                continue;
            }
            String child = path.isEmpty() ? fd.getName() : path + "." + fd.getName();
            if (fd.isRepeated()) {
                List<?> items = (List<?>) field.getValue();
                for (int i = 0; i < items.size(); i++) {
                    collectUnknown(child + "[" + i + "]", (Message) items.get(i), out);
                }
            } else {
                collectUnknown(child, (Message) field.getValue(), out);
            }
        }
    }
}
