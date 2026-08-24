import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Everything the React app needs from the platform backend during dev.
      // In production this app is built and its output can be copied into
      // src/main/resources/static so Spring Boot serves it directly — these
      // proxy entries are only used by `npm run dev`.
      '/api': { target: 'http://localhost:8090', ws: true }, // ws:true — the terminal is a WebSocket under /api
      '/auth': 'http://localhost:8090',
      '/p': 'http://localhost:8090',
      '/explorer.html': 'http://localhost:8090',
    },
  },
})
