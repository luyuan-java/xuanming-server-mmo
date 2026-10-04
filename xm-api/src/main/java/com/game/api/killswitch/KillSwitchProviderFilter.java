package com.game.api.killswitch;

import com.game.api.ClientMessageService;
import com.game.common.killswitch.KillSwitch;
import java.util.Optional;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

/**
 * Dubbo 提供方的热关停（基线每个 Go 服务的 killswitch 拦截器）：本仓库业务接口（{@code com.game.*}）的方法命中关停规则时直接拒绝，
 * 不进入业务代码。规则键按 {@code /接口全名/方法名} 匹配（{@code com.game.api.AccountLoginService/login}、
 * {@code AccountLoginService/*}、{@code *}……，见 {@link KillSwitch#matchKeys}；方法名区分大小写）。
 *
 * <p>本进程没装开关（{@code xm.killswitch.enabled} 没打开）时永远放行。位于调用方鉴权之前（同基线：拦截器在验签之前）。
 * <b>不看 {@code ClientMessageService.handle}</b>：那是 gate 转发客户端消息的通道，客户端方法已在 gate 上按客户端方法全名
 * （{@code /chatpb.ClientPlayerChat/PullChatHistory}）查过一遍；这里再按 {@code ClientMessageService/handle} 查一次，
 * 全局 {@code *} 会把 gate 已按精确规则豁免的客户端方法再拦下来（基线里客户端方法本身就是 gRPC 方法，只查一次）。
 */
@Activate(group = CommonConstants.PROVIDER, order = -9500)
public final class KillSwitchProviderFilter implements Filter {

    static final String BUSINESS_PACKAGE_PREFIX = "com.game.";

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        String service = invoker.getInterface().getName();
        if (service.startsWith(BUSINESS_PACKAGE_PREFIX) && !isClientForwarding(invoker, invocation)) {
            Optional<KillSwitch> killSwitch = KillSwitch.global();
            if (killSwitch.isPresent()) {
                String method = invocation.getMethodName();
                Optional<KillSwitch.Rule> rule = killSwitch.get().blocked("/" + service + "/" + method);
                if (rule.isPresent()) {
                    killSwitch.get().recordBlocked(invoker.getInterface().getSimpleName() + "/" + method);
                    String reason = rule.get().reason();
                    throw new RpcException(RpcException.FORBIDDEN_EXCEPTION, "killswitch: method " + service + "/" + method
                            + " disabled" + (reason.isEmpty() ? "" : ", reason: " + ascii(reason)));
                }
            }
        }
        return invoker.invoke(invocation);
    }

    /** gate 转发客户端消息的那一个方法（按客户端方法的关停已在 gate 上做过）。 */
    static boolean isClientForwarding(Invoker<?> invoker, Invocation invocation) {
        return invoker.getInterface() == ClientMessageService.class && "handle".equals(invocation.getMethodName());
    }

    /** Triple 会把状态消息里的非 ASCII 字符弄成问号：消息用英文，原因里的非 ASCII 字符转成 {@code \\uXXXX}。 */
    static String ascii(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x20 && c < 0x7f) {
                out.append(c);
            } else {
                out.append(String.format("\\u%04x", (int) c));
            }
        }
        return out.toString();
    }
}
