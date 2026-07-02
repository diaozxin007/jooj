package com.xilidou.jooj.prompt;

import com.xilidou.jooj.JoojTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link SystemPromptAssembler} 的核心行为(s10):
 * <ul>
 *   <li>section 顺序固定:identity → tools → workspace → skills → memory</li>
 *   <li>memory / skills 为空时整段跳过(不留 trailing 分隔符)</li>
 *   <li>同 context 二次调用命中缓存,只组装一次</li>
 *   <li>context 变化后缓存失效,重新组装</li>
 *   <li>identity / tools 段从 yml 取(application-test.yml 里的测试值)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(JoojTestConfig.class)
class SystemPromptAssemblerTest {

    @Autowired SystemPromptAssembler assembler;

    @BeforeEach
    void clearCache() {
        // 测试间互不影响:每个测试用与众不同的 context 让缓存自然失效。
    }

    @Test
    @DisplayName("4 个 section 全有时,按顺序拼接")
    void all_sections_assembled_in_order() {
        var ctx = new PromptContext(
                List.of("bash", "read_file"),
                "/test/workspace",
                "## fact-1\nUser likes Python.",
                ""   // 空 skill catalog,本测试不验证 skills 段
        );

        String prompt = assembler.assemble(ctx);

        // 验证顺序:identity 先于 tools 先于 workspace 先于 memory
        // (skills 段被空 catalog 跳过,不参与顺序断言)
        int identityIdx = prompt.indexOf("Test identity");
        int toolsIdx = prompt.indexOf("Available tools");
        int workspaceIdx = prompt.indexOf("Working directory");
        int memoryIdx = prompt.indexOf("Memory:");

        assertTrue(identityIdx >= 0, "identity 段必须在 prompt 里");
        assertTrue(toolsIdx > identityIdx, "tools 段必须在 identity 之后");
        assertTrue(workspaceIdx > toolsIdx, "workspace 段必须在 tools 之后");
        assertTrue(memoryIdx > workspaceIdx, "memory 段必须在 workspace 之后");

        // 验证 workspace 含 ctx.workspace
        assertTrue(prompt.contains("/test/workspace"));
        // 验证 memory 含 catalog 正文
        assertTrue(prompt.contains("User likes Python"));
    }

    @Test
    @DisplayName("memoryCatalog 为空时,memory section 整段跳过")
    void empty_memory_section_is_skipped() {
        var ctx = new PromptContext(
                List.of("bash"),
                "/test/workspace",
                "",   // 空 memory
                ""    // 空 skill catalog
        );

        String prompt = assembler.assemble(ctx);

        assertFalse(prompt.contains("Memory:"),
                "memory 段应该整个跳过,memory header 不应出现");
        // 不应留 trailing 分隔符
        assertFalse(prompt.endsWith("\n\n"),
                "拼接末尾不应有多余分隔符,实际:" + prompt);
    }

    @Test
    @DisplayName("memoryCatalog 为 null 时,memory section 同样跳过")
    void null_memory_section_is_skipped() {
        var ctx = new PromptContext(
                List.of("bash"),
                "/test/workspace",
                null,
                null
        );

        String prompt = assembler.assemble(ctx);

        assertFalse(prompt.contains("Memory:"));
    }

    @Test
    @DisplayName("同 context 二次调用,命中缓存(返回同一字符串实例)")
    void same_context_hits_cache() {
        var ctx = new PromptContext(
                List.of("bash"),
                "/test/cache",
                "memory-A",
                ""
        );

        String first = assembler.assemble(ctx);
        String second = assembler.assemble(ctx);

        // 命中缓存时返回同一引用(字符串拼接每次都会 new 一个新 String,
        // 同引用是缓存命中的强证据)
        assertSame(first, second, "同 context 二次调用应该命中缓存返回同一字符串实例");
    }

    @Test
    @DisplayName("context 字段变化后,缓存失效,重新组装")
    void context_change_invalidates_cache() {
        var ctx1 = new PromptContext(List.of("bash"), "/ws-1", "", "");
        var ctx2 = new PromptContext(List.of("bash"), "/ws-2", "", "");   // workspace 变了

        String prompt1 = assembler.assemble(ctx1);
        String prompt2 = assembler.assemble(ctx2);

        assertNotSame(prompt1, prompt2);
        assertTrue(prompt1.contains("/ws-1"));
        assertTrue(prompt2.contains("/ws-2"));
    }

    @Test
    @DisplayName("identity 从 yml 取; tools 段动态生成工具名 + hint")
    void identity_and_tools_loaded_from_yaml() {
        var ctx = new PromptContext(List.of("bash", "read_file"), "/x", "", "");

        String prompt = assembler.assemble(ctx);

        // application-test.yml 里设了:
        //   identity: "Test identity. Act, don't explain."
        //   tools-hint: "Use tools wisely."
        assertTrue(prompt.contains("Test identity. Act, don't explain."),
                "identity 应该来自 application-test.yml");
        // tools 段动态拼接:从 enabledTools 生成列表 + toolsHint
        assertTrue(prompt.contains("Available tools: bash, read_file."),
                "tools 段应包含动态生成的工具名列表");
        assertTrue(prompt.contains("Use tools wisely."),
                "tools 段应包含 toolsHint(来自 application-test.yml)");
    }

    @Test
    @DisplayName("skillCatalog 非空时,skills section 出现在 workspace 之后、memory 之前")
    void skills_section_appears_between_workspace_and_memory() {
        var ctx = new PromptContext(
                List.of("bash"),
                "/test/workspace",
                "## fact-1\nUser likes Python.",
                "- **find-skills**: discover and install agent skills\n"
        );

        String prompt = assembler.assemble(ctx);

        int workspaceIdx = prompt.indexOf("Working directory");
        int skillsIdx = prompt.indexOf("Available skills");
        int memoryIdx = prompt.indexOf("Memory:");

        assertTrue(skillsIdx > workspaceIdx, "skills 段在 workspace 之后");
        assertTrue(memoryIdx > skillsIdx, "memory 段在 skills 之后");
        assertTrue(prompt.contains("find-skills"), "skill name 应该出现");
        assertTrue(prompt.contains("discover and install"), "skill description 应该出现");
    }

    @Test
    @DisplayName("currentContext() 从真实 ToolRegistry / MemoryService 读出")
    void current_context_reads_real_state() {
        PromptContext ctx = assembler.currentContext();

        assertNotNull(ctx);
        assertNotNull(ctx.enabledTools(), "enabledTools 不应为 null");
        assertFalse(ctx.enabledTools().isEmpty(),
                "测试 profile 至少有 bash/filesystem/todo/load_skill 等工具");
        assertNotNull(ctx.workspace());
        // memoryCatalog 在测试环境下默认是空字符串(MemoryService 读 .memory/MEMORY.md
        // 不存在时返回 ""),不强断言具体值
    }
}
