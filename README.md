# 企业知识助手（Knowledge Assistant）

一个本地部署的企业知识库问答系统：把公司制度 / 技术文档 / 维修手册等资料入库，用**混合检索（向量 + 关键词）+ 重排**找出证据，再让大模型**只依据证据作答并标注引用**；证据不足时明确拒答，不瞎编。

- 后端：Spring Boot 3.5（Java 17）
- 前端：Vue 3 + Vite
- 存储：PostgreSQL 17 + pgvector（元数据 / 权限 / 片段与向量 / 运行账本）
- 知识内核：AnythingLLM（可选，文档仓与对照检索通道）
- 部署形态：单机、单租户，全部监听 `127.0.0.1`

---

## 一、它能做什么

| 能力 | 说明 |
|---|---|
| 混合检索 | 向量召回（pgvector HNSW）+ 关键词召回（pg_trgm），RRF 融合 |
| 权限下推 | 文档级 ACL 在 SQL 层过滤（fail-closed），不可见内容根本不进候选 |
| 引用校验 | 每条结论带引用编号，越界/截断按拒答处理，不返回半句话 |
| 拒答闸门 | 双阈值判定"证据是否足够"，不足时不调用模型 |
| 深度分析 | 问题拆解 → 多轮检索 → 分步作答（可选） |
| 重排（P0） | 可选 cross-encoder 重排层，默认关闭 |
| 查询改写 / 上下文增强 / 版面分析（P1） | 均为可选、默认关闭 |
| 管理能力 | 知识域增删改、浏览器上传文档、文档版本状态切换 |
| 运行账本 | 每次问答一行：命中片段、耗时、token、费用 |

---

## 二、架构与端口

```
浏览器 (Vite 5173)
   │  /api/**（Bearer 令牌，Vite 代理到后端）
   ▼
后端 (Spring Boot 127.0.0.1:9090)
   ├─ security/  登录、会话、ACL
   ├─ ingest/    目录扫描 / 上传、幂等同步、片段入库
   ├─ parse/     PDF(PDFBox) / DOCX(POI) → 块 → chunk
   ├─ retrieval/ 召回、RRF 融合、重排、证据组装、双阈值
   ├─ generation/ 提示词、引用校验、拒答
   └─ observe/   运行账本
   ▼
PostgreSQL 17 + pgvector (127.0.0.1:5432)
AnythingLLM 内核 (127.0.0.1:3002)（可选）
```

| 组件 | 形态 | 地址 |
|---|---|---|
| PostgreSQL + pgvector | Docker 容器 `assistant-pg` | `127.0.0.1:5432` |
| 知识内核 AnythingLLM | Docker 容器 `assistant-anythingllm` | `127.0.0.1:3002` |
| 后端 | 本机 JVM（可执行 jar） | `127.0.0.1:9090` |
| 前端 | Vite | `127.0.0.1:5173` |

> 端口 9090 而非 8080，是为了避开本机其它项目。

---

## 三、目录结构

```
backend/     Spring Boot 后端（src/main/java/com/hykj/assistant/**）
frontend/    Vue 3 前端
deploy/      启动脚本、.env.example、内核启动脚本
docs/        架构说明 / 接口契约 / 部署与启动
tools/       验收脚本（PowerShell / Playwright）、测试语料生成
knowledge/   文档根目录（manifest.json + 各知识域子目录）
启动.bat / 停止.bat   一键启动 / 停止（Windows）
```

---

## 四、环境要求

- **JDK 17**（后端要求；本机若默认是 Java 8，务必按下面方式指定）
- **Maven 3.9+**（离线可用，依赖已锁版本）
- **Node.js 18+**（前端）
- **Docker Desktop**（跑 PostgreSQL 与内核容器）
- 一个**兼容 OpenAI 协议**的向量化模型 + 生成模型的接口地址与 Key（在系统配置页填写）

---

## 五、快速开始

### 1. 准备配置

```powershell
cd <项目根目录>
copy deploy\.env.example deploy\.env
```

编辑 `deploy\.env`，至少填写：

```
PG_PASSWORD=<给数据库设一个密码>
ASSISTANT_ADMIN_PASSWORD=<管理员初始密码，至少 8 位>
ASSISTANT_DEMO_PASSWORD=<演示账号密码，可留空>
ANYTHINGLLM_API_KEY=<AnythingLLM 开发者 API Key，不用内核可留空>
```

> `deploy\.env` 已被 `.gitignore` 排除，**不要把真实值提交或分享**。

### 2. 一键启动（推荐）

双击项目根目录的 **`启动.bat`**。它会自动：

1. 切换到 JDK 17（找不到会明确报错）
2. 起数据库容器 `assistant-pg`（不存在则创建）
3. 起内核容器 `assistant-anythingllm`（仅当未运行；首次需要先在配置页填过模型配置）
4. 缺 jar 时执行 `mvn -o -B package`
5. 起后端并等到就绪（打印内核自检）
6. 新窗口起前端，并打开浏览器

