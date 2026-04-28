import type { LoginData } from '../types';

const ACCESS_TOKEN_KEY = 'accessToken';
const REFRESH_TOKEN_KEY = 'refreshToken';
const USER_INFO_KEY = 'userInfo';

export function saveLogin(login: LoginData): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, login.accessToken);
  localStorage.setItem(REFRESH_TOKEN_KEY, login.refreshToken);
  localStorage.setItem(
    USER_INFO_KEY,
    JSON.stringify({
      userId: login.userId,
      email: login.email,
      name: login.name,
    }),
  );
}

export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function clearLogin(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(USER_INFO_KEY);
}
