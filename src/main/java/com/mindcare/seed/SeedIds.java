package com.mindcare.seed;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 시드 행 id. 엔티티와 같은 UUIDv7이고, 시각을 그 행의 생성 시각으로 맞춰서
 * id 기준 최신순 목록(keyset)에서도 시간 순서가 맞게 나온다.
 */
final class SeedIds {

    private SeedIds() {
    }

    static UUID at(Instant time) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long msb = (time.toEpochMilli() << 16) | 0x7000L | (random.nextLong() & 0x0FFFL);
        long lsb = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
