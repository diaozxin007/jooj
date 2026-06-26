# Jooj 项目代码结构分析

## 1. 所有 Package 及类数量

| Package | 类数量 |
|---------|--------|
| `com.xilidou.jooj.harness.http.dto` | 14 |
| `com.xilidou.jooj.harness.archive.day3` | 11 |
| `com.xilidou.jooj.harness.permission` | 10 |
| `com.xilidou.jooj.harness.http` | 10 |
| `com.xilidou.jooj.harness.skill.impl` | 7 |
| `com.xilidou.jooj.harness.hook.impl` | 6 |
| `com.xilidou.jooj.harness.todo` | 4 |
| `com.xilidou.jooj.harness.hook` | 4 |
| `com.xilidou.jooj` | 3 |
| `com.xilidou.jooj.harness.base` | 3 |
| `com.xilidou.jooj.harness.archive.day4` | 3 |
| `com.xilidou.jooj.harness.entity` | 2 |
| `com.xilidou.jooj.harness.agent` | 2 |
| `com.xilidou.jooj.harness` | 1 |
| `com.xilidou.jooj.harness.skill` | 1 |

> 共 **15 个 package**，合计 **81 个 Java 文件**。

---

## 2. 依赖最多的文件（按 import 数量排序 Top 5）

| 排名 | Import 数 | 文件 |
|------|-----------|------|
| 🥇 1 | 33 | `harness/agent/AgentLoopHarness.java` |
| 🥈 2 | 24 | `harness/agent/AgentLoopHarnessTest.java` |
| 🥉 3 | 18 | `harness/skill/impl/FileSystemSkill.java` |
| 4 | 16 | `harness/skill/impl/TodoSkill.java` |
| 5 | 16 | `harness/http/AnthropicHttpClient.java` |

---

## 3. 项目总结

Jooj 是一个基于 Java + Spring Boot 构建的 AI Agent 框架，整体架构清晰、分层明确。项目以 `harness` 作为核心命名空间，划分了 `agent`（主循环调度）、`skill`（工具能力）、`hook`（事件钩子）、`permission`（权限管控）、`http`（与 Anthropic Claude API 通信）、`todo`（任务管理）等子模块，职责边界清晰。`AgentLoopHarness` 是整个系统的核心调度类，以 33 个 import 居全项目之首，承担工具调用、上下文管理、LLM 交互等重度协作职责。`http.dto` 包以 14 个类规模最大，完整封装了 Claude API 的请求/响应数据结构。项目还保留了 `archive/day3`、`archive/day4` 等历史演进版本，体现了迭代开发过程。整体代码质量较高，测试覆盖充分，是一个结构完善、具备生产参考价值的 Agent 工程实践项目。
