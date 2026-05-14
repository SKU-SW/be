import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { terminateBroadcast } from '../lib/api';
import {
  createAudioBufferFromPcm16,
  createPcmAudioContext,
  resumeAudioContext,
  scheduleAudioBuffer,
  toArrayBuffer,
} from '../lib/audio';
import { getWebSocketBaseUrl } from '../lib/config';
import { getAccessToken } from '../lib/storage';
import type {
  BroadcastVoiceMetadata,
  BroadcastWebSocketErrorMessage,
  BroadcastWebSocketStatusMessage,
} from '../types';

type ConnectionState = 'IDLE' | 'CONNECTING' | 'OPEN' | 'CLOSED' | 'ERROR';

interface TextMessageLog {
  raw: string;
  parsed?: unknown;
  createdAt: string;
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null;

const isBroadcastVoiceMetadata = (value: unknown): value is BroadcastVoiceMetadata =>
  isRecord(value) &&
  (value.eventType === 'VOICE_CHUNK' || value.eventType === 'VOICE_TURN_COMPLETE') &&
  typeof value.turnNumber === 'number';

const isBroadcastWebSocketStatusMessage = (value: unknown): value is BroadcastWebSocketStatusMessage =>
  isRecord(value) && typeof value.status === 'string' && typeof value.message === 'string';

const isBroadcastWebSocketErrorMessage = (value: unknown): value is BroadcastWebSocketErrorMessage =>
  isRecord(value) && typeof value.error === 'string' && typeof value.message === 'string';

export default function ChatPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const wsRef = useRef<WebSocket | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const nextPlaybackTimeRef = useRef(0);
  const activeSourcesRef = useRef<AudioBufferSourceNode[]>([]);

  const params = useMemo(() => new URLSearchParams(location.search), [location.search]);
  const broadcastStreamId = params.get('broadcastStreamId') || '';
  const accessToken = getAccessToken() || '';

  const [connectionState, setConnectionState] = useState<ConnectionState>('IDLE');
  const [status, setStatus] = useState('');
  const [chatInput, setChatInput] = useState('');
  const [textMessages, setTextMessages] = useState<TextMessageLog[]>([]);

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

      activeSourcesRef.current.forEach((source) => {
        try {
          source.stop();
        } catch {
          // no-op
        }
      });
      activeSourcesRef.current = [];
      nextPlaybackTimeRef.current = 0;

      if (audioContextRef.current) {
        void audioContextRef.current.close();
        audioContextRef.current = null;
      }
    };
  }, []);

  const ensureAudioContext = async () => {
    if (!audioContextRef.current || audioContextRef.current.state === 'closed') {
      audioContextRef.current = createPcmAudioContext();
      nextPlaybackTimeRef.current = 0;
    }

    await resumeAudioContext(audioContextRef.current);
    return audioContextRef.current;
  };

  const resetAudioPlayback = async () => {
    activeSourcesRef.current.forEach((source) => {
      try {
        source.stop();
      } catch {
        // no-op
      }
    });
    activeSourcesRef.current = [];
    nextPlaybackTimeRef.current = 0;

    if (audioContextRef.current) {
      await audioContextRef.current.close();
      audioContextRef.current = null;
    }
  };

  const playPcmAudioChunk = async (binaryData: Blob | ArrayBuffer) => {
    const audioContext = await ensureAudioContext();
    const arrayBuffer = await toArrayBuffer(binaryData);
    const { audioBuffer } = createAudioBufferFromPcm16(audioContext, arrayBuffer);
    const { source, nextPlaybackTime } = scheduleAudioBuffer(
      audioContext,
      audioBuffer,
      nextPlaybackTimeRef.current,
    );

    nextPlaybackTimeRef.current = nextPlaybackTime;
    activeSourcesRef.current.push(source);
    source.onended = () => {
      activeSourcesRef.current = activeSourcesRef.current.filter((current) => current !== source);
    };
  };

  const connect = async () => {
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

    try {
      await ensureAudioContext();
    } catch (error) {
      setConnectionState('ERROR');
      setStatus(`AudioContext 생성 실패: ${(error as Error).message}`);
      return;
    }

    const ws = new WebSocket(wsUrl);
    ws.binaryType = 'arraybuffer';

    ws.onopen = () => {
      setConnectionState('OPEN');
      setStatus('WebSocket 연결 성공 (PCM 24kHz 재생 준비 완료)');
    };

    ws.onclose = () => {
      setConnectionState('CLOSED');
      setStatus('WebSocket 연결 종료');
      void resetAudioPlayback();
    };

    ws.onerror = () => {
      setConnectionState('ERROR');
      setStatus('WebSocket 에러 발생');
    };

    ws.onmessage = async (event) => {
      if (event.data instanceof Blob || event.data instanceof ArrayBuffer) {
        try {
          await playPcmAudioChunk(event.data);
          setStatus('PCM 오디오 청크 수신 및 재생 예약 완료');
        } catch (error) {
          setStatus(`PCM 오디오 재생 실패: ${(error as Error).message}`);
        }
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

      if (isBroadcastVoiceMetadata(parsed)) {
        setStatus(
          parsed.eventType === 'VOICE_TURN_COMPLETE'
            ? `턴 ${parsed.turnNumber} 완료 메타데이터 수신`
            : `턴 ${parsed.turnNumber} 음성 청크 메타데이터 수신`,
        );
        return;
      }

      if (isBroadcastWebSocketStatusMessage(parsed)) {
        setStatus(`${parsed.status}: ${parsed.message}`);
        return;
      }

      if (isBroadcastWebSocketErrorMessage(parsed)) {
        setStatus(`${parsed.error}: ${parsed.message}`);
        return;
      }

      setStatus(raw);
    };

    wsRef.current = ws;
  };

  const disconnect = () => {
    wsRef.current?.close();
    wsRef.current = null;
    void resetAudioPlayback();
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
        <h3>수신 텍스트/메타데이터 로그</h3>
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
