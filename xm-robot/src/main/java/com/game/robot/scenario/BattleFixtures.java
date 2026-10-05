package com.game.robot.scenario;

import com.game.net.client.ClientFrames;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.ClientRequest;
import com.game.proto.CreateBattleRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * battle / battle-edge 场景的纯函数件：dev 建房的快照与请求、负面用例的坏票 / 坏帧 / 凑体积请求、回合间隔。不碰网络，单测覆盖。
 *
 * <p>快照的 {@code routing} 一律不填：dev 接口按玩家此刻的在线目录与位置记录补全（battle-node-spec §7.12），所以建房前玩家必须已登录进场。
 */
final class BattleFixtures {

    /** {@code MatchMode} 数值：PVP 1V1 / PVE 单人（battle-node-spec §13.8 第 3、11 步）。 */
    static final int MATCH_MODE_PVP = 3;
    static final int MATCH_MODE_PVE = 4;
    /** PVE 用的 Dungeon 1（怪物 1、2）；PVP 不带副本（0）。 */
    static final int DUNGEON_1 = 1;
    static final int PVP_CONFIG = 0;
    /** 回血药（正式表里能在战斗中用的道具，{@code self_items} 用）。 */
    static final int ITEM_HEAL = 10;
    /** 固定种子：同一份快照每次跑出同样的回合。 */
    static final long SEED = 20261005L;
    /** 签名是 64 位小写 hex（battle-node-spec §2.2）。 */
    static final Pattern LOWER_HEX_64 = Pattern.compile("[0-9a-f]{64}");

    private BattleFixtures() {
    }

    /** robot 自己发 battle_id（6.4 之前没有 match 的雪花号）：非 0 正数，随机避免撞上切片里别的房间。 */
    static long newBattleId(RandomGenerator random) {
        return random.nextLong(1, Long.MAX_VALUE);
    }

    /** PVE 打手：一刀秒怪、血厚到怪打不死，带 {@code heals} 瓶回血药。 */
    static BattlePlayerSnapshot hero(long playerId, String name, int heals) {
        BattlePlayerSnapshot.Builder b = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(playerId)
                .setPlayerName(name)
                .setLevel(10)
                .setTeamIndex(0)
                .setMaxHealth(1_000_000)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(1_000_000).setStrength(1_000_000).setSpeed(1000))
                .setPhysicalAttack(1_000_000);
        if (heals > 0) {
            b.addItems(BattleItemEntry.newBuilder().setItemTableId(ITEM_HEAL).setCount(heals));
        }
        return b.build();
    }

    /** 沙包：血极厚、攻击极低（PVP 两边都用它，PVE 用它就打不完）；带 {@code heals} 瓶回血药（0 = 不带）。 */
    static BattlePlayerSnapshot tank(long playerId, String name, int team, int heals) {
        BattlePlayerSnapshot.Builder b = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(playerId)
                .setPlayerName(name)
                .setLevel(10)
                .setTeamIndex(team)
                .setMaxHealth(100_000_000)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(100_000_000).setStrength(1).setSpeed(100 + team));
        if (heals > 0) {
            b.addItems(BattleItemEntry.newBuilder().setItemTableId(ITEM_HEAL).setCount(heals));
        }
        return b.build();
    }

    static CreateBattleRequest pve(long battleId, long deadlineMs, BattlePlayerSnapshot player) {
        return CreateBattleRequest.newBuilder()
                .setBattleId(battleId)
                .setBattleConfigId(DUNGEON_1)
                .setMatchMode(MATCH_MODE_PVE)
                .setSeed(SEED)
                .setCreatedAtMs(System.currentTimeMillis())
                .setDeadlineMs(deadlineMs)
                .addPlayers(player)
                .build();
    }

    static CreateBattleRequest pvp(long battleId, long deadlineMs, BattlePlayerSnapshot a, BattlePlayerSnapshot b) {
        return CreateBattleRequest.newBuilder()
                .setBattleId(battleId)
                .setBattleConfigId(PVP_CONFIG)
                .setMatchMode(MATCH_MODE_PVP)
                .setSeed(SEED + 1)
                .setCreatedAtMs(System.currentTimeMillis())
                .setDeadlineMs(deadlineMs)
                .addPlayers(a)
                .addPlayers(b)
                .build();
    }

    static boolean isLowerHex64(ByteString signature) {
        return LOWER_HEX_64.matcher(signature.toString(StandardCharsets.US_ASCII)).matches();
    }

    /** 把签名的第一个 hex 字符换成另一个合法的小写 hex 字符（只改一个字节，长度不变）。 */
    static ByteString flipFirstHexChar(ByteString signature) {
        if (signature.isEmpty()) {
            throw new IllegalArgumentException("签名为空");
        }
        byte[] bytes = signature.toByteArray();
        bytes[0] = (byte) (bytes[0] == 'a' ? 'b' : 'a');
        return ByteString.copyFrom(bytes);
    }

    /** 大写的签名（验签对大小写敏感，必须拒绝，battle-node-spec §13.1）。 */
    static ByteString upperCaseSignature(ByteString signature) {
        return ByteString.copyFrom(signature.toString(StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT), StandardCharsets.US_ASCII);
    }

    /** payload 末尾追加一个未知的 varint 字段（号 15）：解析照常通过，但签名对不上了。 */
    static ByteString tamperedPayload(ByteString payload) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            payload.writeTo(bytes);
            CodedOutputStream out = CodedOutputStream.newInstance(bytes);
            out.writeUInt64(15, 1);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ByteString.copyFrom(bytes.toByteArray());
    }

    /** 按客户端帧格式编码（{@code xm-net} {@link ClientFrames}）。 */
    static byte[] encodeFrame(Message message) {
        ByteBuf buf = ClientFrames.encode(UnpooledByteBufAllocator.DEFAULT, message);
        try {
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    /** 帧的副本，最后一个字节（adler32 的末字节）异或 0x5a：校验和不对。 */
    static byte[] corruptChecksum(byte[] frame) {
        byte[] copy = frame.clone();
        copy[copy.length - 1] ^= 0x5a;
        return copy;
    }

    /**
     * 整条序列化后恰好 {@code targetSize} 字节的 {@code ClientRequest}：体后面用未知的 length-delimited 字段（号 15）补齐，服务端照常解析
     * （battle-node-spec §3.5：体积闸按整条 {@code ClientRequest} 序列化后的字节数判，> 1024 B 回 1010）。
     */
    static ClientRequest paddedRequest(long id, int messageId, Message body, int targetSize) {
        for (int pad = 0; pad < targetSize; pad++) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try {
                body.writeTo(bytes);
                CodedOutputStream out = CodedOutputStream.newInstance(bytes);
                out.writeBytes(15, ByteString.copyFrom(new byte[pad]));
                out.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ClientRequest request = ClientRequest.newBuilder().setId(id).setMessageId(messageId)
                    .setBody(ByteString.copyFrom(bytes.toByteArray())).build();
            if (request.getSerializedSize() == targetSize) {
                return request;
            }
            if (request.getSerializedSize() > targetSize) {
                break;
            }
        }
        throw new IllegalArgumentException("凑不出恰好 " + targetSize + " 字节的 ClientRequest");
    }

    /** 相邻到达时刻（纳秒）的间隔，毫秒。 */
    static List<Long> gapsMillis(List<Long> atNanos) {
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < atNanos.size(); i++) {
            gaps.add(TimeUnit.NANOSECONDS.toMillis(atNanos.get(i) - atNanos.get(i - 1)));
        }
        return gaps;
    }
}
