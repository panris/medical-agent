# 智能医疗问诊 Agent — 综合评估与优化计划

> 评估日期：2026-06-06  
> 代码基线：Java 17 + Spring Boot 3.2.5 + LangChain4j，21 个 Java 源文件，2 个静态 HTML 页面

---

## 一、执行摘要

项目已完成 **多 Agent 状态图骨架、SSE 流式聊天、LLM 动态配置、合规脱敏** 等核心模块的 Java 重写，前端聊天页也完成了 v1 UI 重设计。但存在 **3 个阻断级缺陷** 导致主流程无法完整跑通：

| 级别 | 问题 | 影响 |
|------|------|------|
| 🔴 P0 | `ChatController` 诊断流式逻辑被 `null` 检查跳过 | 用户永远收不到 AI 诊断结果 |
| 🔴 P0 | RAG 层 SQL/Schema/配置三方不一致 | 知识库检索默认环境完全不可用 |
| 🔴 P0 | 默认 H2 内存库无表、无数据 | 开箱即跑无法验证 RAG 价值 |

**结论：** 架构设计合理，模块边界清晰，但 **「设计文档 ↔ 实现 ↔ 运行环境」三者严重脱节**。建议分三阶段优化：先修复主路径（1 周）→ 补齐数据与持久化（2 周）→ 质量与工程化（持续）。

---

## 二、系统架构评估

### 2.1 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│  表现层   index.html (SSE Chat)  │  config.html (LLM 配置)  │
└────────────────────────┬────────────────────────────────────┘
                         │ HTTP / SSE
┌────────────────────────▼────────────────────────────────────┐
│  API 层   ChatController │ LlmConfigController │ Eval/Health│
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│  编排层   GraphOrchestrator (手动 LangGraph 等价实现)         │
│           router → collect_info │ retrieval │ emergency │ end│
│           retrieval → diagnose (stub，由 Controller 接管)    │
└──────┬─────────┬─────────┬─────────┬────────────────────────┘
       │         │         │         │
   RouterAgent  CollectInfo Retrieval DiagnosisAgent
       │         Agent      Agent         │
       └─────────┴─────────┴─────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│  基础设施  ModelFactory │ EmbeddingService │ ComplianceService│
│           SessionRepository (Redis) │ HybridSearchRepository  │
│           SensitiveDataFilter                                │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 各层评分

| 层级 | 完成度 | 质量 | 说明 |
|------|--------|------|------|
| **Agent 编排** | 85% | B+ | 状态图清晰，节点职责分明；diagnose 委托 Controller 设计合理但实现有 bug |
| **API 层** | 70% | B | SSE 流式设计完整；history/end 接口前端未接入 |
| **RAG 检索** | 30% | D | SQL 与 init_db.sql 不匹配，H2 无 schema，配置 key 不一致 |
| **LLM 配置** | 80% | B | 文件热更新 + 缓存驱逐可用；密钥明文存储 |
| **会话持久化** | 50% | C | Redis 存 AgentState，但 assistant 消息未写入；前端用 localStorage 双轨 |
| **合规安全** | 60% | C+ | 脱敏 + 免责声明有；审计只打日志，consultation_records 未落库 |
| **前端** | 75% | B | 聊天 UI  polished；config 页风格不统一，server session 未同步 |
| **测试/文档** | 10% | F | 零自动化测试；README 仍描述 Python 栈 |

### 2.3 技术栈一致性

| 组件 | pom.xml 声明 | 实际使用 | 建议 |
|------|-------------|---------|------|
| spring-boot-starter-data-jpa | ✅ | ❌ 无 Entity | 移除或补 JPA 实体 |
| spring-boot-starter-webflux | ✅ | ❌ 无响应式端点 | 移除 |
| PostgreSQL + pgvector | ✅ | ❌ 默认 H2 | 提供 docker-compose |
| Testcontainers | ✅ (test) | ❌ 无测试 | 补集成测试 |
| FallbackChatModel | ✅ 实现 | ❌ 未调用 | 接入或删除 |
| Session.java POJO | ✅ | ❌ 未使用 | 删除或接入 |

