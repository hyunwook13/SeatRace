-- 1. 기존 잘못된 유니크 제약 제거
ALTER TABLE reservation_seats
DROP CONSTRAINT IF EXISTS uq_reservation_seats_event_seat;

-- 2. active 컬럼 default 제거
ALTER TABLE reservation_seats
    ALTER COLUMN active DROP DEFAULT;

-- 3. active 컬럼 null 값 정리 (기존 데이터 보호용)
UPDATE reservation_seats
SET active = false
WHERE active IS NULL;

-- 4. active 컬럼 NOT NULL 보장
ALTER TABLE reservation_seats
    ALTER COLUMN active SET NOT NULL;

-- 5. active=true 인 경우만 같은 event_seat_id 중복 금지
CREATE UNIQUE INDEX IF NOT EXISTS uq_reservation_seats_event_seat_active
    ON reservation_seats (event_seat_id)
    WHERE active = true;