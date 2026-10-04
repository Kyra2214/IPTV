package com.kyra.iptv.ui.testing

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.kyra.iptv.IptvApp
import com.kyra.iptv.data.repository.PlaylistError
import com.kyra.iptv.data.testing.EngineState
import com.kyra.iptv.data.testing.StreamTestConfig
import com.kyra.iptv.data.testing.StreamTestListener
import com.kyra.iptv.data.testing.StreamTestResult
import com.kyra.iptv.data.testing.StreamTestSession
import com.kyra.iptv.data.testing.TestFilter
import com.kyra.iptv.data.testing.TestProgress
import com.kyra.iptv.player.ChannelQueue
import com.kyra.iptv.ui.Background
import com.kyra.iptv.ui.COLOR_MUTED
import com.kyra.iptv.ui.COLOR_TEXT
import com.kyra.iptv.ui.applySystemBarsPadding
import com.kyra.iptv.ui.chip
import com.kyra.iptv.ui.dp
import com.kyra.iptv.ui.player.PlayerActivity
import com.kyra.iptv.ui.userMessage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tela 3 — Testar streams de uma lista ("garimpo"): analisa a lista, testa cada canal com o player
 * (no máximo N ao mesmo tempo), mostra o andamento, permite pausar/continuar/cancelar, filtrar os
 * resultados por motivo e salvar os que funcionam como uma lista nova.
 *
 * O teste vive em [IptvApp.testSession], então a rotação da tela não o interrompe. Ele só roda
 * enquanto o app está em primeiro plano: sair desta tela cancela o teste (a tela mantém o aparelho
 * acordado enquanto testa). Nenhum texto da tela mostra a URL do stream.
 */
class TestListActivity : Activity() {

    private val app get() = application as IptvApp
    private val handler = Handler(Looper.getMainLooper())

    private var playlistId: String? = null
    private var session: StreamTestSession? = null
    private var loading = true
    private var loadError: String? = null
    private var saving = false

    private var concurrency = StreamTestConfig.DEFAULT_CONCURRENCY
    private var filter = TestFilter.ALL
    private var shown: List<StreamTestResult> = emptyList()
    private var listGeneration = 0
    private var lastListedState: EngineState? = null
    private val renderPending = AtomicBoolean(false)

    private lateinit var titleView: TextView
    private lateinit var statusText: TextView
    private lateinit var bar: ProgressBar
    private lateinit var concurrencyRow: LinearLayout
    private lateinit var concurrencyScroll: HorizontalScrollView
    private lateinit var startButton: Button
    private lateinit var pauseButton: Button
    private lateinit var cancelButton: Button
    private lateinit var saveButton: Button
    private lateinit var againButton: Button
    private lateinit var filterRow: LinearLayout
    private lateinit var filterScroll: HorizontalScrollView
    private lateinit var listView: ListView