---

## 三、功能模块评估

### 3.1 意图路由 (RouterAgent)

**能力：** LLM 分类 + 正则 fallback，优先级 emergency > end > retrieval > collect_info  
**优点：** 降级策略完善，EvalController 有 20 条回归用例  
**问题：**
- 路由结果写入 `diagnosisResult`，语义混淆
- 多轮对话仅含 user 消息，LLM 路由上下文不完整

**评分：** 7/10

---

### 3.2 信息采集 (CollectInfoAgent)

**能力：** 7 阶段规则引擎，正则提取年龄/性别/时长/主诉  
**优点：** 无 LLM 依赖，响应快  
**问题：**
- `MAX_TURNS=3` 但 7 个阶段，常提前退出
- 与 Router 循环可能触发 maxSteps 强制诊断

**评分：** 6/10

---

### 3.3 知识检索 (RetrievalAgent + HybridSearchRepository)

**能力：** Embedding → hybrid_search / text-only fallback  
**优点：** 双层 fallback 设计合理  
**问题（阻断）：**

```
HybridSearchRepository 期望          init_db.sql 实际
─────────────────────────────────────────────────────
doc_id, category 列                  id 列，category 在 metadata JSONB
hybrid_search(7 参数 + WHERE)        hybrid_search(5 参数，无 WHERE)
${rag.*} 配置                        application.yml 用 retrieval.*
${embedding.api-url}                 application.yml 用 embedding.base-url
默认 dimension=768                   yaml/DB 均为 1536
H2 内存库                            pgvector + PL/pgSQL 函数
```

**评分：** 3/10（设计 OK，实现不可用）

---

### 3.4 诊断生成 (DiagnosisAgent + ChatController)

**能力：** 同步/流式 LLM 诊断，JSON 结构化输出，mock fallback  
**优点：** `---JSON---` 分隔符设计、token 流式推送架构完整  
**问题（阻断）：**

```java
// ChatController.java L69-74 — diagnose 节点 message 恒为 null
for (StepResult result : results) {
    if (result.getMessage() == null) continue;  // ← 此处跳过 diagnose
    if ("diagnose".equals(result.getNode())) {
        streamDiagnosis(emitter, state);       // ← 永远不会执行
    }
}
```

**评分：** 4/10（Agent 层 OK，Controller 集成断裂）

---

### 3.5 会话管理

**后端：** Redis JSON 存完整 AgentState，TTL 30min，失败静默降级  
**前端：** localStorage 存消息文本，不调用 `/session/{id}/history`  
**问题：**
- 双轨存储，刷新后诊断卡片丢失
- 新会话不调用 `/session/{id}/end`
- Redis 不可用时 session 丢失且无用户提示

**评分：** 5/10

---

### 3.6 LLM 配置管理

**能力：** JSON 文件读写、REST API、连通性测试、ModelFactory 缓存驱逐  
**优点：** 热更新无需重启  
**问题：**
- API Key 明文存盘，`config/llm-config.json` 未加入 `.gitignore`
- config.html 空 key 保存可能覆盖已有密钥
- provider 名称仅 UI 状态，未持久化

**评分：** 7/10

---

### 3.7 合规与安全

| 能力 | 状态 |
|------|------|
| 输入 PII 脱敏 (SensitiveDataFilter) | ✅ 已实现 |
| 免责声明 SSE 推送 | ✅ 已实现 |
| 审计日志结构 (ComplianceService) | ✅ 已实现 |
| 审计持久化 (consultation_records) | ❌ 仅 SLF4J |
| API Key 加密存储 | ❌ 明文 JSON |
| CORS `*` + credentials | ⚠️ 浏览器兼容问题 |

**评分：** 6/10

---

### 3.8 前端

