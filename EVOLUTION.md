# Jooj 项目演进时间线

> 基于 9 个 git commit 整理，按时间顺序排列。
> 标注 ⚠️ **已被重构** 的代码段。

---

## C1 · `7982c0a` — 2026-06-22 18:38
### feat: initial commit - s01 Agent Loop 完成 + 迁移到 OkHttp 直连

**主要改动：**

这是整个项目的奠基提交，一次性建立了完整的骨架。核心是脱离 anthropic-java 官方 SDK，改用 OkHttp 4.12 + Jackson 2.18 **直连 Anthropic Messages API**，从而对 HTTP 协议层拥有完全的掌控权。主要内容包括：

- **HTTP 层**（`harness/http/`）：`AnthropicClient` 接口 + `AnthropicHttpClient` 实现，双鉴权策略（`ApiKeyAuth` / `BearerTokenAuth`），11 个 DTO 覆盖 thinking / tool_use / tool_result / unknown 等所有 block 类型。
- **Agent Loop**（`harness/agent/AgentLoopHarness`）：构造器注入 + `fromEnv` 工厂，支持 thinking + tool_use + tool_result 多轮回传，工具调用通过 `executeToolUses()` 方法封装。
- **Skill 抽象**（`harness/base/`, `harness/skill/`）：`SkillRegistry` + `BashSkill`（真实实现，含黑名单/超时/截断）+ `FileSystemSkill`（⚠️ **当前是 mock 字符串，不做真实文件操作**）。
- **测试体系**：`MockAnthropicClient` + `ResponseFixtures`，13 个单元测试 1.7s 跑完。
- 将 Day3 / Day4 的早期实验代码归档到 `archive/`，保留可追溯。

**学到的设计原则：**

> **自己实现协议层比依赖 SDK 学到更多。** 直连 API 意味着你必须理解每一个字段、每一种 block type、每一次 HTTP 错误——这些细节是真正理解 LLM 工具调用机制的基础。`UnknownBlock` 的兜底设计体现了**防御性编程**：对外部系统永远不要假设"只会出现我知道的类型"。

⚠️ **被后续重构影响的代码：**
- `pom.xml` 中 OkHttp `4.12.0` 的 `okhttp` artifact → 被 **C2** 升级替换
- `AgentLoopHarness.parseToolInput` 用 `Map.class` raw type → 被 **C2** 改为 `TypeReference`
- `AgentLoopHarness.executeToolUses()` 封装方法 → 被 **C4** 拆解内联删除
- `FileSystemSkill` mock 实现 → 被 **C3** 改为真实文件操作
- `SkillRegistry` 手工注册逻辑 → 被 **C5** 改为 Spring `@Component` 自动注入

---

## C2 · `916efb3` — 2026-06-22 19:34
### refactor: 升级 OkHttp 5.4 + Jackson TypeReference 类型安全

**主要改动：**

两处精准的质量修复，没有新增功能：

1. **`pom.xml`**：`okhttp 4.12.0` → `okhttp-jvm 5.4.0`。OkHttp 5.x 进入 KMP（Kotlin Multiplatform）时代后，JVM artifact 的 `artifactId` 改为 `okhttp-jvm`，直接用 `okhttp` 主 jar 会拿到空 META-INF 包。
2. **`AgentLoopHarness.parseToolInput`**：`json.convertValue(input, Map.class)` 改为 `new TypeReference<Map<String, Object>>() {}`，消除 raw type 警告，同时在嵌套泛型场景下类型更安全。

**学到的设计原则：**

> **依赖升级本身就是重构。** OkHttp 5.x 的 artifact 命名变化是典型的"知识盲区型 bug"——功能测试全过但运行时拿不到类。`TypeReference` vs `Map.class` 的差异则说明：**Java 泛型擦除是运行时雷区**，在涉及反序列化的场景应优先使用带完整泛型信息的 Token 类。

---

## C3 · `c35e3c8` — 2026-06-22 19:41
### feat(week4-step1): FileSystemSkill 改真，添加 read/write/edit/glob 4 个工具

**主要改动：**

将 C1 中的 mock `FileSystemSkill` 改为真实文件操作，新增 4 个工具，为后续 Permission（C5）和 Hook（C6）提供真实的"危险操作"触发场景：

