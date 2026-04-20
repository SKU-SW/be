# 1. FROM: 생성할 이미지의 베이스 이미지 지정
# linux/arm64 환경임을 지정
FROM  --platform=linux/arm64 mysql:8.0

# 2. LABEL: 이미지 메타데이터 설정
LABEL description="Spring Boot API 연결 MySQL DB"

# 3. COPY: 특정 디렉터리 경로 모든 파일, 디렉터리를 대상 디렉터리에 복사하는 명령어
COPY sku_sw.cnf /etc/mysql/conf.d/sku_sw.cnf

# 4. EXPOSE: 노출할 포트 문서로 지정
EXPOSE 3306