| 能力 | 状态 |
|------|------|
| SSE 流式聊天 + 诊断卡片 | ✅ (后端 bug 修复后可用) |
| 快捷症状入口、新会话、停止生成 | ✅ |
| 模型配置页 | ✅ 基础可用 |
| 服务端历史同步 | ❌ |
| 健康状态检测 | ❌ 静态绿点 |
| Markdown 完整渲染 | ❌ 仅 bold |
| config 页设计统一 | ❌ |

**评分：** 7/10

---

## 四、数据流评估（主路径）

```
用户输入 → 脱敏 → AgentState.messages (仅 user)
    → GraphOrchestrator.executeUntilBlock()
        → router (LLM/regex 分类)
        → collect_info (提问，needsUserInput=true，阻塞) ✅
        → retrieval (embed + search，常返回空) ⚠️
        → diagnose (StepResult message=null, isPendingExecution=true)
    → ChatController 循环
        → null check continue → streamDiagnosis 被跳过 ❌
    → disclaimer → Redis 保存 → SSE complete
```

**理想路径 vs 实际：**

| 步骤 | 设计预期 | 当前实际 |
|------|---------|---------|
| 信息采集多轮 | 7 阶段完整收集 | 3 轮后强制退出 |
| RAG 检索 | 返回 Top-K 医学文档 | 空结果（DB/Schema 不可用） |
| 流式诊断 | token 逐字 + 诊断卡片 | **完全跳过** |
| 历史恢复 | Redis ↔ 前端一致 | 各存各的，不一致 |

---

## 五、风险矩阵

| 风险 | 概率 | 影响 | 优先级 |
|------|------|------|--------|
| 诊断结果永不返回 | 确定 | 产品核心价值丧失 | P0 |
| API Key 泄露到 Git | 高 | 安全事故 | P0 |
| RAG 不可用导致幻觉诊断 | 确定 | 医疗合规风险 | P0 |
| Redis 宕机丢会话 | 中 | 用户体验差 | P1 |
| 多轮上下文缺失导致诊断质量差 | 高 | 诊断准确性下降 | P1 |
| README 误导新开发者 | 高 | 协作效率低 | P2 |
| 无测试回归 | 高 | 每次改动可能引入 bug | P1 |

---

## 六、优化计划

### Phase 1 — 主路径修复（第 1 周，阻断级）

> 目标：用户能完整体验「描述症状 → 信息采集 → AI 诊断 → 诊断卡片」

| ID | 任务 | 负责模块 | 预估 | 验收标准 |
|----|------|---------|------|---------|
| **F-01** | 修复 ChatController diagnose 调度逻辑 | `ChatController.java` | 2h | 先判断 `isPendingExecution()` 再处理 null message；流式 token + diagnosis 事件正常到达前端 |
| **F-02** | 统一配置 key：`retrieval.*` ↔ `HybridSearchRepository` | `HybridSearchRepository` + yml | 1h | `@Value("${retrieval.top-k}")` 等与 yml 一致 |
| **F-03** | 统一 Embedding 配置：`base-url` + dimension 1536 | `EmbeddingService` + yml | 1h | embedding API 调用成功，维度与 DB 一致 |
| **F-04** | 对齐 HybridSearchRepository SQL 与 init_db.sql | Repository + SQL | 4h | 列名 `id` 非 `doc_id`；函数签名 5 参数；category 从 metadata 提取 |
| **F-05** | 提供 docker-compose (PostgreSQL + pgvector + Redis) | 基础设施 | 4h | `docker compose up` 后 RAG 可查询 |
| **F-06** | 知识库种子数据 + ETL 脚本（最小可用集） | scripts/ | 8h | 至少 50 条医学 FAQ/指南 chunk 可检索 |
| **F-07** | `.gitignore` 加入 `config/llm-config.json` + 密钥轮换提醒 | 安全 | 0.5h | 密钥不会被 git 跟踪 |

