import {
  Target,
  ChangeLog,
  ArchivedMedia,
  SystemSettings,
  TargetPoolConfig,
  DevLogEntry,
  DownloadsResponse,
} from '../types';

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

  async syncTargetMedia(id: string): Promise<void> {
    const res = await fetch(`${BASE_URL}/targets/${encodeURIComponent(id)}/sync-media`, { method: 'POST' });
    await parseJsonOrThrow(res);
  },

  async getDownloads(): Promise<DownloadsResponse> {
    const res = await fetch(`${BASE_URL}/downloads`);
    const data = await parseJsonOrThrow(res);
    return {
      items: data.items || [],
      summary: data.summary || {
        active: 0,
        queued: 0,
        paused: 0,
        completed: 0,
        failed: 0,
        total_speed_bps: 0,
        paused_all: false,
        workers: 4,
      },
      archiving_targets: data.archiving_targets || [],
    };
  },

  async pauseDownload(id: string): Promise<void> {
    const res = await fetch(`${BASE_URL}/downloads/${encodeURIComponent(id)}/pause`, { method: 'POST' });
    await parseJsonOrThrow(res);
  },

  async resumeDownload(id: string): Promise<void> {
    const res = await fetch(`${BASE_URL}/downloads/${encodeURIComponent(id)}/resume`, { method: 'POST' });
    await parseJsonOrThrow(res);
  },

  async cancelDownload(id: string): Promise<void> {
    const res = await fetch(`${BASE_URL}/downloads/${encodeURIComponent(id)}/cancel`, { method: 'POST' });
    await parseJsonOrThrow(res);
  },

  async retryDownload(id: string): Promise<void> {
    const res = await fetch(`${BASE_URL}/downloads/${encodeURIComponent(id)}/retry`, { method: 'POST' });
    await parseJsonOrThrow(res);
  },

  async pauseAllDownloads(): Promise<void> {
    const res = await fetch(`${BASE_URL}/downloads/pause-all`, { method: 'POST' });
    await parseJsonOrThrow(res);
  },

  async resumeAllDownloads(): Promise<void> {
    const res = await fetch(`${BASE_URL}/downloads/resume-all`, { method: 'POST' });
    await parseJsonOrThrow(res);
  },

  async clearCompletedDownloads(): Promise<void> {
    const res = await fetch(`${BASE_URL}/downloads/clear-completed`, { method: 'POST' });
    await parseJsonOrThrow(res);
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
