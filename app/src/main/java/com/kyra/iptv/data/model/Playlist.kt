package com.kyra.iptv.data.model

/** TESTED: lista gerada por "Salvar funcionais" depois de testar os streams de outra lista. */
enum class SourceType { URL, FILE, PASTED, TESTED }

/** Lista fornecida pelo usuário. O app não traz conteúdo IPTV próprio. */
data class Playlist(
    val id: String,
    val name: String,
    /**
     * Origem da lista, conforme [sourceType]:
     * URL http/https (URL), nome do arquivo escolhido (FILE) ou vazio (PASTED, TESTED).
     * O conteúdo em si fica salvo localmente, não neste campo.
     */
    val source: String,
    val sourceType: SourceType,
    val updatedAt: Long,
    /** Quantidade de canais válidos na última importação/atualização. */
    val channelCount: Int = 0,
)
