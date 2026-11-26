import {
  Target,
  ChangeLog,
  ArchivedMedia,
  SystemSettings,
  TargetPoolConfig,
  DevLogEntry,
  DownloadsResponse,
  RelationshipsResponse,
  FollowerHistoryPoint,
  MediaAsset,
  PostComment,
  ProviderHealth,
  NotificationRecord,
  NotificationRule,
  StorageOverview,
  AuthStatus,
} from '../types';

const BASE_URL = '/api';

function authHeaders(): Record<string, string> {
  try {
    const tok = sessionStorage.getItem('instalogger_auth_token');
    return tok ? { 'X-Auth-Token': tok } : {};
  } catch {
    return {};
  }
}

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

  async getRelationships(id: string): Promise<RelationshipsResponse> {
    const res = await fetch(`${BASE_URL}/targets/${encodeURIComponent(id)}/relationships`);
    const data = await parseJsonOrThrow(res);
    return {
      followers: data.followers || [],
      following: data.following || [],
      audits: data.audits || [],
    };
  },

  async syncRelationships(id: string): Promise<RelationshipsResponse> {
    const res = await fetch(`${BASE_URL}/targets/${encodeURIComponent(id)}/sync-relationships`, {
      method: 'POST',
    });
    const data = await parseJsonOrThrow(res);
    return {
      followers: data.followers || [],
      following: data.following || [],
      audits: data.audits || [],
    };
  },

  async exportSessionCookie(): Promise<string> {
    const res = await fetch(`${BASE_URL}/settings/session-export`);
    const data = await parseJsonOrThrow(res);
    return data.cookie || '';
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

  async setTargetStatus(id: string, status: 'active' | 'paused'): Promise<void> {
    const res = await fetch(`${BASE_URL}/targets/${encodeURIComponent(id)}/status`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status }),
    });
    await parseJsonOrThrow(res);
  },

  async setTargetInterval(id: string, check_interval_seconds: number): Promise<void> {
    const res = await fetch(`${BASE_URL}/targets/${encodeURIComponent(id)}/interval`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ check_interval_seconds }),
    });
    await parseJsonOrThrow(res);
  },

  async getTargetHistory(id: string, range: '24h' | '7d' | '30d' | 'all' = '30d'): Promise<FollowerHistoryPoint[]> {
    const res = await fetch(`${BASE_URL}/targets/${encodeURIComponent(id)}/history?range=${range}`);
    const data = await parseJsonOrThrow(res);
    return data.history || [];
  },

  async getMediaAssets(targetId: string, mediaId: string): Promise<MediaAsset[]> {
    const res = await fetch(
      `${BASE_URL}/targets/${encodeURIComponent(targetId)}/media/${encodeURIComponent(mediaId)}/assets`
    );
    const data = await parseJsonOrThrow(res);
    return data.assets || [];
  },

  async deleteTargetAvatar(targetId: string, filename: string): Promise<void> {
    const res = await fetch(
      `${BASE_URL}/targets/${encodeURIComponent(targetId)}/avatars/${encodeURIComponent(filename)}`,
      { method: 'DELETE' }
    );
    await parseJsonOrThrow(res);
  },

  async getPostComments(targetId: string, shortcode: string): Promise<PostComment[]> {
    const res = await fetch(
      `${BASE_URL}/targets/${encodeURIComponent(targetId)}/posts/${encodeURIComponent(shortcode)}/comments`
    );
    const data = await parseJsonOrThrow(res);
    return data.comments || [];
  },

  async syncPostComments(targetId: string, shortcode: string): Promise<PostComment[]> {
    const res = await fetch(
      `${BASE_URL}/targets/${encodeURIComponent(targetId)}/posts/${encodeURIComponent(shortcode)}/comments/sync`,
      { method: 'POST' }
    );
    const data = await parseJsonOrThrow(res);
    return data.comments || [];
  },

  async getNotifications(): Promise<{ notifications: NotificationRecord[]; unread_count: number }> {
    const res = await fetch(`${BASE_URL}/notifications`);
    const data = await parseJsonOrThrow(res);
    return { notifications: data.notifications || [], unread_count: data.unread_count || 0 };
  },

  async markAllNotificationsRead(): Promise<void> {
    const res = await fetch(`${BASE_URL}/notifications/read-all`, { method: 'POST' });
    await parseJsonOrThrow(res);
  },

  async markNotificationRead(id: number): Promise<void> {
    const res = await fetch(`${BASE_URL}/notifications/${id}/read`, { method: 'POST' });
    await parseJsonOrThrow(res);
  },

  async deleteNotification(id: number): Promise<void> {
    const res = await fetch(`${BASE_URL}/notifications/${id}`, { method: 'DELETE' });
    await parseJsonOrThrow(res);
  },

  async clearNotifications(): Promise<void> {
    const res = await fetch(`${BASE_URL}/notifications`, { method: 'DELETE' });
    await parseJsonOrThrow(res);
  },

  async getNotificationRules(): Promise<NotificationRule[]> {
    const res = await fetch(`${BASE_URL}/notification-rules`);
    const data = await parseJsonOrThrow(res);
    return data.rules || [];
  },

  async upsertNotificationRule(rule: NotificationRule): Promise<void> {
    const res = await fetch(`${BASE_URL}/notification-rules`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(rule),
    });
    await parseJsonOrThrow(res);
  },

  async getStorageOverview(): Promise<StorageOverview> {
    const res = await fetch(`${BASE_URL}/storage`);
    return await parseJsonOrThrow(res);
  },

  async cleanupStorage(scope: 'logs' | 'target_media' | 'orphans', target_id?: string): Promise<void> {
    const res = await fetch(`${BASE_URL}/storage/cleanup`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ scope, target_id }),
    });
    await parseJsonOrThrow(res);
  },

  async getProviderHealth(): Promise<ProviderHealth[]> {
    const res = await fetch(`${BASE_URL}/providers/health`);
    const data = await parseJsonOrThrow(res);
    return data.providers || [];
  },

  async resetProviderHealth(): Promise<void> {
    const res = await fetch(`${BASE_URL}/providers/health/reset`, { method: 'POST' });
    await parseJsonOrThrow(res);
  },

  async getAuthStatus(): Promise<AuthStatus> {
    const res = await fetch(`${BASE_URL}/auth/status`, { headers: authHeaders() });
    const data = await parseJsonOrThrow(res);
    return { enabled: Boolean(data.enabled), locked: Boolean(data.locked), timeout_minutes: data.timeout_minutes || 15 };
  },

  async setupPin(pin: string, current_pin?: string, timeout_minutes?: number): Promise<{ enabled: boolean; token?: string }> {
    const res = await fetch(`${BASE_URL}/auth/setup`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
      body: JSON.stringify({ pin, current_pin, timeout_minutes }),
    });
    const data = await parseJsonOrThrow(res);
    if (data.token) {
      try { sessionStorage.setItem('instalogger_auth_token', data.token); } catch {}
    }
    return { enabled: Boolean(data.enabled), token: data.token };
  },

  async unlockPin(pin: string): Promise<string> {
    const res = await fetch(`${BASE_URL}/auth/unlock`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pin }),
    });
    const data = await parseJsonOrThrow(res);
    const token = data.token || '';
    if (token) {
      try { sessionStorage.setItem('instalogger_auth_token', token); } catch {}
    }
    return token;
  },

  async lockDashboard(): Promise<void> {
    await fetch(`${BASE_URL}/auth/lock`, { method: 'POST', headers: authHeaders() }).catch(() => {});
    try { sessionStorage.removeItem('instalogger_auth_token'); } catch {}
  },
};
