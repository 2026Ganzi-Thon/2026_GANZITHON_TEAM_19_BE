package com.mindcare.domain.credential.dto;

/** CRD-01 응답. 프론트는 upload_url에 같은 Content-Type으로 PUT하고, file_key를 CRD-02에 넣는다. */
public record UploadUrlResponse(String uploadUrl, String fileKey, long expiresIn) {
}