- `read_file(path, limit?)`：读文件，可选截断到前 N 行
- `write_file(path, content)`：写文件，自动创建父目录
- `edit_file(path, old_text, new_text)`：替换第一次匹配，精确可控
- `glob(pattern)`：基于 Java NIO `PathMatcher` 支持 `*.java` / `**/*.md` 通配

关键新增了 `safePath()` 防御方法，拒绝 `../etc/passwd` 路径穿越和绝对路径逃逸。新增 19 个测试，覆盖正常 / 异常 / 边界路径，用 `@TempDir` 隔离每个测试的 workspace。

**学到的设计原则：**

> **防御编程（safePath）和权限控制（Permission）是两个层次，不能互相替代。** `safePath` 防的是"LLM 编造错误路径"（无意失误），Permission 防的是"LLM 故意操作危险路径"（策略执行）。两者并行存在、各司其职，混淆会导致其中一层的职责被稀释。

⚠️ **被后续重构影响的代码：**
- `AgentLoopHarness.fromEnv()` 手工 `new FileSystemSkill()` 注册 → 被 **C5** 改为 Spring 自动注入

---

## C4 · `49010ba` — 2026-06-22 19:48
### feat(week4-step2): Permission 三道闸门（s03）

**主要改动：**

新建 `harness/permission/` 包（9 个文件），实现三层串行权限检查流水线：

- **Gate 1 `DenyListGate`**：硬黑名单，`rm -rf /`、`sudo`、`mkfs`、fork bomb 等直接 `DENY`，不询问用户
- **Gate 2 `RuleBasedGate`**：规则门，workspace 外写入 / bash 危险关键字 → `ASK`，否则 `ALLOW`
- **Gate 3 `UserApprovalGate`**：把 `ASK` 状态交给 `UserApprover` 接口升级为最终 `ALLOW` 或 `DENY`
- **`UserApprover` 函数式接口**：`ConsoleUserApprover` 阻塞读 `[y/N]`，测试用 lambda `ALWAYS_ALLOW / ALWAYS_DENY`
- **三态 `PermissionDecision`（ALLOW/DENY/ASK）** 比 boolean 更准确表达意图
- `AgentLoopHarness` 集成：`executeToolUses` 加权限检查，`DENY` 时把原因作为 `tool_result` 回传 LLM

新增 18 个权限测试，累计 50 个测试全部通过。

**学到的设计原则：**

> **接口抽象虽小，扩展价值巨大。** `UserApprover` 只有一个方法，但将"审批渠道"从 CLI 控制台抽象出来，为 Web/IM/移动端审批留了统一扩展点。三态枚举比 boolean 更能精确表达业务意图——`ASK` 表示"需要进一步决策"，这个中间态在两值系统中根本无法表达。

⚠️ **被后续重构影响的代码：**
- `AgentLoopHarness.executeToolUses()` 内置 `permissions.check()` 调用 → 被 **C4'** `a4013f2` 上移到 loop 层，再被 **C6** 替换为 `hooks.triggerPreToolUse()`
- `AgentLoopHarness` 构造器的 `permissions` 参数调用路径 → 被 **C6** 改为通过 `PermissionHook` 桥接，Loop 层不再直接调用

---

## C4' · `a4013f2` — 2026-06-22 21:22
### refactor: 把权限检查上移到 agentLoop 层，executeOneTool 只做纯执行

**主要改动：**

这是 C4 后紧跟的架构修正，将工具调用的完整生命周期（**展示 → 权限 → 执行**）全部内联到 `agentLoop` 主循环中，而不是藏在 `executeToolUses` 封装方法里：

- **删除** `executeToolUses(List<ToolUseBlock>)` — 过度封装，阅读时需要跳转
- **新增** `executeOneTool(toolUse, args)` — 纯执行，不做权限，不做 UI header
- **新增** `printToolHeader(toolUse, args)` — 独立 UI 输出方法
- **新增测试** `loop_should_skip_tool_on_permission_deny`：验证 DENY 时工具不执行、原因回传 LLM、loop 继续到 `end_turn`

**学到的设计原则：**

