import React, { useRef, useState } from 'react';
import { api } from '../../api/client';
import {
  BackupImportSummary,
  NotificationRule,
  SystemSettings,
  Target,
  ThemeMode,
} from '../../types';
import { Card } from '../common/Card';
import { Button } from '../common/Button';
import { SegmentedControl } from '../common/SegmentedControl';
import { Language, useI18n } from '../../i18n';
import {
  AlertCircle,
  CheckCircle2,
  DatabaseBackup,
  Download,
  FileJson,
  FileSpreadsheet,
  Loader2,
  Lock,
  ShieldCheck,
  Upload,
  X,
} from 'lucide-react';

interface DataBackupSectionProps {
  targets: Target[];
  settings: SystemSettings | null;
  rules: NotificationRule[];
  onImportCompleted: () => Promise<void> | void;
}

interface ParsedImportPreview {
  filename: string;
  format: 'json' | 'csv';
  kind: 'full' | 'targets_json' | 'targets_csv';
  rawContent: string;
  source?: string;
  exportedAt?: string;
  targetUsernames: string[];
  newTargetsCount: number;
  existingTargetsCount: number;
  settingsCount: number;
  rulesCount: number;
  hasAppearance: boolean;
  appearanceTheme?: ThemeMode;
  appearanceLanguage?: Language;
  hasSecrets: boolean;
}

const MAX_FILE_BYTES = 2 * 1024 * 1024;
const USERNAME_RE = /^[a-zA-Z0-9._]{1,30}$/;

function triggerBlobDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function sanitizeCsvCell(val: string, protectFormula: boolean): string {
  const cleaned = String(val ?? '').replace(/[\r\n]+/g, ' ');
  const safe =
    protectFormula && /^[=+\-@\t]/.test(cleaned) ? `'${cleaned}` : cleaned;
  if (/[,"\n\r]/.test(safe)) {
    return `"${safe.replace(/"/g, '""')}"`;
  }
  return safe;
}

function parseCsvLine(line: string): string[] {
  const out: string[] = [];
  let cur = '';
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (inQuotes) {
      if (c === '"') {
        if (line[i + 1] === '"') {
          cur += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        cur += c;
      }
    } else if (c === '"') {
      inQuotes = true;
    } else if (c === ',') {
      out.push(cur.trim());
      cur = '';
    } else {
      cur += c;
    }
  }
  out.push(cur.trim());
  return out;
}

function normalizeUsername(raw: unknown): string | null {
  if (typeof raw !== 'string') return null;
  const clean = raw.trim().replace(/^@+/, '').toLowerCase();
  return USERNAME_RE.test(clean) ? clean : null;
}

function inspectFileContent(
  filename: string,
  rawText: string,
  existingTargets: Target[],
): ParsedImportPreview | null {
  const trimmed = rawText.replace(/^\uFEFF/, '').trim();
  if (!trimmed) return null;

  const existingSet = new Set(existingTargets.map((t) => t.username.toLowerCase()));
  const looksJson = trimmed.startsWith('{') || trimmed.startsWith('[');

  if (looksJson) {
    let parsed: any;
    try {
      parsed = JSON.parse(trimmed);
    } catch {
      return null;
    }

    const usernames: string[] = [];
    const seen = new Set<string>();
    const collectTarget = (item: any) => {
      const u = normalizeUsername(typeof item === 'string' ? item : item?.username);
      if (u && !seen.has(u)) {
        seen.add(u);
        usernames.push(u);
      }
    };

    if (Array.isArray(parsed)) {
      parsed.forEach(collectTarget);
      if (usernames.length === 0) return null;
      const existingCount = usernames.filter((u) => existingSet.has(u)).length;
      return {
        filename,
        format: 'json',
        kind: 'targets_json',
        rawContent: trimmed,
        targetUsernames: usernames,
        newTargetsCount: usernames.length - existingCount,
        existingTargetsCount: existingCount,
        settingsCount: 0,
        rulesCount: 0,
        hasAppearance: false,
        hasSecrets: false,
      };
    }

    if (parsed && typeof parsed === 'object') {
      if (Array.isArray(parsed.targets)) {
        parsed.targets.forEach(collectTarget);
      }
      const settingsObj =
        parsed.settings && typeof parsed.settings === 'object' ? parsed.settings : null;
      const settingsKeys = settingsObj ? Object.keys(settingsObj) : [];
      const hasSecrets = Boolean(
        settingsObj &&
          ((typeof settingsObj.telegram_bot_token === 'string' &&
            settingsObj.telegram_bot_token.trim().length > 0 &&
            !settingsObj.telegram_bot_token.startsWith('•••')) ||
            (typeof settingsObj.telegram_chat_id === 'string' &&
              settingsObj.telegram_chat_id.trim().length > 0)),
      );

      let rulesCount = 0;
      if (Array.isArray(parsed.notification_settings?.rules)) {
        rulesCount = parsed.notification_settings.rules.length;
      } else if (Array.isArray(parsed.notification_rules)) {
        rulesCount = parsed.notification_rules.length;
      } else if (parsed.notification_settings?.global_events) {
        rulesCount =
          1 +
          (parsed.notification_settings?.target_overrides
            ? Object.keys(parsed.notification_settings.target_overrides).length
            : 0);
      }

      const appTheme = parsed.appearance?.theme_mode;
      const appLang = parsed.appearance?.language;
      const validTheme: ThemeMode | undefined =
        appTheme === 'light' || appTheme === 'dark' || appTheme === 'system'
          ? appTheme
          : undefined;
      const validLang: Language | undefined =
        appLang === 'az' || appLang === 'en' || appLang === 'ru' ? appLang : undefined;
      const hasAppearance = Boolean(validTheme || validLang);

      const isFull =
        parsed.schema === 'instalogger.backup' ||
        settingsKeys.length > 0 ||
        rulesCount > 0 ||
        hasAppearance;

      if (!isFull && usernames.length === 0) return null;

      const existingCount = usernames.filter((u) => existingSet.has(u)).length;
      return {
        filename,
        format: 'json',
        kind: isFull ? 'full' : 'targets_json',
        rawContent: trimmed,
        source: typeof parsed.source === 'string' ? parsed.source : undefined,
        exportedAt: typeof parsed.exported_at === 'string' ? parsed.exported_at : undefined,
        targetUsernames: usernames,
        newTargetsCount: usernames.length - existingCount,
        existingTargetsCount: existingCount,
        settingsCount: settingsKeys.length,
        rulesCount,
        hasAppearance,
        appearanceTheme: validTheme,
        appearanceLanguage: validLang,
        hasSecrets,
      };
    }

    return null;
  }

  // CSV parsing preview
  const lines = trimmed
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter(Boolean);
  if (lines.length === 0) return null;

  const firstCols = parseCsvLine(lines[0]).map((c) => c.toLowerCase());
  const headerIdx = firstCols.findIndex(
    (h) => h === 'username' || h === 'handle' || h === 'account',
  );
  const startRow = headerIdx >= 0 ? 1 : 0;
  const colIndex = headerIdx >= 0 ? headerIdx : 0;

  const usernames: string[] = [];
  const seen = new Set<string>();
  for (let i = startRow; i < lines.length; i++) {
    const cols = parseCsvLine(lines[i]);
    const u = normalizeUsername(cols[colIndex]);
    if (u && !seen.has(u)) {
      seen.add(u);
      usernames.push(u);
    }
  }
  if (usernames.length === 0) return null;

  const existingCount = usernames.filter((u) => existingSet.has(u)).length;
  return {
    filename,
    format: 'csv',
    kind: 'targets_csv',
    rawContent: trimmed,
    targetUsernames: usernames,
    newTargetsCount: usernames.length - existingCount,
    existingTargetsCount: existingCount,
    settingsCount: 0,
    rulesCount: 0,
    hasAppearance: false,
    hasSecrets: false,
  };
}

export const DataBackupSection: React.FC<DataBackupSectionProps> = ({
  targets,
  settings,
  rules,
  onImportCompleted,
}) => {
  const { language, setLanguage, themeMode, setThemeMode, t } = useI18n();
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const [exportingKey, setExportingKey] = useState<string | null>(null);
  const [includeSecretsOnExport, setIncludeSecretsOnExport] = useState(false);

  const [dragActive, setDragActive] = useState(false);
  const [preview, setPreview] = useState<ParsedImportPreview | null>(null);
  const [optTargets, setOptTargets] = useState(true);
  const [optSettings, setOptSettings] = useState(true);
  const [optRules, setOptRules] = useState(true);
  const [optAppearance, setOptAppearance] = useState(true);
  const [optSecrets, setOptSecrets] = useState(false);
  const [conflictMode, setConflictMode] = useState<'merge' | 'skip'>('merge');

  const [importing, setImporting] = useState(false);
  const [feedback, setFeedback] = useState<{
    type: 'success' | 'error';
    message: string;
    details?: string;
  } | null>(null);

  const buildLocalFallbackExport = (format: 'json' | 'csv', scope: 'full' | 'targets') => {
    const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 16);
    if (format === 'csv') {
      const header =
        '\uFEFFusername,full_name,instagram_id,is_private,status,turbo_mode,check_interval_seconds,followers_count,following_count,media_count,created_at\n';
      const rows = targets
        .map((tgt) =>
          [
            sanitizeCsvCell(tgt.username, false),
            sanitizeCsvCell(tgt.full_name, true),
            sanitizeCsvCell(tgt.id, true),
            String(tgt.is_private),
            sanitizeCsvCell(tgt.status, false),
            String(tgt.turbo_mode),
            String(tgt.check_interval_seconds),
            String(tgt.followers_count),
            String(tgt.following_count),
            String(tgt.media_count),
            sanitizeCsvCell(tgt.created_at, false),
          ].join(','),
        )
        .join('\n');
      return {
        blob: new Blob([header + rows + '\n'], { type: 'text/csv;charset=utf-8' }),
        filename: `instalogger-targets-${stamp}.csv`,
      };
    }

    const mappedTargets = targets.map((tgt) => ({
      username: tgt.username,
      instagram_id: tgt.id,
      full_name: tgt.full_name,
      is_private: tgt.is_private,
      status: tgt.status,
      turbo_mode: tgt.turbo_mode,
      check_interval_seconds: tgt.check_interval_seconds,
      followers_count: tgt.followers_count,
      following_count: tgt.following_count,
      media_count: tgt.media_count,
      created_at: tgt.created_at,
    }));

    const payload =
      scope === 'targets'
        ? {
            schema: 'instalogger.targets',
            version: 2,
            exported_at: new Date().toISOString(),
            source: 'web',
            count: mappedTargets.length,
            targets: mappedTargets,
          }
        : {
            schema: 'instalogger.backup',
            version: 2,
            exported_at: new Date().toISOString(),
            source: 'web',
            includes_secrets: false,
            appearance: { theme_mode: themeMode, language },
            settings: settings
              ? {
                  ...settings,
                  telegram_bot_token: undefined,
                  telegram_chat_id: undefined,
                }
              : {},
            notification_settings: { rules },
            targets: mappedTargets,
          };

    return {
      blob: new Blob([JSON.stringify(payload, null, 2)], {
        type: 'application/json;charset=utf-8',
      }),
      filename: `instalogger-${scope === 'targets' ? 'targets' : 'backup'}-${stamp}.json`,
    };
  };

  const handleExport = async (format: 'json' | 'csv', scope: 'full' | 'targets') => {
    const key = `${scope}_${format}`;
    setFeedback(null);
    if (scope === 'targets' && targets.length === 0) {
      setFeedback({ type: 'error', message: t.backup_no_targets });
      return;
    }
    setExportingKey(key);
    try {
      const { blob, filename } = await api.exportBackup({
        format,
        scope,
        include_secrets: scope === 'full' ? includeSecretsOnExport : false,
        theme_mode: themeMode,
        language,
      });
      triggerBlobDownload(blob, filename);
      setFeedback({ type: 'success', message: `${t.backup_export_success}: ${filename}` });
    } catch {
      // Graceful local fallback so target/config export always works even if backend endpoint is offline
      const fallback = buildLocalFallbackExport(format, scope);
      triggerBlobDownload(fallback.blob, fallback.filename);
      setFeedback({
        type: 'success',
        message: `${t.backup_export_success}: ${fallback.filename}`,
      });
    } finally {
      setExportingKey(null);
    }
  };

  const processSelectedFile = async (file: File) => {
    setFeedback(null);
    if (file.size > MAX_FILE_BYTES) {
      setFeedback({ type: 'error', message: t.backup_file_too_large });
      return;
    }
    try {
      const text = await file.text();
      const inspected = inspectFileContent(file.name, text, targets);
      if (!inspected) {
        setFeedback({ type: 'error', message: t.backup_invalid_file });
        setPreview(null);
        return;
      }
      setPreview(inspected);
      setOptTargets(inspected.targetUsernames.length > 0);
      setOptSettings(inspected.settingsCount > 0);
      setOptRules(inspected.rulesCount > 0);
      setOptAppearance(inspected.hasAppearance);
      setOptSecrets(false);
    } catch {
      setFeedback({ type: 'error', message: t.backup_invalid_file });
      setPreview(null);
    }
  };

  const handleFileInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      void processSelectedFile(file);
    }
    e.target.value = '';
  };

  const handleDrop = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDragActive(false);
    const file = e.dataTransfer.files?.[0];
    if (file) {
      void processSelectedFile(file);
    }
  };

  const handleApplyImport = async () => {
    if (!preview) return;
    setImporting(true);
    setFeedback(null);
    try {
      const res = await api.importBackup({
        format: preview.format,
        content: preview.rawContent,
        options: {
          import_targets: optTargets,
          import_settings: optSettings,
          import_notification_rules: optRules,
          conflict_mode: conflictMode,
          include_secrets: optSecrets,
        },
      });

      if (optAppearance) {
        const nextTheme = res.appearance?.theme_mode || preview.appearanceTheme;
        const nextLang = res.appearance?.language || preview.appearanceLanguage;
        if (nextTheme) setThemeMode(nextTheme);
        if (nextLang) setLanguage(nextLang);
      }

      await onImportCompleted();

      const s: BackupImportSummary = res.summary;
      const parts: string[] = [];
      if (s.targets_added > 0) parts.push(`+${s.targets_added} ${t.backup_targets_new}`);
      if (s.targets_updated > 0) parts.push(`~${s.targets_updated} ${t.backup_targets_existing}`);
      if (s.settings_applied > 0) parts.push(`${s.settings_applied} ${t.backup_settings_found}`);
      if (s.rules_applied > 0) parts.push(`${s.rules_applied} ${t.backup_rules_found}`);

      setFeedback({
        type: 'success',
        message: t.backup_import_success,
        details: parts.length > 0 ? parts.join(' · ') : undefined,
      });
      setPreview(null);
    } catch (e: any) {
      setFeedback({
        type: 'error',
        message: e?.message || t.backup_invalid_file,
      });
    } finally {
      setImporting(false);
    }
  };

  const formatKindLabel = (kind: ParsedImportPreview['kind']) => {
    if (kind === 'full') return t.backup_format_full;
    if (kind === 'targets_csv') return t.backup_format_targets_csv;
    return t.backup_format_targets_json;
  };

  const nothingSelected =
    preview !== null &&
    !(
      (optTargets && preview.targetUsernames.length > 0) ||
      (optSettings && preview.settingsCount > 0) ||
      (optRules && preview.rulesCount > 0) ||
      (optAppearance && preview.hasAppearance)
    );

  return (
    <section className="space-y-2.5">
      <h2 className="text-xs font-medium text-[var(--text-muted)] px-1">
        {t.section_backup_transfer}
      </h2>

      <Card className="space-y-5">
        {/* Feedback Banner */}
        {feedback && (
          <div
            className={`flex items-start gap-2.5 p-3 rounded-xl border text-xs animate-panel ${
              feedback.type === 'success'
                ? 'bg-emerald-500/10 border-emerald-500/25 text-emerald-600 dark:text-emerald-400'
                : 'bg-rose-500/10 border-rose-500/25 text-rose-600 dark:text-rose-400'
            }`}
          >
            {feedback.type === 'success' ? (
              <CheckCircle2 size={15} className="shrink-0 mt-0.5" />
            ) : (
              <AlertCircle size={15} className="shrink-0 mt-0.5" />
            )}
            <div className="min-w-0 flex-1">
              <div className="font-medium">{feedback.message}</div>
              {feedback.details && (
                <div className="opacity-85 mt-0.5">{feedback.details}</div>
              )}
            </div>
            <button
              type="button"
              onClick={() => setFeedback(null)}
              className="opacity-70 hover:opacity-100 p-0.5 cursor-pointer"
              aria-label="Close"
            >
              <X size={14} />
            </button>
          </div>
        )}

        {/* 1. Export Target List (JSON / CSV) */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div className="flex items-start gap-3 min-w-0">
            <div className="w-9 h-9 rounded-xl bg-[var(--accent-soft)] text-[var(--accent)] flex items-center justify-center shrink-0">
              <Download size={17} strokeWidth={1.75} />
            </div>
            <div className="min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <h3 className="text-sm font-medium text-[var(--text-primary)]">
                  {t.backup_export_targets_title}
                </h3>
                <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold bg-[var(--bg-subtle)] text-[var(--text-secondary)]">
                  {targets.length} {t.targets_badge}
                </span>
              </div>
              <p className="text-xs text-[var(--text-muted)] mt-0.5">
                {t.backup_export_targets_desc}
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2 shrink-0">
            <Button
              type="button"
              size="sm"
              variant="secondary"
              disabled={exportingKey !== null}
              icon={
                exportingKey === 'targets_json' ? (
                  <Loader2 size={14} className="animate-spin" />
                ) : (
                  <FileJson size={14} />
                )
              }
              onClick={() => handleExport('json', 'targets')}
            >
              JSON
            </Button>
            <Button
              type="button"
              size="sm"
              variant="secondary"
              disabled={exportingKey !== null}
              icon={
                exportingKey === 'targets_csv' ? (
                  <Loader2 size={14} className="animate-spin" />
                ) : (
                  <FileSpreadsheet size={14} />
                )
              }
              onClick={() => handleExport('csv', 'targets')}
            >
              CSV
            </Button>
          </div>
        </div>

        <div className="h-px bg-[var(--border-subtle)]" />

        {/* 2. Full App Configuration Export */}
        <div className="space-y-3">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div className="flex items-start gap-3 min-w-0">
              <div className="w-9 h-9 rounded-xl bg-[var(--bg-subtle)] text-[var(--text-secondary)] flex items-center justify-center shrink-0">
                <DatabaseBackup size={17} strokeWidth={1.75} />
              </div>
              <div className="min-w-0">
                <h3 className="text-sm font-medium text-[var(--text-primary)]">
                  {t.backup_export_full_title}
                </h3>
                <p className="text-xs text-[var(--text-muted)] mt-0.5">
                  {t.backup_export_full_desc}
                </p>
              </div>
            </div>

            <Button
              type="button"
              size="sm"
              variant="primary"
              disabled={exportingKey !== null}
              icon={
                exportingKey === 'full_json' ? (
                  <Loader2 size={14} className="animate-spin" />
                ) : (
                  <Download size={14} />
                )
              }
              onClick={() => handleExport('json', 'full')}
              className="shrink-0"
            >
              {t.backup_export_full_btn}
            </Button>
          </div>

          <div className="rounded-xl bg-[var(--bg-subtle)]/70 border border-[var(--border-subtle)] p-3 flex flex-col gap-2">
            <label className="flex items-center justify-between gap-3 cursor-pointer">
              <div className="min-w-0">
                <div className="text-xs font-medium text-[var(--text-primary)] flex items-center gap-1.5">
                  <Lock size={12} className="text-[var(--text-muted)]" />
                  {t.backup_include_secrets_title}
                </div>
                <div className="text-[11px] text-[var(--text-muted)] mt-0.5">
                  {t.backup_include_secrets_desc}
                </div>
              </div>
              <input
                type="checkbox"
                checked={includeSecretsOnExport}
                onChange={(e) => setIncludeSecretsOnExport(e.target.checked)}
                className="h-4 w-4 rounded accent-[var(--accent)] shrink-0 cursor-pointer"
              />
            </label>
            <div className="flex items-center gap-1.5 text-[11px] text-emerald-600 dark:text-emerald-400">
              <ShieldCheck size={12} className="shrink-0" />
              <span>{t.backup_security_note}</span>
            </div>
          </div>
        </div>

        <div className="h-px bg-[var(--border-subtle)]" />

        {/* 3. Smart Import (JSON / CSV) */}
        <div className="space-y-3">
          <div className="flex items-start gap-3">
            <div className="w-9 h-9 rounded-xl bg-[var(--bg-subtle)] text-[var(--text-secondary)] flex items-center justify-center shrink-0">
              <Upload size={17} strokeWidth={1.75} />
            </div>
            <div className="min-w-0">
              <h3 className="text-sm font-medium text-[var(--text-primary)]">
                {t.backup_import_title}
              </h3>
              <p className="text-xs text-[var(--text-muted)] mt-0.5">
                {t.backup_import_desc}
              </p>
            </div>
          </div>

          <input
            ref={fileInputRef}
            type="file"
            accept=".json,.csv,application/json,text/csv"
            onChange={handleFileInputChange}
            className="hidden"
          />

          <div
            onDragOver={(e) => {
              e.preventDefault();
              setDragActive(true);
            }}
            onDragLeave={() => setDragActive(false)}
            onDrop={handleDrop}
            onClick={() => fileInputRef.current?.click()}
            role="button"
            tabIndex={0}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                fileInputRef.current?.click();
              }
            }}
            className={`rounded-xl border-2 border-dashed p-5 text-center transition-all cursor-pointer ${
              dragActive
                ? 'border-[var(--accent)] bg-[var(--accent-soft)]'
                : 'border-[var(--border-strong)] bg-[var(--bg-subtle)]/40 hover:border-[var(--accent)]/60 hover:bg-[var(--bg-subtle)]/75'
            }`}
          >
            <Upload
              size={20}
              className="mx-auto mb-2 text-[var(--text-muted)]"
              strokeWidth={1.75}
            />
            <p className="text-xs font-medium text-[var(--text-primary)]">
              {t.backup_drop_hint}
            </p>
            <p className="text-[11px] text-[var(--text-muted)] mt-1">
              .json · .csv (max 2 MB)
            </p>
          </div>

          {/* Animated Import Preview Card */}
          {preview && (
            <div className="rounded-xl border border-[var(--border-strong)] bg-[var(--bg-subtle)]/60 p-4 space-y-4 animate-panel">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-xs font-semibold text-[var(--text-primary)]">
                      {t.backup_preview_title}
                    </span>
                    <span className="px-2 py-0.5 rounded-full text-[11px] font-medium bg-[var(--accent-soft)] text-[var(--accent)]">
                      {formatKindLabel(preview.kind)}
                    </span>
                    {preview.source && (
                      <span className="px-2 py-0.5 rounded-full text-[11px] font-medium bg-[var(--bg-surface)] border border-[var(--border-subtle)] text-[var(--text-secondary)] uppercase">
                        {t.backup_source_label}: {preview.source}
                      </span>
                    )}
                  </div>
                  <p className="text-[11px] text-[var(--text-muted)] truncate mt-1">
                    {preview.filename}
                    {preview.exportedAt
                      ? ` · ${new Date(preview.exportedAt).toLocaleString()}`
                      : ''}
                  </p>
                </div>

                <button
                  type="button"
                  onClick={() => setPreview(null)}
                  className="p-1 rounded-lg text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-surface)] cursor-pointer"
                  aria-label="Clear preview"
                >
                  <X size={15} />
                </button>
              </div>

              {/* Summary Badges */}
              <div className="flex flex-wrap gap-2">
                {preview.targetUsernames.length > 0 && (
                  <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-[var(--bg-surface)] border border-[var(--border-subtle)] text-xs text-[var(--text-primary)]">
                    <strong>{preview.targetUsernames.length}</strong> {t.backup_targets_found}
                    <span className="text-[11px] text-emerald-600 dark:text-emerald-400">
                      (+{preview.newTargetsCount} {t.backup_targets_new}
                      {preview.existingTargetsCount > 0
                        ? `, ${preview.existingTargetsCount} ${t.backup_targets_existing}`
                        : ''}
                      )
                    </span>
                  </span>
                )}
                {preview.settingsCount > 0 && (
                  <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-[var(--bg-surface)] border border-[var(--border-subtle)] text-xs text-[var(--text-primary)]">
                    <strong>{preview.settingsCount}</strong> {t.backup_settings_found}
                  </span>
                )}
                {preview.rulesCount > 0 && (
                  <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-[var(--bg-surface)] border border-[var(--border-subtle)] text-xs text-[var(--text-primary)]">
                    <strong>{preview.rulesCount}</strong> {t.backup_rules_found}
                  </span>
                )}
              </div>

              {/* Selective Checkboxes */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                {preview.targetUsernames.length > 0 && (
                  <label className="flex items-center gap-2.5 p-2.5 rounded-lg bg-[var(--bg-surface)] border border-[var(--border-subtle)] cursor-pointer text-xs text-[var(--text-primary)]">
                    <input
                      type="checkbox"
                      checked={optTargets}
                      onChange={(e) => setOptTargets(e.target.checked)}
                      className="h-4 w-4 accent-[var(--accent)] cursor-pointer"
                    />
                    <span>{t.backup_opt_targets}</span>
                  </label>
                )}

                {preview.settingsCount > 0 && (
                  <label className="flex items-center gap-2.5 p-2.5 rounded-lg bg-[var(--bg-surface)] border border-[var(--border-subtle)] cursor-pointer text-xs text-[var(--text-primary)]">
                    <input
                      type="checkbox"
                      checked={optSettings}
                      onChange={(e) => setOptSettings(e.target.checked)}
                      className="h-4 w-4 accent-[var(--accent)] cursor-pointer"
                    />
                    <span>{t.backup_opt_settings}</span>
                  </label>
                )}

                {preview.rulesCount > 0 && (
                  <label className="flex items-center gap-2.5 p-2.5 rounded-lg bg-[var(--bg-surface)] border border-[var(--border-subtle)] cursor-pointer text-xs text-[var(--text-primary)]">
                    <input
                      type="checkbox"
                      checked={optRules}
                      onChange={(e) => setOptRules(e.target.checked)}
                      className="h-4 w-4 accent-[var(--accent)] cursor-pointer"
                    />
                    <span>{t.backup_opt_rules}</span>
                  </label>
                )}

                {preview.hasAppearance && (
                  <label className="flex items-center gap-2.5 p-2.5 rounded-lg bg-[var(--bg-surface)] border border-[var(--border-subtle)] cursor-pointer text-xs text-[var(--text-primary)]">
                    <input
                      type="checkbox"
                      checked={optAppearance}
                      onChange={(e) => setOptAppearance(e.target.checked)}
                      className="h-4 w-4 accent-[var(--accent)] cursor-pointer"
                    />
                    <span>{t.backup_opt_appearance}</span>
                  </label>
                )}

                {preview.hasSecrets && optSettings && (
                  <label className="flex items-center gap-2.5 p-2.5 rounded-lg bg-amber-500/10 border border-amber-500/25 cursor-pointer text-xs text-[var(--text-primary)] sm:col-span-2">
                    <input
                      type="checkbox"
                      checked={optSecrets}
                      onChange={(e) => setOptSecrets(e.target.checked)}
                      className="h-4 w-4 accent-[var(--accent)] cursor-pointer"
                    />
                    <span className="flex items-center gap-1.5">
                      <Lock size={12} className="text-amber-500 shrink-0" />
                      {t.backup_opt_secrets}
                    </span>
                  </label>
                )}
              </div>

              {/* Conflict Strategy (when targets are selected) */}
              {optTargets && preview.existingTargetsCount > 0 && (
                <div className="space-y-1.5">
                  <div className="text-[11px] font-medium text-[var(--text-muted)]">
                    {t.backup_conflict_label}
                  </div>
                  <SegmentedControl
                    value={conflictMode}
                    onChange={(v) => setConflictMode(v as 'merge' | 'skip')}
                    options={[
                      { value: 'merge', label: t.backup_conflict_merge },
                      { value: 'skip', label: t.backup_conflict_skip },
                    ]}
                  />
                </div>
              )}

              {/* Action Buttons */}
              <div className="flex items-center justify-end gap-2 pt-1">
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  disabled={importing}
                  onClick={() => setPreview(null)}
                >
                  {t.cancel}
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="primary"
                  disabled={nothingSelected || importing}
                  icon={
                    importing ? (
                      <Loader2 size={14} className="animate-spin" />
                    ) : (
                      <Upload size={14} />
                    )
                  }
                  onClick={handleApplyImport}
                >
                  {importing ? t.backup_importing : t.backup_import_apply}
                </Button>
              </div>
            </div>
          )}
        </div>
      </Card>
    </section>
  );
};
