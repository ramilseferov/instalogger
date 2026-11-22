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
      className={`bg-white rounded-3xl p-5 border border-[#E5E8EC] shadow-[0_2px_12px_rgba(0,0,0,0.03)] transition-all duration-200 ease-out animate-fade-up ${staggerClass} ${
        onClick
          ? 'cursor-pointer hover:border-gray-300 hover:shadow-[0_6px_20px_rgba(0,0,0,0.06)] hover:-translate-y-0.5 active:scale-[0.985] active:shadow-sm'
          : ''
      } ${className}`}
    >
      {children}
    </div>
  );
};
