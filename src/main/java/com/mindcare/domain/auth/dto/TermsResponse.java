package com.mindcare.domain.auth.dto;

/** AUTH-02. body_md는 마크다운 원문 (프론트가 약관 모달에서 렌더링). */
public record TermsResponse(String type, String version, String title, String bodyMd, boolean required) {
}
