package com.game.robot.scenario;

import com.game.proto.SceneChannelDirectory;
import com.game.proto.SceneChannelInfo;
import com.game.proto.SceneChannelState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 线路探针的纯判据；无符号 scene_id 与线号不能经过浮点数或有符号正数校验。 */
final class ChannelChecks {
    private ChannelChecks() {}

    static List<String> directory(SceneChannelDirectory value, int zone, int config, long current) {
        List<String> errors = new ArrayList<>();
        if (value.getZoneId() != zone || value.getSceneConfigId() != config) errors.add("目录区/地图不匹配");
        if (value.getUpdatedAtMs() <= 0) errors.add("缺少发布时间");
        if (value.getMaxPlayersPerChannel() == 0) errors.add("人数软上限为0");
        var scenes = new HashSet<Long>();
        var numbers = new HashSet<Integer>();
        long previous = 0;
        for (var channel : value.getChannelsList()) {
            long number = Integer.toUnsignedLong(channel.getChannelNo());
            if (channel.getSceneId() == 0 || !scenes.add(channel.getSceneId())) errors.add("场景号为空/重复");
            if (number == 0 || !numbers.add(channel.getChannelNo())) errors.add("线号为空/重复");
            if (number <= previous) errors.add("未按无符号线号严格升序");
            previous = number;
            if (channel.getState() == SceneChannelState.UNRECOGNIZED
                    || channel.getState() == SceneChannelState.SCENE_CHANNEL_STATE_UNKNOWN) errors.add("未知线路状态");
        }
        if (!scenes.contains(current)) errors.add("目录没有当前线路");
        return List.copyOf(errors);
    }

    static boolean selectable(SceneChannelInfo channel) {
        return channel.getState() == SceneChannelState.SCENE_CHANNEL_STATE_SMOOTH
                || channel.getState() == SceneChannelState.SCENE_CHANNEL_STATE_BUSY;
    }

    /** 删除的线可以释放号码，新线可以复用；仍存活的同一个 scene_id 必须保持原号。 */
    static boolean stable(SceneChannelDirectory before, SceneChannelDirectory after) {
        Map<Long, Integer> old = new HashMap<>();
        before.getChannelsList().forEach(c -> old.put(c.getSceneId(), c.getChannelNo()));
        return after.getChannelsList().stream().allMatch(c -> !old.containsKey(c.getSceneId())
                || old.get(c.getSceneId()) == c.getChannelNo());
    }
}
