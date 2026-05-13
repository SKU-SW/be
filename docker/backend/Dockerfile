# 1. FROM: 생성할 이미지의 베이스가 될 이미지를 결정하는 명령어
# 현재 서버 플랫폼이 linux/arm64 기반이기 때문에 platform을 지정해준다.
# Ubuntu 22.04(Jammy) 기반의 ARM64 Java 21 JRE 이미지 사용
FROM --platform=linux/arm64 eclipse-temurin:21-jre-jammy

# 2. LABEL: 이미지의 메타데이터를 설정하는 명령어
# 프로젝트명, 설명 등의 정보들을 기록한다.
LABEL project="SKU SW 인재양성 Spring Boot" \
      description="AI 동료 스트리머 플랫폼을 위한 Spring Boot API Server"

# 3. WORKDIR: 명령어를 실행할 디렉터리를 지정하는 명령어
# 컨테이너 내부의 작업 디렉터리를 설정한다.
WORKDIR /app

# 4. EXPOSE: Dockerfile의 빌드로 생성된 이미지에서 노출할 포트를 지정하는 명령어
# 하지만 EXPOSE를 설정한 이미지를 기반으로 컨테이너를 생성한다고 해서 해당 포트가 HOST의 포트와 자동으로 바인딩되지는 않는다
# 단지 해당 이미지를 사용하는 사용자에게 해당 포트를 사용한다고 문서화하는 목적을 가지는 것 뿐이다.
EXPOSE 8080

# 5. COPY: 특정 디렉터리 경로의 모든 파일과 디렉터리를 해당 디렉터리에 모두 복사하는 명령어
# Gradle을 사용하는 경우의 JAR_FILE: build/libs/*.jar
# Host에 있는 jar 파일을 container 내부의 app.jar에 복사한다
ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} app.jar

# 6. ENTRYPOINT: 도커 컨테이너가 실행될 때 고정적으로 실행되는 스크립트 or 명령어
# 생략이 가능하며, 생략할 경우 CMD에 지정된 default 명령어가 수행된다.
ENTRYPOINT ["java", "-jar", "app.jar"]