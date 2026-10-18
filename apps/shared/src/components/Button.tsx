import type { ButtonHTMLAttributes } from 'react';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  /** Shows "Sending..." and blocks clicks while a command is on its way. */
  pending?: boolean;
  variant?: 'primary' | 'secondary';
}

/** A button at least 44 px tall that works with the keyboard; disabled while pending. */
export function Button({ pending = false, variant = 'primary', disabled, children, type = 'button', ...rest }: ButtonProps) {
  return (
    <button {...rest} type={type} className={`fp-button ${rest.className ?? ''}`.trim()} data-variant={variant}
      disabled={disabled || pending} aria-busy={pending || undefined}>
      {pending ? 'Sending...' : children}
    </button>
  );
}
