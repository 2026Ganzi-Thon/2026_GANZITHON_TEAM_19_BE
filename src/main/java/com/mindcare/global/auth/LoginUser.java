package com.mindcare.global.auth;

import io.swagger.v3.oas.annotations.Parameter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러에서 로그인한 사용자를 받는다. 가입 전이면 404 USER_NOT_REGISTERED.
 * <pre>
 * public Foo get(@LoginUser AuthUser user) {
 *     user.requireRole(UserRole.CLIENT);
 * }
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Parameter(hidden = true)
public @interface LoginUser {
}