**Phase 1 里程碑：** 端到端演示视频可录制，诊断卡片正常渲染。

---

### Phase 2 — 数据一致性与体验（第 2–3 周）

> 目标：前后端数据统一，多轮对话质量提升，合规落库

| ID | 任务 | 模块 | 预估 | 验收标准 |
|----|------|------|------|---------|
| **D-01** | assistant 消息写入 AgentState.messages | ChatController + DiagnosisAgent | 3h | Redis 历史含完整对话 |
| **D-02** | 前端接入 `/session/{id}/history` 替代 localStorage 主存储 | index.html | 4h | 刷新页面后消息 + 诊断卡片恢复 |
| **D-03** | 新会话调用 `/session/{id}/end` | index.html | 1h | Redis 旧 session 被清理 |
| **D-04** | 诊断卡片持久化到 session state | 前后端 | 4h | 刷新后诊断卡片不丢失 |
| **D-05** | CollectInfoAgent 轮次与阶段对齐 | CollectInfoAgent | 3h | 7 阶段可在 5 轮内完成或智能跳过 |
| **D-06** | consultation_records 审计落库 | ComplianceService + Repository | 6h | 每次诊断写入 DB，含 intent/docs/latency |
| **D-07** | config.html 空 API Key 不覆盖已有值 | LlmConfigController | 2h | 仅填其他字段保存时 key 保留 |
| **D-08** | 前端健康状态接入 `/api/v1/health` | index.html | 2h | 服务不可用时显示离线状态 |
| **D-09** | config.html UI 与 index 设计统一 | config.html | 4h | 共用 CSS 变量 / 组件风格 |

**Phase 2 里程碑：** 多轮问诊 → 诊断 → 刷新恢复，审计可查。

---

### Phase 3 — 质量与工程化（第 4–6 周）

> 目标：可测试、可部署、可维护

| ID | 任务 | 模块 | 预估 | 验收标准 |
|----|------|------|------|---------|
| **Q-01** | ChatController 集成测试（Mock LLM + Redis Testcontainer） | test/ | 8h | diagnose SSE 事件序列断言 |
| **Q-02** | RouterAgent + SensitiveDataFilter 单元测试 | test/ | 4h | EvalController 用例迁移为 JUnit |
| **Q-03** | HybridSearchRepository 集成测试（Testcontainers PG） | test/ | 6h | 种子数据检索 score > 0 |
| **Q-04** | 接入 FallbackChatModel 或删除 dead code | ModelFactory | 4h | 主模型失败时自动 fallback |
| **Q-05** | 清理未使用依赖 (JPA, WebFlux) | pom.xml | 1h | mvn 编译通过，无 unused dep |
| **Q-06** | 重写 README（Java 栈 + docker-compose 快速开始） | docs | 4h | 新开发者 15min 内跑通 |
| **Q-07** | 添加 OpenAPI/Swagger 文档 | springdoc | 4h | `/swagger-ui.html` 可浏览全部 API |
| **Q-08** | flowchart.html 移入 static/ 并更新 SSE 事件描述 | static/ | 2h | 架构图与实现一致 |
| **Q-09** | API Key 加密存储（Jasypt 或 OS Keychain） | LlmConfigService | 8h | 磁盘无明文 key |
| **Q-10** | Eval 管理页（前端可视化跑批） | 新页面 | 8h | 一键跑 20 条 eval 并展示结果 |

**Phase 3 里程碑：** CI 绿灯，README 准确，核心路径有测试覆盖。

---

### Phase 4 — 产品增强（第 7 周+，按需）

> 参考 `docs/ui-redesign-tasks.md` P1–P3 及以下后端增强

