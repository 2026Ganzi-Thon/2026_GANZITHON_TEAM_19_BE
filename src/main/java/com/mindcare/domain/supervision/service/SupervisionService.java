package com.mindcare.domain.supervision.service;
import com.mindcare.domain.supervision.entity.Supervision; import com.mindcare.domain.supervision.repository.SupervisionRepository; import com.mindcare.global.crypto.SensitiveTextCodec; import com.mindcare.global.error.*; import java.util.*; import lombok.RequiredArgsConstructor; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor @Transactional(readOnly=true) public class SupervisionService { private final SupervisionRepository supervisions; private final SensitiveTextCodec codec;
 public List<Supervision> list(UUID userId) { return supervisions.findByExpertIdOrTraineeIdOrderByCreatedAtDesc(userId,userId); }
 @Transactional public void submit(UUID expertId,UUID id,String content){ Supervision s=supervisions.findById(id).orElseThrow(()->new AppException(ErrorCode.NOT_FOUND)); if(!s.getExpertId().equals(expertId))throw new AppException(ErrorCode.FORBIDDEN_RESOURCE); try{s.submit(codec.encrypt(content));}catch(IllegalStateException e){throw new AppException(ErrorCode.INVALID_STATE);} }
}
