import React from 'react';

interface StatusBadgeProps {
  status: 'public' | 'private' | 'turbo' | 'inactive';
  className?: string;
}

export const StatusBadge: React.FC<StatusBadgeProps> = ({ status, className = '' }) => {
  if (status === 'public') {
    return (
      <span
        className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-semibold bg-[#E8F5E9] text-[#2E7D32] ${className}`}
      >
        <span className="w-1.5 h-1.5 rounded-full bg-[#2E7D32]"></span>
        Açıq (Public)
      </span>
    );
  }

  if (status === 'private') {
    return (
      <span
        className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-semibold bg-[#FFF3E0] text-[#E65100] ${className}`}
      >
        <span className="w-1.5 h-1.5 rounded-full bg-[#E65100]"></span>
        Gizli (Private)
      </span>
    );
  }

  if (status === 'turbo') {
    return (
      <span
        className={`inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[11px] font-bold bg-[#E3F2FD] text-[#0C7FF2] ${className}`}
      >
        ⚡ Turbo
      </span>
    );
  }

  return (
    <span
      className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-medium bg-[#ECEFF1] text-[#546E7A] ${className}`}
    >
      Dayandırılıb
    </span>
  );
};
