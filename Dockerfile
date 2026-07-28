# ---- Runtime stage (맥북 빌드본을 활용하여 가볍고 빠르게 가동) ----
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# 기본값을 none으로 유지하여 평소에는 에이전트 없이 가볍게 가동
ARG SCOUTER_AGENT_VERSION=none

# 💡 [핵심 변경] 맥북 build/libs 폴더에 생성된 jar 파일을 컨테이너 내부로 직접 복사
COPY build/libs/*.jar /app/app.jar

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates; \
    rm -rf /var/lib/apt/lists/*; \
    mkdir -p /opt/scouter/agent.java; \
    if [ "$SCOUTER_AGENT_VERSION" != "none" ]; then \
      apt-get update; \
      # 필수 패키지 충돌을 막기 위해 tar만 추가로 설치합니다.
      apt-get install -y --no-install-recommends tar; \
      \
      # 주입된 Scouter 버전 패키지 다운로드 및 압축 해제
      curl -fsSL "https://github.com/scouter-project/scouter/releases/download/v${SCOUTER_AGENT_VERSION}/scouter-all-${SCOUTER_AGENT_VERSION}.tar.gz" -o /tmp/scouter.tar.gz; \
      mkdir -p /tmp/scouter_extracted; \
      tar -xzf /tmp/scouter.tar.gz -C /tmp/scouter_extracted; \
      \
      # 우분투 기본 구조 경로를 직접 타겟팅하여 에이전트 jar 파일만 복사
      cp /tmp/scouter_extracted/scouter/agent.java/scouter.agent.jar /opt/scouter/agent.java/scouter.agent.jar; \
      test -f /opt/scouter/agent.java/scouter.agent.jar; \
      \
      # 레이어 경량화를 위한 잔여 파일 청소 (curl만 안전하게 삭제)
      rm -rf /tmp/scouter.tar.gz /tmp/scouter_extracted; \
      rm -rf /var/lib/apt/lists/*; \
    fi

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