> **内联比封装更可读，当封装层没有复用价值时。** `executeToolUses` 把三件事（UI / 权限 / 执行）混在一起，并且只被调用一次——这是过度封装的典型特征。拆开后，`agentLoop` 主循环一眼就能看到"先展示、再鉴权、再执行"的完整步骤，也为下一步 Hook 系统的替换（把 `permissions.check` 换成 `hooks.trigger`）做好了铺垫。

⚠️ **被后续重构影响的代码：**
- `agentLoop` 中的 `permissions.check()` 调用 → 被 **C6** 替换为 `hooks.triggerPreToolUse()`

---

## C5 · `cd12dcc` — 2026-06-22 19:56
### refactor(spring-1): Skill 自动注册（@Component + 构造器注入 List\<Skill\>）

**主要改动：**

让 Spring IoC 接管 Skill 注册，新增 Skill 不再需要修改 `SkillRegistry` 或 `fromEnv` 工厂：

- `SkillRegistry` 加 `@Component`，构造器显式标注 `@Autowired List<Skill>`（关键：Spring 默认选"最少参数"构造器，不加 `@Autowired` 会导致 0 工具注入）
- `BashSkill` / `FileSystemSkill` 加 `@Component`
- `SearchSkill` / `TerminalSkill` **故意不加 `@Component`**：当前是 mock，避免 LLM 拿假数据产生幻觉链
- `AgentLoopHarness.fromEnv()` 改为非 Spring 场景的 fallback，保持 `SmokeTest` 等原始入口可用

**学到的设计原则：**

> **Spring 的"最少参数"构造器默认选取规则是一个隐藏陷阱。** 多构造器场景下不加 `@Autowired` 注解，Spring 会静默选无参构造器，导致依赖不被注入且不报错——这类"零工具注入"的 bug 极难排查。`@Autowired` 显式标注是对意图的声明，也是防御此类静默失败的最佳实践。另：**mock Skill 故意不注册**体现了"不把假数据暴露给 LLM"的工程原则。

---

## C6 · `5530c7e` — 2026-06-22 21:58
### feat(week4-step3): Hook 系统 (s04)，permission 改为 PreToolUse hook

**主要改动：**

这是最大的架构跃迁。将硬编码的 `permissions.check()` 调用替换为事件驱动的 Hook 总线，`AgentLoopHarness` 不再感知"permission"概念：

- **新包 `harness/hook/`**：`HookEvent` enum（4 种事件）、`Hook` 接口族（4 个 `@FunctionalInterface`，各返回 `Optional<String>`：`empty` 放过，`of(reason)` 阻止）
- **`HookManager`（`@Component`）**：4 个 List 持有不同事件的 hook，`triggerXxx` 实现短路语义（第一个非空 Optional 终止），`@Autowired` 构造器自动注入所有 `@Component` hook
- **3 个 hook 实现**：`PermissionHook`（把 `PermissionPipeline` 桥接为 `OnPreToolUse`）、`ToolUseLogHook`、`LargeOutputHook`（大输出 >10KB 告警）
- `agentLoop` 中 `permissions.check()` 替换为 `hooks.triggerPreToolUse()`，加入 `triggerPostToolUse` 和 `triggerStop`

新增 8 个 Hook 相关测试，累计 58 个全部通过。

**学到的设计原则：**

> **插件总线（Event Bus / Hook）让核心循环对业务策略保持无知。** Loop 只发布事件，不关心谁处理——`PermissionHook` 是 permission 包的事，`MetricsHook` 是 metrics 包的事。加新行为只需新增 `@Component`，不改 Loop。这正是**开闭原则（OCP）**的典型体现。`@FunctionalInterface` 使 hook 可以用 lambda 写，测试成本极低。

⚠️ **被后续重构影响的代码：**
- `AgentLoopHarness.fromEnv()` 手工 `new MetricsHook()` 双 cast 注册 → 被 **C7** 补充

---

## C7 · `a96653e` — 2026-06-22 22:13
### feat(week4-step3): 补 MetricsHook（同时实现 PreToolUse + PostToolUse）

**主要改动：**

补齐 C6 遗漏的 `MetricsHook`，完成 Permission / Logging / Metrics 三件套：

