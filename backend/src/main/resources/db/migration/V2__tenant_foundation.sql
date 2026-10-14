-- ============================================================================
-- V2：租户地基（多项目接入 S1）
--
-- 只做"结构 + 回填"，**不改任何权限判定语义**：`domain_acl` 里的 `ALL:*` 仍是
-- "平台全局"，检索链路也还没有租户谓词——那些是下一步（S2），必须与语义收窄同批做。
-- 本片交付后行为应当与之前完全一致（现有测试一个都不该动）。
--
-- 设计见 docs/多项目接入方案.md。
-- ============================================================================

-- 租户：一个项目一个。它是"知识域、成员、授权"的顶层边界。
create table if not exists tenant (
    code        varchar(32)  primary key,
    name        varchar(64)  not null,
    description text,
    enabled     boolean      not null default true,
    created_at  timestamptz  not null default now()
);
comment on table tenant is '租户（一个项目一套）：知识域、成员与授权的顶层边界';
comment on column tenant.code is '租户代码；唯一且不可改（域与成员都按它关联）';
comment on column tenant.enabled is '停用后不再新建域/成员，历史数据保留';

-- 人属于哪些租户、在租户里是什么角色。
-- 为什么不是给 app_user 加一列：app_user.role_code 是**全局单角色**，而"同一个人在 A 项目
-- 是管理员、在 B 项目只是普通成员"是这套模型的常见形态，一列表达不了。
create table if not exists tenant_member (
    id          bigserial   primary key,
    tenant_code varchar(32) not null references tenant(code) on delete cascade,
    user_id     bigint      not null references app_user(id) on delete cascade,
    role        varchar(16) not null,
    created_at  timestamptz not null default now(),
    constraint uq_tenant_member unique (tenant_code, user_id),
    constraint ck_tenant_member_role check (role in ('admin', 'member'))
);
comment on table tenant_member is '租户成员关系；role=admin 即"租户管理员"（只管本租户的域/成员/授权）';
comment on column tenant_member.role is 'admin=租户管理员 / member=普通成员；CHECK 约束挡住拼错的角色值';

create index if not exists idx_tenant_member_user on tenant_member (user_id);

-- 域归属租户 + 创建者。归属决定生死（人走了库还在），创建者只用于审计与"能管自己建的库"。
alter table knowledge_domain add column if not exists tenant_code varchar(32) references tenant(code);
alter table knowledge_domain add column if not exists created_by  varchar(64);
comment on column knowledge_domain.tenant_code is '域所属租户；可见性的作用域上界（"项目内公开"= 本租户全员）';
comment on column knowledge_domain.created_by is '创建者用户名；升级前就存在的域为 null（那时还没有创建者概念）';

-- 默认租户：升级前就存在的域全部归它，保证"升级后行为不变"。
-- 用 do nothing 而不是 do update：这是**回填**，不该覆盖任何已有取值（种子自愈是 V1 里 app_role 那种语义）。
insert into tenant (code, name, description)
values ('default', '默认租户', '升级前就存在的知识域都归这里')
on conflict (code) do nothing;

update knowledge_domain set tenant_code = 'default' where tenant_code is null;

-- 收紧为 NOT NULL：留着 null 就等于留一个"不属于任何租户"的域，
-- 而 S2 的租户谓词是按等值匹配的——那种域会对所有人隐身（fail-closed，不泄漏，但很难排查）。
alter table knowledge_domain alter column tenant_code set not null;

create index if not exists idx_knowledge_domain_tenant on knowledge_domain (tenant_code);
