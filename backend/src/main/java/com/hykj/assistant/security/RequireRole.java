package com.hykj.assistant.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在接口或控制器上，要求当前身份至少命中其中一个角色。
 * 默认的 /api/** 已经是"必须登录"，这个注解负责再往上加一道角色门槛。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    String[] value();
}
