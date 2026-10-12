package com.game.scene.testing;

import com.game.api.proto.ZoneRedirect;
import com.game.proto.MessageContent;
import com.game.scene.world.ClientSink;
import java.util.ArrayList;
import java.util.List;

/** 记录场景逻辑的全部出站（按发生顺序），供断言下行内容与次序。 */
public final class RecordingSink implements ClientSink {

    public record Sent(long linkId, List<Integer> sessionIds, MessageContent content) {
    }

    public record EnterResult(long linkId, int sessionId, long playerId, long ownerEpoch, int tipId) {
    }

    public record Kicked(long linkId, int sessionId, long playerId, long ownerEpoch, int tipId) {
    }

    /** 一次 PlayerTransfer（{@code onWriteFailed} 留着让测试模拟异步写失败）。 */
    public record Transfer(long linkId, int sessionId, long playerId, long fromEpoch, long toEpoch, int targetNodeId,
                           long targetSceneId, Runnable onWriteFailed) {
    }

    /**
     * 一次重定向帧（{@code PlayerTransfer{redirect}}，批次 5.4）。{@code redirect} 是调用方传进来的那个对象本身（没有拷贝、没有重组），
     * 测试可以逐字段、逐字节对照选目标的结果；{@code onWriteFailed} 留着让测试模拟异步写失败（{@link #failWrite()}）。
     */
    public record Redirect(long linkId, int sessionId, long playerId, long fromEpoch, long toEpoch, ZoneRedirect redirect,
                           Runnable onWriteFailed) {

        /** 模拟「帧已交给链路、随后异步写失败」：真实现把回调投递回逻辑线程，这里由测试线程直接跑（测试线程就是逻辑线程）。 */
        public void failWrite() {
            onWriteFailed.run();
        }
    }

    private final List<Object> events = new ArrayList<>();
    private boolean transferWritable = true;
    private boolean redirectWritable = true;

    @Override
    public boolean playerRedirect(long linkId, int sessionId, long playerId, long fromEpoch, long toEpoch,
                                  ZoneRedirect redirect, Runnable onWriteFailed) {
        if (!redirectWritable) {
            return false;
        }
        events.add(new Redirect(linkId, sessionId, playerId, fromEpoch, toEpoch, redirect, onWriteFailed));
        return true;
    }

    /** 模拟链路已断 / 不可写（false = 重定向帧确定写不出：返回 false、不记录、{@code onWriteFailed} 不会被调用）。 */
    public void setRedirectWritable(boolean writable) {
        this.redirectWritable = writable;
    }

    public List<Redirect> redirects() {
        List<Redirect> out = new ArrayList<>();
        for (Object event : events) {
            if (event instanceof Redirect redirect) {
                out.add(redirect);
            }
        }
        return out;
    }

    @Override
    public boolean playerTransfer(long linkId, int sessionId, long playerId, long fromEpoch, long toEpoch,
                                  int targetNodeId, long targetSceneId, Runnable onWriteFailed) {
        if (!transferWritable) {
            return false;
        }
        events.add(new Transfer(linkId, sessionId, playerId, fromEpoch, toEpoch, targetNodeId, targetSceneId,
                onWriteFailed));
        return true;
    }

    /** 模拟链路已断 / 不可写（false = PlayerTransfer 确定写不出，返回 false 且不记录）。 */
    public void setTransferWritable(boolean writable) {
        this.transferWritable = writable;
    }

    public List<Transfer> transfers() {
        List<Transfer> out = new ArrayList<>();
        for (Object event : events) {
            if (event instanceof Transfer transfer) {
                out.add(transfer);
            }
        }
        return out;
    }

    @Override
    public void send(long linkId, List<Integer> sessionIds, MessageContent content) {
        events.add(new Sent(linkId, List.copyOf(sessionIds), content));
    }

    @Override
    public void enterResult(long linkId, int sessionId, long playerId, long ownerEpoch, int tipId) {
        events.add(new EnterResult(linkId, sessionId, playerId, ownerEpoch, tipId));
    }

    @Override
    public void playerKicked(long linkId, int sessionId, long playerId, long ownerEpoch, int tipId) {
        events.add(new Kicked(linkId, sessionId, playerId, ownerEpoch, tipId));
    }

    public List<Object> events() {
        return events;
    }

    /** 某会话按顺序收到的下行。 */
    public List<MessageContent> to(long linkId, int sessionId) {
        List<MessageContent> out = new ArrayList<>();
        for (Object event : events) {
            if (event instanceof Sent sent && sent.linkId() == linkId && sent.sessionIds().contains(sessionId)) {
                out.add(sent.content());
            }
        }
        return out;
    }

    public List<Integer> messageIdsTo(long linkId, int sessionId) {
        return to(linkId, sessionId).stream().map(MessageContent::getMessageId).toList();
    }

    public List<EnterResult> results() {
        List<EnterResult> out = new ArrayList<>();
        for (Object event : events) {
            if (event instanceof EnterResult result) {
                out.add(result);
            }
        }
        return out;
    }

    public List<Kicked> kicks() {
        List<Kicked> out = new ArrayList<>();
        for (Object event : events) {
            if (event instanceof Kicked kicked) {
                out.add(kicked);
            }
        }
        return out;
    }

    public void clear() {
        events.clear();
    }
}
