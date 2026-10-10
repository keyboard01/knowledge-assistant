package io.github.keyboard01.assistant.security;

/** 登录失败。刻意不区分"用户不存在"与"密码错误"，避免成为账号探测接口。 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
