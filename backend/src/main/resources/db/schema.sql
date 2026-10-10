-- 系统连接与模型配置。密钥仅服务端存储，接口只回显掩码。
create table if not exists app_setting (
    group_code  varchar(64)  not null,
    field_key   varchar(64)  not null,
    field_value text,
    secret      boolean      not null default false,
    updated_at  timestamptz  not null default now(),
    updated_by  varchar(64),
    primary key (group_code, field_key)
);

comment on table app_setting is '连接与模型配置；secret=true 的字段不回显明文';

-- ============ T02 知识域 ============
create table if not exists knowledge_domain (
    code           varchar(32) primary key,
    name           varchar(64)  not null,
    workspace_slug varchar(64)  not null,
    description    text,
    enabled        boolean      not null default true,
    sort_order     int          not null default 0,
    created_at     timestamptz  not null default now()
);

comment on table knowledge_domain is '知识域与 AnythingLLM 工作区的对应关系';

insert into knowledge_domain (code, name, workspace_slug, description, sort_order) values
    ('policy', '制度流程', 'knowledge-policy', '公司制度、管理办法与业务流程', 1),
    ('tech',   '技术文档', 'knowledge-tech',   '技术规格书、图纸与工艺文件',   2),
    ('repair', '维修',     'knowledge-repair', '设备维修、保养与故障排查',     3)
-- 参考数据由产品维护，遇到冲突就覆盖：既能补新增，也能自愈历史乱码
on conflict (code) do update set
    name = excluded.name,
    workspace_slug = excluded.workspace_slug,
    description = excluded.description,
    sort_order = excluded.sort_order;

-- ============ T03 文档 / ACL / chunk 元数据 ============
create table if not exists document (
    id                 bigserial primary key,
    domain_code        varchar(32)  not null references knowledge_domain(code),
    doc_no             varchar(64)  not null,
    title              varchar(255) not null,
    version            varchar(32)  not null,
    doc_status         varchar(16)  not null default '现行',
    secrecy            varchar(16)  not null default '内部公开',
    owner_dept         varchar(64),
    source_path        text         not null,
    content_hash       char(64)     not null,
    file_size          bigint,
    mime_type          varchar(128),
    effective_from     date,
    effective_to       date,
    index_status       varchar(16)  not null default 'PENDING',
    anythingllm_doc_id text,
    last_error         text,
    created_at         timestamptz  not null default now(),
    updated_at         timestamptz  not null default now(),
    constraint uq_document_no_version unique (doc_no, version)
);

comment on table document is '文档主体；同一文档编号的不同版本各占一行，用于版本冲突与追溯';
comment on column document.content_hash is '原始文件内容 SHA-256，同步幂等键';
comment on column document.index_status is 'PENDING / UPLOADED / INDEXED / FAILED';

create index if not exists idx_document_domain on document (domain_code);
create index if not exists idx_document_hash on document (content_hash);
create index if not exists idx_document_status on document (doc_status);

-- T04：内核里的文件名（=文档编号_版本）。检索命中的片段带不回本地主键，
-- 靠这个名字反查 document 行，引用才能指回真实文档。
alter table document add column if not exists kernel_doc_name varchar(160);
comment on column document.kernel_doc_name is '内核中的文件名，= 文档编号_版本，检索命中时用于反查';
-- 内核文档名由"文档编号_版本"推导，重复就意味着反查会指错文档，宁可早点炸
create unique index if not exists uq_document_kernel_name
    on document (kernel_doc_name) where kernel_doc_name is not null;

-- 文档级 ACL。fail-closed：未命中任何 ACL 行的文档一律不可检索。
create table if not exists document_acl (
    id            bigserial primary key,
    document_id   bigint      not null references document(id) on delete cascade,
    subject_type  varchar(16) not null,
    subject_value varchar(64) not null,
    permission    varchar(16) not null default 'READ',
    created_at    timestamptz not null default now(),
    constraint uq_document_acl unique (document_id, subject_type, subject_value, permission)
);

comment on table document_acl is '文档级访问控制；subject_type 取 ALL/ROLE/DEPT/USER。没有匹配行即不可见';

create index if not exists idx_document_acl_subject on document_acl (subject_type, subject_value);

