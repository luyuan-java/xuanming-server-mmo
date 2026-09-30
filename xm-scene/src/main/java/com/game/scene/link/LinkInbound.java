package com.game.scene.link;

import com.game.api.proto.LinkHello;
import com.game.api.proto.NodeLinkFrame;
import io.netty.channel.Channel;

/**
 * gate 链路事件的接收方。{@link NodeLinkHandler} 在 I/O 线程上解码、握手校验后，把事件<b>投递到场景逻辑线程</b>
 * 再调用这里；实现只在场景逻辑线程上运行，可以直接读写场景状态。
 *
 * <p>同一条链路的事件按到达顺序投递：{@link #linkOpened} 一定先于该链路的 {@link #frameReceived}，
 * {@link #linkClosed} 一定最后（握手未成功的链路不会有任何事件）。
 */
public interface LinkInbound {

    /** 握手通过：登记链路并回 {@code LinkHelloAck{accepted=true}}。 */
    void linkOpened(long linkId, LinkHello hello, Channel channel);

    void frameReceived(long linkId, NodeLinkFrame frame);

    void linkClosed(long linkId);
}
