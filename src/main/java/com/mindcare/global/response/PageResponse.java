package com.mindcare.global.response;

import java.util.List;

/**
 * TRD 12-2 목록 응답. 다음 페이지가 없으면 nextCursor = null.
 * 단건 응답은 감싸지 않고 DTO를 그대로 돌려준다.
 * <pre>
 * { "items": [ ... ], "next_cursor": "eyJ0Ijoi..." }
 * </pre>
 */
public record PageResponse<T>(List<T> items, String nextCursor) {

    public static <T> PageResponse<T> of(List<T> items, String nextCursor) {
        return new PageResponse<>(items, nextCursor);
    }

    public static <T> PageResponse<T> last(List<T> items) {
        return new PageResponse<>(items, null);
    }
}
