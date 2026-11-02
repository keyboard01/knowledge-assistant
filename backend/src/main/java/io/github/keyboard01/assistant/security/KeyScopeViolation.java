package io.github.keyboard01.assistant.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * API Key 越过了自身绑定范围（多项目接入 S5）。403 而不是 401：身份是成立的，
 * 只是这把 Key 没被授权碰目标资源——和角色不足走 {@link RequireRole} 的口径一致。
 *
 * <p>类上标 {@code @ResponseStatus} 而不是在每个控制器里写 handler：
 * 检索、问答、深度分析、MCP、评测五个入口都过 {@code RetrievalService.search} 这一道门，
 * 逐个控制器映射漏掉任何一个，表现都是 200 空结果而不是显式拒绝。</p>
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class KeyScopeViolation extends RuntimeException {

    public KeyScopeViolation(String message) {
        super(message);
    }
}
