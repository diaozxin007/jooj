# Marvis - Java Agent Harness

> 用 Java 从零实现 [shareAI-lab/learn-claude-code](https://github.com/shareAI-lab/learn-claude-code) 的 20 课。
> Anthropic 官方协议 + OkHttp 直连 + Spring Boot 4 + Jackson 2.x，零 SDK 依赖。

---

## ✨ 当前状态

✅ **s01 完成**：Agent Loop（一个 loop + 一个 bash 工具，能完成多轮 self-correction）

```
$ printf "数一下当前目录有多少个 .java 文件\nq\n" | ./mvnw -q exec:java -Dexec.mainClass="com.xilidou.marvis.MarvisApplication"

18:32:26 INFO  [SkillRegistry] Loaded skill: bash (1 tools)
s01: Agent Loop (Java)
输入问题，回车发送。输入 q 退出。

s01 >> $ find . -name "*.java" -not -path "./target/*" | wc -l
46
当前 marvis 项目（不含 target/）共有 46 个 .java 文件。

s01 >>
```

---

## 🏗 架构

```
com.xilidou.marvis/
├── S01.java                     ← s01 Agent Loop 入口(R1 重构后已删,统一走 MarvisApplication)
├── MarvisApplication.java       ← Spring Boot 启动占位（暂未启用 IoC）
├── JacksonConfig.java           ← 全局 ObjectMapper 工厂
└── harness/
    ├── agent/
    │   └── AgentLoopHarness.java    ← 核心 Loop（s01 实现）
    ├── http/                        ← Anthropic 协议层
    │   ├── AnthropicClient.java         ← LLM 客户端接口
    │   ├── AnthropicHttpClient.java     ← OkHttp 实现 + Builder + fromEnv
    │   ├── ApiKeyAuth / BearerTokenAuth ← 双 auth 策略
    │   ├── AnthropicException
    │   └── dto/                         ← 11 个协议 DTO
    │       ├── ContentBlock + 5 个实现   ← Text/ToolUse/ToolResult/Thinking/Unknown
    │       ├── CreateMessageRequest/Response
    │       ├── MessageParam, ToolDef, InputSchema, Usage, StopReason
    │
    ├── base/                        ← Harness 基础设施
    │   ├── SkillRegistry.java           ← Skill 池（s07 雏形：loadOnDemand）
    │   ├── McpAdapter.java              ← MCP 桥接（R1 重构后已删,占位代码不留）
    │   └── ToolCall.java
    │
    ├── entity/                      ← Skill 层数据模型
    │   ├── ToolDefinition.java          ← 业务侧工具定义（含 InputSchema）
    │   └── ToolResult.java
    │
    ├── skill/
    │   ├── Skill.java                   ← Skill 接口
    │   └── impl/
    │       ├── BashSkill.java               ← ✅ 真实实现（含安全黑名单 / 超时 / 截断）
    │       ├── FileSystemSkill.java         ← ⚠️ 当前 mock，Week 4 改真
    │       ├── TerminalSkill.java           ← ⚠️ 当前 mock，Week 4 改真
    │       └── SearchSkill.java             ← ⚠️ 当前 mock，Week 5 改真
    │
    └── archive/                     ← 历史代码（保留可追溯，不在主线）
        ├── day3/  (11 个文件)            ← Day3 学习时的 AgentHarness 实现
        └── day4/  (3 个文件)             ← Day4 学习时的 RAGAgentHarness
```

### 设计决策

详见 `docs/design-decisions.md`（如有）和 [[Marvis 项目 Obsidian 笔记]]。

| ADR | 决策 | 理由 |
|-----|------|------|
| 001 | 用 OkHttp + 自写 DTO 替代官方 anthropic-java SDK | 学习协议透明度 > 工程化便利 |
| 002 | DTO 全部 @Data 风格（不用 record）| 与 entity/* 风格一致，少混用 |
| 003 | Spring Boot 4 + Jackson 2.x（不用 Spring 自带的 Jackson 3.x）| Jackson 3.x 包名变了，与代码 import 不兼容 |
| 004 | ToolDefinition 持有 InputSchema（不是松散 Map）| 编译期类型安全 + 适配代码 1:1 |
| 005 | 归档 Day3-4 实验代码到 archive/ 而不是删除 | 学习路径可追溯 |
| 006 | UI 输出（System.out）和日志（SLF4J）严格分离 | 日志前缀不污染 CLI 体验 |

---

## 🚀 快速开始

### 1. 配置认证

设置环境变量（不需要 .env 也能跑）：

```bash
# 方案 A：官方 Anthropic
export ANTHROPIC_API_KEY=sk-ant-xxx
export MODEL_ID=claude-sonnet-4-6

# 方案 B：Anthropic-compatible 代理（自建 / 第三方提供商）
export ANTHROPIC_AUTH_TOKEN=xxx
export ANTHROPIC_BASE_URL=https://your-proxy.example.com
export MODEL_ID=your-model-id
```

或在项目根目录建 `.env` 文件（已被 .gitignore）：

```env
ANTHROPIC_API_KEY=sk-ant-xxx
MODEL_ID=claude-sonnet-4-6
```

### 2. 编译

```bash
./mvnw clean compile
```

### 3. 运行

```bash
# 交互式 REPL
./mvnw -q exec:java -Dexec.mainClass="com.xilidou.marvis.MarvisApplication"

# 或用 raw java（启动更快，无 maven 噪音）
./mvnw -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
java -cp "$(cat /tmp/cp.txt):target/classes" com.xilidou.marvis.MarvisApplication
```

### 4. 跑测试

```bash
./mvnw test
# Tests run: 13, Failures: 0  ← 应该都通过
```

### 5. 调试 HTTP 请求

```bash
java -Dmarvis.log.http=DEBUG -cp ... com.xilidou.marvis.MarvisApplication
# 输出会包含完整的 Anthropic 请求体
```

---

## 🧪 测试覆盖

### 13 个单元测试（1.7 秒跑完）

| 测试类 | 数量 | 覆盖 |
|--------|------|------|
| `AgentLoopHarnessTest` | 5 | Loop 行为：end_turn / tool_use / 多 tool / 完整回传 / 未知工具错误 |
| `ContentBlockDeserializationTest` | 7 | DTO 多态反序列化 + 序列化 + 真实 fixture |
| `MarvisApplicationTests` | 1 | Spring Boot context loads |

### Smoke Test（真实 API）

```bash
# 测试 OkHttp 链路（手写请求）
java -cp "$CP" com.xilidou.marvis.harness.http.SmokeTest

# 测试 AnthropicHttpClient + DI 链路
java -cp "$CP" com.xilidou.marvis.harness.http.HttpClientSmokeTest
```

---

## 📚 学习路径

按 [shareAI-lab/learn-claude-code 20 课] 推进：

| 章节 | 状态 | 文件 |
|------|------|------|
| s01 Agent Loop | ✅ 完成 | `agent/AgentLoopHarness.java` + `skill/impl/BashSkill.java` |
| s02 Tool Use | 🚧 部分（SkillRegistry 抽象就绪）| 需要再注册 1-2 个真实工具验证"加工具不改 loop" |
| s03 Permission | ⏳ Week 4 | - |
| s04 Hooks | ⏳ Week 4 | - |
| s05 TodoWrite | ⏳ Week 5 | - |
| s06 Subagent | ⏳ Week 5 | - |
| s07 Skill Loading | 🚧 骨架（SkillRegistry.loadOnDemand）| 需要 Skill manifest |
| s08 Context Compact | ⏳ Week 6 | - |
| s09 Memory | ⏳ Week 6 | - |
| s10 System Prompt | ⏳ Week 6 | - |
| s11 Error Recovery | ⏳ Week 7 | - |
| s12 Task System | ⏳ Week 7 | - |
| s13 Background Tasks | ⏳ Week 8 | - |
| s14 Cron Scheduler | ⏳ Week 8 | - |
| s15 Agent Teams | ⏳ Week 9 | - |
| s16 Team Protocols | ⏳ Week 9 | - |
| s17 Autonomous Agents | ⏳ Week 9 | - |
| s18 Worktree Isolation | ⏳ Week 9 | - |
| s19 MCP Plugin | 🚧 未开始（R1 删除占位 McpAdapter）| - |
| s20 Comprehensive | ⏳ Week 12 | - |

---

## 🛠 技术栈

| 类别 | 选择 | 版本 |
|------|------|------|
| Java | JDK | 17 |
| 框架 | Spring Boot | 4.1（仅作启动占位）|
| HTTP | OkHttp | 5.4.0（用 `okhttp-jvm` artifact，避开 KMP 主 jar 空陷阱）|
| JSON | Jackson | 2.18.2（显式锁定，不用 Spring 的 3.x）|
| 日志 | SLF4J + Logback | Spring Boot 默认 |
| 工具 | Lombok | Spring Boot 默认 |
| 配置 | dotenv-java | 3.2.0 |
| 构建 | Maven Wrapper | - |
| 测试 | JUnit 5 | Spring Boot 默认 |

---

## 🐛 踩过的坑（学习记录）

### 坑 1: macOS BSD sed 的 `-i` 必须显式空字符串

```bash
# ❌ Linux GNU sed 风格（macOS 静默跳过）
sed -i 's/old/new/' file

# ✅ macOS BSD sed
sed -i '' 's/old/new/' file
```

### 坑 2: OkHttp 5.x 主 jar 是空的（Kotlin Multiplatform 陷阱）

OkHttp 5.x 重构成 Kotlin Multiplatform，**主 artifact `okhttp` 只有 META-INF**。

```xml
<!-- ❌ 编译报 "程序包 okhttp3 不存在" -->
<artifactId>okhttp</artifactId>
<version>5.4.0</version>

<!-- ✅ 用 JVM 平台 artifact -->
<artifactId>okhttp-jvm</artifactId>
<version>5.4.0</version>

<!-- ✅ 或退回 4.12.0 单 jar -->
<artifactId>okhttp</artifactId>
<version>4.12.0</version>
```

`okhttp-jvm` 才是 KMP 时代的"JVM 实现"，含完整 class 文件。
本项目最终选择 `okhttp-jvm:5.4.0` 享受新特性（Java 21 优化等）。

### 坑 3: Spring Boot 4.x 默认 Jackson 3.x（包名变了）

`tools.jackson.databind` 替代了 `com.fasterxml.jackson.databind`。需要显式声明 `jackson-databind:2.x` 强制覆盖。

### 坑 4: Claude Sonnet 4.6 的 Extended Thinking

模型在 tool_use 之前会返回 `{"type": "thinking", ...}` 块。DTO 必须支持，否则反序列化炸。
**修复**：加 `ThinkingBlock` + `defaultImpl = UnknownBlock.class` 双保险。

### 坑 5: `@JsonTypeInfo` 默认会"吃掉" type 字段

Jackson 反序列化多态时 type 字段被消费掉，序列化回去时不会自动加回来。
**修复**：`visible = true` + 每个实现类提供 `getType()`。

### 坑 6: assistant content 必须**完整**回传

第二轮请求里第一轮 assistant 返回的 `[text + tool_use]` 完整数组要原封不动塞回去。少一块（特别是 thinking 的 signature）就 502。

### 坑 7: Mock 持有 List 引用导致快照污染

`MockAnthropicClient` 保存 `request.messages` 引用，但 loop 后续修改这个 List，让历史断言失败。
**修复**：Mock 收到 request 时 `new ArrayList<>(messages)` 拍快照。

详见 [[AI Agent 实战/Marvis_迁移到OkHttp]]。

---

## 📅 进度

- 2026-06-22：s01 Agent Loop 完成；从 anthropic-java SDK 完整迁移到 OkHttp 直连；13 个测试通过；归档 Day3-4 实验代码；统一 SLF4J 日志。

---

## 🔗 相关

- [shareAI-lab/learn-claude-code](https://github.com/shareAI-lab/learn-claude-code) - 主线学习教材
- [Anthropic Messages API](https://docs.anthropic.com/en/api/messages) - 协议参考
- [Datawhale Agent Learning Hub](https://github.com/datawhalechina/Agent-Learning-Hub) - 中文路线索引
