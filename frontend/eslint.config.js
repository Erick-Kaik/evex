import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  // src/api: cliente gerado pelo openapi-generator (regra do frontend/CLAUDE.md — nunca editado à
  // mão, nunca commitado). openapi/: bundle intermediário do Redocly. Nenhum dos dois deve ser lintado.
  globalIgnores(['dist', 'src/api', 'openapi']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
  },
])
