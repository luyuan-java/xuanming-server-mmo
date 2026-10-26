package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.net.client.ClientFrames;
import com.game.proto.match.*;
import com.game.robot.client.RobotException;
import com.google.protobuf.Message;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;

/** 全球匹配的真实网络探测：客户端只回显nonce并转交签名回执，不构造RTT或持有服务端秘密。 */
final class PveNetworkProbe {
    private static final Map<String, Message> INBOUND = Map.of(
            NetworkProbeChallenge.getDescriptor().getFullName(), NetworkProbeChallenge.getDefaultInstance(),
            NetworkProbeComplete.getDescriptor().getFullName(), NetworkProbeComplete.getDefaultInstance());
    private PveNetworkProbe() {}

    static String prepare(MatchSupport.Caller bot, MessageIdRegistry registry, Duration timeout) throws RobotException {
        var plan = bot.call(registry.requireId("MatchService", "GetNetworkProbePlan"), GetNetworkProbePlanRequest.getDefaultInstance(),
                GetNetworkProbePlanResponse.parser());
        if (plan.getErrorMessage().getId() != 0 || !plan.getRequired() || plan.getPlanId().isEmpty()
                || plan.getTargetsCount() == 0 || plan.getTargetsCount() > 16)
            throw new RobotException("全球网络探测未开放或没有有效目标，不能退回无原件排队：tip=" + plan.getErrorMessage().getId());
        var commit = CommitNetworkProbeRequest.newBuilder().setPlanId(plan.getPlanId());
        for (var target : plan.getTargetsList()) commit.addReceipts(measure(target, bot.id(), plan.getPlanId(), timeout));
        var confirmed = bot.call(registry.requireId("MatchService", "CommitNetworkProbe"), commit.build(), CommitNetworkProbeResponse.parser());
        if (confirmed.getErrorMessage().getId() != 0 || confirmed.getExpiresAtMs() == 0)
            throw new RobotException("全球网络探测回执未获确认：tip=" + confirmed.getErrorMessage().getId());
        return plan.getPlanId();
    }

    static SignedNetworkProbe measure(NetworkProbeTarget target, long player, String plan, Duration timeout) throws RobotException {
        long until = System.nanoTime() + timeout.toNanos();
        try (var socket = new Socket()) {
            var original = NetworkProbeGrant.parseFrom(target.getGrant().getPayload());
            if (target.getHost().isBlank() || target.getPort() <= 0 || target.getPort() > 65535 || target.getPhysicalRegion() == 0
                    || original.getPlayerId() != player || !original.getPlanId().equals(plan)
                    || original.getPhysicalRegion() != target.getPhysicalRegion() || target.getGrant().getSignature().isEmpty())
                throw new RobotException("全球探测目标与原计划或本人不符");
            socket.connect(new InetSocketAddress(target.getHost(), target.getPort()), remaining(until));
            socket.setTcpNoDelay(true);
            var input = new DataInputStream(socket.getInputStream());
            var output = new DataOutputStream(socket.getOutputStream());
            write(output, NetworkProbeStart.newBuilder().setGrant(target.getGrant()).build());
            for (int index = 0; index < 3; index++) {
                Message frame = read(socket, input, until);
                if (!(frame instanceof NetworkProbeChallenge challenge) || challenge.getSampleIndex() != index || challenge.getNonce().size() != 32)
                    throw new RobotException("全球网络探测缺少按序的三次nonce挑战");
                write(output, NetworkProbeEcho.newBuilder().setSampleIndex(index).setNonce(challenge.getNonce()).build());
            }
            Message frame = read(socket, input, until);
            if (!(frame instanceof NetworkProbeComplete complete) || complete.getReceipt().getSignature().isEmpty())
                throw new RobotException("全球网络探测没有签名完成回执");
            var receipt = NetworkProbeReceipt.parseFrom(complete.getReceipt().getPayload());
            if (!receipt.getGrant().equals(original) || receipt.getSampleCount() != 3 || receipt.getRttMs() == 0 || receipt.getExpiresAtMs() == 0)
                throw new RobotException("全球网络探测回执与完整原授权不符");
            return complete.getReceipt(); // 最终密码学验证由CommitNetworkProbe完成。
        } catch (RobotException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new RobotException("全球网络探测失败：" + failure.getClass().getSimpleName(), failure);
        }
    }
    private static int remaining(long until) throws IOException {
        long millis = (until - System.nanoTime() + 999_999) / 1_000_000;
        if (millis <= 0) throw new java.net.SocketTimeoutException("探测预算已用完");
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }
    private static Message read(Socket socket, DataInputStream input, long until) throws Exception {
        socket.setSoTimeout(remaining(until));
        int len = input.readInt();
        if (len < ClientFrames.MIN_LEN || len > ClientFrames.DEFAULT_MAX_LEN) throw new IOException("探测帧长度非法");
        byte[] raw = new byte[len];
        int at = 0;
        while (at < len) {
            socket.setSoTimeout(remaining(until));
            int count = input.read(raw, at, len - at);
            if (count < 0) throw new java.io.EOFException("探测帧未完整");
            at += count;
        }
        var frame = Unpooled.wrappedBuffer(raw);
        try { return ClientFrames.decodeBody(frame, INBOUND); }
        finally { frame.release(); }
    }
    private static void write(DataOutputStream output, Message message) throws IOException {
        var frame = ClientFrames.encode(UnpooledByteBufAllocator.DEFAULT, message);
        try {
            byte[] raw = new byte[frame.readableBytes()];
            frame.readBytes(raw);
            output.write(raw);
            output.flush();
        } finally { frame.release(); }
    }
}
