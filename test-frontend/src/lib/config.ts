const DEFAULT_API_BASE_URL = 'http://localhost:8080/api/v1';

export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL?.trim() || DEFAULT_API_BASE_URL;

export const WS_BASE_URL = import.meta.env.VITE_WS_BASE_URL?.trim();

export function deriveWebSocketBaseUrl(apiBaseUrl: string): string {
  const url = new URL(apiBaseUrl);
  const protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${url.host}`;
}

export function getWebSocketBaseUrl(): string {
  return WS_BASE_URL || deriveWebSocketBaseUrl(API_BASE_URL);
}
