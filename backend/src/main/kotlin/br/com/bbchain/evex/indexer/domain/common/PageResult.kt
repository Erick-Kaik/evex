package br.com.bbchain.evex.indexer.domain.common

/** Página de resultados, sem dependência de Spring Data — usada entre `application` e `adapters/inbound/api`. */
data class PageResult<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
) {
    val totalPages: Int
        get() = if (size == 0) 0 else ((totalElements + size - 1) / size).toInt()
}