-- chunk 元数据：页码与章节定位的唯一来源，引用可点开依赖它
create table if not exists document_chunk (
    id           bigserial primary key,
    document_id  bigint  not null references document(id) on delete cascade,
    chunk_index  int     not null,
    section_path text,
    page_from    int,
    page_to      int,
    char_count   int,
    content_hash char(64),
    preview      text,
    created_at   timestamptz not null default now(),
    constraint uq_document_chunk unique (document_id, chunk_index)
);

comment on table document_chunk is '切分后的片段元数据；页码与章节路径用于引用定位';

create index if not exists idx_chunk_document on document_chunk (document_id);

-- P2：文档的"自建片段"状态。刻意与 index_status 分开——
-- "内核收下了"和"我们解析并向量化好了"是两件事，一个成功掩盖不了另一个失败。
alter table document add column if not exists chunk_status varchar(16) not null default 'PENDING';
alter table document add column if not exists chunk_count int not null default 0;
alter table document add column if not exists chunk_error text;
comment on column document.chunk_status is 'PENDING/INDEXED/FAILED —— 自建解析与向量化的状态，与 index_status 独立';
-- ============ P2 T14/T15：自建解析与自建检索 ============
-- 分块粒度是检索质量的第一变量，实测（余弦）：同一句定义句，埋在一整页里 0.21，
-- 独立成块 0.75；而 AnythingLLM 给的相似度是压缩过的（拿块原文自问自答最高才 0.572，
-- 该答 0.44 与库外 0.42 几乎分不开），所以 P2 把"相似度口径"收回自己手里：
-- 自己分块、自己向量化、自己算余弦，内核退为文档仓与对照通道。
create extension if not exists pg_trgm;

alter table document_chunk add column if not exists content text;
alter table document_chunk add column if not exists kind varchar(16);
alter table document_chunk add column if not exists level int;
alter table document_chunk add column if not exists embed_model varchar(64);
alter table document_chunk add column if not exists embedded_at timestamptz;
alter table document_chunk add column if not exists embedding vector(1024);

-- P1 上下文增强：入库时给片段补一句上下文摘要，随片段一起向量化（展示与关键词仍用正文）。
-- 关闭时两列为空；context_hash 参与片段幂等指纹，只有上下文真的变了才会触发重嵌。
alter table document_chunk add column if not exists context_prefix text;
alter table document_chunk add column if not exists context_hash char(64);
comment on column document_chunk.context_prefix is '片段上下文摘要（P1 上下文增强）；随片段一起嵌入，展示不用它';
comment on column document_chunk.context_hash is '上下文摘要的 SHA-256；与 content_hash 组成片段幂等指纹';

comment on column document_chunk.content is '片段正文；关键词召回与展示用（向量化时另加章节路径前缀）';
comment on column document_chunk.kind is 'HEADING/PARAGRAPH/BULLET/TABLE_ROW —— 解析器认出来的块类型';
comment on column document_chunk.embedding is '自建向量（嵌入模型，1024 维）。维度写死在 DDL：换模型=迁移，不能静默换';
comment on column document_chunk.page_from is '起始页码（PDF 按页真实页码；DOCX 无页码语义时为 null，不许瞎填）';

-- 向量检索用余弦距离；HNSW 建索引，量小的时候也走它，避免以后换全表扫描
create index if not exists idx_chunk_embedding on document_chunk
    using hnsw (embedding vector_cosine_ops);
create index if not exists idx_chunk_content_trgm on document_chunk using gin (content gin_trgm_ops);
create index if not exists idx_chunk_doc_index on document_chunk (document_id, chunk_index);

-- 同步任务（T04 使用）：内容哈希做幂等，失败可重试
create table if not exists ingest_task (
    id           bigserial primary key,
    document_id  bigint references document(id) on delete set null,
    source_path  text        not null,
    content_hash char(64),
    status       varchar(16) not null default 'PENDING',
    attempts     int         not null default 0,
    last_error   text,
    started_at   timestamptz,
    finished_at  timestamptz,
    created_at   timestamptz not null default now()
);

comment on table ingest_task is '文档接入任务；status 取 PENDING/RUNNING/SUCCEEDED/FAILED/SKIPPED';

-- T04：给任务补上"做了什么"，status 只说明结果不说明动作
alter table ingest_task add column if not exists action varchar(16);
alter table ingest_task add column if not exists detail text;
comment on column ingest_task.action is 'CREATED/REUPLOADED/SKIPPED/FAILED —— 本次对文档做了什么';

create index if not exists idx_ingest_status on ingest_task (status);

