package com.mindcare.domain.user.service;

import java.util.List;
import java.util.Set;

/**
 * TRD 16-2 상담사 분야 태그 사전. 프로필 specialties(USR-06)는 이 목록에서만 고른다.
 * TRD 기준 원본은 ai/contract/dictionary.json (AI 서버와 공유). ai/가 레포에 들어오면 그 파일을 읽도록 바꾼다.
 */
public final class SpecialtyTags {

    public static final List<String> ALL = List.of(
            "청년 진로", "취업 스트레스", "직장 번아웃", "직장 내 관계", "연애 갈등", "이별",
            "가족 관계", "자존감", "학업 스트레스", "대인관계 불안", "상실·애도", "생활 리듬");

    private static final Set<String> SET = Set.copyOf(ALL);

    private SpecialtyTags() {
    }

    public static boolean contains(String tag) {
        return SET.contains(tag);
    }
}
