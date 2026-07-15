package com.xilidou.jooj.bootstrap;

/**
 * {@code ~/.jooj/.env} 首次缺失时写入的模板内容。
 *
 * <p>风格参考 hermes 的 {@code .env.example},裁成 jooj 实际用得到的 6 个 section:
 * Anthropic / DeepSeek / Recovery fallback / 通用日志 / Memory / MCP。每个 section 有
 * 分隔线 + 一两行说明 + 注释占位 {@code # KEY=...},用户解开注释填值即可。
 *
 * <h3>为什么硬编码字符串而不是 classpath 资源</h3>
 *
 * <p>{@link JoojEnvBootstrap} 在 {@code EnvironmentPostProcessor} 阶段跑,那时候
 * Spring 的 ResourceLoader 还没初始化。直接走 {@code getResourceAsStream} 也行
 * 但需要处理 fallback 异常,反不如 50 行字符串简单 —— 改起来一目了然。
 */
final class JoojEnvTemplate {

    private JoojEnvTemplate() {
        // 静态常量持有者,禁止实例化
    }

    /**
     * 默认模板内容。新人打开 {@code ~/.jooj/.env} 第一眼就知道:
     * 有哪些 key 可填、走哪条路径、怎么解开注释。
     */
    static final String DEFAULT = """
            # =============================================================================
            # jooj env file - put KEY=VALUE here (no quotes)
            # This file is auto-generated. Edit freely; uncomment a line to enable a value.
            #
            # Format notes (Spring Boot 4 dotenv parser):
            #   ✅ KEY=value              直接写,值里有空格也行
            #   ❌ KEY="value"            引号会被当成值的一部分
            #   ⚠️ # 行首注释可以,行内 # 也是字面字符,不是注释
            # =============================================================================

            # =============================================================================
            # Anthropic API (二选一:官方 vs 公司代理)
            # =============================================================================
            # 路径 1:官方 API key —— https://console.anthropic.com/
            # ANTHROPIC_API_KEY=

            # 路径 2:公司代理 / OpenAI-compatible Bearer token(与 ANTHROPIC_API_KEY 二选一)
            # ANTHROPIC_AUTH_TOKEN=

            # 覆盖 base URL —— 默认 https://api.anthropic.com
            # ANTHROPIC_BASE_URL=

            # 默认模型 id,例如 claude-sonnet-4-6
            # MODEL_ID=

            # =============================================================================
            # DeepSeek(Anthropic 兼容端点)—— 可选。api-key 非空才注册 provider,
            # 之后 model ID 以 "deepseek-" 开头(如 deepseek-chat)的请求自动路由过来。
            # =============================================================================
            # 从 https://platform.deepseek.com/api_keys 申请
            # DEEPSEEK_API_KEY=

            # 覆盖默认端点(默认 https://api.deepseek.com/anthropic)
            # DEEPSEEK_BASE_URL=

            # 默认模型 id(默认 deepseek-chat;推理任务可换 deepseek-reasoner)
            # DEEPSEEK_MODEL=

            # =============================================================================
            # OpenAI Chat Completions —— 可选(P2 Step H)。api-key 非空才注册 provider,
            # 之后 model ID 以 gpt-/o1-/o3-/o4-/chatgpt- 开头的请求自动路由过来。
            # 常见搭配:MODEL_ID=claude-sonnet-4-6 + FALLBACK_MODEL_ID=gpt-4o-mini —— Anthropic
            # 主模型 + OpenAI fallback 跨 provider 兜底。
            # =============================================================================
            # 从 https://platform.openai.com/api-keys 申请
            # OPENAI_API_KEY=

            # 覆盖默认端点(默认 https://api.openai.com;走 Azure OpenAI proxy 时改这个)
            # OPENAI_BASE_URL=

            # 若想让 OpenAI 做主 provider 而非仅 fallback,把 MODEL_ID 直接设成 gpt-4o-mini 等
            # OpenAiHttpClient 内部按 model 前缀路由,不依赖 default-model 字段。
            # OPENAI_DEFAULT_MODEL=

            # =============================================================================
            # Recovery (s11) —— 连续 529 ≥ 3 时切换到的 fallback 模型
            # =============================================================================
            # FALLBACK_MODEL_ID=

            # =============================================================================
            # Logging
            # =============================================================================
            # 打开 HTTP wire logs(DEBUG 级别记录 Anthropic 请求/响应正文)
            # JOOJ_LOG_HTTP=DEBUG

            # =============================================================================
            # Memory / Tasks paths(需要时 override application.yml 的默认值)
            # =============================================================================
            # JOOJ_MEMORY_DIR=.memory
            # JOOJ_TASKS_DIR=.tasks

            # =============================================================================
            # MCP (s19) —— 大部分 server 配置在 application.yml,这里只放 secret
            # =============================================================================
            # 例:传给某个自定义 MCP server env block 的 API key
            # MCP_GITHUB_TOKEN=
            """;
}
