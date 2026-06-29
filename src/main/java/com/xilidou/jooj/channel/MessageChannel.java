package com.xilidou.jooj.channel;

/**
 * MessageChannel —— 异步 push 入站渠道的统一接口。
 *
 * <h3>定位</h3>
 *
 * <p>**只覆盖 push 类(server 主动推 / 客户端长轮询)渠道**:微信 / Discord / Telegram / Slack。
 * CLI(REPL 阻塞读 stdin)和 Web(REST 同步)走自己的路径不实现这个接口 ——
 * 它们已经天然有「调一次回一次」的语义,套这个抽象反而加复杂度。
 *
 * <p>这是 YAGNI 取舍:Channel 抽象的真正价值是给「我不主动调它它会推消息进来」的渠道
 * 一个统一接入位,不是把所有入口刷一遍同一接口。
 *
 * <h3>实现契约</h3>
 *
 * <ul>
 *   <li>{@link #name()} 唯一标识,用于 session 路由(如 "weixin")</li>
 *   <li>{@link #start} 启动后台轮询/订阅,把入站消息提交给 dispatcher。
 *       通常起 daemon 线程,这里返回不阻塞</li>
 *   <li>{@link #stop} 优雅关停,释放线程 / 长连接</li>
 *   <li>{@link #sendOutbound} 把 LLM 回复发回给具体 peer</li>
 * </ul>
 *
 * <h3>错误处理</h3>
 *
 * <p>Channel 内部应当**自己重试 + 容错**(轮询断流、token 失效、网络抖动等),
 * 不要把 transient 错误冒泡到 dispatcher。
 */
public interface MessageChannel {

    /** 渠道名,例如 "weixin"。session id 路由用 "chat:{name}:{peerId}"。 */
    String name();

    /**
     * 启动入站。开始把消息推给 dispatcher.dispatch(...);非阻塞调用,
     * 内部起后台线程跑长轮询 / WebSocket / 订阅。
     *
     * <p>{@code dispatcher == null} 视为 dry-run(只跑连接但不喂 LLM,调试用)。
     */
    void start(InboundDispatcher dispatcher);

    /** 停止入站,释放资源。重复调用幂等。 */
    void stop();

    /**
     * 把回复发给指定 peer。{@code text} 可能为 multi-line / markdown,
     * Channel 自行决定是否分段 / 转纯文本。
     */
    void sendOutbound(String peerId, String text);

    /** 当前是否在运行(给状态查询 / 健康检查用)。 */
    boolean isRunning();
}
