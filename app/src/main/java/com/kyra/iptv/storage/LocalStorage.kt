package com.kyra.iptv.storage

import com.kyra.iptv.data.model.Playlist
import com.kyra.iptv.data.model.SourceType
import java.io.File
import java.io.IOException
import java.io.Reader

/**
 * Armazenamento local simples, sem dependências (a decisão sobre Room/SQLite fica para
 * quando houver necessidade medida, ver PLANO.md §10):
 *
 * - `playlists.tsv`: índice (um registro por linha, campos separados por TAB, com escape);
 * - `content/<id>.m3u`: o M3U original de cada lista, gravado em streaming.
 *
 * Escritas do índice e do conteúdo são feitas em arquivo temporário + rename.
 * Não depende de Android: recebe o diretório raiz (ex.: `File(filesDir, "iptv")`).
 */
class LocalStorage(private val root: File) {
    private val indexFile = File(root, "playlists.tsv")
    private val contentDir = File(root, "content")

    init {
        contentDir.mkdirs()
        contentDir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() } // sobras de importações interrompidas
    }

    fun readPlaylists(): List<Playlist> {
        if (!indexFile.exists()) return emptyList()
        val out = ArrayList<Playlist>()
        indexFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                parseLine(line)?.let { out += it } // linha corrompida é ignorada
            }
        }
        return out
    }

    fun writePlaylists(playlists: List<Playlist>) {
        val tmp = File(root, "playlists.tsv.tmp")
        tmp.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("#iptv-index v1\n")
            for (p in playlists) {
                w.write(
                    listOf(p.id, p.name, p.source, p.sourceType.name, p.updatedAt.toString(), p.channelCount.toString())
                        .joinToString("\t") { escape(it) }
                )
                w.write("\n")
            }
        }
        replace(tmp, indexFile)
    }

    /** Arquivo temporário no mesmo diretório do conteúdo (garante rename no mesmo volume). */
    fun newTempFile(): File = File.createTempFile("import", ".tmp", contentDir)

    fun commitContent(tmp: File, id: String) = replace(tmp, contentFile(id))

    fun openContent(id: String): Reader {
        val f = contentFile(id)
        if (!f.exists()) throw IOException("conteúdo ausente")
        return f.reader(Charsets.UTF_8)
    }

    fun deleteContent(id: String) {
        contentFile(id).delete()
    }

    fun contentFile(id: String): File {
        require(ID_PATTERN.matches(id)) { "id inválido" } // evita path traversal
        return File(contentDir, "$id.m3u")
    }


    /** Lê um arquivo de texto simples de [root] (uma entrada por linha, sem linhas vazias). */
    fun readLines(name: String): List<String> {
        val f = namedFile(name)
        if (!f.exists()) return emptyList()
        return f.bufferedReader(Charsets.UTF_8).useLines { seq -> seq.map { it.trim() }.filter { it.isNotEmpty() }.toList() }
    }

    fun writeLines(name: String, lines: List<String>) {
        val dest = namedFile(name)
        val tmp = File(root, "$name.tmp")
        tmp.bufferedWriter(Charsets.UTF_8).use { w -> lines.forEach { w.write(it); w.write("\n") } }
        replace(tmp, dest)
    }

    private fun namedFile(name: String): File {
        require(NAME_PATTERN.matches(name)) { "nome inválido" }
        return File(root, name)
    }

    // ---- helpers ------------------------------------------------------------

    private fun replace(tmp: File, dest: File) {
        if (tmp.renameTo(dest)) return
        dest.delete() // alguns sistemas (ex.: Windows) não sobrescrevem no rename
        if (!tmp.renameTo(dest)) throw IOException("não foi possível gravar o arquivo")
    }

    private fun parseLine(line: String): Playlist? = runCatching {
        val f = splitTabs(line).map { unescape(it) }
        if (f.size < 6 || !ID_PATTERN.matches(f[0])) return null
        Playlist(
            id = f[0], name = f[1], source = f[2],
            sourceType = SourceType.valueOf(f[3]),
            updatedAt = f[4].toLong(),
            channelCount = f[5].toInt(),
        )
    }.getOrNull()

    private fun splitTabs(s: String): List<String> = s.split('\t')

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
    }

    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    private companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9-]{1,64}")
        val NAME_PATTERN = Regex("[a-z_]{1,32}\\.txt")
    }
}
