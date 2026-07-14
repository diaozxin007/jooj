package com.xilidou.jooj.http;

import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;
import com.xilidou.jooj.llm.LlmClient;
import com.xilidou.jooj.llm.domain.LlmException;
import com.xilidou.jooj.llm.domain.LlmRequest;
import com.xilidou.jooj.llm.domain.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型路由器 —— 按 model ID 前缀将请求派发到对应的 {@link ModelProvider}。
 *
 * <p>同时实现:
 * <ul>
 *   <li>{@link AnthropicClient} — 老 signature,兼容既有调用方(Steps C-G 前)</li>
 *   <li>{@link LlmClient} — 新 canonical signature,新调用方使用</li>
 * </ul>
 * 两个 @Primary bean 都由 {@link ModelRouterConfiguration} 装配,caller 按需选择。
 *
 * <h3>路由逻辑</h3>
 * <ol>
 *   <li>从请求取 model ID</li>
 *   <li>遍历注册的 provider,找第一个 prefix 匹配的</li>
 *   <li>无匹配时 fallback 到默认 provider(第一个注册的 = Anthropic)</li>
 * </ol>
 */
public class ModelRouter implements AnthropicClient, LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private final List<ModelProvider> providers;
    private final ModelProvider defaultProvider;

    /** prefix → provider 缓存,避免每次遍历 */
    private final Map<String, ModelProvider> cache = new ConcurrentHashMap<>();

    public ModelRouter(List<ModelProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("At least one ModelProvider must be registered");
        }
        this.providers = List.copyOf(providers);
        this.defaultProvider = providers.get(0);
        log.info("ModelRouter initialized with {} provider(s): {}, default={}",
                providers.size(),
                providers.stream().map(ModelProvider::name).toList(),
                defaultProvider.name());
    }

    @Override
    public CreateMessageResponse createMessage(CreateMessageRequest req) {
        ModelProvider provider = resolveProvider(req.getModel());
        log.debug("Routing model='{}' → provider='{}'", req.getModel(), provider.name());
        return provider.createMessage(req);
    }

    /** P2 canonical routing entrypoint. */
    @Override
    public LlmResponse createMessage(LlmRequest req) throws LlmException {
        ModelProvider provider = resolveProvider(req.getModel());
        log.debug("Routing (canonical) model='{}' → provider='{}'", req.getModel(), provider.name());
        return provider.createMessage(req);
    }

    /**
     * 解析 model ID → provider。先查缓存,miss 则遍历 prefix 匹配。
     */
    ModelProvider resolveProvider(String model) {
        if (model == null || model.isBlank()) {
            return defaultProvider;
        }
        return cache.computeIfAbsent(model, this::findByPrefix);
    }

    private ModelProvider findByPrefix(String model) {
        for (ModelProvider p : providers) {
            for (String prefix : p.modelPrefixes()) {
                if (model.startsWith(prefix)) {
                    return p;
                }
            }
        }
        log.warn("No provider matched model='{}', falling back to default='{}'",
                model, defaultProvider.name());
        return defaultProvider;
    }

    // ── 可观测性 ──────────────────────────────────────────────────

    /** 返回注册的 provider 列表(只读,供测试 / actuator 用)。 */
    public List<ModelProvider> getProviders() {
        return providers;
    }

    /** 返回默认 provider。 */
    public ModelProvider getDefaultProvider() {
        return defaultProvider;
    }
}
