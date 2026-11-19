import { fileURLToPath } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, fileURLToPath(new URL('..', import.meta.url)), 'LABREVIEW_')
  return {
    plugins: [react()],
    server: {
      host: '127.0.0.1',
      port: Number(env.LABREVIEW_WEB_PORT ?? 5173),
      strictPort: true,
      proxy: {
        '/api': `http://127.0.0.1:${env.LABREVIEW_API_PORT ?? 8080}`,
      },
    },
  }
})
