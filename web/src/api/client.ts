import { Target, ChangeLog, ArchivedMedia, SystemSettings, TargetPoolConfig, DevLogEntry } from '../types';

const BASE_URL = '/api';

async function parseJsonOrThrow(res: Response) {
  let data: any = null;
  try {
    data = await res.json();
  } catch {
    data = null;
  }
  if (!res.ok || (data && data.status === 'error')) {
    const msg = data?.message || `HTTP ${res.status} ${res.statusText}`;
    throw new Error(msg);
  }
  return data;
}

export const api = {
  async getTargets(): Promise<Target[]> {
    const res = await fetch(`${BASE_URL}/targets`);
    const data = await parseJsonOrThrow(res);
    return data.targets || [];
  },

  async getTarget(idOrUsername: string): Promise<Target> {
    const res = await fetch(`${BASE_URL}/targets/${encodeURIComponent(idOrUsername)}`);
    const data = await parseJsonOrThrow(res);
    return data.target;
  },

  async addTarget(username: string, turbo: boolean = false): Promise<{ target: Target; warning?: string | null }> {
    const res = await fetch(`${BASE_URL}/targets`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, turbo }),
    });
    const data = await parseJsonOrThrow(res);
    return { target: data.target, warning: data.warning ?? null };
  },

  async deleteTarget(id: string): Promise<void> {
    const res = await fetch(`${BASE_URL}/targets/${encodeURIComponent(id)}`, { method: 'DELETE' });
    await parseJsonOrThrow(res);
  },

  async toggleTurbo(id: string, turbo: boolean): Promise<void> {
    const res = await fetch(`${BASE_URL}/targets/${encodeURIComponent(id)}/turbo`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ turbo }),
    });
    await parseJsonOrThrow(res);
  },

  async checkNow(id: string): Promise<{ target?: Target }> {
    const res = await fetch(`${BASE_URL}/targets/${encodeURIComponent(id)}/check-now`, { method: 'POST' });
    return await parseJsonOrThrow(res);
  },

  async getTargetPool(id: string): Promise<TargetPoolConfig> {
    const res = await fetch(`${BASE_URL}/targets/${encodeURIComponent(id)}/pool`);
    const data = await parseJsonOrThrow(res);
    return data.pool;
  },

  async updateTargetPool(id: string, payload: Partial<TargetPoolConfig>): Promise<TargetPoolConfig> {
    const res = await fetch(`${BASE_URL}/targets/${encodeURIComponent(id)}/pool`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    const data = await parseJsonOrThrow(res);
    return data.pool;
  },

  async flushTargetPool(id: string): Promise<TargetPoolConfig> {
    const res = await fetch(`${BASE_URL}/targets/${encodeURIComponent(id)}/pool/flush`, { method: 'POST' });
    const data = await parseJsonOrThrow(res);
    return data.pool;
  },

  async getLogs(id: string): Promise<ChangeLog[]> {
    const res = await fetch(`${BASE_URL}/targets/${encodeURIComponent(id)}/logs`);
    const data = await parseJsonOrThrow(res);
    return data.logs || [];
  },

  async getAvatars(id: string): Promise<string[]> {
    const res = await fetch(`${BASE_URL}/targets/${encodeURIComponent(id)}/avatars`);
    const data = await parseJsonOrThrow(res);
    return data.avatars || [];
  },

  async getMedia(id: string, type?: string): Promise<ArchivedMedia[]> {
    const query = type && type !== 'all' ? `?type=${encodeURIComponent(type)}` : '';
    const res = await fetch(`${BASE_URL}/targets/${encodeURIComponent(id)}/media${query}`);
    const data = await parseJsonOrThrow(res);
    return data.items || [];
  },

  async getSettings(): Promise<SystemSettings> {
    const res = await fetch(`${BASE_URL}/settings`);
    const data = await parseJsonOrThrow(res);
    return data.settings;
  },

  async updateSettings(
    payload: Partial<SystemSettings> & { session_cookie?: string; clear_session_cookie?: boolean }
  ): Promise<void> {
    const res = await fetch(`${BASE_URL}/settings`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    await parseJsonOrThrow(res);
  },

  async getDevLogs(): Promise<DevLogEntry[]> {
    const res = await fetch(`${BASE_URL}/dev-logs`);
    const data = await parseJsonOrThrow(res);
    return data.logs || [];
  },

  async clearDevLogs(): Promise<void> {
    const res = await fetch(`${BASE_URL}/dev-logs`, { method: 'DELETE' });
    await parseJsonOrThrow(res);
  },
};
