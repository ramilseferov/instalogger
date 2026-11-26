import React from 'react';

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost';
  size?: 'sm' | 'md' | 'lg';
  icon?: React.ReactNode;
}

export const Button: React.FC<ButtonProps> = ({
  children,
  variant = 'primary',
  size = 'md',
  icon,
  className = '',
  ...props
}) => {
  const baseStyles =
    'inline-flex items-center justify-center font-medium rounded-xl transition-[background-color,border-color,color,transform,opacity] duration-150 ease-out active:scale-[0.97] disabled:opacity-45 disabled:pointer-events-none select-none focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#0C7FF2]/50';

  const sizeStyles = {
    sm: 'text-xs px-3.5 h-9 gap-1.5',
    md: 'text-sm px-4 h-10 gap-2',
    lg: 'text-sm px-5 h-11 gap-2',
  };

  const variantStyles = {
    primary:
      'bg-[#0C7FF2] text-white hover:bg-[#006CE6] active:bg-[#005BB5] border border-transparent',
    secondary:
      'bg-[var(--bg-surface)] text-[var(--text-primary)] hover:bg-[var(--bg-subtle)] border border-[var(--border-strong)]',
    danger:
      'bg-rose-500/10 text-rose-600 dark:text-rose-400 hover:bg-rose-500/15 border border-rose-500/25',
    ghost:
      'bg-transparent text-[var(--text-secondary)] hover:bg-[var(--bg-subtle)] hover:text-[var(--text-primary)] border border-transparent',
  };

  return (
    <button
      className={`${baseStyles} ${sizeStyles[size]} ${variantStyles[variant]} ${className}`}
      {...props}
    >
      {icon && <span className="flex items-center shrink-0">{icon}</span>}
      <span className="truncate">{children}</span>
    </button>
  );
};
