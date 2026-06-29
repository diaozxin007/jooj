# jooj — Java Agent Harness

> Java 实现的可扩展 AI Agent 运行时 —— Anthropic 协议直连 / Spring Boot 4 装配 / 多工具协同 / Web UI + CLI 双形态。
>
> 来源:基于 [shareAI-lab/learn-claude-code](https://github.com/shareAI-lab/learn-claude-code) 20 课的工程化实现,**当前已完成 s01 ~ s19**。

---

## 这是什么

**jooj 是一个完整的 LLM agent harness** —— 把"模型 + 工具 + 上下文 + 协作"装进一个 Java 进程,既能交互式跑(CLI / Web),也能后台调度(cron / teammate).

它能做什么:

- 跑 multi-turn agent loop —— LLM 调工具、看结果、自我修正,直到任务完成
- 用十几种内置工具 —— `bash` / `read_file` / `write_file` / `edit_file` / `glob` / `todo_write` / 任务管理 / cron / 团队通信 / git worktree / 等
- **MCP 协议外挂** —— 通过 `connect_mcp("filesystem")` 在运行期连接外部 MCP server,新工具立即可用,不用重启
- **Skill 双层加载** —— 启动期扫描三层 skill 目录(项目 / `~/.jooj/skills` / `~/.claude/skills`),catalog 注入 system prompt,LLM 按需 `load_skill(name)` 拉完整 body
- **持久 Memory** —— 跨 session 记住用户偏好、项目事实、参考资料(LLM 自动提取 + 索引)
- **Web sidebar** —— 浏览器里实时查看已加载的 skill / memory / 运行状态

---

## 快速开始

### 1. 运行(60 秒)

```bash
# 克隆 + 编译
git clone <this-repo> && cd jooj
./mvnw -DskipTests=true package

# 启动 web 模式
java -jar target/jooj-0.0.1-SNAPSHOT.jar --web
```

**首次启动时**:jooj 会自动建 `~/.jooj/.env` 模板文件(POSIX 0600,只有你能读),并提示打开它填 API key。

### 2. 配置 API key

打开 `~/.jooj/.env`,**解开注释填一组**(二选一):

```env
# 路径 1:Anthropic 官方
ANTHROPIC_API_KEY=sk-ant-...
MODEL_ID=claude-sonnet-4-6

# 路径 2:Anthropic 兼容代理(自建 / 公司 LLM 网关)
ANTHROPIC_AUTH_TOKEN=your-token
ANTHROPIC_BASE_URL=https://your-proxy.example.com
MODEL_ID=your-model-id
```

OS 环境变量优先级最高,所以也可以 `export ANTHROPIC_API_KEY=...` 直接跑。

### 3. 用

| 入口 | 命令 |
|---|---|
| **Web UI** | `./mvnw spring-boot:run -Dspring-boot.run.arguments=--web` → http://localhost:8080 |
| **CLI REPL** | `./mvnw spring-boot:run`(默认) |
| **打包后跑** | `java -jar target/jooj-0.0.1-SNAPSHOT.jar --web` |

Web UI 左上角 hamburger 按钮展开 sidebar,可以看 Skills / Memory / 运行状态。

---

## 安装 skill

jooj 兼容 [vercel-labs/skills](https://github.com/vercel-labs/skills) CLI 生态。任何 SKILL.md 风格的 skill 都能用:

```bash
# 用 npx 装一个全局 skill(到 ~/.claude/skills/,jooj 自动扫描)
npx skills add vercel-labs/skills --skill find-skills -g

# 或手动放到 ~/.jooj/skills/
mkdir -p ~/.jooj/skills/my-skill
cat > ~/.jooj/skills/my-skill/SKILL.md <<'EOF'
---
name: my-skill
description: 我自己写的 skill
---
# Skill body...
EOF
```

**装完不需要重启** —— 在 Web sidebar 点 ↻ 立即生效,或下一轮对话自动重扫(节流 1s)。

### Skill 三层优先级

```
<cwd>/skills/         ← 项目专属(进 git,团队共享)         优先级最高
~/.jooj/skills/       ← jooj 用户级(个人偏好)
~/.claude/skills/     ← Claude Code 共享池(npx 装这里)     优先级最低
```

同名 skill 按上面顺序覆盖。

---

## MCP 集成(外部工具协议)

jooj 内置 [MCP](https://modelcontextprotocol.io) 客户端,LLM 在对话中调 `connect_mcp("filesystem")` 就能连 stdio 子进程,自动发现该 server 的全部工具,以 `mcp__filesystem__read` 之类的前缀名暴露给 LLM。

配置方式 — 在 `application.yml` 或 `application-local.yml` 里:

```yaml
jooj:
  mcp:
    servers:
      filesystem:
        command: npx
        args: ["-y", "@modelcontextprotocol/server-filesystem", "/Users/me/projects"]
      git:
        command: npx
        args: ["-y", "@modelcontextprotocol/server-git", "--repo", "."]
```

未在 yml 配置的 server 名(如 `docs` / `deploy`)走内置 mock —— 用于教学演示,不需要真起子进程。

---

## 架构

```
com.xilidou.jooj/
├── JoojApplication.java        ← Spring Boot 主入口(--web 切换 web/cli)
├── JoojCliRunner.java          ← CLI 模式 ApplicationRunner
├── JoojProperties.java         ← 全部 jooj.* 配置(@ConfigurationProperties)
│
├── agent/        ← AgentLoopHarness、RecoveryCoordinator、BackgroundTaskManager
├── bootstrap/    ← JoojEnvBootstrap(EnvironmentPostProcessor): 启动期建 ~/.jooj/.env
├── compact/      ← s08 上下文压缩 pipeline
├── config/       ← 跨域配置 / Bean 装配
├── cron/         ← s14 cron scheduler + durable store(~/.jooj/cron/scheduled_tasks.json)
├── hook/         ← s04 lifecycle hooks(PreToolUse/PostToolUse/Metrics)
├── http/         ← Anthropic 协议层(OkHttp 直连 + 11 个 DTO)
├── mcp/          ← s19 MCP 客户端(SDK 2.0.0 stdio + mock 双 transport)
├── memory/       ← s09 持久 memory + LLM extractor + 索引
├── permission/   ← s03 工具权限(rule-based + CLI prompt)
├── prompt/       ← s10 system prompt 片段化组装 + ephemeral cache
├── skill/        ← s07 skill 三层加载 + LoadSkillTool
├── subagent/     ← s06/s15-17 subagent + teammate + autonomous idle
├── tasks/        ← s12 task system(create/claim/complete)
├── team/         ← s15-18 message bus + protocols + worktree isolation
├── todo/         ← s05 TodoWrite 工具
├── tool/         ← Tool 接口 + ToolRegistry + 各 *Tool 实现
└── web/          ← REST + 静态资源(ChatController/SidebarController + index.html/style.css/app.js)
```

### 关键设计原则

| 原则 | 说明 |
|---|---|
| **Anthropic 直连** | OkHttp + 自写 DTO,不依赖官方 SDK,协议透明 |
| **Spring Boot 4 装配** | 所有组件 `@Component`,工具/钩子自动发现 |
| **Jackson 2.x 显式锁定** | Boot 4 默认 Jackson 3,显式声明 `jackson-databind:2.18.2` 强制覆盖 |
| **路径分离** | 用户级 secret/state 进 `~/.jooj/`,项目级状态留 cwd(memory / mailboxes / transcripts) |
| **接口隔离外部依赖** | `McpTransport` `GitClient` 等接口 + 多实现,易测试易替换 |

---

## REST API

Web 模式启动后暴露:

### 对话相关 ([[ChatController]])

| Endpoint | 作用 |
|---|---|
| `POST /api/chat` | 喂一条 query,跑一轮 agent loop,返 reply / historySize / 本轮 toolCalls |
| `GET /api/history` | 完整对话历史(role + 揉平 text) |
| `POST /api/clear` | 清空 history |

### Sidebar 只读 ([[SidebarController]])

| Endpoint | 作用 |
|---|---|
| `GET /api/skills` | skill 概要列表(name + description,不含 body) |
| `POST /api/skills/rescan` | 强制重扫 skill 目录 + 返回更新后的列表(force,跳过节流) |
| `GET /api/memory` | memory catalog 字符串(markdown) |
| `GET /api/status` | 运行时状态(model / cwd / 工具数 / skill 数 / cron / memory 字数) |

注意:Web UI 跟 CLI REPL **共享同一个 history**(单 `AgentLoopHarness` 单例 + `agentLock` 全局互斥)。两边并行操作会串聊,v1 单用户场景接受。

---

## 测试

```bash
./mvnw test
# Tests run: 442, Failures: 0, Errors: 0
```

按包覆盖:agent / compact / cron / hook / http / mcp / memory / permission / prompt / skill / subagent / tasks / team / todo / tool / web / bootstrap —— 几乎每个包都有专门测试类,集成测试在 `JoojSpringIntegrationTest`(6 个端到端场景)。

---

## 学习路径(s01 ~ s20)

按 [shareAI-lab/learn-claude-code 20 课](https://github.com/shareAI-lab/learn-claude-code) 推进:

| 章节 | 状态 | 主要文件 |
|------|------|------|
| s01 Agent Loop | ✅ | `agent/AgentLoopHarness.java` |
| s02 Tool Use | ✅ | `tool/Tool.java` + `tool/ToolRegistry.java` |
| s03 Permission | ✅ | `permission/PermissionPipeline.java` |
| s04 Hooks | ✅ | `hook/HookManager.java` + `hook/impl/*` |
| s05 TodoWrite | ✅ | `tool/impl/TodoTool.java` + `todo/TodoStore.java` |
| s06 Subagent | ✅ | `subagent/Subagent.java` + `tool/impl/TaskTool.java` |
| s07 Skill Loading | ✅ | `skill/SkillRegistry.java` + `tool/impl/LoadSkillTool.java` |
| s08 Context Compact | ✅ | `compact/CompactPipeline.java` |
| s09 Memory | ✅ | `memory/MemoryService.java` |
| s10 System Prompt | ✅ | `prompt/SystemPromptAssembler.java` |
| s11 Error Recovery | ✅ | `agent/RecoveryCoordinator.java` |
| s12 Task System | ✅ | `tasks/TaskService.java` + `tool/impl/TasksTool.java` |
| s13 Background Tasks | ✅ | `agent/BackgroundTaskManager.java` |
| s14 Cron Scheduler | ✅ | `cron/CronService.java` + `tool/impl/CronTool.java` |
| s15 Agent Teams | ✅ | `team/MessageBus.java` + `subagent/Teammate.java` |
| s16 Team Protocols | ✅ | `team/ProtocolRegistry.java` |
| s17 Autonomous Agents | ✅ | `team/AutonomousIdle.java` |
| s18 Worktree Isolation | ✅ | `team/WorktreeService.java` + `tool/impl/WorktreeTool.java` |
| s19 MCP Plugin | ✅ | `mcp/McpRegistry.java` + `tool/impl/McpProxyTool.java` |
| s20 Comprehensive | ⏳ Week 12 | - |

---

## 技术栈

| 类别 | 选择 | 版本 |
|------|------|------|
| Java | JDK | 17+ |
| 框架 | Spring Boot | 4.1.0 |
| HTTP | OkHttp | 5.4.0(`okhttp-jvm`,避开 KMP 主 jar 空陷阱)|
| JSON | Jackson | 2.18.2(显式锁定,不用 Boot 默认 3.x)|
| MCP | 官方 Java SDK | 2.0.0 |
| 日志 | SLF4J + Logback | Spring Boot 默认 |
| 工具 | Lombok | Spring Boot 默认 |
| 构建 | Maven Wrapper | - |
| 测试 | JUnit 5 + Mockito + AssertJ | Spring Boot 默认 |

---

## 文件布局(用户视角)

```
~/.jooj/                          ← 用户级 jooj 数据(0700)
├── .env                          ← API key / model 配置(0600,首次启动自动建模板)
├── cron/
│   └── scheduled_tasks.json      ← durable cron job(jooj 重启恢复)
└── skills/                       ← 用户级 skill(可选,跨项目共享)

<project>/                        ← 当前 jooj 项目目录
├── .memory/                      ← 项目级 memory(MEMORY.md 索引 + 每条 .md 正文)
├── .tasks/                       ← 项目级 task 列表
├── .mailboxes/                   ← teammate 通信文件邮箱
├── .transcripts/                 ← session 录像
├── .task_outputs/                ← 大 tool result 落盘
├── .worktrees/                   ← s18 git worktree 隔离
├── skills/                       ← 项目级 skill(进 git,团队共享)
└── ...
```

**用户级 vs 项目级**:

- 用户级在 `~/.jooj/`(secret / 跨项目共享的能力)
- 项目级在 cwd(memory / tasks / 团队通信 — 跟"这个项目里跑的这次工作"绑定)

---

## 已知踩坑

| # | 坑 | 解决 |
|---|---|---|
| 1 | OkHttp 5.x 主 artifact 是空的(KMP 重构) | 用 `okhttp-jvm:5.4.0` |
| 2 | Spring Boot 4 默认 Jackson 3,包名变 | 显式声明 `jackson-databind:2.18.2` |
| 3 | Spring Boot 4 `DeferredLog` 移到 `org.springframework.boot.logging` | 改 import 路径 |
| 4 | `EnvironmentPostProcessor` 必须用 `META-INF/spring.factories` 注册 | 不能用 4.x 的 `.imports` 文件 |
| 5 | Claude Sonnet 4.6 的 Extended Thinking 块 | DTO 加 `ThinkingBlock` + `defaultImpl = UnknownBlock` |
| 6 | `@JsonTypeInfo` 默认会"吃掉" type 字段 | `visible = true` + 每个实现类显式 `getType()` |
| 7 | assistant content 必须**完整**回传 | 第二轮请求里第一轮 assistant 返回的 `[text + tool_use]` 完整数组要原封不动塞回去 |
| 8 | `MockAnthropicClient` 持有 List 引用导致快照污染 | Mock 收到 request 时 `new ArrayList<>(messages)` |
| 9 | `SkillRegistry` 测试被真实 `~/.jooj/skills/` `~/.claude/skills/` 污染 | 加 `(Path, scanGlobalTiers=false)` 测试构造器 |

---

## 调试技巧

```bash
# 看 Anthropic HTTP 请求/响应正文
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Dlogging.level.com.xilidou.jooj.http=DEBUG"

# 看每次工具调用的完整 input(默认只 preview 60 字)
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Dlogging.level.com.xilidou.jooj.hook=DEBUG"

# Web 模式换端口(默认 8080 被占)
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8090"
```

---

## 路线图

- [ ] **s20 Comprehensive Agent** — 把 19 章机制合到一个 demo flow
- [ ] **Web UI: Sessions 列表** — 当前是单 history 单用户,加 session 抽象 + 切换
- [ ] **Web UI: Memory body 查看** — 当前 sidebar 只显示 catalog,加 `GET /api/memory/{name}` 看完整 body
- [ ] **MCP transport: SSE / HTTP** — 当前只支持 stdio
- [ ] **多用户 / 认证** — 当前假设本机单用户,无 CSRF / auth

---

## 相关链接

- [shareAI-lab/learn-claude-code](https://github.com/shareAI-lab/learn-claude-code) — 主线学习教材(Python 版)
- [Anthropic Messages API](https://docs.anthropic.com/en/api/messages) — 协议参考
- [Model Context Protocol](https://modelcontextprotocol.io) — MCP 规范
- [vercel-labs/skills](https://github.com/vercel-labs/skills) — skill 包管理 CLI