停止：双击 **`停止.bat`**（停后端与前端，容器保留）。

可选参数：`启动.bat -Rebuild`（强制重打包）、`-SkipFrontend`、`-NoBrowser`。

### 3. 分步启动（排障用）

```powershell
# (a) 确认容器在跑
docker ps --format "{{.Names}}  {{.Status}}"

# (b) 构建并启动后端（必须先切 JDK 17）
$env:JAVA_HOME = "<你的 JDK17 路径>"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd backend
mvn -o -B package
cd ..
pwsh deploy\restart-backend.ps1      # 停旧 → 启动 → 等就绪 → 打印自检

# (c) 启动前端
cd frontend
npm install        # 首次
npm run dev        # http://127.0.0.1:5173
```

---

## 六、首次使用

浏览器打开 `http://127.0.0.1:5173`，用 `deploy\.env` 里的 `admin` 账号登录。

1. **配置模型**：进入「系统配置」，填「向量化 Embedding」与「生成模型出口」两组的**接口地址、模型名、API Key**（都是兼容 OpenAI 协议的地址），点「测试连接」确认可用。
2. **建知识域**：「知识域与内核」→「新建知识域」（代码用英文小写），保存后点「初始化工作区」把内核工作区建出来。
3. **入库文档**（二选一）：
   - 「文档与同步」→「上传文档」，填编号/标题/版本等并选文件；
   - 或把文件放进 `knowledge/<域>/` 并在 `knowledge/manifest.json` 登记，再点「立即同步」。
4. **提问**：「知识问答」选知识域 → 输入问题 → 得到带引用编号的答案；点「看原文」可跳转原文并高亮被引用的片段。
5. **看账本**：管理员可在「运行记录」查每次问答的命中、耗时、token 与费用。

---

## 七、数据存在哪

| 数据 | 位置 |
|---|---|
| 文档元数据、ACL、自建片段与向量、运行账本、配置与密钥、会话 | PostgreSQL（Docker 卷 `assistant_pg_data`） |
| 原始文档 + `manifest.json` | 项目内 `knowledge/` |
| 内核工作区、上传副本、内核向量库 | 内核容器挂载目录（默认 `D:\DockerData\AnythingLLM-assistant`） |
| 后端日志 | `backend/logs/`（不入库） |
| 敏感配置 | `deploy/.env`（不入库） |

备份需同时带走：PG 卷 + `knowledge/` + 内核挂载目录 + `deploy/.env`。

---

## 八、验收脚本

```powershell
pwsh tools\run-acceptance.ps1          # 全套串行（需后端 9090 + 前端在跑、容器可用）
pwsh tools\run-acceptance.ps1 -SkipSlow
```

也可单跑，如 `pwsh tools\verify-retrieval.ps1`。判定看脚本末尾的 `[FAIL]` 计数，不是退出码。

---

## 九、常见问题

| 现象 | 原因 / 处理 |
|---|---|
| 后端启动报 `Port 9090 was already in use` | 端口被占；改 `backend/src/main/resources/application.yml` 的 `server.port`，并同步改 `frontend/vite.config.js` 与 `tools/*.ps1` 里的地址 |
| 启动报 `UnsupportedClassVersionError` | 用了 Java 8；把 `JAVA_HOME` 切到 JDK 17 |
| 编译报一堆"非法字符" | 同上，用的是 Java 8，不支持文本块 |
| 问答报 503 | 生成模型出口不可用（Key/地址/出网被拦），不是"库里没有" |
| 检索报 503 | 内核容器没起；或 `retrieval.mode` 配置问题 |
| 上传 200 但检索不到 | 内核会把嵌入失败吞掉仍返回 200；看日志与向量数增量 |

---

## 十、文档索引

| 文档 | 内容 |
|---|---|
| [`docs/功能说明.md`](docs/功能说明.md) | 能做什么、怎么用、边界在哪 |
| [`docs/交付部署.md`](docs/交付部署.md) | 从零部署：拉镜像、起容器、构建、首次配置（含 docker 命令） |
| [`docs/架构说明.md`](docs/架构说明.md) | 分层与依赖方向、检索链路、权限边界、已知偏离 |
| [`docs/接口契约.md`](docs/接口契约.md) | 前后端接口与字段口径 |
| [`docs/部署与启动.md`](docs/部署与启动.md) | 分步启动与排障 |

## 十一、许可

本项目以 [MIT](LICENSE) 许可开源。

## 十二、安全提示

- 所有密钥只存在服务端（`deploy/.env` 或数据库 `app_setting`），接口只回显掩码。
- `deploy/.env`、`*.log`、`target/`、`node_modules/` 已在 `.gitignore` 中排除。
- 上线前请修改默认口令，并确保不把 `deploy/.env` 提交到任何仓库。