-- T19 文档查看审计：谁、何时、看了哪份、走哪个入口（三种入口都记）
--
-- 为什么拒绝也要记：一次"有人试图打开秘密文档"本身就是安全事件，
-- 只记成功的话，这行证据永远不存在。outcome 取 OK / DENIED。
create table if not exists document_view_log (
    id          bigserial primary key,
    document_id bigint      references document(id) on delete set null,
    doc_no      varchar(64),
    version     varchar(32),
    username    varchar(64) not null,
    role_code   varchar(32),
    channel     varchar(16) not null,
    outcome     varchar(16) not null,
    watermark   varchar(96),
    client_ip   varchar(64),
    created_at  timestamptz not null default now()
);

comment on table document_view_log is '文档查看审计；channel 取 FILE/CONTENT/DOWNLOAD，outcome 取 OK/DENIED';
comment on column document_view_log.watermark is '本次实际烧进文件的水印文字（账号 + 空格 + yyyy-MM-dd）';

create index if not exists idx_view_log_document on document_view_log (document_id, created_at desc);
create index if not exists idx_view_log_user on document_view_log (username, created_at desc);

-- ============ 本地账号（T06 使用）============
create table if not exists app_role (
    code        varchar(32) primary key,
    name        varchar(64) not null,
    description text
);

insert into app_role (code, name, description) values
    ('admin',  '系统管理员',   '配置、知识域与权限管理'),
    ('hr',     '人力资源部',   '可见秘密级人事文档'),
    ('ops',    '运营/业务人员', '默认角色，仅可见被授权文档'),
    ('viewer', '只读用户',     '仅问答，无管理权限')
on conflict (code) do update set
    name = excluded.name,
    description = excluded.description;

create table if not exists app_user (
    id            bigserial primary key,
    username      varchar(64)  not null unique,
    display_name  varchar(64),
    password_hash varchar(100),
    role_code     varchar(32)  not null references app_role(code),
    dept_code     varchar(64),
    enabled       boolean      not null default true,
    created_at    timestamptz  not null default now(),
    last_login_at timestamptz
);

comment on table app_user is '本地账号；密码使用 BCrypt 哈希存储';

-- T06 登录会话。只存令牌的 SHA-256：即使库被拖走也换不出可用的令牌。
create table if not exists app_session (
    token_hash   char(64) primary key,
    user_id      bigint      not null references app_user(id) on delete cascade,
    created_at   timestamptz not null default now(),
    expires_at   timestamptz not null
);

comment on table app_session is '登录会话；token_hash = SHA-256(令牌)，明文令牌只在登录响应里出现一次';

create index if not exists idx_app_session_user on app_session (user_id);
create index if not exists idx_app_session_expires on app_session (expires_at);

-- ============ T10 运行记录（AC-P1-011） ============
-- 一次问答一条。**落库而不是只写日志**：日志能回答"出过什么事"，
-- 回答不了"这类问题一共问了多少次、拒答率多少、这个月花了多少 token 与费用"——
-- 前者靠 grep，后者要聚合。两件事的读者也不同：日志给排障，账本给管理和校准。
create table if not exists qa_trace (
    id                      bigserial primary key,
    created_at              timestamptz  not null default now(),
    user_name               varchar(64)  not null,
    domain_code             varchar(32)  not null,
    question                text         not null,
    -- 检索侧：当时的可见范围与命中情况
    access_filter           text         not null,
    requested_top_n         int          not null,
    min_score               numeric(5,4) not null,
    answer_min_score        numeric(5,4) not null,
    kernel_top_n            int          not null,
    kernel_hits             int          not null,
    dropped_unauthorized    int          not null,
    dropped_unknown         int          not null,
    dropped_not_indexed     int          not null,
    dropped_duplicate       int          not null,
    evidence_count          int          not null,
    document_count          int          not null,
    best_score              numeric(6,4),
    version_conflict        boolean      not null,
    insufficient            boolean      not null,
    retrieval_elapsed_ms    bigint       not null,
    -- 生成侧
    model                   varchar(64),
    model_called            boolean      not null,
    refused                 boolean      not null,
    refusal_rule            varchar(32),
    cited_count             int          not null,
    out_of_range            text,
    truncated               boolean      not null,
    prompt_tokens           int          not null,
    completion_tokens       int          not null,
    generation_elapsed_ms   bigint       not null,
    total_elapsed_ms        bigint       not null,
    -- 费用：单价随账期变，所以把**当时用的单价**一起快照下来；
    -- 否则运营改一次价，历史成本会被一起改掉，账就再也对不上了。
    -- 单价未配置时 cost_total 为 null——不用 0 表示"不知道"，0 会被读成"免费"。
    prompt_price_per_1k     numeric(12,6),
    completion_price_per_1k numeric(12,6),
    cost_total              numeric(14,6)
);

