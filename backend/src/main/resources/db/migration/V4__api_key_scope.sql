-- 多项目接入 S5：API Key 绑租户/域 + 终端用户弱断言。
-- Key 的可见范围 = 绑定账号的可见范围 ∩ Key 自身的绑定（两者都 fail-closed）。
-- 两列都用空数组表达"不限"：Key 默认沿用绑定账号的全部可见范围，只有显式绑定才收窄。
alter table api_client add column if not exists tenant_codes text[] not null default '{}';
alter table api_client add column if not exists domain_codes text[] not null default '{}';
comment on column api_client.tenant_codes is 'S5：Key 可用的租户范围；空数组=不限（沿用绑定账号的租户可见范围）';
comment on column api_client.domain_codes is 'S5：Key 可用的知识域范围；空数组=不限';

-- 终端用户弱断言（X-End-User）：只入账本、不判权（方案 §6.2）。
-- 判权仍走 Key 绑定的账号；将来升级成签名令牌真判权时，主体早已在账本里，判权位置不用重写。
alter table qa_trace add column if not exists end_user varchar(128);
comment on column qa_trace.end_user is 'S5：终端用户弱断言（外部调用经 X-End-User 头传入），只用于审计，不参与判权';
