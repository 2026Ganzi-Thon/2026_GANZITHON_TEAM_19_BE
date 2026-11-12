package com.mindcare.global.storage;

import java.time.Duration;
import java.util.Optional;

/**
 * TRD 17장 파일 저장. 운영은 S3Storage, 로컬·테스트는 InMemoryStorage (app.storage.type).
 * 프론트는 presignPut으로 받은 URL에 파일을 직접 PUT하고, 서버는 key만 저장한다.
 */
public interface Storage {

    Duration UPLOAD_EXPIRES = Duration.ofMinutes(5);
    Duration DOWNLOAD_EXPIRES = Duration.ofMinutes(10);

    /** 업로드용 URL (5분). contentType·size가 요청과 다르면 S3가 거절한다. */
    String presignPut(String key, String contentType, long size);

    /** 보기용 URL (10분). 어드민 심사 화면에서 쓴다. */
    String presignGet(String key);

    /** 올라간 파일 정보. 아직 안 올라왔으면 비어 있다. */
    Optional<ObjectInfo> head(String key);

    record ObjectInfo(String contentType, long size) {
    }
}
