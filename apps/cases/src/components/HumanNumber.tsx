import { useId } from 'react';

// Validate transport fidelity only. All scientific arithmetic and QC stay on the server.
function decimalKey(text: string): string | null {
  const match = /^([+-]?)(?:(\d+)(?:\.(\d*))?|\.(\d+))(?:[eE]([+-]?\d+))?$/.exec(text);
  if (!match) return null;
  const fraction = match[3] ?? match[4] ?? '';
  const coefficient = ((match[2] ?? '0') + fraction).replace(/^0+/, '');
  if (!coefficient) return '0';
  const digits = coefficient.replace(/0+$/, '');
  const exponent = Number(match[5] ?? 0) - fraction.length + coefficient.length - digits.length;
  return `${match[1] === '-' ? '-' : ''}${digits}e${exponent}`;
}

export function humanNumber(text: string): {value: number | null; error: string | null} {
  const trimmed = text.trim();
  if (!trimmed) return {value: null, error: null};
  const value = Number(trimmed);
  const key = trimmed.length <= 256 ? decimalKey(trimmed) : null;
  if (key === null || !Number.isFinite(value) || key !== decimalKey(JSON.stringify(value))) {
    return {value: null, error: 'This screen cannot send this decimal unchanged. Enter a finite decimal with explicitly chosen precision.'};
  }
  return {value, error: null};
}

export function HumanNumber({label, value, onChange}: {label: string; value: string; onChange(value: string): void}) {
  const id = useId();
  const {error} = humanNumber(value);
  return <div><label htmlFor={id}>{label}</label>
    <input id={id} inputMode="decimal" value={value} onChange={e => onChange(e.target.value)}
      aria-invalid={Boolean(error)} aria-describedby={error ? `${id}-error` : undefined}/>
    {error ? <p id={`${id}-error`} role="alert">{error}</p> : null}
  </div>;
}
