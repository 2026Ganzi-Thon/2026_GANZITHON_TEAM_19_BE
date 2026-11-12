package com.mindcare.domain.credential.controller;

import com.mindcare.domain.credential.dto.CredentialCreateRequest;
import com.mindcare.domain.credential.dto.CredentialResponse;
import com.mindcare.domain.credential.dto.CredentialUpdateRequest;
import com.mindcare.domain.credential.dto.SubmitResponse;
import com.mindcare.domain.credential.dto.UploadUrlRequest;
import com.mindcare.domain.credential.dto.UploadUrlResponse;
import com.mindcare.domain.credential.service.CredentialService;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.auth.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CredentialController {

    private final CredentialService credentialService;

    @Operation(operationId = "CRD-01", summary = "자격 서류 업로드 URL 발급 (PDF·JPG·PNG, 10MB, 5분)")
    @PostMapping("/me/credentials/upload-url")
    public UploadUrlResponse uploadUrl(@LoginUser AuthUser user, @Valid @RequestBody UploadUrlRequest request) {
        return credentialService.issueUploadUrl(user, request);
    }

    @Operation(operationId = "CRD-02", summary = "자격 등록 (draft). 국가자격 verify=true면 진위 확인")
    @PostMapping("/me/credentials")
    @ResponseStatus(HttpStatus.CREATED)
    public CredentialResponse create(@LoginUser AuthUser user, @Valid @RequestBody CredentialCreateRequest request) {
        return credentialService.create(user, request);
    }

    @Operation(operationId = "CRD-03", summary = "내 자격 목록 (상태·반려 사유)")
    @GetMapping("/me/credentials")
    public List<CredentialResponse> listMine(@LoginUser AuthUser user) {
        return credentialService.listMine(user);
    }

    @Operation(operationId = "CRD-05", summary = "자격 수정 (draft·rejected만, user_corrected=true)")
    @PatchMapping("/me/credentials/{credentialId}")
    public CredentialResponse update(@LoginUser AuthUser user, @PathVariable UUID credentialId,
            @Valid @RequestBody CredentialUpdateRequest request) {
        return credentialService.update(user, credentialId, request);
    }

    @Operation(operationId = "CRD-04", summary = "자격 심사 요청 (draft 전부 제출, 프로필 필수)")
    @PostMapping("/me/credentials/submit")
    public SubmitResponse submit(@LoginUser AuthUser user) {
        return credentialService.submit(user);
    }
}
