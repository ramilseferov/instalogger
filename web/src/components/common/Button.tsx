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
    'inline-flex items-center justify-center font-medium rounded-full transition-all duration-200 ease-out active:scale-[0.96] disabled:opacity-50 disabled:pointer-events-none select-none';

  const sizeStyles = {
    sm: 'text-xs px-3.5 py-1.5 gap-1.5 shadow-sm hover:shadow',
    md: 'text-sm px-5 py-2.5 gap-2 shadow-sm hover:shadow-md hover:-translate-y-0.5',
    lg: 'text-base px-6 py-3.5 gap-2.5 shadow hover:shadow-lg hover:-translate-y-0.5',
  };

  const variantStyles = {
    primary: 'bg-[#0C7FF2] text-white hover:bg-[#006CE6] active:bg-[#005bb5]',
    secondary: 'bg-[#F2F4F7] text-gray-800 hover:bg-[#E5E8EC] hover:text-gray-900',
    danger: 'bg-rose-50 text-rose-600 hover:bg-rose-100 active:bg-rose-200',
    ghost: 'bg-transparent text-gray-600 hover:bg-gray-100 hover:text-gray-900',
  };

  return (
    <button
      className={`${baseStyles} ${sizeStyles[size]} ${variantStyles[variant]} ${className}`}
      {...props}
    >
      {icon && <span className="flex items-center transition-transform duration-200 group-hover:scale-110">{icon}</span>}
      {children}
    </button>
  );
};
