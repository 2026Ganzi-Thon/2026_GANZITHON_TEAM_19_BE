package com.mindcare.global.auth;

import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.service.UserService;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
@RequiredArgsConstructor
public class LoginUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final UserService userService;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(LoginUser.class)
                && parameter.getParameterType().equals(AuthUser.class);
    }

    @Override
    public AuthUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object firebaseUid = webRequest.getAttribute(
                AuthInterceptor.FIREBASE_UID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (firebaseUid == null) {
            // 공개 경로에서 @LoginUser를 쓴 경우 (토큰 검사를 안 거침)
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        User user = userService.getByFirebaseUid((String) firebaseUid);
        return new AuthUser(user.getId(), user.getRole(), user.getStatus());
    }
}
