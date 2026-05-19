import { defineConfig } from 'vite';

export default defineConfig({
  root: '.',
  build: {
    // Bundled into the Worker on deploy (server/public) for one-click Deploy to Cloudflare.
    outDir: '../server/public',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/relay': {
        target: 'http://127.0.0.1:8787',
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/relay/, ''),
        ws: true,
      },
    },
  },
});
