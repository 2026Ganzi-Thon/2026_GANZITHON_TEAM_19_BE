package com.mindcare.domain.supervision.repository;
import com.mindcare.domain.supervision.entity.Supervision;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SupervisionRepository extends JpaRepository<Supervision,UUID>{
 List<Supervision> findByExpertIdOrTraineeIdOrderByCreatedAtDesc(UUID expertId, UUID traineeId);
}
