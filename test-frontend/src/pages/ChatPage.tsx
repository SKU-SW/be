import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { terminateBroadcast } from '../lib/api';
import { getWebSocketBaseUrl } from '../lib/config';
import { getAccessToken } from '../lib/storage';

type ConnectionState = 'IDLE' | 'CONNECTING' | 'OPEN' | 'CLOSED' | 'ERROR';

interface AudioMessage {
  url: string;
  createdAt: string;
}

interface TextMessage {
  raw: string;
  parsed?: unknown;
  createdAt: string;
}

export default function ChatPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const wsRef = useRef<WebSocket | null>(null);

  const params = useMemo(() => new URLSearchParams(location.search), [location.search]);
  const broadcastStreamId = params.get('broadcastStreamId') || '';
  const accessToken = getAccessToken() || '';

  const [connectionState, setConnectionState] = useState<ConnectionState>('IDLE');
  const [status, setStatus] = useState('');
  const [chatInput, setChatInput] = useState('');
  const [audioMessages, setAudioMessages] = useState<AudioMessage[]>([]);
  const [textMessages, setTextMessages] = useState<TextMessage[]>([]);

  const wsUrl = useMemo(() => {
    if (!broadcastStreamId || !accessToken) return '';
    const base = getWebSocketBaseUrl();
    const query = new URLSearchParams({
      broadcastStreamId,
      accessToken,
    });
    return `${base}/api/v1/stream/ws?${query.toString()}`;
  }, [broadcastStreamId, accessToken]);

  useEffect(() => {
    return () => {
      wsRef.current?.close();
      wsRef.current = null;
      setAudioMessages((prev) => {
        prev.forEach((m) => URL.revokeObjectURL(m.url));
        return [];
      });
    };
  }, []);

  const connect = () => {
    if (!broadcastStreamId) {
      setStatus('broadcastStreamId 쿼리 파라미터가 필요합니다.');
      return;
    }
    if (!accessToken) {
      setStatus('accessToken 이 없습니다. /auth 에서 로그인 후 시도하세요.');
      return;
    }
    if (!wsUrl) {
      setStatus('WebSocket URL 생성 실패');
      return;
    }

    wsRef.current?.close();
    setConnectionState('CONNECTING');
    setStatus('WebSocket 연결 중...');

    const ws = new WebSocket(wsUrl);
    ws.binaryType = 'blob';

    ws.onopen = () => {
      setConnectionState('OPEN');
      setStatus('WebSocket 연결 성공');
    };

    ws.onclose = () => {
      setConnectionState('CLOSED');
      setStatus('WebSocket 연결 종료');
    };

    ws.onerror = () => {
      setConnectionState('ERROR');
      setStatus('WebSocket 에러 발생');
    };

    ws.onmessage = async (event) => {
      if (event.data instanceof Blob) {
        const blobUrl = URL.createObjectURL(event.data);
        setAudioMessages((prev) => [
          {
            url: blobUrl,
            createdAt: new Date().toISOString(),
          },
          ...prev,
        ]);
        return;
      }

      const raw = typeof event.data === 'string' ? event.data : String(event.data);
      let parsed: unknown;
      try {
        parsed = JSON.parse(raw);
      } catch {
        parsed = undefined;
      }
      setTextMessages((prev) => [
        {
          raw,
          parsed,
          createdAt: new Date().toISOString(),
        },
        ...prev,
      ]);
    };

    wsRef.current = ws;
  };

  const disconnect = () => {
    wsRef.current?.close();
    wsRef.current = null;
  };

  const onSendChat = (e: FormEvent) => {
    e.preventDefault();
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      setStatus('WebSocket OPEN 상태에서만 송신 가능합니다.');
      return;
    }
    if (!chatInput.trim()) {
      setStatus('채팅 내용을 입력하세요.');
      return;
    }
    ws.send(
      JSON.stringify({
        type: 'CHAT',
        message: chatInput,
      }),
    );
    setChatInput('');
    setStatus('채팅 송신 완료');
  };

  const onTerminateBroadcast = async () => {
    try {
      await terminateBroadcast();
      disconnect();
      setStatus('방송 종료 성공');
    } catch (error) {
      setStatus(`방송 종료 실패: ${(error as Error).message}`);
    }
  };

  return (
    <div className="stack">
      <section className="card">
        <h2>채팅/스트림 테스트</h2>
        <p>
          broadcastStreamId: <strong>{broadcastStreamId || '(없음)'}</strong>
        </p>
        <p>
          WS URL: <code>{wsUrl || '(생성 불가)'}</code>
        </p>
        <p>상태: {connectionState}</p>
        <div className="inline-buttons">
          <button onClick={connect}>WebSocket 연결</button>
          <button onClick={disconnect}>WebSocket 종료</button>
          <button onClick={() => navigate('/characters')}>캐릭터 페이지로</button>
          <button onClick={() => void onTerminateBroadcast()}>방송 종료 API 호출</button>
        </div>
      </section>

      <section className="card">
        <h3>채팅 송신</h3>
        <form onSubmit={onSendChat} className="form inline-form">
          <input
            placeholder="메시지를 입력하세요"
            value={chatInput}
            onChange={(e) => setChatInput(e.target.value)}
          />
          <button type="submit">송신</button>
        </form>
      </section>

      <section className="card">
        <h3>수신 음성 Blob 목록</h3>
        {audioMessages.length === 0 ? (
          <p>수신된 음성 없음</p>
        ) : (
          <ul className="list">
            {audioMessages.map((audio, idx) => (
              <li key={`${audio.createdAt}-${idx}`} className="list-item">
                <div>
                  <small>{audio.createdAt}</small>
                </div>
                <audio controls src={audio.url} />
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card">
        <h3>수신 텍스트(metadata) 로그</h3>
        {textMessages.length === 0 ? (
          <p>수신된 텍스트 없음</p>
        ) : (
          <ul className="list">
            {textMessages.map((msg, idx) => (
              <li key={`${msg.createdAt}-${idx}`} className="list-item">
                <small>{msg.createdAt}</small>
                <pre className="pre">{msg.parsed ? JSON.stringify(msg.parsed, null, 2) : msg.raw}</pre>
              </li>
            ))}
          </ul>
        )}
      </section>

      {status && <p className="status">{status}</p>}
    </div>
  );
}
