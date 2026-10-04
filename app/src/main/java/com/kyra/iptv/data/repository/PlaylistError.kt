package com.kyra.iptv.data.repository

/** Erros esperados do repositório. As mensagens nunca incluem URLs (podem conter tokens). */
sealed class PlaylistError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidUrl : PlaylistError("URL inválida: use http:// ou https://")
    class InvalidName : PlaylistError("O nome da lista não pode ser vazio")
    class Empty : PlaylistError("Nenhum canal válido encontrado na lista")
    class TooLarge(val maxBytes: Long) : PlaylistError("Lista maior que o limite de ${maxBytes / (1024 * 1024)} MB")
    class TooManyChannels(val maxChannels: Int) :
        PlaylistError("Lista com mais de $maxChannels canais; use uma lista menor")
    class NotAChannelList :
        PlaylistError("Isto parece um stream HLS único, não uma lista de canais IPTV")
    class Timeout : PlaylistError("Tempo esgotado ao carregar a lista")
    class NotEnoughLists : PlaylistError("Escolha pelo menos duas listas para juntar")
    class NotFound : PlaylistError("Lista não encontrada")
    class NotRefreshable : PlaylistError("Só listas importadas por URL podem ser atualizadas")
    class Network(message: String, cause: Throwable? = null) : PlaylistError(message, cause)
    class Storage(cause: Throwable? = null) : PlaylistError("Falha ao ler ou gravar a lista no aparelho", cause)
}