comment on table qa_trace is '问答运行记录；AC-P1-011 的落点。token 有记录，费用按提问当时的单价快照计算';

create index if not exists idx_qa_trace_created on qa_trace (created_at desc);
create index if not exists idx_qa_trace_domain on qa_trace (domain_code, created_at desc);
create index if not exists idx_qa_trace_refused on qa_trace (refused, created_at desc);

-- 候选证据明细：单列一表而不是把片段塞进 JSON 字段，是为了能回答
-- "某份文档到底被检索到过几次"——这个答案决定它该不该继续留在库里。
create table if not exists qa_trace_evidence (
    id          bigserial primary key,
    trace_id    bigint       not null references qa_trace(id) on delete cascade,
    rank        int          not null,
    document_id bigint,
    doc_no      varchar(64),
    title       text,
    version     varchar(32),
    chunk_id    varchar(64),
    score       numeric(6,4),
    cited       boolean      not null default false
);

comment on table qa_trace_evidence is '一次问答实际命中的候选证据（文档 + 片段）；cited 标记它有没有被引用';

create index if not exists idx_qa_trace_evidence_trace on qa_trace_evidence (trace_id);
create index if not exists idx_qa_trace_evidence_doc on qa_trace_evidence (document_id);

-- P2 T16：账本里也要留下页码，页码级引用才可复核。
-- 为 null = 当时那条片段没有页码信息（内核通道或 DOCX），不是"第 0 页"。
alter table qa_trace_evidence add column if not exists page_from int;
alter table qa_trace_evidence add column if not exists page_to int;
comment on column qa_trace_evidence.page_from is '片段起始页码；null 表示无页码信息，界面不显示';

-- P2 T18（AC-P2-007）：两路召回的**融合前**名次与关键词分。
-- rank 是融合之后的最终名次，只记它答不了"哪一路失效了"——某一路为 0 表示那条通道根本没召回它，
-- 这才是排查时第一眼要看的东西（向量没召回、关键词没召回，处置完全不同）。
alter table qa_trace_evidence add column if not exists vector_rank int not null default 0;
alter table qa_trace_evidence add column if not exists keyword_rank int not null default 0;
alter table qa_trace_evidence add column if not exists keyword_score numeric(6,4) not null default 0;
comment on column qa_trace_evidence.vector_rank is '向量通道名次；0 = 该通道没召回这条片段（不是第 0 名）';
comment on column qa_trace_evidence.keyword_rank is '关键词通道名次；0 = 该通道没召回这条片段';
comment on column qa_trace_evidence.keyword_score is '关键词相似度（trigram 覆盖率）；向量余弦已由 score 记录';

-- P2 收口（A3）：账本片段要能在"片段 id 变了"之后依然找得回原文。
-- chunk_id 是 document_chunk 的代理键（bigserial），历史上"强制重解析"会把整份片段删了重灌，
-- 于是 2987 条证据里 2554 条指向了已不存在的 id。现在两头都收：
--   1) 写库改成按 (document_id, chunk_index) upsert，同一位置的片段 id 不再变（见 DocumentChunkRepository）；
--   2) 账本另存自然键 chunk_index，(document_id, chunk_index) 是唯一约束，不随重灌漂移。
-- 注意 chunk_index 为 null 有两种含义：内核通道来的片段没有本地序号；或这条是历史悬空行（见下）。
alter table qa_trace_evidence add column if not exists chunk_index int;
comment on column qa_trace_evidence.chunk_index is '片段在文档内的序号；null = 内核通道片段或历史悬空行（无本地序号）';

create index if not exists idx_qa_trace_evidence_chunk on qa_trace_evidence (document_id, chunk_index);

-- 一次性回填：chunk_id 现在还指向真实行的（没被历史重解析打断的）补上序号。
-- 已经被打断的那些**无法恢复**——账本从没存过片段正文或序号，没有任何可判定正确性的依据，
-- 与其猜一个位置，不如让它留 null 并在界面上承认"这条查不回原文"。
update qa_trace_evidence e
   set chunk_index = c.chunk_index
  from document_chunk c
 where e.chunk_index is null
   and e.chunk_id = c.id::text;
