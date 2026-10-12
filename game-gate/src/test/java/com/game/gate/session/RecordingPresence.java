package com.game.gate.session;

import java.util.ArrayList;
import java.util.List;

/** 记录在线目录写入口收到的调用（按顺序）。下线事件的 epoch 记 0。 */
final class RecordingPresence implements PresenceRecorder {

    record Event(boolean online, long playerId, int sessionId, long ownerEpoch) {
    }

    final List<Event> events = new ArrayList<>();

    @Override
    public void online(long playerId, int sessionId, long ownerEpoch) {
        events.add(new Event(true, playerId, sessionId, ownerEpoch));
    }

    @Override
    public void offline(long playerId, int sessionId) {
        events.add(new Event(false, playerId, sessionId, 0));
    }
}
