package com.xilidou.jooj.session;

import com.xilidou.jooj.http.dto.ContentBlock;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.ToolResultBlock;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 加载 history 时的兜底净化器(s21 Demo 25 副作用)。
 *
 * <p>背景:Anthropic Messages API 强制 {@code tool_use(id=X) ↔ tool_result(tool_use_id=X)}
 * 严格配对。一旦磁盘上的 session JSON 含孤儿 tool_result(对应 tool_use 不存在),
 * 下一次发给 Anthropic 直接 400。
 *
 * <p>孤儿来源:
 * <ul>
 *   <li>历史 SnipCompactor / Teammate.trimWindow 切坏配对(Demo 8 / Demo 25 修过,
 *       但磁盘上已存的坏数据不会自动修复)</li>
 *   <li>jooj 进程在 tool_use 已写但 tool_result 还没回写时崩溃 →
 *       重启后 history 里有 tool_use 没 tool_result(头部孤儿,这种较少见)</li>
 *   <li>从其他来源(切换 backend / 手动改文件)倒入的不完整 history</li>
 * </ul>
 *
 * <p>策略 —— **保守、向前兼容、原子**:
 * <ol>
 *   <li>扫整个 list 收集所有 {@code tool_use.id}</li>
 *   <li>从每条 user message 里过滤 {@code tool_result} 块,
 *       如果其 {@code tool_use_id} 不在集合里就丢弃</li>
 *   <li>过滤后内容空的 message 整条丢</li>
 *   <li>不动任何 {@code TextBlock} / {@code ThinkingBlock} 等其他 block</li>
 * </ol>
 *
 * <p>**不主动写回磁盘** —— scrub 是读盘后的运行期净化,JSON 仍是 source-of-truth。
 * 下一次正常 saveHistory 才会用净化后的列表覆盖 JSON(自然修好磁盘上的坏数据)。
 *
 * <p>反向情况(头部孤儿:tool_use 没 tool_result)Anthropic API 同样会拒。这种 case 较罕见
 * (只有崩溃半截才出),scrub 也兜:把孤儿 tool_use 块过滤掉。
 */
@Slf4j
public final class HistoryScrubber {

    private HistoryScrubber() {
        // utility class
    }

    /**
     * 在 {@code history} 上做一次 self-consistent 净化,返回新 list(不原地 mutate)。
     * 调用方负责把返回值塞回 cache / 列表引用。
     *
     * @param history 原 history(可空 / 可不变)
     * @return 净化后的 history;不含孤儿 tool_use / tool_result
     */
    public static List<MessageParam> scrub(List<MessageParam> history) {
        if (history == null || history.isEmpty()) return history;

        // Pass 1: 收集所有 tool_use id 和所有 tool_result 引用的 id
        Set<String> useIds = new HashSet<>();
        Set<String> resultIds = new HashSet<>();
        for (MessageParam m : history) {
            if (m == null) continue;
            if (m.getContent() instanceof List<?> blocks) {
                for (Object b : blocks) {
                    if (b instanceof ToolUseBlock tu && tu.getId() != null) {
                        useIds.add(tu.getId());
                    } else if (b instanceof ToolResultBlock tr && tr.getToolUseId() != null) {
                        resultIds.add(tr.getToolUseId());
                    }
                }
            }
        }

        // 没任何配对相关的块 → 啥都不用做
        if (useIds.isEmpty() && resultIds.isEmpty()) {
            return history;
        }

        // Pass 2: 过滤孤儿
        List<MessageParam> out = new ArrayList<>(history.size());
        int droppedBlocks = 0;
        int droppedMessages = 0;
        for (MessageParam m : history) {
            if (m == null) {
                out.add(null);
                continue;
            }
            Object content = m.getContent();
            if (!(content instanceof List<?> blocks)) {
                // String / Map / 其他原样保留
                out.add(m);
                continue;
            }
            List<ContentBlock> kept = new ArrayList<>(blocks.size());
            for (Object b : blocks) {
                if (b instanceof ToolResultBlock tr) {
                    String tid = tr.getToolUseId();
                    if (tid == null || !useIds.contains(tid)) {
                        droppedBlocks++;
                        continue;
                    }
                } else if (b instanceof ToolUseBlock tu) {
                    String tid = tu.getId();
                    if (tid == null || !resultIds.contains(tid)) {
                        droppedBlocks++;
                        continue;
                    }
                }
                if (b instanceof ContentBlock cb) {
                    kept.add(cb);
                }
            }
            if (kept.isEmpty()) {
                // 这条 message 所有 block 都被丢 → 整条 drop
                droppedMessages++;
                continue;
            }
            // block 数量没变 → 复用原 message 引用,避免无谓拷贝
            if (kept.size() == blocks.size()) {
                out.add(m);
            } else {
                out.add(new MessageParam(m.getRole(), kept));
            }
        }

        if (droppedBlocks > 0 || droppedMessages > 0) {
            log.warn("[HistoryScrub] dropped {} orphan tool block(s) + {} now-empty message(s) " +
                            "from history of size {}",
                    droppedBlocks, droppedMessages, history.size());
        }
        return out;
    }
}
