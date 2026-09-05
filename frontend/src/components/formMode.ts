/** Modos de preenchimento de um formulário de configuração (DEC-072). `visual` entra na passada B. */
export type FormMode = 'guided' | 'manual' | 'visual'

export const DEFAULT_FORM_MODES: FormMode[] = ['guided', 'manual']

export const FORM_MODE_LABELS: Record<FormMode, string> = {
  guided: 'Guiado',
  manual: 'Manual',
  visual: 'Visual',
}

function storageKeyFor(key: string): string {
  return `formMode:${key}`
}

/**
 * Lê a preferência de modo persistida em `localStorage` (`formMode:<key>`). Tolera `localStorage`
 * lançar (Safari em modo privado, storage desabilitado). Cai no primeiro modo disponível
 * (default `guided`) quando não há nada salvo ou o valor salvo não está entre os modos permitidos.
 */
export function readFormMode(key: string, modes: FormMode[] = DEFAULT_FORM_MODES): FormMode {
  const fallback = modes[0] ?? 'guided'
  try {
    const stored = window.localStorage.getItem(storageKeyFor(key))
    if (stored && (modes as string[]).includes(stored)) {
      return stored as FormMode
    }
  } catch {
    // storage indisponível — usa o fallback
  }
  return fallback
}

/** Persiste a preferência de modo. Silencioso se `localStorage` lançar (a escolha vale só na sessão). */
export function persistFormMode(key: string, mode: FormMode): void {
  try {
    window.localStorage.setItem(storageKeyFor(key), mode)
  } catch {
    // storage indisponível
  }
}