| ID | 任务 | 优先级 | 预估 |
|----|------|--------|------|
| **E-01** | 会话历史侧边栏（多 session 管理） | P1 | 6h |
| **E-02** | Markdown 完整渲染（marked.js） | P1 | 3h |
| **E-03** | 消息反馈（👍/👎）+ 后端存储 | P1 | 6h |
| **E-04** | 深色模式 | P1 | 3h |
| **E-05** | 知识库管理 API（上传/重建索引） | P2 | 16h |
| **E-06** | 多模型路由（chat/embedding 分离配置） | P2 | 8h |
| **E-07** | 前端工程化（Vite + 组件化） | P3 | 2d |
| **E-08** | PWA + 离线缓存 | P3 | 4h |
| **E-09** | 国际化 i18n | P3 | 8h |
| **E-10** | Playwright E2E 测试 | P3 | 8h |

---

## 七、任务总览与排期

```
Week 1  ████████████ Phase 1: F-01 ~ F-07 (主路径修复)
Week 2  ██████████   Phase 2: D-01 ~ D-05 (数据一致性)
Week 3  ████████     Phase 2: D-06 ~ D-09 (合规 + UI 统一)
Week 4  ██████████   Phase 3: Q-01 ~ Q-05 (测试 + 清理)
Week 5  ████████     Phase 3: Q-06 ~ Q-10 (文档 + 安全)
Week 6+ ░░░░░░░░░░   Phase 4: 按产品优先级选取
```

### 任务统计

| 阶段 | 任务数 | 总预估工时 |
|------|--------|-----------|
| Phase 1 主路径修复 | 7 | ~21h |
| Phase 2 数据与体验 | 9 | ~29h |
| Phase 3 质量工程化 | 10 | ~49h |
| Phase 4 产品增强 | 10 | ~70h+ |
| **合计** | **36** | **~169h** |

---

## 八、建议优先级决策

如果资源有限，**最小可用产品 (MVP) 只需完成 5 项：**

1. **F-01** — 修复诊断流式 bug（2h，最高 ROI）
2. **F-02 + F-03** — 配置对齐（2h）
3. **F-05 + F-06** — docker-compose + 种子数据（12h，RAG 可用）
4. **F-07** — gitignore 密钥（0.5h）
5. **D-01** — assistant 消息持久化（3h）

> 以上 ~20h 工作量即可交付可演示的完整问诊闭环。

---

## 九、架构演进建议（长期）

```
当前                          目标 (3 个月)
─────                         ─────────────
单文件 HTML                   Vite + 组件化前端
Redis-only session            Redis 热缓存 + PG 持久化
JSON 文件 LLM 配置            model_configs 表 + 加密
无测试                        核心路径 80% 覆盖
H2 默认 / PG 可选             docker-compose 标准环境
手动 LangGraph                考虑 LangGraph4j 或 Spring AI 对齐
```

---

## 附录 A：API 清单与前端接入状态

| 端点 | 方法 | 后端 | 前端 | 说明 |
|------|------|------|------|------|
| `/api/v1/chat/stream` | POST | ✅ | ✅ | SSE 聊天 |
| `/api/v1/chat/session/{id}/history` | GET | ✅ | ❌ | 应接入 |
| `/api/v1/chat/session/{id}/end` | POST | ✅ | ❌ | 应接入 |
| `/api/v1/config/llm` | GET/POST | ✅ | ✅ | 模型配置 |
| `/api/v1/config/llm/test` | GET | ✅ | ✅ | 连通性测试 |
| `/api/v1/health` | GET | ✅ | ❌ | 应接入 |
| `/api/v1/eval/run` | POST | ✅ | ❌ | 可建管理页 |

## 附录 B：关键代码位置索引

| 模块 | 路径 |
|------|------|
| 状态图编排 | `agent/GraphOrchestrator.java` |
| 诊断流式 bug | `api/ChatController.java:69-74` |
| RAG SQL 不匹配 | `repository/HybridSearchRepository.java` |
| DB Schema | `resources/db/init_db.sql` |
| Embedding 配置 | `service/EmbeddingService.java` |
| 聊天前端 | `resources/static/index.html` |
| 配置前端 | `resources/static/config.html` |
| 架构图 | `flowchart.html` |
