# 智能医疗问诊 Agent 系统

> Java/Spring Boot + LangChain4j 多 Agent 协作系统，支持 RAG 知识库检索、流式诊断和动态模型配置

## 快速开始（15 分钟）

### 1. 启动应用

```bash
# 克隆项目
git clone <repo-url> && cd medical-agent

# 编译
mvn package -DskipTests

# 启动（H2 内存数据库，开箱即用）
java -jar target/medical-agent-1.0.0-SNAPSHOT.jar
```

访问 http://localhost:8080 即可使用。

### 2. 配置 AI 模型

访问 http://localhost:8080/config.html ，填写：
- **Base URL**: OpenAI 兼容接口地址（支持 DeepSeek、Evol 本地代理等）
- **API Key**: 你的 API 密钥
- **默认模型**: 如 `deepseek-chat`、`gpt-4o-mini`

### 3.（可选）PostgreSQL + Redis 生产环境

```bash
# 启动基础设施
docker compose up -d

# 初始化数据库
psql postgresql://med:med123@localhost:5432/medical_agent < src/main/resources/init_db.sql

# 导入种子数据
pip install requests psycopg2-binary
python3 scripts/seed_knowledge.py --pg "postgresql://med:med123@localhost:5432/medical_agent" --embed --api-key YOUR_KEY

# 以 PostgreSQL profile 启动
java -jar target/medical-agent-1.0.0-SNAPSHOT.jar --spring.profiles.active=postgres
```

## 架构

```
┌─────────┐    SSE     ┌──────────────────────────────────────┐
│  前端 H5 │ ◄────────► │  Spring Boot (8080)                  │
│  单页面  │            │                                      │
└─────────┘            │  ChatController → GraphOrchestrator  │
                       │    ├→ RouterAgent (LLM 意图分类)      │
                       │    ├→ CollectInfoAgent (多轮追问)     │
                       │    ├→ RetrievalAgent (混合检索)       │
                       │    └→ DiagnosisAgent (流式诊断)       │
                       │                                      │
                       │  H2 (dev) / PostgreSQL+pgvector (prod)│
                       │  Redis (session TTL)                  │
                       └──────────────────────────────────────┘
```

## API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/chat/stream` | POST | SSE 流式问诊（主接口） |
| `/api/v1/chat/session/{id}/history` | GET | 获取会话历史 |
| `/api/v1/chat/session/{id}/end` | POST | 结束会话 |
| `/api/v1/config/llm` | GET/POST | 读取/保存模型配置 |
| `/api/v1/config/llm/test` | GET | 测试模型连通性 |
| `/api/v1/health` | GET | 健康检查 |
| `/api/v1/eval/run` | POST | 运行评估用例 |

## 项目结构

```
src/main/java/com/medicalagent/
├── agent/                    # 多 Agent 核心
│   ├── RouterAgent.java      # LLM 意图路由
│   ├── CollectInfoAgent.java # 信息采集（最多5轮追问）
│   ├── RetrievalAgent.java   # 混合检索
│   ├── DiagnosisAgent.java   # 流式诊断（SSE逐token）
│   └── GraphOrchestrator.java# 状态图编排
├── api/                      # REST 控制器
├── config/                   # 模型工厂 + 配置服务
├── filter/                   # 敏感数据脱敏
├── model/                    # AgentState + Session
├── repository/               # 数据访问（JdbcTemplate + Redis）
└── service/                  # 合规审计 + Embedding

src/main/resources/
├── static/                   # 前端页面
│   ├── index.html            # 问诊主界面
│   ├── config.html           # 模型配置页
│   └── flowchart.html        # 架构流程图
├── application.yml           # H2 默认配置
└── init_db.sql               # PostgreSQL 建表脚本
```

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Java 17, Spring Boot 3.2, LangChain4j 0.35.0 |
| 数据库 | H2 (dev) / PostgreSQL + pgvector (prod) |
| 缓存 | Redis (session TTL) |
| LLM | OpenAI 兼容接口（DeepSeek / Evol / OpenAI） |
| 前端 | 原生 HTML/CSS/JS, SSE, DM Sans |

## 注意事项

1. **免责声明**: 所有诊断建议自动附带免责声明，不可移除
2. **数据脱敏**: 手机号、身份证号、银行卡号、姓名自动脱敏
3. **合规审计**: 问诊记录写入 `consultation_records` 表（PostgreSQL 环境）
4. **API Key 安全**: 配置文件已加入 `.gitignore`，生产环境建议加密存储

## 许可证

MIT License
