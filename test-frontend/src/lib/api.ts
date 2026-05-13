import { API_BASE_URL } from './config';
import { getAccessToken } from './storage';
import type {
  ApiResponse,
  BroadcastStartData,
  Character,
  CharacterListData,
  CharacterSettings,
  LoginData,
} from '../types';

type HttpMethod = 'GET' | 'POST' | 'PATCH';

async function request<T>(
  method: HttpMethod,
  path: string,
  body?: unknown,
): Promise<ApiResponse<T>> {
  const token = getAccessToken();
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });

  const json = (await response.json()) as ApiResponse<T>;
  if (!response.ok) {
    throw new Error(json.message || `HTTP ${response.status}`);
  }
  return json;
}

export async function registerEmail(input: {
  name: string;
  email: string;
  password: string;
  passwordConfirm: string;
}) {
  return request<unknown>('POST', '/auth/register/email', input);
}

export async function loginEmail(input: { email: string; password: string }) {
  return request<LoginData>('POST', '/auth/login/email', input);
}

export async function getCharacterSettings() {
  return request<CharacterSettings>('GET', '/characters/settings');
}

export async function createCharacter(input: {
  characterName: string;
  triggerWords: string[];
  gender: string;
  voiceTypeId: number;
  characterImageId: number;
  characterPersona: {
    presetType: string;
    speechStyle: string;
    personality: string;
  };
}) {
  return request<Character>('POST', '/characters', input);
}

export async function listCharacters(page = 1, size = 10) {
  return request<CharacterListData>('GET', `/characters?page=${page}&size=${size}`);
}

export async function selectCharacter(characterId: number) {
  return request<Character>(
    'PATCH',
    `/characters/${characterId}`,
    { isSelected: true },
  );
}

export async function startBroadcast(characterId: number) {
  return request<BroadcastStartData>('POST', `/stream/start?characterId=${characterId}`);
}

export async function terminateBroadcast() {
  return request<unknown>('POST', '/stream/terminate');
}
