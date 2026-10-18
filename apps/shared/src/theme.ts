// IDEAVERSE palette tokens. The same names exist as CSS variables in theme.css (--fp-...).
export const palette = {
  velvetGreen: '#1E3B32',
  ivory: '#F4F2EC',
  sage: '#DCE5DD',
  ink: '#16211D',
  clay: '#B8663E',
} as const;

export interface Theme {
  name: 'light' | 'dark';
  background: string;
  surface: string;
  text: string;
  muted: string;
  accent: string;
  warning: string;
  border: string;
}

/** Operations, Cases and Alerts. */
export const lightTheme: Theme = {
  name: 'light',
  background: palette.ivory,
  surface: '#FFFFFF',
  text: palette.ink,
  muted: '#4A5A53',
  accent: palette.velvetGreen,
  warning: palette.clay,
  border: palette.sage,
};

/** Field (the 3D world at night). */
export const darkTheme: Theme = {
  name: 'dark',
  background: palette.ink,
  surface: palette.velvetGreen,
  text: palette.ivory,
  muted: '#B9C7BD',
  accent: palette.sage,
  warning: '#E08A5F',
  border: '#2E5246',
};

/** Smallest touch target (AGENTS.md: buttons at least 44 px tall on phones). */
export const MIN_TOUCH_PX = 44;
