# jooj 项目 —— Claude 上下文

> 由 Claudian 维护,记录 jooj 项目的架构约定和历史决策。
> Next session 在 jooj 目录下工作时会自动加载。

## 配置架构(2026-07-14 重构完成)

原 `JoojProperties.java`(484 行上帝配置类)已按子系统拆成 **14 个独立 `*Properties` 类**,通过 `@ConfigurationPropertiesScan`(在 `JoojApplication` 上)自动装配。**不再有顶层 JoojProperties 类**。

### 子系统 → Properties 映射

| 子系统 | Properties 类 | yml 前缀 | 章节 |
|---|---|---|---|
| Anthropic | `http/AnthropicProperties` | `jooj.anthropic` | s01 |
| DeepSeek | `http/DeepSeekProperties` | `jooj.deepseek` | - |
| Compact | `compact/CompactProperties` | `jooj.compact` | s08 + s22D |
| Memory | `memory/MemoryProperties` | `jooj.memory` | s09 + Hermes T3 |
| Permission | `permission/PermissionProperties` | `jooj.permission` | s03 |
| Skills | `skill/SkillProperties` | `jooj.skills` | s07 |
| Prompt | `prompt/PromptProperties` | `jooj.prompt` | s10 |
| Recovery | `agent/RecoveryProperties` | `jooj.recovery` | s11 |
| Tasks | `tasks/TasksProperties` | `jooj.tasks` | s12 |
| Cron | `cron/CronProperties` | `jooj.cron` | s14 |
| Team | `team/TeamProperties` | `jooj.team` | s15/s17/s18 |
| Concurrency | `config/ConcurrencyProperties` | `jooj.concurrency` | - |
| Mcp | `mcp/McpProperties` | `jooj.mcp` | s19 |
| Search | `search/SearchProperties` | `jooj.search` | s21 D25 |

### 命名规范

| 后缀 | 含义 |
|---|---|
| `*Properties.java` | `@ConfigurationProperties` —— yml → Java 桥接 |
| `*Configuration.java` | `@Configuration` + `@Bean` 装配 |
| `*Config.java` | 运行时 POJO,业务代码直接消费(如 `CompactConfig` `MemoryConfig` `TaskConfig` `CronConfig` `TeamConfig` `SearchConfig`) |
| 工具类(如 `JsonMappers`) | 不带上述后缀 |

### 三分法(Properties/Config/Configuration)

有派生字段/校验的子系统走**三分法**(6 个):Compact / Memory / Tasks / Cron / Team / Search。
- `*Properties` — 只做 yml 桥接,原始类型字段
- `*Config` — 运行时 POJO,已解析 Path 或计算过默认值,业务代码依赖它
- `*Configuration` — `@Bean` 装配把 Properties 拍平成 Config

无派生字段的子系统**豁免三分法**(8 个):Anthropic / DeepSeek / Permission / Skills / Prompt / Recovery / Concurrency / Mcp —— 业务代码直接注入 Properties。

## 关键 Bean 装配约定

| Bean | Configuration 类 | 位置 |
|---|---|---|
| `okHttpClient` / `joojObjectMapper` / `anthropicProvider` / `httpAuth` | `http/HttpClientConfiguration` | http/ |
| `modelRouter` | `http/ModelRouterConfiguration` | http/ |
| `deepseekProvider`(条件) | `http/DeepSeekProviderConfiguration` | http/ |
| `compactConfig` / `compactPipeline` | `compact/CompactConfiguration` | compact/ |
| `memoryConfig` / `memoryStore` / `pendingMemoryStore` / `backgroundReviewer` / `memoryService` | `memory/MemoryConfiguration` | memory/ |
| `permissionPipeline` | `permission/PermissionConfiguration` | permission/ |
| `taskConfig` / `taskStore` / `taskService` | `tasks/TasksConfiguration` | tasks/ |
| `cronConfig` / `cronStore` / `cronService` / `agentLock` | `cron/CronConfiguration` | cron/ |
| `teamConfig` / `messageBus` / `gitClient` | `team/TeamConfiguration` | team/ |
| `searchConfig` / `searchStore` / `searchService` | `search/SearchConfiguration` | search/ |
| `sessionStore` / `sessionService` / `agentLockProvider` | `session/SessionConfiguration` | session/ |
| `transcriptStore` / `transcriptService` | `transcript/TranscriptConfiguration` | transcript/ |
| `weixinSdk`(条件) | `weixin/WeixinConfiguration` | weixin/ |
| `joojTaskScheduler` / `joojBgExecutor` / `joojTeammateExecutor` | `config/JoojExecutors` | config/ |

## 重构历史

- **2026-06-24**:D 期规划,识别出上帝配置类问题(重构 D 组件清单)
- **2026-07-14**:配置架构重构完成,12 个 commit
  - 阶段 1(5 commit):命名统一 + 拆 Mcp/Compact/Memory/Permission/Recovery
  - 阶段 2(4 commit):拆 Tasks/Cron/Team/Search/Skills/Prompt/Concurrency
  - 阶段 3(2 commit):拆 Anthropic/DeepSeek + 删空 JoojProperties.java
  - 收官:更新架构图 + 设计笔记
- **减重**:484 → 0 行(彻底删除),测试全程 970 pass 零回归

## 命令备忘

```bash
# 编译
./mvnw clean compile

# 单元测试
rtk mvn test  # 970 tests, ~20-30s

# 定位配置
rtk grep -rn "@ConfigurationProperties" src/main/java  # 找所有 Properties 类
rtk grep -rn "@Bean" src/main/java  # 找所有 Bean 装配点

# 运行主程序
./mvnw exec:java -Dexec.mainClass="com.xilidou.jooj.JoojApplication"
```
