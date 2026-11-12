package com.mindcare.domain.user.entity;

/**
 * provider_profiles.career 한 줄 (jsonb). from·to는 "yyyy-MM", to가 없으면 재직 중.
 */
public record Career(String org, String from, String to) {
}
