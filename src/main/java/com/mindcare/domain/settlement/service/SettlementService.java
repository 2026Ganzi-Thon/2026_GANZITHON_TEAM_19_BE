package com.mindcare.domain.settlement.service;

import com.mindcare.domain.admin.service.AuditLogService;
import com.mindcare.domain.admin.service.PriceConfigService;
import com.mindcare.domain.notification.service.NotificationService;
import com.mindcare.domain.settlement.dto.SettlementPayResponse;
import com.mindcare.domain.settlement.dto.SettlementResponse;
import com.mindcare.domain.settlement.dto.SettlementSummaryResponse;
import com.mindcare.domain.settlement.entity.Settlement;
import com.mindcare.domain.settlement.entity.SettlementKind;
import com.mindcare.domain.settlement.entity.SettlementStatus;
import com.mindcare.domain.settlement.repository.SettlementRepository;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.service.UserService;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TRD 15-5 정산. 정산 행은 BE-2(세션 종료·슈퍼비전 제출)가 아래 create·unlock 메서드를 불러서 만든다.
 * 조회·지급(STL-01~03)은 이 서비스에서. 월은 KST 기준.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementService {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SettlementRepository settlementRepository;
    private final PriceConfigService priceConfigService;
    private final UserService userService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    // ---- BE-2가 부르는 메서드 (같은 트랜잭션에 묶인다) ----

    /**
     * 세션이 completed 될 때. gross는 실제 결제 금액(sessions.price), 수수료율은 트랙 설정값.
     * locked=true: 연계 후 첫 전문 세션 (연계 슈퍼비전이 다 제출될 때까지 잠금).
     * 같은 세션으로 두 번 불러도 한 건만 만든다.
     *
     * @param track "coaching" | "professional"
     */
    @Transactional
    public UUID createForSession(UUID sessionId, UUID providerId, String track, int price, boolean locked) {
        return settlementRepository.findBySessionId(sessionId).map(Settlement::getId).orElseGet(() -> {
            BigDecimal feeRate = priceConfigService.trackPricing(track).feeRate();
            int fee = BigDecimal.valueOf(price).multiply(feeRate).setScale(0, RoundingMode.HALF_UP).intValueExact();
            return settlementRepository.save(Settlement.forSession(providerId, sessionId, price, fee, locked)).getId();
        });
    }

    /** 슈퍼비전이 submitted 될 때. 작성한 전문가 몫, 단가는 supervision.price (수수료 없음). 중복 호출 안전. */
    @Transactional
    public UUID createForSupervision(UUID supervisionId, UUID expertId) {
        return settlementRepository.findBySupervisionId(supervisionId).map(Settlement::getId).orElseGet(() ->
                settlementRepository.save(Settlement.forSupervision(expertId, supervisionId,
                        priceConfigService.supervisionPrice())).getId());
    }

    /**
     * 그 연계의 슈퍼비전이 모두 submitted(또는 cancelled)일 때, 연계 후 첫 전문 세션의 정산을 locked → pending.
     * "모두 끝났는지" 판단과 동시성(advisory lock, TRD 15-5)은 호출하는 쪽(BE-2)에서 한다. 풀렸으면 true.
     */
    @Transactional
    public boolean unlockForSession(UUID sessionId) {
        return settlementRepository.findBySessionId(sessionId).map(Settlement::unlock).orElse(false);
    }

    // ---- API ----

    /** STL-01. 수련생·전문가 본인 것만. 정지 중에도 볼 수 있다 (TRD: +suspended). */
    public SettlementSummaryResponse listMine(AuthUser user, String month) {
        if (user.role() != UserRole.TRAINEE && user.role() != UserRole.EXPERT) {
            throw new AppException(ErrorCode.FORBIDDEN_ROLE);
        }
        if (user.status() != UserStatus.SUSPENDED) {
            user.requireActive();
        }
        YearMonth ym = parseMonth(month);
        List<Settlement> rows = settlementRepository
                .findByProviderIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                        user.id(), start(ym), start(ym.plusMonths(1)));
        return new SettlementSummaryResponse(ym.toString(), rows.stream().map(SettlementResponse::from).toList(),
                totals(rows));
    }

    /** STL-02. */
    public List<SettlementResponse> search(AuthUser admin, String month, SettlementStatus status, SettlementKind kind) {
        admin.requireRole(UserRole.ADMIN);
        YearMonth ym = parseMonth(month);
        return settlementRepository.search(start(ym), start(ym.plusMonths(1)), status, kind).stream()
                .map(SettlementResponse::from)
                .toList();
    }

    /** STL-02 CSV. 엑셀에서 한글이 안 깨지게 BOM을 붙인다. */
    public String searchCsv(AuthUser admin, String month, SettlementStatus status, SettlementKind kind) {
        List<SettlementResponse> rows = search(admin, month, status, kind);
        StringBuilder csv = new StringBuilder("﻿id,provider_id,kind,session_id,supervision_id,gross,fee,net,status,"
                + "paid_at,created_at\n");
        for (SettlementResponse r : rows) {
            csv.append(String.join(",", Arrays.asList(
                    r.id().toString(), r.providerId().toString(), r.kind().getValue(),
                    str(r.sessionId()), str(r.supervisionId()),
                    String.valueOf(r.gross()), String.valueOf(r.fee()), String.valueOf(r.net()),
                    r.status().getValue(), str(r.paidAt()), str(r.createdAt())))).append('\n');
        }
        return csv.toString();
    }

    /**
     * STL-03. 고른 건이 전부 pending일 때만 한꺼번에 paid (하나라도 아니면 아무것도 안 바꾸고 409).
     * 받는 사람마다 알림 1건, 감사 로그 1건.
     */
    @Transactional
    public SettlementPayResponse pay(AuthUser admin, List<UUID> ids) {
        admin.requireRole(UserRole.ADMIN);
        Set<UUID> unique = new HashSet<>(ids);
        List<Settlement> rows = settlementRepository.findAllForUpdate(unique);
        if (rows.size() != unique.size()) {
            Set<UUID> found = rows.stream().map(Settlement::getId).collect(Collectors.toSet());
            throw new AppException(ErrorCode.NOT_FOUND, Map.of("missing",
                    unique.stream().filter(id -> !found.contains(id)).map(UUID::toString).toList()));
        }
        List<String> notPending = rows.stream().filter(s -> s.getStatus() != SettlementStatus.PENDING)
                .map(s -> s.getId().toString()).toList();
        if (!notPending.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_STATE, "지급 대기(pending) 상태가 아닌 정산이 있습니다",
                    Map.of("not_pending", notPending));
        }

        Instant now = Instant.now();
        rows.forEach(s -> s.markPaid(now));
        Map<UUID, Integer> netByProvider = rows.stream()
                .collect(Collectors.groupingBy(Settlement::getProviderId, Collectors.summingInt(Settlement::getNet)));
        netByProvider.forEach((providerId, net) -> notificationService.notify(providerId, "settlement_paid",
                "정산금이 지급되었어요", String.format("%,d원이 지급 처리되었습니다.", net),
                userService.getById(providerId).getRole() == UserRole.TRAINEE ? "/t/settlements" : "/e/settlements",
                Map.of("net", net)));
        rows.forEach(s -> auditLogService.record(admin.id(), "pay_settlement", "settlement", s.getId(), null));
        return new SettlementPayResponse(rows.size(), rows.stream().mapToInt(Settlement::getNet).sum());
    }

    private static Map<String, Integer> totals(List<Settlement> rows) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (SettlementStatus status : SettlementStatus.values()) {
            totals.put(status.getValue(), 0);
        }
        rows.forEach(s -> totals.merge(s.getStatus().getValue(), s.getNet(), Integer::sum));
        return totals;
    }

    // month가 없으면 이번 달 (KST)
    private static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now(KST);
        }
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new AppException(ErrorCode.VALIDATION_ERROR,
                    Map.of("fields", List.of(Map.of("field", "month", "reason", "format_yyyy_mm"))));
        }
    }

    private static Instant start(YearMonth ym) {
        return ym.atDay(1).atStartOfDay(KST).toInstant();
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
