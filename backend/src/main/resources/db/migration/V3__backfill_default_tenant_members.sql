-- ============================================================================
-- V3：可见性收窄前的数据准备——把升级前就存在的本地账号加入默认租户。
--
-- 为什么必须先做这一步：S2 给检索/查看/找图/列表都加了租户谓词（"只能看见自己所属租户的域"）。
-- `tenant_member` 在 V2 里是空的，若不回填，**所有人（包括 admin 之外的既有账号）都将看不到任何域**——
-- 那是"接口正常、结果为空"的静默故障，比报错难查得多。
--
-- 回填口径与 V2 的域回填一致：升级前是单租户，所以每个既有账号都算默认租户的成员。
-- 角色：平台管理员（app_role=admin）记 admin（默认租户由他管），其余记 member。
--   —— 这不改变可见性：平台管理员本来就跨租户可见（见 TenantRule），这里只是把归属记清楚。
--
-- 幂等：`not exists` 保证已有归属的账号不会被重复插入，也不会被覆盖。
-- ============================================================================

insert into tenant_member (tenant_code, user_id, role)
select 'default', u.id, case when lower(u.role_code) = 'admin' then 'admin' else 'member' end
  from app_user u
 where not exists (select 1 from tenant_member tm where tm.user_id = u.id);
