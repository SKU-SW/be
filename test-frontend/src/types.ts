export interface ApiResponse<T> {
  status: string;
  message: string;
  data: T;
}

export interface UserInfo {
  userId: number;
  email: string;
  name: string;
}

export interface LoginData extends UserInfo {
  accessToken: string;
  refreshToken: string;
}

export interface CharacterSettings {
  voiceTypes?: Array<{ id: number; name: string }>;
  characterImages?: Array<{ id: number; name: string }>;
  [key: string]: unknown;
}

export interface CharacterPersona {
  presetType: string;
  speechStyle: string;
  personality: string;
}

export interface Character {
  characterId: number;
  characterName: string;
  triggerWords: string[];
  gender: string;
  voiceTypeId: number;
  characterImageId: number;
  isSelected?: boolean;
  characterPersona: CharacterPersona;
  [key: string]: unknown;
}

export interface CharacterListData {
  content?: Character[];
  items?: Character[];
  [key: string]: unknown;
}

export interface BroadcastStartData {
  broadcastStreamId: string;
  broadcastStartedAt: string;
}

export type BroadcastVoiceEventType = 'VOICE_CHUNK' | 'VOICE_TURN_COMPLETE';

export interface BroadcastVoiceMetadata {
  eventType: BroadcastVoiceEventType;
  turnNumber: number;
  characterId: number | null;
  voiceText: string | null;
  broadcastDialogueCursorId: number | null;
}

export interface BroadcastWebSocketStatusMessage {
  status: string;
  message: string;
}

export interface BroadcastWebSocketErrorMessage {
  error: string;
  message: string;
}
