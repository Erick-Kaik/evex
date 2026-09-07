import type { AbiEntryParam } from '../../api/api'

/**
 * Renderiza `inputs`/`outputs` de um item de ABI (E24) como texto compacto — `nome: tipo` por
 * parâmetro (ou só `tipo` quando o parâmetro não tem nome), separados por vírgula; parâmetro
 * `indexed` (só inputs de evento) ganha o sufixo ` (indexed)`. Lista vazia vira `—`.
 *
 * Em arquivo próprio (não dentro de `AbiEntriesPage.tsx`) por causa da regra do `react-refresh` do
 * ESLint: um módulo que exporta um componente React só pode exportar componentes, senão o fast
 * refresh não funciona corretamente.
 */
export function formatAbiEntryParams(params: AbiEntryParam[]): string {
  if (params.length === 0) {
    return '—'
  }
  return params
    .map((param) => {
      const base = param.name ? `${param.name}: ${param.type}` : param.type
      return param.indexed ? `${base} (indexed)` : base
    })
    .join(', ')
}
