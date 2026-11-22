import { Target, ChangeLog, ArchivedMedia, SystemSettings } from '../types';

const BASE_URL = '/api';

export const api = {
  async getTargets(): Promise<Target[]> {
    const res = await fetch(`${BASE_URL}/targets`);
    const data = await res.json();
    return data.targets || [];
  },

  async getTarget(idOrUsername: string): Promise<Target> {
    const res = await fetch(`${BASE_URL}/targets/${idOrUsername}`);
    const data = await res.json();
    return data.target;
  },

  async addTarget(username: string, turbo: boolean = false): Promise<Target> {
    const res = await fetch(`${BASE_URL}/targets`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, turbo }),
    });
    const data = await res.json();
    if (data.status !== 'ok') {
      throw new Error(data.message || 'Failed to add target');
    }
    return data.target;
  },

  async deleteTarget(id: string): Promise<void> {
    await fetch(`${BASE_URL}/targets/${id}`, { method: 'DELETE' });
  },

  async toggleTurbo(id: string, turbo: boolean): Promise<void> {
    await fetch(`${BASE_URL}/targets/${id}/turbo`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ turbo }),
    });
  },

  async checkNow(id: string): Promise<void> {
    await fetch(`${BASE_URL}/targets/${id}/check-now`, { method: 'POST' });
  },

  async getLogs(id: string): Promise<ChangeLog[]> {
    const res = await fetch(`${BASE_URL}/targets/${id}/logs`);
    const data = await res.json();
    return data.logs || [];
  },

  async getMedia(id: string, type?: string): Promise<ArchivedMedia[]> {
    const query = type && type !== 'all' ? `?type=${type}` : '';
    const res = await fetch(`${BASE_URL}/targets/${id}/media${query}`);
    const data = await res.json();
    return data.items || [];
  },

  async getSettings(): Promise<SystemSettings> {
    const res = await fetch(`${BASE_URL}/settings`);
    const data = await res.json();
    return data.settings;
  },

  async updateSettings(payload: Partial<SystemSettings> & { session_cookie?: string }): Promise<void> {
    await fetch(`${BASE_URL}/settings`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
  },
};
