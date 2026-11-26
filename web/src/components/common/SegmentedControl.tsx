import React from 'react';

export interface SegmentedOption<T extends string> {
  value: T;
  label: string;
  count?: number;
  icon?: React.ReactNode;
}

interface SegmentedControlProps<T extends string> {
  options: SegmentedOption<T>[];
  value: T;
  onChange: (next: T) => void;
  className?: string;
}

export function SegmentedControl<T extends string>({
  options,
  value,
  onChange,
  className = '',
}: SegmentedControlProps<T>) {
  const activeIdx = Math.max(
    0,
    options.findIndex((o) => o.value === value)
  );
  const count = Math.max(1, options.length);

  return (
    <div
      role="tablist"
      className={`relative grid p-1 rounded-xl bg-[var(--bg-subtle)] border border-[var(--border-subtle)] select-none ${className}`}
      style={{ gridTemplateColumns: `repeat(${count}, minmax(0, 1fr))` }}
    >
      <div
        aria-hidden="true"
        className="absolute top-1 bottom-1 left-1 rounded-lg bg-[var(--bg-surface)] border border-[var(--border-subtle)] shadow-[0_1px_2px_rgba(0,0,0,0.06)] transition-transform duration-200 ease-[cubic-bezier(0.22,1,0.36,1)] pointer-events-none"
        style={{
          width: `calc((100% - 8px) / ${count})`,
          transform: `translate3d(${activeIdx * 100}%, 0, 0)`,
        }}
      />
      {options.map((opt) => {
        const selected = opt.value === value;
        return (
          <button
            key={opt.value}
            type="button"
            role="tab"
            aria-selected={selected}
            onClick={() => onChange(opt.value)}
            className={`relative z-10 flex items-center justify-center gap-1.5 h-8 px-2.5 rounded-lg text-xs font-medium transition-colors duration-150 ${
              selected
                ? 'text-[var(--text-primary)]'
                : 'text-[var(--text-muted)] hover:text-[var(--text-secondary)]'
            }`}
          >
            {opt.icon && <span className="shrink-0">{opt.icon}</span>}
            <span className="truncate">{opt.label}</span>
            {typeof opt.count === 'number' && (
              <span className="tabular-nums text-[11px] opacity-75">({opt.count})</span>
            )}
          </button>
        );
      })}
    </div>
  );
}
