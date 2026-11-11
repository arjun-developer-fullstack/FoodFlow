import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// The dev server proxies API calls to the API gateway, so the browser sees a single origin and
// the SameSite=Strict refresh-token cookie works without CORS during development.
const gateway = process.env.FOODFLOW_GATEWAY_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: gateway, changeOrigin: false },
      '/.well-known': { target: gateway, changeOrigin: false },
    },
  },
  build: {
    // Maps are generated for error monitoring uploads but not referenced from the shipped bundle.
    sourcemap: 'hidden',
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    restoreMocks: true,
  },
});
