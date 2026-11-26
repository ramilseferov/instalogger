import React, { useEffect, useState, useMemo } from 'react';
import { api } from '../../api/client';
import { FollowerHistoryPoint } from '../../types';
import { useI18n } from '../../i18n';
import { Card } from '../common/Card';
import { TrendingUp, TrendingDown } from 'lucide-react';

interface FollowerGrowthChartProps {
  targetId: string;
}

type RangeKey = '24h' | '7d' | '30d' | 'all';

export const FollowerGrowthChart: React.FC<FollowerGrowthChartProps> = ({ targetId }) => {
  const { t } = useI18n();
  const [range, setRange] = useState<RangeKey>('30d');
  const [points, setPoints] = useState<FollowerHistoryPoint[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    api
      .getTargetHistory(targetId, range)
      .then(setPoints)
      .catch(() => setPoints([]))
      .finally(() => setLoading(false));
  }, [targetId, range]);

  const stats = useMemo(() => {
    if (points.length === 0) return null;
    const vals = points.map((p) => p.followers_count);
    const min = Math.min(...vals);
    const max = Math.max(...vals);
    const first = vals[0];
    const last = vals[vals.length - 1];
    const delta = last - first;
    return { min, max, first, last, delta };
  }, [points]);

  const svgPath = useMemo(() => {
    if (points.length < 2 || !stats) return null;
    const w = 520;
    const h = 140;
    const padX = 8;
    const padY = 14;
    const span = Math.max(1, stats.max - stats.min);
    const coords = points.map((p, i) => {
      const x = padX + (i / (points.length - 1)) * (w - padX * 2);
      const y = h - padY - ((p.followers_count - stats.min) / span) * (h - padY * 2);
      return [x, y] as [number, number];
    });
    const line = coords.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
    const area = `${line} L${coords[coords.length - 1][0].toFixed(1)},${h - padY} L${coords[0][0].toFixed(1)},${h - padY} Z`;
    return { line, area, coords, w, h };
  }, [points, stats]);

  return (
    <Card className="space-y-3.5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-[var(--text-primary)]">{t.growth_chart_title}</span>
          {stats && (
            <span
              className={`inline-flex items-center gap-1 text-[11px] font-medium tabular-nums px-2 py-0.5 rounded-md ${
                stats.delta >= 0
                  ? 'bg-emerald-500/12 text-emerald-700 dark:text-emerald-400'
                  : 'bg-rose-500/12 text-rose-600 dark:text-rose-400'
              }`}
            >
              {stats.delta >= 0 ? <TrendingUp size={12} /> : <TrendingDown size={12} />}
              {stats.delta >= 0 ? `+${stats.delta.toLocaleString()}` : stats.delta.toLocaleString()}
            </span>
          )}
        </div>
        <div className="flex items-center gap-1">
          {(['24h', '7d', '30d', 'all'] as RangeKey[]).map((r) => (
            <button
              key={r}
              type="button"
              onClick={() => setRange(r)}
              className={`h-7 px-2.5 rounded-lg text-[11px] font-medium border transition-colors duration-150 ${
                range === r
                  ? 'bg-[#0C7FF2] text-white border-[#0C7FF2]'
                  : 'bg-[var(--bg-subtle)] text-[var(--text-secondary)] border-[var(--border-subtle)] hover:border-[var(--border-strong)]'
              }`}
            >
              {r.toUpperCase()}
            </button>
          ))}
        </div>
      </div>

      {loading ? (
        <div className="h-36 rounded-xl skeleton-box" />
      ) : points.length < 2 || !svgPath || !stats ? (
        <div className="h-28 rounded-xl bg-[var(--bg-subtle)] border border-[var(--border-subtle)] flex items-center justify-center text-xs text-[var(--text-muted)] px-4 text-center">
          {t.growth_chart_empty}
        </div>
      ) : (
        <>
          <div className="w-full overflow-hidden rounded-xl bg-[var(--bg-subtle)] border border-[var(--border-subtle)] p-2">
            <svg viewBox={`0 0 ${svgPath.w} ${svgPath.h}`} className="w-full h-36" preserveAspectRatio="none">
              <defs>
                <linearGradient id="growthGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#0C7FF2" stopOpacity="0.28" />
                  <stop offset="100%" stopColor="#0C7FF2" stopOpacity="0.0" />
                </linearGradient>
              </defs>
              <path d={svgPath.area} fill="url(#growthGrad)" />
              <path d={svgPath.line} fill="none" stroke="#0C7FF2" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </div>
          <div className="grid grid-cols-3 gap-2 text-center">
            <div className="rounded-lg bg-[var(--bg-subtle)] py-2 px-2.5 border border-[var(--border-subtle)]">
              <div className="text-[10px] text-[var(--text-muted)]">{t.growth_min}</div>
              <div className="text-xs font-semibold tabular-nums text-[var(--text-primary)]">{stats.min.toLocaleString()}</div>
            </div>
            <div className="rounded-lg bg-[var(--bg-subtle)] py-2 px-2.5 border border-[var(--border-subtle)]">
              <div className="text-[10px] text-[var(--text-muted)]">{t.growth_max}</div>
              <div className="text-xs font-semibold tabular-nums text-[var(--text-primary)]">{stats.max.toLocaleString()}</div>
            </div>
            <div className="rounded-lg bg-[var(--bg-subtle)] py-2 px-2.5 border border-[var(--border-subtle)]">
              <div className="text-[10px] text-[var(--text-muted)]">{t.growth_points}</div>
              <div className="text-xs font-semibold tabular-nums text-[var(--text-primary)]">{points.length}</div>
            </div>
          </div>
        </>
      )}
    </Card>
  );
};
