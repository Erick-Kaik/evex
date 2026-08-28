import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Conveniência opcional de dev, NÃO crítica: desde a E8.1 o backend já expõe CORS de verdade
    // via Spring Security (indexer.cors.allowed-origins, default http://localhost:5173 — a porta
    // padrão deste `npm run dev`). O cliente gerado (src/api/config.ts) já aponta direto para
    // http://localhost:8080 por padrão e funciona cross-origin sem depender deste proxy. Mantido só
    // para quem preferir usar caminhos relativos (/v1/**) em vez de configurar VITE_API_BASE_URL.
    proxy: {
      '/v1': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/actuator': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
