import React from 'react';

interface CardProps {
  children: React.ReactNode;
  className?: string;
  onClick?: () => void;
  index?: number;
}

export const Card: React.FC<CardProps> = ({ children, className = '', onClick, index }) => {
  const staggerClass = index !== undefined ? `stagger-${Math.min(index + 1, 5)}` : '';

  return (
    <div
      onClick={onClick}
      className={`bg-white dark:bg-slate-900 rounded-2xl p-5 border border-slate-200 dark:border-slate-800 shadow-[0_1px_3px_rgba(0,0,0,0.04)] transition-all duration-150 ${staggerClass} ${
        onClick
          ? 'cursor-pointer hover:border-slate-300 dark:hover:border-slate-700 hover:shadow-md active:scale-[0.99]'
          : ''
      } ${className}`}
    >
      {children}
    </div>
  );
};
