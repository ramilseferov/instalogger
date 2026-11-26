import React from 'react';

interface CardProps {
  children: React.ReactNode;
  className?: string;
  onClick?: () => void;
  index?: number;
}

export const Card: React.FC<CardProps> = ({ children, className = '', onClick, index }) => {
  const staggerClass = index !== undefined ? `animate-card-enter stagger-${Math.min(index + 1, 5)}` : '';

  return (
    <div
      onClick={onClick}
      className={`bg-[var(--bg-surface)] rounded-2xl p-5 border border-[var(--border-subtle)] transition-[border-color,background-color,transform] duration-150 ease-out ${staggerClass} ${
        onClick
          ? 'cursor-pointer hover:border-[var(--border-strong)] active:scale-[0.99]'
          : ''
      } ${className}`}
    >
      {children}
    </div>
  );
};
