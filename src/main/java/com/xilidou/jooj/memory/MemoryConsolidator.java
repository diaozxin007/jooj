package com.xilidou.jooj.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JsonMappers;
import com.xilidou.jooj.http.AnthropicClient;
import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;
import com.xilidou.jooj.http.dto.MessageParam;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Memory Consolidation 子系统 —— LLM 去重合并 memory(类似数据库的 VACUUM)。
 *
 * <p>对应 Python s09 的 {@code consolidate_memories()}。
 *
 * <p>触发时机:memory 文件数 ≥ {@link MemoryConfig#consolidateThreshold()}
 * (默认 10)。由 AgentLoop 在 Extractor 之后调用。
 *
 * <p>核心策略:
 * <ol>
 *   <li>列所有 memory + 完整 body 拼成 catalog</li>
 *   <li>调 LLM:"按规则去重 / 删过期 / 合并矛盾, 返回整理后的 JSON 列表"</li>
 *   <li>解析 JSON, 把每条转成 MemoryFile(校验字段)</li>
 *   <li>**原子性写入**:全部新文件先写入(同 name 自动覆盖), 然后删除"老有新没"的差集</li>
 * </ol>
 *
 * <p>与 Python 教学版的关键差异 —— 原子性:
 * <ul>
 *   <li>Python 版先 {@code f.unlink()} 删所有旧文件, 再写新的。中途失败 = 数据全丢</li>
 *   <li>Java 版先 LLM 调用 + 全部解析校验, 再开始改文件系统</li>
 *   <li>不无脑删空目录, 用"差集"(旧文件名集合 - 新文件名集合)只删该删的</li>
 *   <li>同 name 写入靠 {@link MemoryStore#write} 的覆盖语义, 不需要先删后写</li>
 * </ul>
 *
 * <p>失败处理:
 * <ul>
 *   <li>LLM 调用失败 → log + 不动磁盘, 返回 0</li>
 *   <li>JSON 解析失败 → log + 不动磁盘, 返回 0</li>
 *   <li>所有 item 都缺字段 → log + 不动磁盘, 返回 0(避免把 memory 全删)</li>
 *   <li>部分 item 缺字段 → 跳过该 item, 继续处理其他</li>
 * </ul>
 *
 * <p>不做的事(留给生产 / 后续):
 * <ul>
 *   <li>不做 CC 真实实现的 4 道闸门(time / scan throttle / session / lock),
 *       教学版直接看文件数</li>
 *   <li>不做 forked agent 隔离(直接同步调 LLM)</li>
 *   <li>不做 dry-run / preview(直接 commit)</li>
 *   <li>不做版本备份(rebuildIndex 之后旧文件直接消失,
 *       依赖外部 git 做版本控制)</li>
 * </ul>
 */
@Slf4j
public class MemoryConsolidator {

    /** Consolidator LLM 调用 max_tokens(返回整理后的全集, 给 3000 留足空间)。*/
    private static final int CONSOLIDATE_MAX_TOKENS = 3000;

    /** catalog 字符数上限, 避免单次 prompt 自己爆。*/
    private static final int CATALOG_MAX_CHARS = 16000;

    /** JSON 数组提取 pattern。*/
    private static final Pattern JSON_ARRAY = Pattern.compile("\\[.*\\]", Pattern.DOTALL);

    /** Consolidator 的独立 SYSTEM prompt。*/
    private static final String CONSOLIDATE_SYSTEM =
            "You are a memory consolidator. Output ONLY a JSON array, no preamble.";

    private final MemoryStore store;
    private final MemoryConfig config;
    private final AnthropicClient client;
    private final String model;
    private final ObjectMapper json;

    public MemoryConsolidator(MemoryStore store, MemoryConfig config,
                              AnthropicClient client, String model) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        if (config == null) throw new IllegalArgumentException("config must not be null");
        if (client != null && (model == null || model.isBlank())) {
            throw new IllegalArgumentException("model required when client provided");
        }
        this.store = store;
        this.config = config;
        this.client = client;
        this.model = model;
        this.json = JsonMappers.newMapper();
    }

    /**
     * 触发整理。memory 数 < 阈值 / 无 client 时直接返回 0。
     *
     * @return consolidate 后的 memory 总数(0 = 没动)
     */
    public int consolidate() {
        if (client == null) return 0;

        List<MemoryFile> originals = store.list();
        if (originals.size() < config.consolidateThreshold()) {
            return 0;
        }

        // 1) 拼 prompt
        String catalog = renderCatalog(originals);
        String prompt = buildConsolidatePrompt(catalog);

        // 2) 调 LLM
        String text;
        try {
            CreateMessageRequest req = CreateMessageRequest.builder()
                    .model(model)
                    .maxTokens(CONSOLIDATE_MAX_TOKENS)
                    .system(CONSOLIDATE_SYSTEM)
                    .messages(List.of(MessageParam.user(prompt)))
                    .build();
            CreateMessageResponse resp = client.createMessage(req);
            text = resp.firstText();
        } catch (Exception e) {
            log.warn("[Memory] consolidation LLM call failed: {}", e.toString());
            return 0;
        }

        if (text == null || text.isBlank()) return 0;

        // 3) 解析
        List<Map<String, Object>> items = parseConsolidatedItems(text);
        if (items == null) return 0;

        // 4) 全部解析成 MemoryFile, 全部通过校验后才动磁盘(原子性)
        List<MemoryFile> consolidated = new ArrayList<>();
        for (Map<String, Object> item : items) {
            MemoryFile mem = MemoryExtractor.toMemoryFile(item);
            if (mem != null) {
                consolidated.add(mem);
            }
        }

        // 安全闸门:LLM 把所有 memory 都判成无效 → 不动磁盘
        // (避免一次 buggy LLM 响应让用户丢光所有 memory)
        if (consolidated.isEmpty()) {
            log.warn("[Memory] consolidation returned 0 valid items, skipping (originals preserved)");
            return 0;
        }

        // 5) 原子性写入: 写新 + 删差集
        return commit(originals, consolidated);
    }

    // ─────────────────────────────────────────────────────────────
    //  实现细节
    // ─────────────────────────────────────────────────────────────

    /**
     * 拼 catalog: 每条 memory 完整呈现(name + description + body), 让 LLM
     * 看清全貌再合并。截断到 {@link #CATALOG_MAX_CHARS} 防止 prompt 爆。
     */
    String renderCatalog(List<MemoryFile> files) {
        StringBuilder sb = new StringBuilder();
        for (MemoryFile f : files) {
            sb.append("## ").append(f.getFilename()).append('\n');
            sb.append("name: ").append(f.getName()).append('\n');
            sb.append("description: ").append(f.getDescription() == null ? "" : f.getDescription()).append('\n');
            sb.append("type: ").append(f.getType().slug()).append('\n');
            sb.append(f.getBody() == null ? "" : f.getBody()).append('\n');
            sb.append('\n');
        }
        String out = sb.toString();
        if (out.length() > CATALOG_MAX_CHARS) {
            out = out.substring(0, CATALOG_MAX_CHARS);
        }
        return out;
    }

    /**
     * Consolidator 的 prompt。
     *
     * <p>关键约束:
     * <ul>
     *   <li>规则 1-4 明确:合并 / 删过期 / 控总量 / 保用户偏好</li>
     *   <li>"Keep the total under 30 memories" 给 LLM 一个目标上限</li>
     *   <li>"Preserve important user preferences above all" 防止 LLM 误删高价值 fact</li>
     *   <li>同 Extractor 一样, 要求返回 JSON 数组 {name, type, description, body}</li>
     * </ul>
     */
    static String buildConsolidatePrompt(String catalog) {
        return "Consolidate the following memory files. Rules:\n" +
                "1. Merge duplicates into one\n" +
                "2. Remove outdated/contradicted memories\n" +
                "3. Keep the total under 30 memories\n" +
                "4. Preserve important user preferences above all\n" +
                "Return a JSON array. Each item: {name, type, description, body}.\n\n" +
                catalog;
    }

    /** 抠 JSON 数组并解析。失败返回 null。*/
    List<Map<String, Object>> parseConsolidatedItems(String text) {
        Matcher matcher = JSON_ARRAY.matcher(text);
        if (!matcher.find()) {
            log.warn("[Memory] no JSON array found in consolidation response");
            return null;
        }
        try {
            return json.readValue(matcher.group(),
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("[Memory] failed to parse consolidation JSON: {}", e.toString());
            return null;
        }
    }

    /**
     * 提交整理结果 —— 集合差删除 + 同 name 覆盖写入。
     *
     * <p>步骤:
     * <ol>
     *   <li>计算"该删的" = 旧 filename 集合 - 新 filename 集合</li>
     *   <li>调 store.write 写入所有新 memory(同 name 自动覆盖)</li>
     *   <li>调 store.delete 删除"该删的"</li>
     * </ol>
     *
     * <p>顺序:**先写后删**, 跟 ext4 的 fsync(file) 然后 fsync(parent) 一个思路。
     * 中途崩溃也只是"多了几个等待清理的孤儿文件", 不会丢任何应该保留的内容。
     */
    private int commit(List<MemoryFile> originals, List<MemoryFile> consolidated) {
        // 计算新文件名集合
        Set<String> newFilenames = new HashSet<>();
        for (MemoryFile m : consolidated) {
            // 注意:filename 由 MemoryFile.of 时算出,跟 store.write 内部用的 slug 一致
            newFilenames.add(m.getFilename());
        }

        // 计算该删的(旧有, 新没)
        List<String> toDelete = new ArrayList<>();
        for (MemoryFile orig : originals) {
            if (orig.getFilename() != null && !newFilenames.contains(orig.getFilename())) {
                toDelete.add(orig.getFilename());
            }
        }

        // 先写入新内容(同 name 覆盖)
        int written = 0;
        for (MemoryFile m : consolidated) {
            try {
                store.write(m);
                written++;
            } catch (Exception e) {
                log.warn("[Memory] consolidation: failed to write {}: {}",
                        m.getFilename(), e.toString());
            }
        }

        // 再删除差集
        int deleted = 0;
        for (String filename : toDelete) {
            try {
                if (store.delete(filename)) deleted++;
            } catch (Exception e) {
                log.warn("[Memory] consolidation: failed to delete {}: {}", filename, e.toString());
            }
        }

        log.info("[Memory] consolidated {} → {} memories (wrote {}, deleted {})",
                originals.size(), consolidated.size(), written, deleted);
        return consolidated.size();
    }
}
