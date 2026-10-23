import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  base: './',
  esbuild: {
    tsconfigRaw: JSON.stringify({
      compilerOptions: { target: 'ES2022', jsx: 'react-jsx', useDefineForClassFields: true }
    })
  },
  build: { target: 'chrome100', outDir: 'android/app/src/main/assets/public', emptyOutDir: true },
  server: { port: 5178, strictPort: true, fs: { allow: ['..'] } }
})
