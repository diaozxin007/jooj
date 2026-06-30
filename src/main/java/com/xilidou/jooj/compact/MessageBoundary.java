package com.xilidou.jooj.compact;

import com.xilidou.jooj.http.dto.ContentBlock;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.ToolResultBlock;
import com.xilidou.jooj.http.dto.ToolUseBlock;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * tool_use ↔ tool_result 配对边界保护工具。
 *
 * <p>背景:Anthropic Messages API 强制要求 assistant.tool_use(id=X) 必须有一条
 * user.tool_result(tool_use_id=X) 配对。{@link SnipCompactor} 裁中间消息时如果切口落在
 * 这对消息之间,会留下"孤儿":
 * <ul>
 *   <li>头部孤儿:head 内有 tool_use,但配对的 tool_result 被裁掉了 → 400</li>
 *   <li>尾部孤儿:tail 内有 tool_result,但配对的 tool_use 被裁掉了 → 400</li>
 * </ul>
 *
 * <p>策略:
 * <ul>
 *   <li>{@link #adjustHeadEnd}:头切口往后挪,把 tool_result 也保留在头部</li>
 *   <li>{@link #adjustTailStart}:尾切口往前挪,把 tool_use 也保留在尾部</li>
 * </ul>
 *
 * <p>极端情况(headEnd 推到 ≥ tailStart)由 {@link SnipCompactor} 兜底:不进行裁剪。
 *
 * <h3>s21 Demo 25:加固为 self-consistency walk</h3>
 *
 * <p>老实现只检查相邻 1 格,在以下场景漏:
 *
 * <pre>
 *   [0] user "hi"
 *   [1] assistant thinking + text          ← 没 tool_use
 *   [2] user "你用哪个模型"
 *   [3] assistant tool_use(date)           ← 待 snip
 *   [4] user tool_result(date)             ← 孤儿点
 *   [5] assistant tool_use(schedule_cron)
 * </pre>
 *
 * <p>SnipCompactor 选 {@code headEnd=2, tailStart=5},删掉 {@code [2,5)} 三条 ——
 * 老 {@code adjustTailStart(5)} 只看 {@code msgs[5]} 是不是 tool_result + {@code msgs[4]}
 * 是不是 tool_use。这个例子里 {@code msgs[5]} 不是 tool_result,**根本不进 if**;
 * 但孤儿不是 {@code msgs[5]} 而是被 snip 掉的 {@code msgs[4]} 跟 {@code msgs[3]} 一起被裁,
 * 然后 {@code msgs[5]} 之后有任何继续 ref 的 tool_result 也漏判。
 *
 * <p>新实现:**self-consistency walk** —— 每次扩展后扫一遍 head/tail 范围,看
 * 范围内是否还有 unmatched tool_use(head) / unmatched tool_result(tail),有就继续扩。
 * 这样能修复跨多条边界的孤儿。
 *
 * <p>这是 Demo 8 教训(Teammate.trimWindow 切坏 tool_use ↔ tool_result)在 SnipCompactor
 * 路径的复发 —— 老 adjustTailStart 假设 tool_use 永远紧贴 tool_result(现实里 thinking
 * + text + tool_use 同 message,中间隔几条纯文本 user 之后才有 tool_result 的也存在)。
 */
public final class MessageBoundary {

    private MessageBoundary() {
        // 工具类禁止实例化
    }

    /**
     * 头切口调整:避免在头部留下孤儿 tool_use。
     *
     * <p>self-consistency walk:扫 {@code msgs[0..headEnd)} 内所有 tool_use,如果某个
     * tool_use_id 没有对应的 tool_result 在 head 范围内,就把 headEnd 往后扩一格。
     * 重复扫直到 head 自洽(或 headEnd ≥ msgs.size())。
     *
     * @param msgs    完整消息列表
     * @param headEnd 当前头切口(exclusive,即 msgs[0..headEnd) 是头部保留区)
     * @return 调整后的 headEnd(≥ 原值;最大到 msgs.size())
     */
    public static int adjustHeadEnd(List<MessageParam> msgs, int headEnd) {
        if (headEnd <= 0 || headEnd >= msgs.size()) {
            return headEnd;
        }
        int max = msgs.size();
        while (headEnd < max) {
            // openUses = head 范围内出现的 tool_use_id 集合
            Set<String> openUses = new HashSet<>();
            collectUseIds(msgs.subList(0, headEnd), openUses);
            // 扫 head 范围内的 tool_result,把已配对的 use_id 移除
            removeIfResultIn(msgs.subList(0, headEnd), openUses);
            if (openUses.isEmpty()) break;
            // 还有 unmatched tool_use → 扩 headEnd 一格,下一轮再扫
            headEnd++;
        }
        return headEnd;
    }

    /**
     * 尾切口调整:避免在尾部留下孤儿 tool_result。
     *
     * <p>self-consistency walk:扫 {@code msgs[tailStart..)} 内所有 tool_result,如果
     * 某个 tool_use_id 没有对应的 tool_use 在 tail 范围内,把 tailStart 往前缩一格。
     * 重复扫直到 tail 自洽(或 tailStart ≤ 0)。
     *
     * @param msgs      完整消息列表
     * @param tailStart 当前尾切口(inclusive,即 msgs[tailStart..) 是尾部保留区)
     * @return 调整后的 tailStart(≤ 原值;最小到 0)
     */
    public static int adjustTailStart(List<MessageParam> msgs, int tailStart) {
        if (tailStart <= 0 || tailStart >= msgs.size()) {
            return tailStart;
        }
        while (tailStart > 0) {
            // openResults = tail 范围内 tool_result 携带的 tool_use_id 集合
            Set<String> openResults = new HashSet<>();
            collectResultIds(msgs.subList(tailStart, msgs.size()), openResults);
            // 扫 tail 范围内的 tool_use,把已配对的 use_id 移除
            removeIfUseIn(msgs.subList(tailStart, msgs.size()), openResults);
            if (openResults.isEmpty()) break;
            // 还有 unmatched tool_result → 把 tailStart 往前缩一格,下一轮再扫
            tailStart--;
        }
        return tailStart;
    }

    /** 收集 messages 范围内所有 tool_use 的 id 到 set。 */
    private static void collectUseIds(List<MessageParam> range, Set<String> out) {
        for (MessageParam m : range) {
            if (!(m.getContent() instanceof List<?> blocks)) continue;
            for (Object b : blocks) {
                if (b instanceof ToolUseBlock tu && tu.getId() != null) {
                    out.add(tu.getId());
                }
            }
        }
    }

    /** 收集 messages 范围内所有 tool_result 的 tool_use_id 到 set。 */
    private static void collectResultIds(List<MessageParam> range, Set<String> out) {
        for (MessageParam m : range) {
            if (!(m.getContent() instanceof List<?> blocks)) continue;
            for (Object b : blocks) {
                if (b instanceof ToolResultBlock tr && tr.getToolUseId() != null) {
                    out.add(tr.getToolUseId());
                }
            }
        }
    }

    /**
     * 扫 range,把范围内有 tool_result(tool_use_id=X)的 X 从 ids 中 remove。
     * 给 head walk 用:openUses 装 unmatched tool_use_id,这里找 head 内的 tool_result 来配对。
     */
    private static void removeIfResultIn(List<MessageParam> range, Set<String> ids) {
        if (ids.isEmpty()) return;
        for (MessageParam m : range) {
            if (!(m.getContent() instanceof List<?> blocks)) continue;
            for (Object b : blocks) {
                if (b instanceof ToolResultBlock tr && tr.getToolUseId() != null) {
                    ids.remove(tr.getToolUseId());
                }
            }
        }
    }

    /**
     * 扫 range,把范围内有 tool_use(id=X)的 X 从 ids 中 remove。
     * 给 tail walk 用:openResults 装 unmatched tool_use_id from tool_result,
     * 这里找 tail 内的 tool_use 来配对。
     */
    private static void removeIfUseIn(List<MessageParam> range, Set<String> ids) {
        if (ids.isEmpty()) return;
        for (MessageParam m : range) {
            if (!(m.getContent() instanceof List<?> blocks)) continue;
            for (Object b : blocks) {
                if (b instanceof ToolUseBlock tu && tu.getId() != null) {
                    ids.remove(tu.getId());
                }
            }
        }
    }

    /** 判定:assistant 消息中是否含 tool_use block。 */
    public static boolean hasToolUse(MessageParam m) {
        if (!"assistant".equals(m.getRole())) return false;
        if (!(m.getContent() instanceof List<?> blocks)) return false;
        for (Object b : blocks) {
            if (b instanceof ToolUseBlock) return true;
        }
        return false;
    }

    /** 判定:user 消息中是否含 tool_result block。 */
    public static boolean isToolResult(MessageParam m) {
        if (!"user".equals(m.getRole())) return false;
        if (!(m.getContent() instanceof List<?> blocks)) return false;
        for (Object b : blocks) {
            if (b instanceof ToolResultBlock) return true;
        }
        return false;
    }

    /**
     * 判定一个 ContentBlock 是否是 tool_use(给 MicroCompactor 用,虽然它现在不用)。
     * 保留为静态工具,未来 L3 budget 可能需要。
     */
    @SuppressWarnings("unused")
    static boolean isToolUseBlock(ContentBlock b) {
        return b instanceof ToolUseBlock;
    }
}
