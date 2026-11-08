package com.mindcare.domain.session.service;
import com.mindcare.domain.session.dto.MoodCheckRequest;
import com.mindcare.domain.session.dto.SessionResponse;
import com.mindcare.domain.session.dto.SessionReviewRequest;
import com.mindcare.domain.session.entity.MoodCheck;
import com.mindcare.domain.session.entity.Session;
import com.mindcare.domain.session.repository.MoodCheckRepository;
import com.mindcare.domain.session.repository.SessionRepository;
import com.mindcare.domain.user.service.UserService;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import com.mindcare.global.crypto.SensitiveTextCodec;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class SessionService {
    private final SessionRepository sessionRepository; private final MoodCheckRepository moodCheckRepository; private final UserService userService; private final SensitiveTextCodec sensitiveTextCodec;
    public List<SessionResponse> list(UUID actorId) { return sessionRepository.findByClientIdOrderByScheduledStartDesc(actorId).stream().map(SessionResponse::from).toList(); }
    public SessionResponse detail(UUID actorId, UUID sessionId) { Session s = getParticipantSession(actorId, sessionId); return SessionResponse.from(s); }
    @Transactional public void recordMood(UUID clientId, UUID sessionId, MoodCheckRequest request) {
        Session session = sessionRepository.findById(sessionId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        if (!session.getClient().getId().equals(clientId)) throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);
        if (moodCheckRepository.existsBySessionIdAndPhase(sessionId, request.phase())) throw new AppException(ErrorCode.ALREADY_SUBMITTED);
        moodCheckRepository.save(new MoodCheck(session, userService.getById(clientId), request.phase(), request.score()));
    }

    @Transactional public void cancel(UUID actorId, UUID sessionId, String reason) {
        Session session = sessionRepository.findById(sessionId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        if (!session.getClient().getId().equals(actorId) && !session.getProvider().getId().equals(actorId)) {
            throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        try { session.cancel(actorId, reason); }
        catch (IllegalStateException e) { throw new AppException(ErrorCode.INVALID_STATE); }
    }

    @Transactional public void end(UUID providerId, UUID sessionId) {
        Session session = sessionRepository.findById(sessionId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        if (!session.getProvider().getId().equals(providerId)) throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);
        try { session.complete(); }
        catch (IllegalStateException e) { throw new AppException(ErrorCode.INVALID_STATE); }
    }
    @Transactional public void review(UUID clientId, UUID sessionId, SessionReviewRequest request) { Session s = sessionRepository.findById(sessionId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND)); if (!s.getClient().getId().equals(clientId)) throw new AppException(ErrorCode.FORBIDDEN_RESOURCE); try { s.review(request.rating(), sensitiveTextCodec.encrypt(request.text() == null ? "" : request.text())); } catch (IllegalStateException e) { throw new AppException(ErrorCode.INVALID_STATE); } }
    @Transactional public void note(UUID providerId, UUID sessionId, String note) { Session s = sessionRepository.findById(sessionId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND)); if (!s.getProvider().getId().equals(providerId)) throw new AppException(ErrorCode.FORBIDDEN_RESOURCE); s.writeNote(sensitiveTextCodec.encrypt(note)); }
    private Session getParticipantSession(UUID actorId, UUID sessionId) { Session s = sessionRepository.findById(sessionId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND)); if (!s.getClient().getId().equals(actorId) && !s.getProvider().getId().equals(actorId)) throw new AppException(ErrorCode.FORBIDDEN_RESOURCE); return s; }
}
