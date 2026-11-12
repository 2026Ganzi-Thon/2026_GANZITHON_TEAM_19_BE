package com.mindcare.global.storage;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로컬·테스트용. 실제 업로드는 없고, presignPut을 받은 key는 "올라온 것"으로 친다.
 * 그래서 로컬에서도 업로드 URL 발급 → 자격 등록 → 심사 흐름을 끝까지 돌려볼 수 있다.
 */
public class InMemoryStorage implements Storage {

    private final Map<String, ObjectInfo> objects = new ConcurrentHashMap<>();

    @Override
    public String presignPut(String key, String contentType, long size) {
        objects.put(key, new ObjectInfo(contentType, size));
        return "http://localhost/local-storage/" + key + "?upload";
    }

    @Override
    public String presignGet(String key) {
        return "http://localhost/local-storage/" + key;
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        return Optional.ofNullable(objects.get(key));
    }
}
