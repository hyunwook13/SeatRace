# SeatRace

SeatRace는 공연/이벤트 좌석 예약을 위한 백엔드 서버입니다.  
이벤트/좌석 조회, 좌석 홀드(HOLD), 관리자용 공연장/이벤트 관리 API를 제공합니다.

## 1) 기술 스택

- Java 17
- Spring Boot 4.0.3
- Spring Web / Spring Security / Spring Data JPA / Validation
- PostgreSQL, Redis
- Flyway (DB 마이그레이션)
- JWT (Nimbus JOSE + JWT)
- Springdoc OpenAPI (Swagger UI)
- Spring Boot Actuator + Prometheus

## 2) 주요 기능

- 회원가입: `POST /api/auth/signup`
- 로그인(JWT 발급): `POST /login` (form login)
- 이벤트 목록 조회: `GET /api/events`
- 이벤트 좌석 조회: `GET /api/events/{eventId}/seats`
- 좌석 홀드: `POST /api/events/{eventId}/holds`
- 관리자 API:
  - 공연장 생성: `POST /api/admin/venues`
  - 좌석 일괄 생성: `POST /api/admin/venues/{venueId}/seats/generate`
  - 이벤트 생성: `POST /api/admin/events`

## 3) 실행 전 준비

- JDK 17
- Docker / Docker Compose

## 4) 환경 변수 설정

`.env.template`을 복사해 `.env`를 생성합니다.

```bash
cp .env.template .env
```

`.env` 예시:

```env
# Database
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/seatrace
DB_NAME=seatrace
DB_PW=seatrace

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# JWT
JWT_SECRET=change-this-to-a-secure-random-secret
```

## 5) 실행 방법

### A. Docker Compose로 전체 실행

```bash
docker compose up --build
```

앱은 `http://localhost:8080` 에서 실행됩니다.

### B. 인프라만 Docker로 띄우고 앱은 로컬 실행

1) PostgreSQL/Redis 실행

```bash
docker compose up -d postgres redis
```

2) 애플리케이션 실행

```bash
./gradlew bootRun
```

## 6) 테스트 실행

```bash
./gradlew test
```

## 7) 인증 방식

### 회원가입

```bash
curl -X POST http://localhost:8080/api/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"user1@example.com","name":"user1","password":"1234"}'
```

### 로그인 (JWT 발급)

`/login`은 form login 방식입니다.

```bash
curl -X POST http://localhost:8080/login \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=admin&password=1234"
```

응답의 `accessToken`을 이후 요청에서 `Authorization: Bearer <token>`으로 사용합니다.

## 8) 기본 시드 계정

애플리케이션 시작 시 아래 계정이 없으면 자동 생성됩니다.

- USER: `user / 1234`
- ADMIN: `admin / 1234`

## 9) API 문서 및 모니터링

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Health 체크: `http://localhost:8080/health`
- Actuator Health: `http://localhost:8080/actuator/health`
- Prometheus 메트릭: `http://localhost:8080/actuator/prometheus`

## 10) 프로젝트 구조

```text
src/main/java/org/example/seatrace
├── config        # 보안, OpenAPI, 앱 설정
├── controller    # REST API 엔드포인트
├── dto           # 요청/응답 DTO
├── entity        # JPA 엔티티
├── repository    # 데이터 접근 계층
├── security      # 인증/인가(JWT 필터, 사용자 principal)
└── service       # 비즈니스 로직
```

`src/main/resources/db/migration` 아래 Flyway SQL이 순서대로 적용됩니다.

## 11) 운영/개발 참고

- 좌석 홀드 TTL 기본값은 `reservation.hold.ttl-seconds=10` 입니다.
- 홀드 정리 스케줄러는 `reservation.hold.cleanup-delay-ms=3000` 주기로 동작합니다.
- `docker-compose.yml`의 `app.environment`에 `DB_URL` 키가 있으나, 애플리케이션은 `SPRING_DATASOURCE_URL`을 사용합니다.
  - 컨테이너에서 DB URL을 명시하려면 `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/seatrace`를 사용하세요.
