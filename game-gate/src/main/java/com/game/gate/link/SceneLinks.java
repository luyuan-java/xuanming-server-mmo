package com.game.gate.link;

import com.game.api.proto.NodeLinkFrame;

/**
 * gate → scene 节点链路的发送面（architecture.md §4.2）。
 *
 * <p>契约：
 * <ul>
 *   <li>返回这帧被分配到的链路<b>代次</b>（&gt; 0）。同一 scene 节点断链重连后代次变化，
 *       调用方用 (节点号, 代次) 判断后续链路事件是否属于自己当时发出的那条链路。</li>
 *   <li>链路未就绪时帧按序排队，握手成功后按原顺序发出；同一线程先后发出的帧在链路上保持顺序。</li>
 *   <li>建链失败时，排队中的 {@code PlayerEnter} 经 {@link SceneLinkListener#onEnterUndeliverable} 逐条回报，其余帧丢弃。</li>
 *   <li>无法受理（链路层已关闭）时返回 0，帧被丢弃，不回报。</li>
 * </ul>
 * 线程安全，不阻塞（寻址与建连都在别的线程上做）。
 */
public interface SceneLinks {

    long send(int sceneNodeId, NodeLinkFrame frame);
}
