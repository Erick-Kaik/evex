/**
 * Modo de uma página de formulário CRUD padronizada (E18): `view` (campos só leitura), `edit`
 * (campos editáveis sobre um recurso existente) ou `create` (campos editáveis, recurso novo).
 *
 * Não confundir com `FormMode` (`guided`/`visual`/`manual`, DEC-072, em `components/formMode.ts`) —
 * são dimensões ortogonais: `PageMode` resolve "a página deixa editar ou só mostra" (rota/ação),
 * `FormMode` resolve "como o usuário preenche os campos editáveis" (estilo de preenchimento).
 */
export type PageMode = 'view' | 'edit' | 'create'
