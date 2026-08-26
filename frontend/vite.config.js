import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // The frontend never talks to the database, and it never talks to a second origin either:
    // every request goes to /api and the dev server proxies it to Spring Boot, so there is no
    // CORS configuration to get wrong and no API base URL baked into the bundle.
    proxy: {
      '/api': {
        // 127.0.0.1, not localhost: Node resolves "localhost" to ::1 first, and the Spring Boot
        // dev server does not necessarily listen on IPv6 -- which shows up as the proxy hanging
        // until it times out rather than as a connection error.
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
})
