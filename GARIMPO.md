# Garimpo (testar streams de uma lista) — estado das etapas 4 a 6

Nada abaixo foi compilado nem executado: o ambiente onde o código foi escrito não tem Gradle, SDK Android nem Media3.
O `PLANO-GARIMPO.md` citado nos comentários do código não estava no pacote recebido; as etapas 4–6 seguiram o que o código das etapas 1–3 descrevia.

| Etapa | O que é | Arquivos |
|---|---|---|
| 4 | Sonda real com ExoPlayer; peças de Media3 compartilhadas com o player | `data/testing/ExoStreamProbe.kt`, `player/MediaSupport.kt` |
| 5 | Leitura da lista salva em streaming; análise da lista | `data/testing/ParserChannelSource.kt`, `ListAnalyzer.kt`, `PlaylistRepository.analyze/openChannelSource` |
| 6 | Sessão de teste, tela, exportação M3U e "Salvar funcionais" | `data/testing/StreamTestSession.kt`, `ui/testing/TestListActivity.kt`, `data/parser/M3uExporter.kt`, `PlaylistRepository.saveTested`, `SourceType.TESTED` |

## Decisões
- O teste só roda com o app em primeiro plano (sem serviço em primeiro plano); sair da tela cancela e descarta os resultados.
- A lista de resultados só aparece com o teste pausado ou concluído.
- Aprovado = player `STATE_READY` **e** pelo menos `confirmBufferedMs` (500 ms) em buffer; HTTP 200 sozinho não basta.
- Nenhum texto da tela ou dos resultados contém a URL do stream.

## Antes de rodar os testes
1. Suba o projeto e rode o workflow: ele agora compila o app (`compileDebugKotlin`) e os testes (`compileDebugUnitTestKotlin`) em passos separados, antes de `testDebugUnitTest`.
2. Pontos onde o compilador pode reclamar (código novo, nunca compilado):
   - `ExoStreamProbe`: `DefaultLoadControl.Builder` (`setBufferDurationsMs`, `setTargetBufferBytes`, `setPrioritizeTimeOverSizeThresholds`), `DefaultTrackSelector.parameters = buildUponParameters().setForceLowestBitrate(true).build()`, `ExoPlayer.Builder.setLooper`, o `when` sobre `FailureAction`.
   - `MediaSupport`: `@UnstableApi` em `object` e `HttpDataSource.InvalidResponseCodeException.responseCode`.
   - `ParserChannelSource`: `RuntimeException(null, null, false, false)` (construtor protegido) e `Thread(::feed, ...)`.
   - `TestListActivity`: `when` em `statusText.text` com `String?` e smart cast de `s` / `progress!!` em `render()`.
   - `IptvApp`: `@OptIn(UnstableApi::class)` em `newStreamProbe()`.
3. Testes que mais podem falhar por tempo/concorrência (usam threads reais): `ParserChannelSourceTest.feederDoesNotReadFarAheadOfTheConsumer`, `engineCancelClosesTheReader`, `StreamTestSessionTest.listenersGetStateChangesAndCanBeRemoved`.
4. Se algo falhar, cole aqui o erro do passo do workflow (não o resumo).

## Juntar listas
`PlaylistRepository.merge(ids, nome)` (menu "+ Adicionar lista → Juntar listas existentes"): lê uma lista por vez em streaming, descarta URLs repetidas (a primeira vence), cria uma lista `MERGED` ("mesclada"). Dica: junte só as listas "testadas" para não trazer canais mortos de volta; juntar listas não testadas e testar o resultado leva muito mais tempo.

## Pendente (exige aparelho)
HLS real, stream fora do ar, header obrigatório, cancelamento com vários testes ativos, memória do motor com listas de ~150 mil canais (o motor guarda todos os resultados) e uso de rede com 6–8 testes simultâneos.
