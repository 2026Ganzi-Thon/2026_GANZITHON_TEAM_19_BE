package com.mindcare.domain.message.repository;
import com.mindcare.domain.message.entity.Message;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MessageRepository extends JpaRepository<Message, UUID> {
    Optional<Message> findBySessionIdAndClientMsgId(UUID sessionId, String clientMsgId);
    List<Message> findTop100BySessionIdAndIdGreaterThanOrderByIdAsc(UUID sessionId, UUID afterId);
    List<Message> findTop100BySessionIdOrderByIdAsc(UUID sessionId);
}
