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
  (value.eventType === 'VOICE_CHUNK' ||
    value.eventType === 'VOICE_EMOTION' ||
    value.eventType === 'VOICE_TURN_COMPLETE' ||
    value.eventType === 'VOICE_INTERRUPTED') &&
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
  const currentIncomingTurnRef = useRef<number | null>(null);
  const ignoredTurnNumbersRef = useRef<Set<number>>(new Set());
  const interruptPendingTurnRef = useRef<number | null>(null);
  const pendingStreamerMessageRef = useRef('');
  const autoSendPendingMessageRef = useRef(true);

  const params = useMemo(() => new URLSearchParams(location.search), [location.search]);
  const broadcastStreamId = params.get('broadcastStreamId') || '';
  const accessToken = getAccessToken() || '';

  const [connectionState, setConnectionState] = useState<ConnectionState>('IDLE');
  const [status, setStatus] = useState('');
  const [chatInput, setChatInput] = useState('');
  const [textMessages, setTextMessages] = useState<TextMessageLog[]>([]);
  const [currentTurnNumber, setCurrentTurnNumber] = useState<number | null>(null);
  const [ignoredTurnNumbers, setIgnoredTurnNumbers] = useState<number[]>([]);
  const [interruptPendingTurn, setInterruptPendingTurn] = useState<number | null>(null);
  const [lastInterruptedMetadata, setLastInterruptedMetadata] = useState<BroadcastVoiceMetadata | null>(null);
  const [pendingStreamerMessage, setPendingStreamerMessage] = useState('');
  const [autoSendPendingMessage, setAutoSendPendingMessage] = useState(true);

  useEffect(() => {
    interruptPendingTurnRef.current = interruptPendingTurn;
  }, [interruptPendingTurn]);

  useEffect(() => {
    pendingStreamerMessageRef.current = pendingStreamerMessage;
  }, [pendingStreamerMessage]);

  useEffect(() => {
    autoSendPendingMessageRef.current = autoSendPendingMessage;
  }, [autoSendPendingMessage]);

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

  const addIgnoredTurnNumber = (turnNumber: number) => {
    ignoredTurnNumbersRef.current.add(turnNumber);
    setIgnoredTurnNumbers(Array.from(ignoredTurnNumbersRef.current).sort((a, b) => a - b));
  };

  const sendStreamerMessage = (message: string) => {
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      setStatus('WebSocket OPEN 상태에서만 스트리머 발화를 송신할 수 있습니다.');
      return false;
    }

    const trimmedMessage = message.trim();
    if (!trimmedMessage) {
      setStatus('송신할 스트리머 발화가 없습니다.');
      return false;
    }

    ws.send(JSON.stringify({ message: trimmedMessage }));
    setStatus('대기 중인 스트리머 발화 송신 완료');
    return true;
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
        const estimatedTurnNumber = currentIncomingTurnRef.current;
        if (estimatedTurnNumber !== null && ignoredTurnNumbersRef.current.has(estimatedTurnNumber)) {
          setStatus(`턴 ${estimatedTurnNumber} 오디오 청크 무시 (interrupt 요청됨)`);
          return;
        }

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
        currentIncomingTurnRef.current = parsed.turnNumber;
        setCurrentTurnNumber(parsed.turnNumber);

        if (ignoredTurnNumbersRef.current.has(parsed.turnNumber) && parsed.eventType !== 'VOICE_INTERRUPTED') {
          setStatus(`턴 ${parsed.turnNumber} ${parsed.eventType} 메타데이터 무시`);
          return;
        }

        if (parsed.eventType === 'VOICE_INTERRUPTED') {
          setLastInterruptedMetadata(parsed);
          if (interruptPendingTurnRef.current === parsed.turnNumber) {
            setInterruptPendingTurn(null);
          }

          if (autoSendPendingMessageRef.current && pendingStreamerMessageRef.current.trim()) {
            if (sendStreamerMessage(pendingStreamerMessageRef.current)) {
              setPendingStreamerMessage('');
            }
          }

          setStatus(`턴 ${parsed.turnNumber} 응답 중단 완료 이벤트 수신`);
          return;
        }

        const eventLabel: Record<BroadcastVoiceMetadata['eventType'], string> = {
          VOICE_CHUNK: '음성 청크',
          VOICE_EMOTION: '감정',
          VOICE_TURN_COMPLETE: '완료',
          VOICE_INTERRUPTED: '중단 완료',
        };
        setStatus(`턴 ${parsed.turnNumber} ${eventLabel[parsed.eventType]} 메타데이터 수신`);
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
        message: chatInput,
      }),
    );
    setChatInput('');
    setStatus('채팅 송신 완료');
  };

  const onRequestInterrupt = async () => {
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      setStatus('WebSocket OPEN 상태에서만 interrupt 요청을 보낼 수 있습니다.');
      return;
    }

    const targetTurnNumber = currentIncomingTurnRef.current ?? currentTurnNumber;
    if (targetTurnNumber === null) {
      setStatus('중단할 turnNumber가 없습니다. 먼저 AI 응답 메타데이터를 수신해야 합니다.');
      return;
    }

    await resetAudioPlayback();
    addIgnoredTurnNumber(targetTurnNumber);
    setInterruptPendingTurn(targetTurnNumber);

    ws.send(
      JSON.stringify({
        message: `__AI_RESPONSE_INTERRUPT_REQUEST__:${JSON.stringify({
          turnNumber: targetTurnNumber,
          reason: 'STREAMER_SPEECH_START',
        })}`,
      }),
    );
    setStatus(`턴 ${targetTurnNumber} AI 응답 중단 요청 송신 완료`);
  };

  const onSendPendingStreamerMessage = () => {
    if (sendStreamerMessage(pendingStreamerMessage)) {
      setPendingStreamerMessage('');
    }
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

      <section className="card interrupt-card">
        <h3>AI 응답 Interrupt 테스트</h3>
        <p className="hint">
          스트리머 발화 시작 상황을 시뮬레이션합니다. 현재 구현은 binary audio에 turnNumber가 없어,
          최근 수신한 메타데이터의 turnNumber를 기준으로 이후 binary 청크를 무시합니다.
        </p>
        <div className="inline-buttons">
          <button
            type="button"
            onClick={() => void onRequestInterrupt()}
            disabled={connectionState !== 'OPEN' || currentTurnNumber === null || interruptPendingTurn !== null}
          >
            스트리머 발화 시작 / AI 응답 중단 요청
          </button>
          <button type="button" onClick={() => void resetAudioPlayback()}>
            로컬 오디오 즉시 중단
          </button>
        </div>

        <div className="debug-grid">
          <div>
            <strong>현재/마지막 turnNumber</strong>
            <p>{currentTurnNumber ?? '(없음)'}</p>
          </div>
          <div>
            <strong>interrupt pending</strong>
            <p>{interruptPendingTurn === null ? '없음' : `턴 ${interruptPendingTurn}`}</p>
          </div>
          <div>
            <strong>무시 중인 turnNumbers</strong>
            <p>{ignoredTurnNumbers.length ? ignoredTurnNumbers.join(', ') : '(없음)'}</p>
          </div>
        </div>

        <label>
          interrupt 완료 전 대기시킬 스트리머 발화
          <textarea
            rows={3}
            placeholder="interrupt 완료 후 송신할 발화를 입력하세요"
            value={pendingStreamerMessage}
            onChange={(e) => setPendingStreamerMessage(e.target.value)}
          />
        </label>
        <label className="checkbox-label">
          <input
            type="checkbox"
            checked={autoSendPendingMessage}
            onChange={(e) => setAutoSendPendingMessage(e.target.checked)}
          />
          VOICE_INTERRUPTED 수신 시 대기 발화 자동 송신
        </label>
        <div className="inline-buttons">
          <button type="button" onClick={() => setStatus('대기 발화 저장 완료')}>
            대기 발화 저장
          </button>
          <button type="button" onClick={onSendPendingStreamerMessage} disabled={!pendingStreamerMessage.trim()}>
            대기 발화 즉시 송신
          </button>
        </div>

        <h4>마지막 VOICE_INTERRUPTED</h4>
        <pre className="pre">
          {lastInterruptedMetadata ? JSON.stringify(lastInterruptedMetadata, null, 2) : '아직 수신되지 않음'}
        </pre>
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