    private val listener = object : StreamTestListener {
        // Chamado na thread do motor: só agenda a atualização da tela.
        override fun onProgress(progress: TestProgress) = scheduleRender()
        override fun onStateChanged(state: EngineState) = scheduleRender()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: newRow()
            val r = shown[position]
            (row.getChildAt(0) as TextView).apply {
                text = (if (r.isWorking) "✓ " else "✗ ") + r.channel.name
                setTextColor(if (r.isWorking) COLOR_TEXT else COLOR_MUTED)
            }
            (row.getChildAt(1) as TextView).text =
                "${r.reason} · ${r.channel.group} · ${"%.1f".format(r.elapsedMs / 1000.0)} s"
            return row
        }
    }

    private fun newRow() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        addView(TextView(context).apply {
            textSize = 16f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        addView(TextView(context).apply {
            textSize = 12f; setTextColor(COLOR_MUTED); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
    }

    // ---- ciclo de vida ------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_PLAYLIST_ID)
        if (id == null) {
            finish()
            return
        }
        playlistId = id
        if (savedInstanceState != null) {
            concurrency = savedInstanceState.getInt(STATE_CONCURRENCY, concurrency)
            filter = TestFilter.values().getOrElse(savedInstanceState.getInt(STATE_FILTER, 0)) { TestFilter.ALL }
        }
        buildViews()

        val existing = app.testSession?.takeIf { it.playlistId == id }
        if (existing != null) attach(existing) else prepare(id)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_CONCURRENCY, concurrency)
        outState.putInt(STATE_FILTER, filter.ordinal)
    }

    override fun onDestroy() {
        session?.removeListener(listener)
        if (isFinishing) {
            // Saiu da tela: o teste não continua em segundo plano e a memória dos resultados é liberada.
            session?.let { s ->
                s.cancel()
                if (app.testSession === s) app.testSession = null
            }
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val state = session?.state
        if (state == EngineState.RUNNING || state == EngineState.PAUSED || state == EngineState.CANCELLING) {
            AlertDialog.Builder(this)
                .setTitle("Cancelar o teste?")
                .setMessage("Sair desta tela cancela o teste e descarta os resultados. Salve os funcionais antes, se quiser.")
                .setNegativeButton("Continuar testando", null)
                .setPositiveButton("Cancelar e sair") { _, _ -> finish() }
                .show()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---- preparação ---------------------------------------------------------

    /** Analisa a lista (em segundo plano) e cria a sessão, ainda sem começar. */
    private fun prepare(id: String) {
        loading = true
        loadError = null
        session = null
        render()
        Background.run({
            val playlist = app.playlists.get(id) ?: throw PlaylistError.NotFound()
            val analysis = app.playlists.analyze(id)
            StreamTestSession(
                playlistId = id,
                playlistName = playlist.name,
                analysis = analysis,
                openSource = { app.playlists.openChannelSource(id) },
                probe = app.newStreamProbe(),
            )
        }) { result ->
            if (isFinishing || isDestroyed) return@run
            loading = false
            result.onSuccess {
                app.testSession = it
                attach(it)
            }.onFailure {
                loadError = userMessage(it)
                render()
            }
        }
    }

    private fun attach(s: StreamTestSession) {
        loading = false
        session?.removeListener(listener)
        session = s
        s.addListener(listener)
        lastListedState = null
        render()
    }

    // ---- ações --------------------------------------------------------------

    private fun startTest() {
        val s = session ?: return
        try {
            s.start(StreamTestConfig(concurrency = concurrency))
        } catch (e: Exception) {
            toast(userMessage(e))
        }
        render()
    }

    private fun askSave() {
        val s = session ?: return
        if (saving) return
        if (s.workingChannels().isEmpty()) {
            toast("Nenhum canal funcionando para salvar")
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText("${s.playlistName} (funcionais)")
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Salvar canais funcionais")
            .setView(LinearLayout(this).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(input)
            })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ -> save(s, input.text.toString()) }
            .show()
    }

    private fun save(s: StreamTestSession, name: String) {
        saving = true
        render()
        Background.run({ app.playlists.saveTested(s.workingChannels(), name) }) { result ->
            saving = false
            if (!isFinishing && !isDestroyed) {
                result.onSuccess { toast("Lista salva: ${it.name} (${it.channelCount} canais)") }
                    .onFailure { toast(userMessage(it)) }
                render()
            }
        }
    }

    private fun testAgain() {
        val id = playlistId ?: return
        session?.let { old ->
            old.removeListener(listener)
            if (app.testSession === old) app.testSession = null
        }
        shown = emptyList()
        adapter.notifyDataSetChanged()
        filter = TestFilter.ALL
        prepare(id)
    }

    private fun play(index: Int) {
        val id = playlistId ?: return
        val list = shown
        val ch = list.getOrNull(index)?.channel ?: return
        app.playbackQueue = ChannelQueue(list.map { it.channel }, index)
        PlayerActivity.start(this, id, ch.id)
    }

    // ---- exibição -----------------------------------------------------------

    private fun scheduleRender() {
        if (renderPending.compareAndSet(false, true)) {
            handler.post {
                renderPending.set(false)
                if (!isFinishing && !isDestroyed) render()
            }
        }
    }

    private fun render() {
        val s = session
        val state = s?.state ?: EngineState.IDLE
        val progress = s?.progress()
        val running = state == EngineState.RUNNING || state == EngineState.PAUSED || state == EngineState.CANCELLING
        window.decorView.keepScreenOn = state == EngineState.RUNNING

        titleView.text = s?.playlistName ?: "Testar streams"

        statusText.text = when {
            loadError != null -> loadError
            loading || s == null -> "Analisando a lista…"
            state == EngineState.IDLE -> analysisText(s)
            else -> progressText(s, progress!!, state)
        }

        val showBar = s != null && state != EngineState.IDLE
        bar.visibility = if (showBar) View.VISIBLE else View.GONE
        if (progress != null) bar.progress = progress.percent

        val canStart = s != null && state == EngineState.IDLE && s.analysis.uniqueUrls > 0
        concurrencyScroll.visibility = if (canStart) View.VISIBLE else View.GONE
        if (canStart) rebuildConcurrencyChips()

        startButton.visibility = if (canStart) View.VISIBLE else View.GONE
        pauseButton.visibility = if (state == EngineState.RUNNING || state == EngineState.PAUSED) View.VISIBLE else View.GONE
        pauseButton.text = if (state == EngineState.PAUSED) "Continuar" else "Pausar"
        cancelButton.visibility = if (state == EngineState.RUNNING || state == EngineState.PAUSED) View.VISIBLE else View.GONE
        val done = state.isTerminal
        saveButton.visibility = if (done && (progress?.working ?: 0) > 0) View.VISIBLE else View.GONE
        saveButton.isEnabled = !saving
        againButton.visibility = if (done || loadError != null) View.VISIBLE else View.GONE

        // Resultados: só quando o teste não está em andamento (evita copiar a lista a cada avanço).
        val listable = s != null && (state == EngineState.PAUSED || done)
        filterScroll.visibility = if (listable) View.VISIBLE else View.GONE
        listView.visibility = if (listable) View.VISIBLE else View.GONE
        if (listable) {
            rebuildFilterChips(progress!!)
            if (state != lastListedState) refreshResults()
        } else if (running) {
            shown = emptyList()
            adapter.notifyDataSetChanged()
        }
        lastListedState = state
    }

    private fun analysisText(s: StreamTestSession): String {
        val a = s.analysis
        val head = "Entradas na lista: ${a.totalEntries}\n" +
            "URLs únicas (serão testadas): ${a.uniqueUrls}\n" +
            "Repetidas: ${a.duplicates} · Inválidas: ${a.invalid}\n" +
            "Grupos: ${a.groups}"
        return if (a.uniqueUrls == 0) "$head\n\nNão há canais para testar nesta lista." else head
    }

    private fun progressText(s: StreamTestSession, p: TestProgress, state: EngineState): String {
        val line = "${p.tested} de ${p.total} testados (${p.percent}%)\n" +
            "Funcionando: ${p.working} · Falhas: ${p.failed}"
        val extra = when (state) {
            EngineState.PAUSED -> "\nPausado."
            EngineState.CANCELLING -> "\nCancelando…"
            EngineState.CANCELLED -> "\nTeste cancelado. Os resultados até aqui foram mantidos."
            EngineState.FINISHED -> "\nTeste concluído."
            else -> ""
        }
        val warn = if (s.sourceFailed) "\nA leitura da lista falhou no meio; só foi testado o que veio antes." else ""
        return line + extra + warn
    }

    private fun refreshResults() {
        val s = session ?: return
        val ticket = ++listGeneration
        val f = filter
        Background.run({ s.results(f) }) { result ->
            if (isFinishing || isDestroyed || ticket != listGeneration) return@run
            shown = result.getOrDefault(emptyList())
            adapter.notifyDataSetChanged()
            listView.setSelection(0)
        }
    }

    private fun rebuildConcurrencyChips() {
        concurrencyRow.removeAllViews()
        for (n in CONCURRENCY_OPTIONS) {
            concurrencyRow.addView(
                chip("$n simultâneos", n == concurrency) { concurrency = n; render() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { rightMargin = dp(8) },
            )
        }
    }

    private fun rebuildFilterChips(p: TestProgress) {
        filterRow.removeAllViews()
        for (f in TestFilter.values()) {
            val count = p.count(f)
            val always = f == TestFilter.ALL || f == TestFilter.WORKING || f == TestFilter.FAILING
            if (count == 0 && !always && f != filter) continue
            filterRow.addView(
                chip("${filterLabel(f)} ($count)", f == filter) {
                    filter = f
                    refreshResults()
                    render()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { rightMargin = dp(8) },
            )
        }
    }

    private fun filterLabel(f: TestFilter) = when (f) {
        TestFilter.ALL -> "Todos"
        TestFilter.WORKING -> "Funcionando"
        TestFilter.FAILING -> "Falhas"
        TestFilter.HTTP_403 -> "403"
        TestFilter.HTTP_404 -> "404"
        TestFilter.HTTP_411 -> "411"
        TestFilter.TIMEOUT -> "Timeout"
        TestFilter.HTTP_5XX -> "5xx"
        TestFilter.OTHER -> "Outros"
    }

    // ---- construção da tela -------------------------------------------------

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        visibility = View.GONE
        setOnClickListener { onClick() }
    }

    private fun buildViews() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        titleView = TextView(this).apply {
            textSize = 20f; setTextColor(COLOR_TEXT); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        root.addView(titleView)

        statusText = TextView(this).apply {
            textSize = 14f; setTextColor(COLOR_TEXT)
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        root.addView(statusText)

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), 0, dp(16), dp(8))
        })

        concurrencyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        concurrencyScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(concurrencyRow)
        }
        root.addView(concurrencyScroll)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        startButton = button("Iniciar teste") { startTest() }
        pauseButton = button("Pausar") {
            val s = session
            if (s?.state == EngineState.PAUSED) s.resume() else s?.pause()
        }
        cancelButton = button("Cancelar") { session?.cancel() }
        saveButton = button("Salvar funcionais") { askSave() }
        againButton = button("Testar de novo") { testAgain() }
        for (b in listOf(startButton, pauseButton, cancelButton, saveButton, againButton)) buttons.addView(b)
        root.addView(HorizontalScrollView(this).apply { addView(buttons) })

        filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(filterRow)
        }
        root.addView(filterScroll)

        listView = ListView(this).apply {
            adapter = this@TestListActivity.adapter
            visibility = View.GONE
            setOnItemClickListener { _, _, position, _ -> play(position) }
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.applySystemBarsPadding()
        setContentView(root)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
        private const val STATE_CONCURRENCY = "concurrency"
        private const val STATE_FILTER = "filter"
        private val CONCURRENCY_OPTIONS = listOf(1, 2, 4, 6, 8)
    }
}