- **`ToolMetric`**：线程安全指标对象，`callCount` / `totalLatencyNanos` / `failureCount` 均用 `AtomicLong`，提供 `avgLatencyMs()` / `failureRate()` 衍生计算
- **`MetricsHook`**：**同时实现 `OnPreToolUse` + `OnPostToolUse`**，Pre 用 `nanoTime()` 记录 `tool_use_id → startTime`，Post 查表算耗时更新 `ToolMetric`；失败判定：output 以 `"Error"` 或 `"Permission denied"` 开头
- `AgentLoopHarness.fromEnv()` 手工注册时需**显式 cast** `.register((Hook.OnPreToolUse) metrics)` + `.register((Hook.OnPostToolUse) metrics)` 解决重载歧义
- `@Component` 让 Spring 场景自动将同一实例注入两个 `List<Hook.OnXxx>`

新增 11 个 MetricsHook 测试，累计 69 个全部通过。

**学到的设计原则：**

> **一个类实现多个接口，是跨事件共享状态的优雅方案。** `MetricsHook` 的 Pre hook 记录开始时间，Post hook 读取并计算耗时——这必须是同一个实例才能共享 `startTime` map。用 `nanoTime()` 而非 `currentTimeMillis()` 是测量耗时的正确选择：**墙上时间会被 NTP 跳变干扰，`nanoTime` 单调递增专为耗时测量设计**。

---

## C8 · `993c124` — 2026-06-22 22:23
### feat(week5): TodoWrite (s05)，让 LLM 学会先列计划再执行

**主要改动：**

引入 `todo_write` 工具，让 LLM 在多步任务中持有显式计划状态：

- **新包 `harness/todo/`**：`TodoStatus` enum（`@JsonValue` + `@JsonCreator` 双端映射，Java 内部用 enum，JSON 用 snake_case 字符串）、`TodoItem`（`content + status`，`@JsonIgnoreProperties` 兼容未来扩展）、`TodoStore`（`@Component`，`synchronized` 方法保证并发安全，`replace()` 整体替换语义）
- **`TodoSkill`（`@Component`）**：工具名 `todo_write`，description 明确提示 "Call this BEFORE starting any multi-step task"，校验 `content` 和合法 `status`，屏幕打印彩色任务列表
- **`AgentLoopHarness` 改造**：System Prompt 加计划提示；加入 `NAG_THRESHOLD = 3` 计数器——连续 3 轮未调 `todo_write` 则注入 `<reminder>` 消息催促，调用后清零

新增 16 个测试（TodoStore 6 + TodoSkill 8 + Loop nag 逻辑 2），累计 85 个全部通过。

**学到的设计原则：**

> **`todo_write` 不是"做事工具"，是 LLM 的"思考脚手架"。** LLM 没有原生的跨轮次状态记忆，把计划显式写到工具调用里，才能在多轮对话中保持任务一致性。`nag` 机制则体现了另一个原则：**当 LLM 可能遗忘系统级约定时，Loop 有权主动注入提醒**——这是 Loop 级别的"守护者"职责，而非违反关注点分离。`@Autowired` 显式标注的再次出现（TodoSkill 多构造器），也印证了 C5 中踩过的 Spring 陷阱。

---

## 重构轨迹汇总

| 代码 / 设计 | 引入于 | 被改动于 | 改动原因 |
|---|---|---|---|
| OkHttp `4.12.0` `okhttp` artifact | C1 | **C2** | 5.x KMP 时代 artifact 改名为 `okhttp-jvm` |
| `Map.class` raw type 反序列化 | C1 | **C2** | 改用 `TypeReference` 消除泛型擦除风险 |
| `FileSystemSkill` mock 实现 | C1 | **C3** | 改为真实文件操作，供 Permission 触发场景使用 |
| `executeToolUses()` 封装方法 | C1 | **C4'** | 过度封装，拆解内联让 loop 步骤一目了然 |
| `permissions.check()` 在 loop 层直调 | C4 → C4' | **C6** | 替换为 `hooks.triggerPreToolUse()`，Loop 不再感知 permission |
| `SkillRegistry` 手工注册 | C1/C3 | **C5** | Spring `@Component` 自动注入，扩展 Skill 不改注册代码 |
| `AgentLoopHarness.fromEnv()` 手工 `new` | C1 | C3 → C5 → **C6/C7** | 每次新增能力都需更新，逐步迁移为 Spring 管理 |
