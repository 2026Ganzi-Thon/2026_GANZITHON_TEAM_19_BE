package com.mindcare.domain.supervision.service;
import com.mindcare.domain.supervision.entity.Supervision; import com.mindcare.domain.supervision.repository.SupervisionRepository; import com.mindcare.global.error.*; import java.util.UUID; import lombok.RequiredArgsConstructor; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor @Transactional(readOnly=true) public class SupervisionService { private final SupervisionRepository supervisions;
 @Transactional public void submit(UUID expertId,UUID id,byte[] encryptedContent){ Supervision s=supervisions.findById(id).orElseThrow(()->new AppException(ErrorCode.NOT_FOUND)); if(!s.getExpertId().equals(expertId))throw new AppException(ErrorCode.FORBIDDEN_RESOURCE); try{s.submit(encryptedContent);}catch(IllegalStateException e){throw new AppException(ErrorCode.INVALID_STATE);} }
}
