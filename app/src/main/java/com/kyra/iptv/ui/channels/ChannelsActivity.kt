package com.kyra.iptv.ui.channels

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import com.kyra.iptv.IptvApp
import com.kyra.iptv.data.ChannelCatalog
import com.kyra.iptv.data.ChannelSort
import com.kyra.iptv.data.ChannelTab
import com.kyra.iptv.data.channelTabFromKey
import com.kyra.iptv.data.toKey
import com.kyra.iptv.data.model.Channel
import com.kyra.iptv.data.repository.PlaylistError
import com.kyra.iptv.player.ChannelQueue
import com.kyra.iptv.ui.Background
import com.kyra.iptv.ui.COLOR_MUTED
import com.kyra.iptv.ui.COLOR_STAR
import com.kyra.iptv.ui.COLOR_TEXT
import com.kyra.iptv.ui.applySystemBarsPadding
import com.kyra.iptv.ui.chip
import com.kyra.iptv.ui.dp
import com.kyra.iptv.ui.player.PlayerActivity
import com.kyra.iptv.ui.userMessage

/**
 * Tela 2 — Conteúdo de uma lista: abas TODOS / FAVORITOS / RECENTES / GRUPOS, busca por nome,
 * ordenação simples e favoritos. Tocar em um canal registra no histórico e abre o player, com a
 * lista exibida (aba/grupo/busca) como fila para canal anterior/próximo.
 *
 * Filtrar/buscar/ordenar roda fora da thread principal (a busca com debounce), e aba, busca e
 * ordenação sobrevivem à recriação da Activity ([onSaveInstanceState]).
 */
class ChannelsActivity : Activity() {

    private val app get() = application as IptvApp

    private var catalog: ChannelCatalog? = null
    private var favorites: Set<String> = emptySet()
    private var recent: List<String> = emptyList()
    private var shown: List<Channel> = emptyList()
    private var playlistId: String? = null
    private var loaded = false

    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0 // descarta resultados de consultas já superadas por outra mais nova
    private val searchRunnable = Runnable { refresh() }

    private var tab: ChannelTab = ChannelTab.All
    private var query: String = ""
    private var sort: ChannelSort = ChannelSort.PLAYLIST_ORDER

