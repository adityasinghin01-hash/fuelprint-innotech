// Vite settings every FuelPrint app shares: served under /<app>/, dev server on 5173, and /api plus /ws proxied to
// the Java server (FP_SERVER, default http://localhost:8080). The proxy rewrites Origin to the server's own address,
// so the server's same-origin check passes in development without opening it to other sites.
import react from '@vitejs/plugin-react';
import type { UserConfig } from 'vite';

export function fpViteConfig(app: string): UserConfig {
  const target = process.env.FP_SERVER ?? 'http://localhost:8080';
  const origin = new URL(target).origin;
  return {
    base: `/${app}/`,
    plugins: [react()],
    server: {
      port: 5173,
      strictPort: true,
      host: true,
      proxy: {
        '/api': {
          target,
          changeOrigin: true,
          configure: (proxy) => {
            proxy.on('proxyReq', (req) => req.setHeader('origin', origin));
          },
        },
        '/ws': {
          target,
          ws: true,
          changeOrigin: true,
          configure: (proxy) => {
            proxy.on('proxyReqWs', (req) => req.setHeader('origin', origin));
          },
        },
      },
    },
    build: { outDir: 'dist', emptyOutDir: true },
  };
}
