package com.xilidou.jooj.web;

/**
 * "对话框"里的一条展示项。这是**给用户看的**视图,不是 LLM 上下文。
 *
 * <p>raw session history(`List&lt;MessageParam&gt;`)混住了很多东西:用户输入、assistant
 * text/thinking/tool_use、tool_result、cron/memory/inbox 注入、recovery 恢复、L1/L4 归档占位
 * ……这些统称"agent 通信协议",不能直接搬到 UI 上。{@link ChatHistoryMapper} 把它翻译成
 * 一串 ChatItem 之后,前端只按 {@link Type} 分派渲染就行。
 *
 * <p>不显式建"回合"结构 —— 每条 {@link Type#USER_INPUT} 就是回合的锥点,后面直到下一个
 * USER_INPUT 之间的 items 都属于这个回合。视觉分组交给前端。
 *
 * <p>{@code id} 稳定基于 raw history 里的位置(见 mapper 实现),前端可以拿它做 dedup —
 * 切走再切回时,`GET /api/chat-history` 会返回完整列表,前端整体重渲染,不会重复插入。
 *
 * @param id         稳定 id (e.g. "msg-42-b-1" = raw history index 42 的第 1 个 block)
 * @param type       决定前端怎么渲染
 * @param role       "user" | "assistant" | "system" —— 主要给前端选气泡颜色
 * @param text       {@link Type#USER_INPUT} / {@link Type#ASSISTANT_TEXT} / {@link Type#THINKING}
 *                   下的文字内容;其它 type 为 null
 * @param toolCall   仅 {@link Type#TOOL_CALL}
 * @param notice     仅 {@link Type#SYSTEM_NOTICE}
 * @param createdAt  ISO-8601 UTC。raw history 没时间戳,这里由 mapper 补当前时间——只用来做
 *                   排序/展示;前端不应依赖它做"多久之前"这类相对时间
 */
public record ChatItem(
        String id,
        Type type,
        String role,
        String text,
        ToolCall toolCall,
        SystemNotice notice,
        String createdAt) {

    /** 展示项类型。跟 raw history 的 role 是两码事——role 是通信协议,type 是 UI 分派。 */
    public enum Type {
        /** 用户真实输入(已剥掉 &lt;relevant_memories&gt; 前缀)。 */
        USER_INPUT,

        /** LLM 的文字回复(拼 TextBlock)。 */
        ASSISTANT_TEXT,

        /** LLM 的思考过程(ThinkingBlock);前端默认折叠。 */
        THINKING,

        /** 一 tool_use = 一张卡;含配对 tool_result 摘要。 */
        TOOL_CALL,

        /** cron/inbox/L1/L4 归档、recovery Fatal —— 折叠系统卡。 */
        SYSTEM_NOTICE
    }

    /**
     * 工具调用卡。默认只显 name + inputSummary,展开才看完整 input/result。
     *
     * @param toolUseId          Anthropic 分配的 tool_use_id,前端可作卡片 key
     * @param name               工具名 (bash / read_file / ...)
     * @param inputSummary       input JSON 头 120 字左右——列表页显示用
     * @param inputFull          完整 input JSON,点开展示
     * @param resultPreview      result 前 300 字;若 result 走 L3 stub,就是 stub 提示串本身
     * @param resultFull         完整 result 字串
     * @param resultTruncated    resultPreview vs resultFull 是否被截过
     * @param isBackground       BashTool run_in_background=true 起步就 true;其它情况 false
     * @param backgroundId       bg 任务的 bg_XXXX id;非 bg 或还没 placeholder 时 null。
     *                           用于把后续 {@code <task_notification id="bg_XXXX">} 精确回填到本卡
     * @param status             "ok" / "error" / "background_pending"(bg 还没 drain notification)
     */
    public record ToolCall(
            String toolUseId,
            String name,
            String inputSummary,
            String inputFull,
            String resultPreview,
            String resultFull,
            boolean resultTruncated,
            boolean isBackground,
            String backgroundId,
            String status) {}

    /**
     * 系统通知卡。cron 触发 / teammate inbox / L1&amp;L4 归档 / recovery Fatal 都走这里,
     * 前端根据 {@link Source} 挑图标 + 摘要显示,默认折叠,点开看 fullText。
     *
     * @param source    通知来源分类
     * @param summary   一行摘要 —— 如 "⏰ 定时任务触发"、"📥 收到 2 条队友消息"
     * @param fullText  原文,点开看
     */
    public record SystemNotice(Source source, String summary, String fullText) {

        public enum Source {
            /** [Scheduled] xxx —— cron 任务触发注入的 prompt。 */
            CRON,
            /** [Inbox] N message(s) from teammates: ... —— 队友消息 drain。 */
            INBOX,
            /** [snipped N messages(, archived to ...)] —— L1 SnipCompactor 归档占位。 */
            ARCHIVE_L1,
            /** [Conversation summary] (N messages archived to ...) —— L4 HistoryCompactor 摘要。 */
            ARCHIVE_L4,
            /** [Error] ... —— RecoveryCoordinator Fatal,给用户知道 AI 这次挂了。 */
            ERROR
        }
    }
}
