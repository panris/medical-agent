# 智能医疗问诊 Agent 系统

> 基于 LangChain + LangGraph 的多 Agent 协作系统，支持 RAG 知识库检索和动态模型配置

## 项目结构

```
medical-agent/
├── backend/                    # 后端服务（Python + FastAPI）
│   ├── agents/                # LangGraph 多 Agent
│   │   ├── state.py          # 状态定义 (T2.1)
│   │   ├── router_agent.py   # 路由 Agent (T2.2)
│   │   ├── collect_info_agent.py  # 信息采集 Agent (T2.3)
│   │   ├── retrieval_agent.py     # 医疗检索 Agent (T2.4)
│   │   ├── diagnosis_agent.py     # 诊断建议 Agent (T2.5)
│   │   └── graph.py              # LangGraph 状态图 (T2.6)
│   ├── api/                  # FastAPI 接口
│   │   ├── app.py          # 主应用
│   │   └── routes.py      # API 路由
│   ├── models/              # 数据库模型
│   │   └── init_db.sql    # 数据库初始化脚本 (T1.1, T1.4)
│   ├── services/            # 服务层
│   │   ├── embedding_service.py  # 文本向量化 (T1.2)
│   │   ├── hybrid_search.py     # 混合检索 (T1.3)
│   │   └── model_factory.py     # 模型工厂 (T3.2)
│   ├── scripts/             # 脚本工具
│   │   └── etl_pipeline.py    # 知识库 ETL (T1.2)
│   ├── requirements.txt      # Python 依赖
│   └── .env.example        # 环境变量模板
├── frontend/                  # H5 前端（待开发 T4.x）
├── admin/                    # 管理后台（待开发 T3.x）
└── data/                     # 数据目录
    ├── raw/                # 原始医疗指南文档
    └── processed/          # 处理后的数据
```

## 快速开始

### 1. 环境准备

```bash
# 创建 Python 虚拟环境
cd /Users/panris/Projects/medical-agent/backend
python3 -m venv venv
source venv/bin/activate

# 安装依赖
pip install -r requirements.txt
```

### 2. 数据库初始化

```bash
# 安装 PostgreSQL + pgvector 扩展
# （macOS）brew install postgresql pgvector

# 创建数据库
createdb medical_agent

# 执行初始化脚本
psql medical_agent < backend/models/init_db.sql
```

### 3. 配置环境变量

```bash
cp backend/.env.example backend/.env
# 编辑 .env 填写数据库连接、API Key 等
```

### 4. 启动服务

```bash
cd backend
uvicorn api.app:app --host 0.0.0.0 --port 8000 --reload
```

访问 `http://localhost:8000/docs` 查看 API 文档

## 任务进度

- [x] **T1.1** - PostgreSQL + pgvector 数据库初始化
- [x] **T1.2** - 知识库 ETL 流水线（框架完成，需补充实际数据源）
- [x] **T1.3** - 混合检索模块（框架完成，需连接数据库）
- [x] **T1.4** - Sessions 表 + Redis 缓存策略
- [x] **T2.1** - LangGraph State 定义
- [x] **T2.2** - 路由 Agent
- [x] **T2.3** - 信息采集 Agent
- [x] **T2.4** - 医疗检索 Agent
- [x] **T2.5** - 诊断建议 Agent
- [x] **T2.6** - LangGraph 状态图组装
- [ ] **T3.1** - model_configs 表设计（已在 init_db.sql 中定义）
- [ ] **T3.2** - 模型工厂（待实现）
- [ ] **T3.3** - Web 管理后台（待开发）
- [ ] **T3.4** - 配置热更新（待实现）
- [ ] **T4.1~T4.4** - H5 前端（待开发）
- [ ] **T5.1~T5.4** - 安全、合规、性能优化（待实现）

## API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/chat/stream` | POST | SSE 流式问诊接口 |
| `/api/v1/session/{id}/history` | GET | 获取会话历史 |
| `/api/v1/session/{id}/clear` | POST | 清空会话 |
| `/api/v1/models` | GET | 列出可用模型 |
| `/health` | GET | 健康检查 |

## 技术栈

- **后端**: Python 3.10+, FastAPI, LangChain, LangGraph
- **数据库**: PostgreSQL + pgvector, Redis
- **LLM**: OpenAI API (支持多种提供商)
- **前端**: H5 (待开发)
- **部署**: Docker (待配置)

## 注意事项

1. **API Key 安全**: 所有 API Key 必须加密存储（使用 `cryptography.Fernet`）
2. **免责声明**: 所有诊断建议必须附带免责声明，不可移除
3. **数据脱敏**: 用户输入的手机号、身份证号等必须脱敏处理
4. **合规审计**: 所有问诊记录必须存入 `consultation_records` 表

## 许可证

MIT License

## 联系人

项目负责：panris