    private lateinit var titleView: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var countText: TextView
    private lateinit var messageText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var listView: ListView

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: newRow()
            val ch = shown[position]
            val texts = row.getChildAt(0) as LinearLayout
            (texts.getChildAt(0) as TextView).text = ch.name
            (texts.getChildAt(1) as TextView).text = ch.group
            val star = row.getChildAt(1) as TextView
            val fav = ch.id in favorites
            star.text = if (fav) "★" else "☆"
            star.setTextColor(if (fav) COLOR_STAR else COLOR_MUTED)
            star.setOnClickListener { setFavorite(ch, !fav) }
            return row
        }
    }

    private fun newRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setGravity(Gravity.CENTER_VERTICAL)
        setPadding(dp(16), 0, 0, 0)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(TextView(context).apply {
                textSize = 16f; setTextColor(COLOR_TEXT); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                textSize = 12f; setTextColor(COLOR_MUTED); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            textSize = 24f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isFocusable = false // não rouba o clique da linha
            isFocusableInTouchMode = false
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID)
        if (playlistId == null) {
            finish()
            return
        }
        this.playlistId = playlistId
        if (savedInstanceState != null) {
            tab = channelTabFromKey(savedInstanceState.getString(STATE_TAB))
            query = savedInstanceState.getString(STATE_QUERY).orEmpty()
            sort = ChannelSort.values().getOrElse(savedInstanceState.getInt(STATE_SORT, 0)) { ChannelSort.PLAYLIST_ORDER }
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        titleView = TextView(this).apply {
            textSize = 20f; setTextColor(COLOR_TEXT); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val sortButton = TextView(this).apply {
            text = "Ordenar"; textSize = 14f; setTextColor(0xFF1565C0.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { showSortDialog() }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.CENTER_VERTICAL)
            setPadding(dp(16), dp(8), dp(4), 0)
            addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sortButton)
        })

        val search = EditText(this).apply {
            hint = "Buscar..."
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine()
            setText(query) // antes do listener: restaura a busca sem disparar consulta
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    handler.removeCallbacks(searchRunnable)
                    handler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS)
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), 0, dp(12), 0)
        })

        chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        })

        countText = TextView(this).apply {
            textSize = 12f; setTextColor(COLOR_MUTED)
            setPadding(dp(16), 0, dp(16), dp(4))
        }
        root.addView(countText)

        progress = ProgressBar(this)
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        messageText = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setPadding(dp(24), dp(40), dp(24), dp(24))
            visibility = View.GONE
        }
        root.addView(messageText)

        listView = ListView(this).apply {
            adapter = this@ChannelsActivity.adapter
            setOnItemClickListener { _, _, position, _ -> onChannelClicked(shown[position]) }
            setOnItemLongClickListener { _, _, position, _ ->
                val ch = shown[position]
                setFavorite(ch, ch.id !in favorites)
                true
            }
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.applySystemBarsPadding()
        setContentView(root)

        load(playlistId)
    }

    // ---- carregar -----------------------------------------------------------

    private class Loaded(val name: String, val catalog: ChannelCatalog, val favorites: Set<String>, val recent: List<String>)

    private fun load(playlistId: String) {
        Background.run({
            val playlist = app.playlists.get(playlistId) ?: throw PlaylistError.NotFound()
            val parsed = app.playlists.loadChannels(playlistId)
            Loaded(playlist.name, ChannelCatalog(parsed.channels), app.favorites.getAll(), app.history.getAll())
        }) { result ->
            if (isFinishing || isDestroyed) return@run
            progress.visibility = View.GONE
            result.onSuccess {
                titleView.text = it.name
                val savedGroup = (tab as? ChannelTab.Group)?.name
                if (savedGroup != null && it.catalog.groups.none { g -> g.name == savedGroup }) {
                    tab = ChannelTab.All // o grupo salvo não existe mais (lista atualizada)
                }
                catalog = it.catalog
                favorites = it.favorites
                recent = it.recent
                loaded = true
                refresh()
            }.onFailure {
                messageText.text = userMessage(it)
                messageText.visibility = View.VISIBLE
            }
        }
    }

    /** Ao voltar do player o histórico pode ter mudado (troca de canal lá dentro). */
    override fun onRestart() {
        super.onRestart()
        if (!loaded) return
        Background.run({ app.history.getAll() }) { result ->
            if (isFinishing || isDestroyed) return@run
            result.onSuccess {
                recent = it
                if (tab is ChannelTab.Recent) refresh()
            }
        }
    }

    // ---- exibição -----------------------------------------------------------

    /**
     * Refaz a lista exibida em segundo plano (com dezenas de milhares de canais a busca/ordenação
     * não deve rodar na thread principal). [resetScroll] volta ao topo; favoritar não deve fazê-lo.
     */
    private fun refresh(resetScroll: Boolean = true) {
        val c = catalog ?: return
        handler.removeCallbacks(searchRunnable)
        val ticket = ++generation
        val tabNow = tab
        val textNow = query
        val sortNow = sort
        val favoritesNow = favorites
        val recentNow = recent
        Background.run({ c.query(tabNow, textNow, sortNow, favoritesNow, recentNow) }) { result ->
            if (isFinishing || isDestroyed || ticket != generation) return@run
            shown = result.getOrDefault(emptyList())
            adapter.notifyDataSetChanged()
            if (resetScroll) listView.setSelection(0)
            countText.text = "${shown.size} de ${c.channels.size} canais"
            rebuildChips(c)

            val empty = when {
                shown.isNotEmpty() -> null
                textNow.isNotBlank() -> "Nenhum canal encontrado para a busca."
                tabNow is ChannelTab.Favorites -> "Nenhum favorito ainda.\nToque na estrela de um canal para favoritá-lo."
                tabNow is ChannelTab.Recent -> "Nenhum canal assistido recentemente."
                else -> "Esta lista não tem canais."
            }
            messageText.text = empty.orEmpty()
            messageText.visibility = if (empty == null) View.GONE else View.VISIBLE
            listView.visibility = if (empty == null) View.VISIBLE else View.GONE
        }
    }

    private fun rebuildChips(c: ChannelCatalog) {
        chipsRow.removeAllViews()
        fun add(label: String, selected: Boolean, onClick: () -> Unit) {
            chipsRow.addView(chip(label, selected, onClick), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) })
        }
        add("TODOS", tab is ChannelTab.All) { selectTab(ChannelTab.All) }
        add("FAVORITOS", tab is ChannelTab.Favorites) { selectTab(ChannelTab.Favorites) }
        add("RECENTES", tab is ChannelTab.Recent) { selectTab(ChannelTab.Recent) }
        val group = (tab as? ChannelTab.Group)?.name
        add(if (group != null) "$group ▾" else "GRUPOS ▾", group != null) { showGroupsDialog(c) }
    }

    private fun selectTab(t: ChannelTab) {
        tab = t
        refresh()
    }

    private fun showGroupsDialog(c: ChannelCatalog) {
        if (c.groups.isEmpty()) return
        val labels = c.groups.map { "${it.name} (${it.count})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Grupos")
            .setItems(labels) { _, which -> selectTab(ChannelTab.Group(c.groups[which].name)) }
            .show()
    }

    private fun showSortDialog() {
        val options = arrayOf("Ordem da lista", "Nome (A–Z)")
        AlertDialog.Builder(this)
            .setTitle("Ordenar")
            .setSingleChoiceItems(options, sort.ordinal) { dialog, which ->
                sort = ChannelSort.values()[which]
                dialog.dismiss()
                refresh()
            }
            .show()
    }

    // ---- ações --------------------------------------------------------------

    private fun setFavorite(ch: Channel, favorite: Boolean) {
        favorites = if (favorite) favorites + ch.id else favorites - ch.id
        adapter.notifyDataSetChanged() // a estrela muda na hora
        refresh(resetScroll = false) // só importa na aba Favoritos ou com busca; não volta ao topo
        Background.execute { app.favorites.setFavorite(ch.id, favorite) }
    }

    private fun onChannelClicked(ch: Channel) {
        val id = playlistId ?: return
        recent = listOf(ch.id) + recent.filter { it != ch.id }
        Background.execute { app.history.record(ch.id) }
        val list = shown
        app.playbackQueue = ChannelQueue(list, list.indexOfFirst { it.id == ch.id })
        PlayerActivity.start(this, id, ch.id)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, tab.toKey())
        outState.putString(STATE_QUERY, query)
        outState.putInt(STATE_SORT, sort.ordinal)
    }

    override fun onDestroy() {
        handler.removeCallbacks(searchRunnable)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
        private const val STATE_TAB = "tab"
        private const val STATE_QUERY = "query"
        private const val STATE_SORT = "sort"
        private const val SEARCH_DEBOUNCE_MS = 250L
    }
}
