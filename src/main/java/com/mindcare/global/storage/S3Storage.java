package com.mindcare.global.storage;

import java.util.Optional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** 운영용. 버킷은 비공개(퍼블릭 액세스 차단)이고, 접근은 presigned URL로만 한다. */
public class S3Storage implements Storage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3Storage(S3Client s3, S3Presigner presigner, String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public String presignPut(String key, String contentType, long size) {
        return presigner.presignPutObject(request -> request
                        .signatureDuration(UPLOAD_EXPIRES)
                        .putObjectRequest(put -> put.bucket(bucket).key(key)
                                .contentType(contentType)
                                .contentLength(size)))
                .url().toString();
    }

    @Override
    public String presignGet(String key) {
        return presigner.presignGetObject(request -> request
                        .signatureDuration(DOWNLOAD_EXPIRES)
                        .getObjectRequest(get -> get.bucket(bucket).key(key)))
                .url().toString();
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        try {
            HeadObjectResponse head = s3.headObject(request -> request.bucket(bucket).key(key));
            return Optional.of(new ObjectInfo(head.contentType(), head.contentLength()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
