package com.mindcare.domain.session.service;
import com.mindcare.domain.intake.entity.Intake;
import com.mindcare.domain.intake.repository.IntakeRepository;
import com.mindcare.domain.session.dto.MoodCheckRequest;
import com.mindcare.domain.session.dto.SessionCreateRequest;
import com.mindcare.domain.session.dto.SessionCreateResponse;
import com.mindcare.domain.session.dto.SessionResponse;
import com.mindcare.domain.session.dto.SessionReviewRequest;
import com.mindcare.domain.session.dto.SessionHistoryItem;
import com.mindcare.domain.session.entity.MoodCheck;
import com.mindcare.domain.session.entity.Session;
import com.mindcare.domain.session.entity.SessionStatus;
import com.mindcare.domain.slot.service.SlotHold;
import com.mindcare.domain.slot.service.SlotService;
import com.mindcare.domain.session.repository.MoodCheckRepository;
import com.mindcare.domain.session.repository.SessionRepository;
import com.mindcare.domain.user.service.UserService;
import com.mindcare.domain.report.entity.Report;
import com.mindcare.domain.report.repository.ReportRepository;
import com.mindcare.domain.report.service.ReportService;
import com.mindcare.domain.kpi.service.KpiEventService;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import com.mindcare.global.crypto.SensitiveTextCodec;
import com.mindcare.realtime.RealtimePublisher;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class SessionService {
    private final SessionRepository sessionRepository; private final MoodCheckRepository moodCheckRepository; private final UserService userService; private final SensitiveTextCodec sensitiveTextCodec; private final RealtimePublisher realtimePublisher; private final ReportService reportService; private final KpiEventService kpiEvents;
    private final SlotService slotService; private final IntakeRepository intakeRepository; private final ReportRepository reportRepository;

    /**
     * SES-01 예약 생성 (TRD 13-2, 15-1). 슬롯을 10분 홀드하고 pending_payment 세션을 만든다.
     * 트랙·가격은 슬롯 소유자 역할로 정한다. 검증이 하나라도 실패하면 홀드까지 같이 롤백된다.
     */
    @Transactional public SessionCreateResponse create(UUID clientId, SessionCreateRequest request) {
        // hold()는 영속성 컨텍스트를 비우므로 엔티티 조회는 그 뒤에 한다
        SlotHold hold = slotService.hold(request.slotId());
        UUID intakeId = request.intakeId();
        if (request.rebookOf() != null) {
            intakeId = rebookSource(clientId, request.rebookOf(), hold.providerId()).getIntakeId();
        } else if (intakeId != null) {
            Intake intake = intakeRepository.findById(intakeId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
            if (!intake.getClient().getId().equals(clientId)) throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        Session session = sessionRepository.save(Session.reserve(userService.getById(clientId), userService.getById(hold.providerId()),
                hold.track(), hold.slotId(), hold.price(), hold.startAt(), hold.endAt(), intakeId, request.referralId(), request.rebookOf()));
        return SessionCreateResponse.of(session, hold.holdExpiresAt());
    }

    /** rebook_of: 본인의 completed 세션이고 슬롯 상담사가 같아야 한다. 아니면 422 SESSION_NOT_ELIGIBLE. */
    private Session rebookSource(UUID clientId, UUID rebookOf, UUID slotProviderId) {
        Session previous = sessionRepository.findById(rebookOf).orElse(null);
        if (previous == null || !previous.getClient().getId().equals(clientId)
                || previous.getStatus() != SessionStatus.COMPLETED || !previous.getProvider().getId().equals(slotProviderId)) {
            throw new AppException(ErrorCode.SESSION_NOT_ELIGIBLE);
        }
        return previous;
    }

    /** 결제 확정(PAY-03) 이벤트에서 호출. pending_payment → confirmed, 아니면 409 INVALID_STATE (결제도 롤백). */
    @Transactional public void confirmPaid(UUID sessionId) {
        Session session = sessionRepository.findById(sessionId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        try { session.confirm(); }
        catch (IllegalStateException e) { throw new AppException(ErrorCode.INVALID_STATE); }
    }

    public List<SessionResponse> list(UUID actorId) {
        List<Session> sessions = sessionRepository.findByClientIdOrProviderIdOrderByScheduledStartDesc(actorId, actorId);
        // 세션마다 따로 조회하지 않고 리포트를 한 번에 모은다
        Map<UUID, Report> reports = new HashMap<>();
        if (!sessions.isEmpty()) {
            for (Report r : reportRepository.findBySessionIdIn(sessions.stream().map(Session::getId).toList())) reports.put(r.getSessionId(), r);
        }
        return sessions.stream().map(s -> SessionResponse.from(s, reports.get(s.getId()))).toList();
    }
    public SessionResponse detail(UUID actorId, UUID sessionId) { Session s = getParticipantSession(actorId, sessionId); return SessionResponse.from(s, reportRepository.findBySessionId(s.getId()).orElse(null)); }
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
        reportService.requestForCompletedSession(session);
        kpiEvents.record("session-completed-" + sessionId, "session_completed", Map.of("track", session.getTrack()));
        // TRD 14-3 session:ended. 실패해도 세션 종료 자체는 유지한다
        realtimePublisher.publish(List.of(session.getClient().getId(), session.getProvider().getId()),
                "session:ended", Map.of("session_id", sessionId, "ended_by", "provider"));
    }

    /** TRD 14-1: 첫 provider 입장 시 confirmed → in_progress. realtime의 session.join이 쓴다. */
    @Transactional public void markInProgress(UUID providerId, UUID sessionId) {
        Session session = sessionRepository.findById(sessionId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        if (!session.getProvider().getId().equals(providerId)) throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);
        session.start();
    }
    @Transactional public void review(UUID clientId, UUID sessionId, SessionReviewRequest request) { Session s = sessionRepository.findById(sessionId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND)); if (!s.getClient().getId().equals(clientId)) throw new AppException(ErrorCode.FORBIDDEN_RESOURCE); try { s.review(request.rating(), sensitiveTextCodec.encrypt(request.text() == null ? "" : request.text())); } catch (IllegalStateException e) { throw new AppException(ErrorCode.INVALID_STATE); } }
    @Transactional public void note(UUID providerId, UUID sessionId, String note) { Session s = sessionRepository.findById(sessionId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND)); if (!s.getProvider().getId().equals(providerId)) throw new AppException(ErrorCode.FORBIDDEN_RESOURCE); s.writeNote(sensitiveTextCodec.encrypt(note)); }
    /** SES-09: provider sees only their own completed sessions with this client (latest 10). */
    public List<SessionHistoryItem> history(UUID providerId, UUID sessionId) {
        Session current = sessionRepository.findById(sessionId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        if (!current.getProvider().getId().equals(providerId)) throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);
        return sessionRepository.findByProviderIdAndClientIdAndStatusOrderByScheduledStartDesc(providerId,
                        current.getClient().getId(), SessionStatus.COMPLETED).stream()
                .filter(session -> !session.getId().equals(sessionId)).limit(10).map(session -> {
                    Map<com.mindcare.domain.session.entity.MoodPhase, Short> moods = new HashMap<>();
                    moodCheckRepository.findBySessionId(session.getId())
                            .forEach(mood -> moods.put(mood.getPhase(), mood.getScore()));
                    return new SessionHistoryItem(session.getId(), session.getScheduledStart(),
                            moods.get(com.mindcare.domain.session.entity.MoodPhase.BEFORE),
                            moods.get(com.mindcare.domain.session.entity.MoodPhase.AFTER),
                            session.getProviderNoteEnc() == null ? null : sensitiveTextCodec.decrypt(session.getProviderNoteEnc()));
                }).toList();
    }
    private Session getParticipantSession(UUID actorId, UUID sessionId) { Session s = sessionRepository.findById(sessionId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND)); if (!s.getClient().getId().equals(actorId) && !s.getProvider().getId().equals(actorId)) throw new AppException(ErrorCode.FORBIDDEN_RESOURCE); return s; }
}
