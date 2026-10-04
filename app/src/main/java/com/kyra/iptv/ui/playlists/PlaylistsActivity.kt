package com.kyra.iptv.ui.playlists

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.kyra.iptv.IptvApp
import com.kyra.iptv.data.model.Playlist
import com.kyra.iptv.data.model.SourceType
import com.kyra.iptv.data.repository.PlaylistError
import com.kyra.iptv.ui.Background
import com.kyra.iptv.ui.COLOR_MUTED
import com.kyra.iptv.ui.COLOR_TEXT
import com.kyra.iptv.ui.applySystemBarsPadding
import com.kyra.iptv.ui.channels.ChannelsActivity
import com.kyra.iptv.ui.dp
import com.kyra.iptv.ui.testing.TestListActivity
import com.kyra.iptv.ui.userMessage

/** Tela 1 — Listas: adicionar (URL, arquivo, colar), abrir, atualizar, renomear e excluir. */
class PlaylistsActivity : Activity() {

    private val app get() = application as IptvApp
    private var items: List<Playlist> = emptyList()
    private var busy = false

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var addButton: Button

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@PlaylistsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(TextView(context).apply { textSize = 17f; setTextColor(COLOR_TEXT); maxLines = 1 })
                addView(TextView(context).apply { textSize = 13f; setTextColor(COLOR_MUTED) })
            }
            val p = items[position]
            (row.getChildAt(0) as TextView).text = p.name
            (row.getChildAt(1) as TextView).text = "${p.channelCount} canais · ${sourceLabel(p)}"
            return row
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.CENTER_VERTICAL)
            setPadding(dp(16), dp(8), dp(8), dp(8))
        }
        header.addView(TextView(this).apply {
            text = "IPTV"; textSize = 24f; setTextColor(COLOR_TEXT)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addButton = Button(this).apply {
            text = "+ Adicionar lista"
            setOnClickListener { showAddMenu() }
        }
        header.addView(addButton)
        root.addView(header)

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        emptyText = TextView(this).apply {
            text = "Nenhuma lista ainda.\nToque em “+ Adicionar lista” e informe uma URL, um arquivo M3U/M3U8 ou cole o conteúdo."
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setPadding(dp(24), dp(48), dp(24), dp(24))
            visibility = View.GONE
        }
        root.addView(emptyText)

        listView = ListView(this).apply {
            adapter = this@PlaylistsActivity.adapter
            setOnItemClickListener { _, _, position, _ -> openPlaylist(items[position]) }
            setOnItemLongClickListener { _, _, position, _ -> showActions(items[position]); true }
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.applySystemBarsPadding()
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    // ---- lista --------------------------------------------------------------

    private fun reload() {
        Background.run({ app.playlists.getAll() }) { result ->
            if (isFinishing || isDestroyed) return@run
            items = result.getOrDefault(emptyList())
            adapter.notifyDataSetChanged()
            emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            listView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun sourceLabel(p: Playlist) = when (p.sourceType) {
        SourceType.URL -> "URL"
        SourceType.FILE -> "arquivo"
        SourceType.PASTED -> "colada"
        SourceType.TESTED -> "testada"
    }

    private fun openPlaylist(p: Playlist) {
        startActivity(Intent(this, ChannelsActivity::class.java).putExtra(ChannelsActivity.EXTRA_PLAYLIST_ID, p.id))
    }

    // ---- adicionar ----------------------------------------------------------

    private fun showAddMenu() {
        if (busy) return
        AlertDialog.Builder(this)
            .setTitle("Adicionar lista")
            .setItems(arrayOf("Por URL", "Arquivo M3U/M3U8", "Colar conteúdo")) { _, which ->
                when (which) {
                    0 -> askUrl()
                    1 -> pickFile()
                    2 -> askPaste()
                }
            }
            .show()
    }

    private fun field(hint: String, multiline: Boolean = false) = EditText(this).apply {
        this.hint = hint
        if (multiline) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = Gravity.TOP
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
    }

    private fun form(vararg fields: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
        fields.forEach { addView(it) }
    }

    private fun askUrl() {
        val url = field("http://… ou https://…")
        val name = field("Nome (opcional)").apply { inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this)
            .setTitle("Adicionar por URL")
            .setView(form(url, name))
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Importar") { _, _ ->
                val u = url.text.toString()
                val n = name.text.toString()
                runWork("Importando…", { app.playlists.importFromUrl(u, n) }) { "Lista adicionada: ${it.name} (${it.channelCount} canais)" }
            }
            .show()
    }

    private fun askPaste() {
        val content = field("Cole aqui o conteúdo M3U", multiline = true).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        val name = field("Nome (opcional)").apply { inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this)
            .setTitle("Colar conteúdo")
            .setView(form(content, name))
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Importar") { _, _ ->
                val c = content.text.toString()
                val n = name.text.toString()
                runWork("Importando…", { app.playlists.importFromText(c, n) }) { "Lista adicionada: ${it.name} (${it.channelCount} canais)" }
            }
            .show()
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        try {
            startActivityForResult(intent, REQ_PICK_FILE)
        } catch (e: Exception) {
            toast("Nenhum seletor de arquivos disponível")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val fileName = displayName(uri)
        runWork("Importando…", {
            val input = contentResolver.openInputStream(uri) ?: throw PlaylistError.Storage()
            app.playlists.importFromFile(input, fileName)
        }) { "Lista adicionada: ${it.name} (${it.channelCount} canais)" }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Lista"
    }

    // ---- ações em uma lista -------------------------------------------------

    private fun showActions(p: Playlist) {
        if (busy) return
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (p.sourceType == SourceType.URL) {
            labels += "Atualizar"
            actions += { runWork("Atualizando…", { app.playlists.refresh(p.id) }) { "Atualizada: ${it.channelCount} canais" } }
        }
        labels += "Testar streams"; actions += { openTest(p) }
        labels += "Renomear"; actions += { askRename(p) }
        labels += "Excluir"; actions += { confirmDelete(p) }
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun openTest(p: Playlist) {
        startActivity(Intent(this, TestListActivity::class.java).putExtra(TestListActivity.EXTRA_PLAYLIST_ID, p.id))
    }

    private fun askRename(p: Playlist) {
        val name = field("Nome").apply { inputType = InputType.TYPE_CLASS_TEXT; setText(p.name); setSelection(p.name.length) }
        AlertDialog.Builder(this)
            .setTitle("Renomear lista")
            .setView(form(name))
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val n = name.text.toString()
                runWork(null, { app.playlists.rename(p.id, n) }) { "Renomeada" }
            }
            .show()
    }

    private fun confirmDelete(p: Playlist) {
        AlertDialog.Builder(this)
            .setTitle("Excluir lista?")
            .setMessage("“${p.name}” será removida deste aparelho. Os favoritos dos canais são mantidos.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Excluir") { _, _ ->
                runWork(null, { app.playlists.delete(p.id) }) { "Lista excluída" }
            }
            .show()
    }

    // ---- infraestrutura -----------------------------------------------------

    /** Roda [work] em segundo plano, mostra progresso e feedback, e recarrega a lista. */
    private fun <T> runWork(startMessage: String?, work: () -> T, success: (T) -> String) {
        setBusy(true)
        if (startMessage != null) toast(startMessage)
        Background.run(work) { result ->
            setBusy(false)
            if (!isFinishing && !isDestroyed) {
                result.onSuccess { toast(success(it)) }.onFailure { toast(userMessage(it)) }
                reload()
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        addButton.isEnabled = !value
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQ_PICK_FILE = 1001
    }
}
