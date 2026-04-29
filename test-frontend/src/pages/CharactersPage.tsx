import { FormEvent, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  createCharacter,
  getCharacterSettings,
  listCharacters,
  selectCharacter,
  startBroadcast,
} from '../lib/api';
import type { Character, CharacterSettings } from '../types';

function normalizeCharacters(payload: unknown): Character[] {
  if (Array.isArray(payload)) return payload as Character[];
  if (payload && typeof payload === 'object') {
    const obj = payload as Record<string, unknown>;
    if (Array.isArray(obj.content)) return obj.content as Character[];
    if (Array.isArray(obj.items)) return obj.items as Character[];
  }
  return [];
}

export default function CharactersPage() {
  const navigate = useNavigate();
  const [settings, setSettings] = useState<CharacterSettings | null>(null);
  const [characters, setCharacters] = useState<Character[]>([]);
  const [selectedCharacterId, setSelectedCharacterId] = useState<number | null>(
    null,
  );
  const [status, setStatus] = useState('');
  const [loading, setLoading] = useState(false);

  const [form, setForm] = useState({
    characterName: '',
    triggerWords: '안녕,하이',
    gender: 'FEMALE',
    voiceTypeId: 1,
    characterImageId: 1,
    presetType: 'DEFAULT',
    speechStyle: '친근하고 밝음',
    personality: '상냥하고 재치 있음',
  });

  const selectedCharacter = useMemo(
    () => characters.find((c) => c.characterId === selectedCharacterId) ?? null,
    [characters, selectedCharacterId],
  );

  const loadSettings = async () => {
    try {
      const res = await getCharacterSettings();
      setSettings(res.data);
    } catch (error) {
      setStatus(`settings 조회 실패: ${(error as Error).message}`);
    }
  };

  const loadCharacters = async () => {
    try {
      const res = await listCharacters(1, 10);
      const parsed = normalizeCharacters(res.data);
      setCharacters(parsed);
      const selected = parsed.find((c) => c.isSelected);
      setSelectedCharacterId(selected?.characterId ?? null);
    } catch (error) {
      setStatus(`캐릭터 조회 실패: ${(error as Error).message}`);
    }
  };

  useEffect(() => {
    void loadSettings();
    void loadCharacters();
  }, []);

  const onCreateCharacter = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setStatus('');
    try {
      await createCharacter({
        characterName: form.characterName,
        triggerWords: form.triggerWords
          .split(',')
          .map((v) => v.trim())
          .filter(Boolean),
        gender: form.gender,
        voiceTypeId: Number(form.voiceTypeId),
        characterImageId: Number(form.characterImageId),
        characterPersona: {
          presetType: form.presetType,
          speechStyle: form.speechStyle,
          personality: form.personality,
        },
      });
      setStatus('캐릭터 생성 성공');
      await loadCharacters();
    } catch (error) {
      setStatus(`캐릭터 생성 실패: ${(error as Error).message}`);
    } finally {
      setLoading(false);
    }
  };

  const onSelectCharacter = async () => {
    if (!selectedCharacterId) {
      setStatus('선택한 캐릭터가 없습니다.');
      return;
    }
    setLoading(true);
    setStatus('');
    try {
      await selectCharacter(selectedCharacterId);
      setStatus('캐릭터 선택 완료');
      await loadCharacters();
    } catch (error) {
      setStatus(`캐릭터 선택 실패: ${(error as Error).message}`);
    } finally {
      setLoading(false);
    }
  };

  const onStartBroadcast = async () => {
    if (!selectedCharacterId) {
      setStatus('방송 시작 전 캐릭터를 선택하세요.');
      return;
    }
    setLoading(true);
    setStatus('');
    try {
      const res = await startBroadcast(selectedCharacterId);
      const streamId = res.data.broadcastStreamId;
      setStatus(`방송 시작됨: ${streamId}`);
      navigate(`/chat?broadcastStreamId=${encodeURIComponent(streamId)}`);
    } catch (error) {
      setStatus(`방송 시작 실패: ${(error as Error).message}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="stack">
      <section className="card">
        <h2>Character Settings (GET /characters/settings)</h2>
        <pre className="pre">{JSON.stringify(settings, null, 2)}</pre>
      </section>

      <section className="card">
        <h2>캐릭터 생성 (POST /characters)</h2>
        <form className="form grid-form" onSubmit={onCreateCharacter}>
          <label>
            이름
            <input
              value={form.characterName}
              onChange={(e) =>
                setForm((p) => ({ ...p, characterName: e.target.value }))
              }
              required
            />
          </label>
          <label>
            Trigger Words(,로 구분)
            <input
              value={form.triggerWords}
              onChange={(e) => setForm((p) => ({ ...p, triggerWords: e.target.value }))}
              required
            />
          </label>
          <label>
            Gender
            <input
              value={form.gender}
              onChange={(e) => setForm((p) => ({ ...p, gender: e.target.value }))}
              required
            />
          </label>
          <label>
            Voice Type ID
            <input
              type="number"
              value={form.voiceTypeId}
              onChange={(e) => setForm((p) => ({ ...p, voiceTypeId: Number(e.target.value) }))}
              required
            />
          </label>
          <label>
            Character Image ID
            <input
              type="number"
              value={form.characterImageId}
              onChange={(e) =>
                setForm((p) => ({ ...p, characterImageId: Number(e.target.value) }))
              }
              required
            />
          </label>
          <label>
            presetType
            <input
              value={form.presetType}
              onChange={(e) => setForm((p) => ({ ...p, presetType: e.target.value }))}
              required
            />
          </label>
          <label>
            speechStyle
            <input
              value={form.speechStyle}
              onChange={(e) => setForm((p) => ({ ...p, speechStyle: e.target.value }))}
              required
            />
          </label>
          <label>
            personality
            <input
              value={form.personality}
              onChange={(e) => setForm((p) => ({ ...p, personality: e.target.value }))}
              required
            />
          </label>
          <button type="submit" disabled={loading}>
            캐릭터 생성
          </button>
        </form>
      </section>

      <section className="card">
        <h2>캐릭터 리스트 (GET /characters?page=1&size=10)</h2>
        <div className="inline-buttons">
          <button onClick={() => void loadCharacters()} disabled={loading}>
            새로고침
          </button>
          <button onClick={onSelectCharacter} disabled={loading || !selectedCharacterId}>
            선택 캐릭터 저장 (PATCH)
          </button>
          <button onClick={onStartBroadcast} disabled={loading || !selectedCharacterId}>
            선택 캐릭터로 방송 시작
          </button>
        </div>

        {characters.length === 0 ? (
          <p>캐릭터가 없습니다.</p>
        ) : (
          <ul className="list">
            {characters.map((character) => (
              <li key={character.characterId} className="list-item">
                <label className="radio-label">
                  <input
                    type="radio"
                    name="selectedCharacter"
                    checked={selectedCharacterId === character.characterId}
                    onChange={() => setSelectedCharacterId(character.characterId)}
                  />
                  <span>
                    #{character.characterId} {character.characterName}{' '}
                    {character.isSelected ? '(server-selected)' : ''}
                  </span>
                </label>
              </li>
            ))}
          </ul>
        )}

        <p>
          현재 선택: {selectedCharacter ? `${selectedCharacter.characterName}` : '없음'}
        </p>
      </section>

      {status && <p className="status">{status}</p>}
    </div>
  );
}
