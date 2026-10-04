package com.kyra.iptv.data.repository

import com.kyra.iptv.data.model.Channel
import com.kyra.iptv.data.model.Playlist
import com.kyra.iptv.data.model.SourceType
import com.kyra.iptv.data.parser.M3uExporter
import com.kyra.iptv.data.parser.M3uParser
import com.kyra.iptv.data.parser.ParseResult
import com.kyra.iptv.data.parser.TextEncoding
import com.kyra.iptv.data.testing.ChannelSource
import com.kyra.iptv.data.testing.ListAnalysis
import com.kyra.iptv.data.testing.ListAnalyzer
import com.kyra.iptv.data.testing.ParserChannelSource
import com.kyra.iptv.data.testing.StreamTestConfig
import com.kyra.iptv.storage.LocalStorage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.UUID

/**
 * Gerencia as listas do usuário: importar (URL, arquivo, texto colado), persistir,
 * atualizar, renomear, excluir e carregar os canais.
 *
 * Todas as funções são bloqueantes (rede/disco): chame fora da thread principal.
 * Uma importação/atualização só altera o que está salvo se for bem-sucedida:
 * falhas (rede, lista vazia, limite de tamanho) deixam a lista anterior intacta.
 */
class PlaylistRepository(
    private val storage: LocalStorage,
    private val fetcher: ContentFetcher = HttpContentFetcher(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxChannels: Int = DEFAULT_MAX_CHANNELS,
    private val maxLoadMs: Long = DEFAULT_MAX_LOAD_MS,
) {
    private val lock = Any()

    fun getAll(): List<Playlist> = synchronized(lock) { storage.readPlaylists() }

    fun get(id: String): Playlist? = getAll().firstOrNull { it.id == id }

    // ---- importar -----------------------------------------------------------

    fun importFromUrl(url: String, name: String? = null): Playlist {
        val clean = validateUrl(url)
        val staged = stage(clean, baseUrl = clean) { PlaylistError.Network("Falha ao baixar a lista", it) }
        return add(staged, name.orBlankDefault(hostOf(clean)), clean, SourceType.URL)
    }

    /** [input] é fechado por esta função. [fileName] é só o nome de exibição do arquivo. */
    fun importFromFile(input: InputStream, fileName: String, name: String? = null): Playlist {
        val staged = stage(input, baseUrl = null) { PlaylistError.Storage(it) }
        val shown = fileName.trim()
        return add(staged, name.orBlankDefault(shown.substringBeforeLast('.').ifBlank { "Lista" }), shown, SourceType.FILE)
    }

    fun importFromText(text: String, name: String? = null): Playlist {
        val staged = stage(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), baseUrl = null) {
            PlaylistError.Storage(it)
        }
        return add(staged, name.orBlankDefault("Lista colada"), "", SourceType.PASTED)
    }

    /**
     * Salva [channels] (os que passaram no teste de streams) como uma lista nova, tipo [SourceType.TESTED].
     * A lista original não é alterada. Os headers e atributos de cada canal são preservados.
     */
    fun saveTested(channels: List<Channel>, name: String? = null): Playlist {
        if (channels.isEmpty()) throw PlaylistError.Empty()
        val file = try {
            storage.newTempFile()
        } catch (e: IOException) {
            throw PlaylistError.Storage(e)
        }
        try {
            val written = file.bufferedWriter(Charsets.UTF_8).use { M3uExporter.write(channels, it) }
            if (written == 0) throw PlaylistError.Empty()
            return add(Staged(file, written), name.orBlankDefault("Lista testada"), "", SourceType.TESTED)
        } catch (e: IOException) {
            file.delete()
            throw PlaylistError.Storage(e)
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    /** Resultado de [merge]: a lista criada, quantos canais entraram e quantas URLs repetidas foram descartadas. */
    class MergeResult(val playlist: Playlist, val merged: Int, val duplicates: Int)

    /**
     * Junta várias listas salvas em uma nova ([SourceType.MERGED]), na ordem de [ids]. URLs repetidas
     * (dentro de uma lista ou entre listas) entram uma vez só: a primeira ocorrência vence. Lê uma lista
     * por vez, em streaming; as originais não são alteradas. Respeita o limite de canais.
     */
    fun merge(ids: List<String>, name: String? = null): MergeResult {
        val distinct = ids.distinct()
        if (distinct.size < 2) throw PlaylistError.NotEnoughLists()
        val sources = distinct.map { get(it) ?: throw PlaylistError.NotFound() }
        val file = try {
            storage.newTempFile()
        } catch (e: IOException) {
            throw PlaylistError.Storage(e)
        }
        try {
            val seen = HashSet<String>()
            var written = 0
            var duplicates = 0
            file.bufferedWriter(Charsets.UTF_8).use { w ->
                M3uExporter.writeHeader(w)
                for (p in sources) {
                    val stats = storage.openContent(p.id).use { reader ->
                        M3uParser.scan(reader, baseUrlOf(p)) { ch ->
                            if (!seen.add(ch.id)) {
                                duplicates++
                            } else {
                                if (written >= maxChannels) throw PlaylistError.TooManyChannels(maxChannels)
                                if (M3uExporter.writeChannel(ch, w)) written++
                            }
                        }
                    }
                    duplicates += stats.duplicates
                }
            }
            if (written == 0) throw PlaylistError.Empty()
            val playlist = add(Staged(file, written), name.orBlankDefault("Listas juntas"), "", SourceType.MERGED)
            return MergeResult(playlist, written, duplicates)
        } catch (e: IOException) {
            file.delete()
            throw PlaylistError.Storage(e)
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    // ---- gerenciar ----------------------------------------------------------

    /** Baixa de novo a URL de origem e substitui o conteúdo salvo. Só para listas URL. */
    fun refresh(id: String): Playlist {
        val current = get(id) ?: throw PlaylistError.NotFound()
        if (current.sourceType != SourceType.URL) throw PlaylistError.NotRefreshable()
        val staged = stage(current.source, baseUrl = current.source) {
            PlaylistError.Network("Falha ao baixar a lista", it)
        }
        synchronized(lock) {
            val all = storage.readPlaylists()
            val existing = all.firstOrNull { it.id == id }
            if (existing == null) { // excluída durante o download
                staged.file.delete()
                throw PlaylistError.NotFound()
            }
            try {
                storage.commitContent(staged.file, id)
            } catch (e: IOException) {
                staged.file.delete()
                throw PlaylistError.Storage(e)
            }
            val updated = existing.copy(updatedAt = clock(), channelCount = staged.channelCount)
            storage.writePlaylists(all.map { if (it.id == id) updated else it })
            return updated
        }
    }

    fun rename(id: String, newName: String): Playlist {
        val name = newName.trim()
        if (name.isEmpty()) throw PlaylistError.InvalidName()
        synchronized(lock) {
            val all = storage.readPlaylists()
            val existing = all.firstOrNull { it.id == id } ?: throw PlaylistError.NotFound()
            val updated = existing.copy(name = name)
            storage.writePlaylists(all.map { if (it.id == id) updated else it })
            return updated
        }
    }

    fun delete(id: String) {
        synchronized(lock) {
            val all = storage.readPlaylists()
            if (all.none { it.id == id }) throw PlaylistError.NotFound()
            storage.writePlaylists(all.filterNot { it.id == id })
            storage.deleteContent(id)
        }
    }

    /** Lê e interpreta o conteúdo salvo da lista (URLs relativas usam a URL de origem como base). */
    fun loadChannels(id: String): ParseResult {
        val playlist = get(id) ?: throw PlaylistError.NotFound()
        try {
            return storage.openContent(id).use { M3uParser.parse(it, baseUrlOf(playlist)) }
        } catch (e: IOException) {
            throw PlaylistError.Storage(e)
        }
    }

    /** Números da lista salva (entradas, únicas, repetidas, inválidas, grupos), lidos em streaming. */
    fun analyze(id: String): ListAnalysis {
        val playlist = get(id) ?: throw PlaylistError.NotFound()
        try {
            return ListAnalyzer.analyze(storage.openContent(id), baseUrlOf(playlist))
        } catch (e: IOException) {
            throw PlaylistError.Storage(e)
        }
    }

    /**
     * Fonte que entrega os canais da lista salva um a um, para o teste de streams (a lista nunca fica
     * inteira na memória). Quem recebe deve fechá-la ([ChannelSource.close]); o motor de teste faz isso.
     */
    fun openChannelSource(id: String, queueSize: Int = StreamTestConfig.DEFAULT_FEEDER_QUEUE_SIZE): ChannelSource {
        val playlist = get(id) ?: throw PlaylistError.NotFound()
        if (!storage.contentFile(id).exists()) throw PlaylistError.Storage(IOException("conteúdo ausente"))
        return ParserChannelSource({ storage.openContent(id) }, baseUrlOf(playlist), queueSize)
    }

    private fun baseUrlOf(playlist: Playlist): String? =
        if (playlist.sourceType == SourceType.URL) playlist.source else null

    // ---- internos -----------------------------------------------------------

    private class Staged(val file: File, val channelCount: Int)

    private fun stage(url: String, baseUrl: String?, ioError: (IOException) -> PlaylistError): Staged {
        val input = try {
            fetcher.open(url)
        } catch (e: IOException) {
            throw ioError(e)
        }
        return stage(input, baseUrl, ioError)
    }

    /**
     * Copia (com limites de tamanho e de tempo) para um temporário, normaliza para UTF-8, valida
     * que há canais — sem guardar a lista na memória — e devolve o temporário.
     */
    private fun stage(input: InputStream, baseUrl: String?, ioError: (IOException) -> PlaylistError): Staged {
        val startedAt = clock()
        var file = try {
            storage.newTempFile()
        } catch (e: IOException) {
            input.close()
            throw PlaylistError.Storage(e)
        }
        try {
            input.use { copyLimited(it, file, startedAt) }
            file = normalizeEncoding(file)
            var count = 0
            val stats = file.reader(Charsets.UTF_8).use { reader ->
                M3uParser.scan(reader, baseUrl) {
                    count++
                    if (count > maxChannels) throw PlaylistError.TooManyChannels(maxChannels)
                }
            }
            if (stats.looksLikeHlsStream) throw PlaylistError.NotAChannelList()
            if (stats.count == 0) throw PlaylistError.Empty()
            return Staged(file, stats.count)
        } catch (e: IOException) {
            file.delete()
            throw ioError(e)
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    /** Converte UTF-16/Windows-1252 para UTF-8 (o resto do app só lê UTF-8). Devolve o arquivo a usar. */
    private fun normalizeEncoding(file: File): File {
        val converted = storage.newTempFile()
        try {
            if (TextEncoding.transcodeToUtf8IfNeeded(file, converted)) {
                file.delete()
                return converted
            }
            converted.delete()
            return file
        } catch (t: Throwable) {
            converted.delete()
            throw t
        }
    }

    private fun copyLimited(input: InputStream, dest: File, startedAt: Long) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        dest.outputStream().buffered().use { out ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw PlaylistError.TooLarge(maxBytes)
                // Cada leitura tem timeout próprio; este limite cobre o download inteiro (servidor "conta-gotas").
                if (clock() - startedAt > maxLoadMs) throw PlaylistError.Timeout()
                out.write(buf, 0, n)
            }
        }
    }

    private fun add(staged: Staged, name: String, source: String, type: SourceType): Playlist {
        val playlist = Playlist(
            id = UUID.randomUUID().toString(),
            name = name,
            source = source,
            sourceType = type,
            updatedAt = clock(),
            channelCount = staged.channelCount,
        )
        synchronized(lock) {
            try {
                storage.commitContent(staged.file, playlist.id)
                storage.writePlaylists(storage.readPlaylists() + playlist)
            } catch (e: IOException) {
                staged.file.delete()
                storage.deleteContent(playlist.id)
                throw PlaylistError.Storage(e)
            }
        }
        return playlist
    }

    private fun validateUrl(raw: String): String {
        val url = raw.trim()
        val uri = runCatching { URI(url) }.getOrNull() ?: throw PlaylistError.InvalidUrl()
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrEmpty()) throw PlaylistError.InvalidUrl()
        return url
    }

    private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { "Lista" }

    private fun String?.orBlankDefault(default: String): String = this?.trim()?.takeIf { it.isNotEmpty() } ?: default

    companion object {
        /** Limite de tamanho de uma lista (proteção contra downloads absurdos). */
        const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024

        /**
         * Limite de canais por lista. Cada canal ocupa algumas centenas de bytes na memória (catálogo
         * inteiro carregado ao abrir a lista); o valor deve ser validado em aparelho de pouca RAM (PLANO.md §Fase 7).
         */
        const val DEFAULT_MAX_CHANNELS = 150_000

        /** Tempo máximo para baixar/ler uma lista inteira. */
        const val DEFAULT_MAX_LOAD_MS = 5 * 60_000L
    }
}
