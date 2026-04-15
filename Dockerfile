# 1. 실행 환경 설정 (경량화된 JRE 사용)
FROM eclipse-temurin:17-jre-jammy

# 2. 작업 디렉토리 생성
WORKDIR /app

# 3. 로컬에서 빌드된 JAR 파일을 컨테이너 안으로 복사
# (./gradlew bootJar 실행 후 생성된 파일을 가져옵니다)
COPY build/libs/*.jar app.jar

# 4. 포트 노출
EXPOSE 8080

# 5. 앱 실행
ENTRYPOINT ["java", "-jar", "app.jar"]