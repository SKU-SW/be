# test-frontend

SKU_SW 백엔드 API를 빠르게 수동 검증하기 위한 **임시 React(Vite + TypeScript) 프론트**입니다.

## 1) 실행 방법

```bash
cd test-frontend
npm install
npm run dev
```

- 기본 실행 URL: `http://localhost:5173`
- Vite server는 `strictPort: true`로 설정되어 5173 포트 고정입니다.

## 2) 환경 변수

`.env.example`를 복사해 `.env`를 만들고 필요 시 수정하세요.

```env
VITE_API_BASE_URL=http://localhost:8080/api/v1
VITE_WS_BASE_URL=
```

- `VITE_API_BASE_URL` 기본값: `http://localhost:8080/api/v1`
- `VITE_WS_BASE_URL` 미설정 시 API URL에서 자동 파생
  - API가 `http://...`면 `ws://...`
  - API가 `https://...`면 `wss://...`

예)
- API: `http://localhost:8080/api/v1` → WS base: `ws://localhost:8080`
- API: `https://dev.example.com/api/v1` → WS base: `wss://dev.example.com`

## 3) 페이지/기능

### `/auth`
- 회원가입: `POST /auth/register/email`
- 로그인: `POST /auth/login/email`
- 로그인 성공 시 localStorage 저장
  - `accessToken`
  - `refreshToken`
  - `userInfo`
- 이후 `/characters`로 이동

### `/characters`
- settings 조회: `GET /characters/settings`
- 캐릭터 생성: `POST /characters`
- 캐릭터 목록 조회: `GET /characters?page=1&size=10`
- 캐릭터 선택 저장: `PATCH /characters/{characterId}` body `{isSelected:true}`
- 방송 시작: `POST /stream/start?characterId={id}`
  - 성공 시 `/chat?broadcastStreamId=...` 이동

### `/chat`
- WebSocket 연결 URL
  - `${WS_BASE}/stream/ws?broadcastStreamId=...&accessToken=...`
  - 브라우저 WebSocket은 커스텀 Authorization 헤더를 직접 넣기 어려워 쿼리 파라미터 방식 사용
- 채팅 송신 JSON
  - `{ "type": "CHAT", "message": "..." }`
- binary 수신
  - Blob URL 생성 후 audio 목록에 표시 및 재생 가능
- text 수신
  - JSON parse 시도 후 파싱 결과(또는 raw text) 로그 표시
- 방송 종료 버튼
  - `POST /stream/terminate`

## 4) API 응답 처리

공통 응답 형식 `{status, message, data}`를 기준으로 처리합니다.

인증이 필요한 요청에는 localStorage의 `accessToken`으로

`Authorization: Bearer <accessToken>`

헤더를 자동 주입합니다.

## 5) 권장 테스트 플로우

1. `/auth`에서 회원가입
2. 같은 계정으로 로그인
3. `/characters`에서 settings 확인
4. 캐릭터 생성
5. 리스트에서 캐릭터 선택 후 PATCH 저장
6. 방송 시작 버튼 클릭 → `/chat` 이동
7. `/chat`에서 WebSocket 연결
8. 채팅 메시지 송신, 서버 text/binary 수신 확인
9. `방송 종료 API 호출` 실행

## 6) ws/wss 주의사항

- 프론트가 HTTPS 환경이면 브라우저 보안 정책상 `wss://` 사용이 필요합니다.
- 혼합 콘텐츠(HTTPS 페이지 + ws://)는 차단될 수 있습니다.
- 개발 환경(localhost HTTP)에서는 일반적으로 `ws://` 사용 가능합니다.
