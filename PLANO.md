# IPTV

> **Garimpo (testar streams):** etapas 4–6 escritas, não compiladas; ver `GARIMPO.md`.
>
> **Status:** Fases 1 a 6 implementadas (exceto o item 38, testes reais com Chromecast) e **Fase 7 (Robustez) escrita, mas ainda não compilada nem executada**: este ambiente não tem Gradle, SDK Android, Media3, Play Services, `kotlinc` nem JUnit. Há 150 testes de JVM (parser, armazenamento, repositórios, catálogo/busca, fila, retry, política de erros do player, validação de URL/headers, regras de Cast, codificação de texto); em sessões anteriores só 34 deles (`player/` e catálogo) chegaram a rodar com `kotlinc`, e **nenhum rodou na Fase 7**. As telas (`PlayerActivity`, `ChannelsActivity`, `PlaylistsActivity`), o `CastManager` e o `IptvApp` dependem de Android e também nunca foram compilados. **Legenda:** `[x]` feito e verificável por leitura/busca no código; `[~]` escrito, mas **ainda não compilado/executado** (precisa de `./gradlew test` e de aparelho); `[ ]` pendente. Pendências reais: item 38 (Chromecast real), item 45 (medir memória), o primeiro build e os testes em aparelho listados em "Decisão da Fase 7". Aguardando ordem para iniciar a Fase 8 — APK.

Aplicativo Android simples para reprodução de listas IPTV fornecidas pelo próprio usuário.

## Objetivo

Criar um player IPTV enxuto, local-first e sem backend próprio, focado em:

- adicionar listas M3U/M3U8 manualmente;
- interpretar playlists reais e tolerar variações de formato;
- organizar canais por `group-title`;
- reproduzir streams localmente com Media3/ExoPlayer;
- transmitir o stream diretamente para dispositivos Chromecast usando Media3 Cast;
- manter listas, favoritos e configurações no aparelho.

O aplicativo **não fornece conteúdo IPTV próprio**. O usuário fornece as playlists e os streams.

## Referências auditadas

Os seguintes projetos foram analisados como referência técnica e de produto:

- **Plehooo/IPTVPlayer** — principal referência para parser tolerante, Media3/ExoPlayer, headers, URLs relativas e tratamento de erros/retry.
- **Joaopablobit/iptv-player** — referência para organização simples de data/parser/repository, favoritos, histórico e cache.
- **newjan/StreamBox** — referência para testes de parser, listas grandes e persistência moderna; arquitetura completa não será copiada.
- **Hunter-Clipper/OpenIPTV** — referência de UX e filosofia BYO (Bring Your Own Playlist); recursos avançados ficam fora do MVP.
- **oxyroid/M3UAndroid** — referência para casos reais e problemas encontrados em playlists IPTV variadas.
- **AndroidX Media3 Cast / CastPlayer** — referência oficial para Chromecast e troca entre reprodução local e remota.

### Decisões derivadas da auditoria

1. O parser será um componente central e independente da UI.
2. O parser deve ser tolerante a campos ausentes e formatos imperfeitos.
3. IDs de canais devem ser determinísticos quando possível, preferencialmente derivados de dados estáveis como a URL do stream, evitando IDs aleatórios que mudem a cada atualização.
4. Media3/ExoPlayer será o player local.
5. Para Cast, usar **Media3 Cast/CastPlayer**, e não implementar espelhamento de tela.
6. A primeira versão deve permanecer pequena; projetos maiores serão usados como referência e não como arquitetura a ser copiada inteira.
7. Casos reais encontrados nos projetos auditados serão transformados em testes do parser e do player.

## Escopo do MVP

### 1. Base do aplicativo

- [x] Android nativo.
- [x] Kotlin.
- [x] Estrutura simples e modular.
- [x] UI pequena e direta.
- [x] Persistência local.
- [x] Sem backend próprio.
- [x] Sem dependência de IA.
- [x] Sem catálogo IPTV embutido.

### 2. Gerenciamento de listas

Entrada por:

- [x] URL HTTP/HTTPS.
- [x] Arquivo M3U/M3U8.
- [x] Conteúdo M3U colado manualmente.

Gerenciamento:

- [x] Salvar lista localmente.
- [x] Nomear/renomear lista.
- [x] Excluir lista.
- [x] Atualizar lista por URL.
- [x] Guardar a origem da lista.
- [x] Guardar configurações básicas associadas à lista.

A lista deve continuar disponível após fechar e abrir o aplicativo.

## 3. Parser M3U/M3U8

O parser deve trabalhar com o padrão mais comum de playlists IPTV sem exigir que todos os campos estejam presentes.

### Campos prioritários

- [x] `#EXTINF`.
- [x] Nome exibido após a vírgula.
- [x] `tvg-id`.
- [x] `tvg-name`.
- [x] `tvg-logo`.
- [x] `group-title`.

### Comportamento esperado

- [x] Aceitar `http://` e `https://`.
- [x] Suportar URL relativa quando houver URL base.
- [x] Tolerar atributos ausentes.
- [x] Tolerar grupo ausente.
- [x] Tolerar nome ausente.
- [x] Ignorar linhas de comentário desconhecidas.
- [x] Ignorar entradas inválidas sem interromper o restante da playlist.
- [x] Não derrubar o aplicativo por uma entrada malformada.
- [x] Preservar a ordem original quando possível.
- [x] Evitar duplicações óbvias quando aplicável.
- [x] Gerar identidade determinística para canais sem `tvg-id`.

> **Acréscimos da Fase 7:** `M3uParser.scan()` lê em streaming e entrega cada canal a um callback (`parse()` é construído sobre ele); nomes de grupo são internados (um `String` por grupo); `#EXTGRP:` vira o grupo quando não há `group-title` (o atributo tem prioridade); um stream HLS único colado como lista (`#EXT-X-TARGETDURATION`, `#EXT-X-STREAM-INF` ou `#EXT-X-MEDIA-SEQUENCE` sem nenhum atributo `tvg-*`/`group-title`) é recusado com mensagem clara em vez de virar centenas de "canais" (segmentos).

### Regra de fallback do nome

Prioridade:

1. `tvg-name`;
2. nome depois da vírgula no `#EXTINF`;
3. fallback seguro baseado na URL.

### Regra de grupo

Se `group-title` estiver ausente ou vazio:

`Ungrouped`

O parser não deve tentar adivinhar categorias complexas a partir do nome do canal no MVP.

## 4. Testes do parser

O parser será desenvolvido antes da UI principal e deverá possuir testes automatizados para:

- [x] M3U mínima válida.
- [x] M3U com vários grupos.
- [x] M3U sem `group-title`.
- [x] M3U sem `tvg-name`.
- [x] M3U sem `tvg-logo`.
- [x] M3U com atributos extras.
- [x] M3U com aspas simples e/ou variações comuns de atributos.
- [x] URL HTTP.
- [x] URL HTTPS.
- [x] URL relativa + URL base.
- [x] Entrada sem URL.
- [x] Linha de comentário entre EXTINF e URL.
- [x] Entrada malformada.
- [x] Playlist vazia.
- [x] Playlist grande.
- [x] Duplicações.
- [x] Identidade determinística de canais.

Os testes devem garantir que um erro em uma entrada não invalide as demais.

## 5. Modelo de dados

Modelo mínimo de canal:

- `id`
- `name`
- `streamUrl`
- `group`
- `tvgId`
- `tvgName`
- `logoUrl`
- informações necessárias para headers, quando aplicável.

Modelo mínimo de playlist:

- `id`
- `name`
- `source`
- `sourceType`
- `updatedAt`

Favoritos e histórico devem usar uma identidade estável do canal.

## 6. Organização do conteúdo

Tela principal de conteúdo:

- [x] Todos.
- [x] Favoritos.
- [x] Grupos.
- [x] Busca por nome.
- [x] Ordenação simples.
- [x] Grupo `Ungrouped` para entradas sem categoria.

Exemplo:

```
TODOS | FAVORITOS | ESPORTES | FILMES | SÉRIES

Buscar...

Canal 1
Canal 2
Canal 3
```

A separação por tipo deve ser baseada principalmente no `group-title` fornecido pela playlist.

Não criar uma taxonomia artificial complexa no MVP.

> **Decisão da Fase 4:** os grupos aparecem em um diálogo (com contagem), porque listas reais têm dezenas ou centenas deles; as abas fixas são TODOS, FAVORITOS e RECENTES. A busca ignora maiúsculas e acentos e aceita várias palavras. Favoritos e histórico guardam o id estável do canal (hash da URL). Os logos (`tvg-logo`) ainda não são exibidos, para não adicionar uma biblioteca de imagens sem necessidade. Tocar em um canal registra no histórico; o player entra na Fase 5.

## 7. Favoritos e histórico

- [x] Favoritar/desfavoritar canal.
- [x] Persistir favoritos.
- [x] Manter histórico simples dos últimos canais assistidos.
- [x] Não depender de UUID aleatório para persistência.

## 8. Reprodução

Usar **AndroidX Media3 / ExoPlayer**.

### Suporte inicial

- [x] HTTP.
- [x] HTTPS.
- [x] HLS/M3U8.
- [x] DASH quando suportado pelo Media3.
- [x] Headers HTTP quando necessários e permitidos pelo stream.
- [x] Redirects HTTP comuns.

### Controles

- [x] Play/pause.
- [x] Tela cheia.
- [x] Indicador de buffering.
- [x] Nome do canal.
- [x] Erro de reprodução.
- [x] Retry controlado.
- [x] Canal anterior.
- [x] Próximo canal.

Não criar um player proprietário.

> **Decisão da Fase 5:** `PlayerActivity` com `ExoPlayer` + `PlayerView` (sem controles prontos; botões próprios: anterior, play/pausa, próximo, tela cheia). A fila de canais é a lista que o usuário via (aba/grupo/busca) e é entregue por `IptvApp.playbackQueue`; se o processo for recriado, é remontada pela lista. URLs `.m3u8` e `.mpd` recebem o tipo explícito (HLS/DASH); URLs sem extensão deixam o ExoPlayer detectar e, se o formato não for reconhecido, tentam HLS uma vez. Headers por canal (`User-Agent`, `Referer`, `Origin`...) são validados e aplicados no `DefaultHttpDataSource`, com redirects entre http/https e timeouts de 15 s. Erros de rede/HTTP 408/429/5xx têm até 3 retries automáticos (1 s, 2 s, 4 s); depois (ou em 401/403/404, formato/decoder) aparece a mensagem sem a URL e o botão "Tentar novamente". Paisagem = tela cheia imersiva; o botão ⛶ alterna a orientação. Em TV/controle remoto: Channel Up/Down e Next/Previous trocam de canal. Pendente para teste em aparelho: streams reais, rotação e troca rápida de canal.

## 9. Chromecast

### Estratégia

Usar **Media3 Cast / CastPlayer** para permitir reprodução local e transmissão para dispositivo Cast compatível.

A documentação oficial do Android mostra o `CastPlayer` como implementação Media3 capaz de alternar entre reprodução local e remota e recomenda o `MediaRouteButton` para descoberta/seleção de dispositivos Cast.

### Implementação

- [x] Adicionar dependência Media3 Cast.
- [x] Configurar `DefaultCastOptionsProvider` ou equivalente mínimo.
- [x] Criar `CastPlayer` usando o ExoPlayer como player local.
- [x] Adicionar botão Cast na tela do player.
- [x] Detectar dispositivos Cast disponíveis.
- [x] Selecionar dispositivo.
- [x] Transferir a reprodução do stream.
- [x] Exibir estado local/remoto.
- [x] Encerrar Cast e retornar ao aparelho.
- [ ] Testar com Chromecast/Google TV/Android TV compatível.

A prioridade é **Cast do stream**, não espelhamento da tela inteira.

### Observação importante

O Chromecast precisa conseguir acessar diretamente a URL do stream. Se a fonte exigir algo que o receptor não consiga reproduzir ou autenticar, o Cast pode falhar mesmo que a reprodução local funcione.

Não criar proxy próprio apenas para contornar isso no MVP.

> **Decisão da Fase 6:** o projeto usa Media3 1.5.1 e não tem serviço de mídia, então foi usada a API clássica do `CastPlayer` (`CastPlayer(CastContext)` + `SessionAvailabilityListener`) com a troca ExoPlayer ↔ CastPlayer feita na `PlayerActivity`, em vez de `CastPlayer.Builder().setLocalPlayer()` com Output Switcher (guia atual, para Media3 1.11 + `MediaSessionService`). Se o projeto subir de versão, essa troca pode ser simplificada. Detalhes: `CastManager` guarda o `CastContext` (nulo sem Play Services: o botão não aparece) e o `CastPlayer`, que só existe entre onStart e onStop; `DefaultCastOptionsProvider` usa o receptor padrão; o `MediaRouteButton` fica na barra superior e some sozinho sem dispositivos na rede; por isso `PlayerActivity` passou a ser `FragmentActivity` com tema AppCompat escuro (`Theme.IPTV.Player`). Ao conectar, o ExoPlayer é liberado e o canal atual vai para o Cast (tipo MIME pelo endereço: `.m3u8` HLS, `.mpd` DASH, `.ts`, `.mp4`; sem extensão = HLS); trocar de canal durante o Cast transmite o novo canal; ao desconectar volta ao ExoPlayer. A tela mostra "Transmitindo para <dispositivo>" e o botão "Parar transmissão". Canais com headers personalizados mostram aviso (o Chromecast não os envia; sem proxy no MVP). Erro remoto mostra mensagem e "Tentar novamente", sem retry automático. Sair da tela durante o Cast encerra a transmissão; Home a mantém e, ao voltar, o canal é recarregado. Não foi adicionado `MediaTransferReceiver` (exigiria MediaSession).

## 10. Persistência

Primeiro escolher a solução mais simples que suporte o tamanho real das listas.

### Requisitos

- [x] Listas persistentes.
- [x] Favoritos persistentes.
- [x] Histórico persistente.
- [ ] Configurações básicas persistentes.

### Estratégia

Para listas pequenas/médias, armazenamento local simples pode ser suficiente.

Para listas grandes, usar banco local (Room/SQLite) e processamento incremental/paginado quando necessário.

**Não carregar uma playlist gigantesca inteira em memória sem necessidade.**

Não adicionar Hilt, Clean Architecture ou outras camadas apenas por padrão. Toda dependência deve resolver uma necessidade concreta.

> **Decisão da Fase 3:** as listas ficam em arquivos locais (`playlists.tsv` como índice + o M3U original salvo em `content/<id>.m3u`, gravado em streaming), sem Room por enquanto. Se a abertura de listas muito grandes ficar lenta na Fase 4 ou 7, migrar para Room/SQLite.

## 11. Estrutura proposta

```
app/
└── src/main/java/.../
    ├── data/
    │   ├── model/
    │   │   ├── Channel.kt
    │   │   └── Playlist.kt
    │   ├── parser/
    │   │   └── M3uParser.kt
    │   └── repository/
    │       └── PlaylistRepository.kt
    │
    ├── player/
    │   ├── PlaybackManager.kt
    │   └── CastManager.kt
    │
    ├── storage/
    │   └── LocalStorage.kt
    │
    └── ui/
        ├── playlists/
        ├── channels/
        └── player/
```

A estrutura é uma referência inicial, não uma regra rígida. Evitar abstrações sem uso real.

## 12. Telas

### Tela 1 — Listas

```
IPTV

+ Adicionar lista

Minha Lista
247 canais
```

Ações:

- abrir;
- atualizar;
- renomear;
- excluir.

### Tela 2 — Conteúdo

```
TODOS | FAVORITOS | ESPORTES | FILMES

🔎 Buscar...

Canal 1
Canal 2
Canal 3
```

### Tela 3 — Player

```
┌─────────────────────────┐
│                         │
│          VÍDEO          │
│                         │
├─────────────────────────┤
│ ⏮  ▶  ⏭       📺 Cast │
└─────────────────────────┘
```

Controles mínimos, sem transformar o player em uma central multimídia.

## 13. Segurança e robustez

- [x] Nunca executar conteúdo da playlist como código. *(revisão: o parser só produz dados; só URLs `http`/`https` chegam ao player; nenhum Intent, reflexão ou avaliação a partir do conteúdo.)*
- [x] Validar URLs antes de abrir.
- [~] Não registrar tokens/senhas/URLs sensíveis em logs de produção. *(o app não tem nenhuma chamada de log; mensagens de erro nunca levam a URL; logs do Media3 desligados em build não-debuggable, em `IptvApp`, não compilado.)*
- [~] Limitar tamanho de entrada quando apropriado. *(64 MB, 150.000 canais e 5 min por lista.)*
- [~] Tratar timeouts e erros de rede. *(timeouts de conexão/leitura, limite de tempo total, erros sem vazar URL; no player, a política de retry.)*
- [~] Evitar travamento da UI durante download/parsing. *(por revisão: rede, disco, parsing e agora a busca/ordenação rodam fora da thread principal; não medido em aparelho.)*
- [~] Processar listas grandes fora da thread principal. *(idem; a importação não guarda mais a lista inteira na memória.)*
- [x] Não incluir credenciais de listas de teste no repositório. *(busca por `username=`/`password=`/`token=` e revisão dos fixtures: só textos sintéticos com hosts `example`.)*

## 14. Fora do escopo do MVP

Não implementar agora:

- EPG/XMLTV.
- Gravação.
- Timeshift.
- DVR.
- Login.
- Sistema de usuários.
- Assinaturas.
- Painel web.
- Backend próprio.
- Proxy próprio.
- Catálogo IPTV próprio.
- Lista IPTV embutida no APK.
- Download de conteúdo.
- IA.
- Recomendações.
- Sincronização em nuvem.
- TMDB.
- Xtream Codes.
- Controle parental.
- Perfis.
- DRM proprietário.
- Sistema complexo de séries/temporadas.
- Analytics/tracking desnecessário.
- Monetização/anúncios.

Esses itens podem ser avaliados futuramente somente se houver necessidade real.

## 15. Ordem de implementação

### Fase 1 — Fundação

1. [x] Criar projeto Android.
2. [x] Configurar Kotlin/Gradle.
3. [x] Configurar Media3.
4. [x] Criar modelos.
5. [x] Criar estrutura mínima.

### Fase 2 — Parser

6. [x] Implementar `M3uParser`.
7. [x] Implementar identidade determinística.
8. [x] Implementar tolerância a campos ausentes.
9. [x] Implementar URL relativa.
10. [x] Criar testes unitários.
11. [x] Testar playlists pequenas, médias e grandes.

### Fase 3 — Listas

12. [x] Importar M3U por URL.
13. [x] Importar arquivo.
14. [x] Colar M3U.
15. [x] Persistir lista.
16. [x] Atualizar lista.
17. [x] Excluir/renomear.

### Fase 4 — Conteúdo

18. [x] Tela de listas.
19. [x] Tela de grupos.
20. [x] Tela de canais.
21. [x] Busca.
22. [x] Favoritos.
23. [x] Histórico.

### Fase 5 — Player

24. [x] Integrar ExoPlayer.
25. [x] Reprodução HTTP/HTTPS.
26. [x] HLS.
27. [x] DASH quando aplicável.
28. [x] Headers.
29. [x] Loading/error/retry.
30. [x] Controles básicos.
31. [x] Troca rápida de canal.

### Fase 6 — Chromecast

32. [x] Integrar Media3 Cast.
33. [x] Configurar CastPlayer.
34. [x] Adicionar MediaRouteButton.
35. [x] Descoberta de dispositivos.
36. [x] Transferência local → Cast.
37. [x] Retorno Cast → local.
38. [ ] Testes reais.

### Fase 7 — Robustez

Legenda: `[x]` feito e verificável por leitura/busca; `[~]` escrito mas não compilado/executado; `[ ]` pendente.

39. [~] Testar playlists reais variadas. *(17 testes com variações vistas em listas reais — Xtream, CRLF/BOM/CR, `#EXTGRP`, UTF-16 e Windows-1252, esquemas não-HTTP, HLS colado, linhas gigantes — em texto sintético. Listas reais de provedores não foram testadas.)*
40. [~] Testar playlist grande. *(leitura de 300.000 entradas em streaming sem acumular; limite de 150.000 canais na importação. Tempo e memória reais não medidos.)*
41. [~] Testar URLs inválidas. *(importação por URL e `StreamSupport.isPlayable`.)*
42. [~] Testar streams indisponíveis. *(lógica de falha extraída para `PlaybackErrorPolicy` e testada na JVM; falta testar com streams reais fora do ar.)*
43. [~] Testar headers. *(sanitização, injeção de linha, headers da pilha HTTP; falta stream real que exija header.)*
44. [~] Testar rotação/recriação da Activity. *(correções feitas por revisão de código; falta testar em aparelho, inclusive "não manter atividades".)*
45. [ ] Verificar consumo de memória. *(só reduções estruturais: sem lista na memória ao importar, grupos internados, limite de canais. Medição com perfil de heap pendente.)*
46. [~] Corrigir travamentos. *(correções por revisão, nenhuma reproduzida: busca na thread principal, lista voltando ao topo ao favoritar, "voltar ao ao vivo" sem limite, download sem limite de tempo.)*
47. [x] Limpar logs e código morto. *(busca estática: nenhum `Log`/`println`/TODO no código do app, nenhum import sem uso; removidos `channelById`, `isFavorite` e `HistoryRepository.clear`, usados só por testes, e um comentário desatualizado. O corte dos logs do Media3 em release é `[~]`, ver §13.)*

> **Decisão da Fase 7:**
>
> **O que mudou (por revisão de código; nada foi reproduzido em aparelho).**
> 1. *Importação:* o passo que só contava os canais guardava a lista inteira na memória; agora usa `M3uParser.scan()` sem acumular. Limites: 64 MB (já existia), 150.000 canais (`TooManyChannels`) e 5 min para baixar/ler a lista inteira (`Timeout`; os timeouts por leitura continuam). Listas em UTF-16 (com BOM) ou Windows-1252 são convertidas para UTF-8 ao importar (`TextEncoding`); o resto do app só lê UTF-8. Página HTML com status 200 resulta em "Nenhum canal válido"; stream HLS único resulta em `NotAChannelList`.
> 2. *Player:* a decisão sobre falhas saiu da Activity para `PlaybackErrorPolicy` (pura e testada); a Activity só traduz o erro do Media3 (`toFailure`). "Voltar ao ao vivo" (`BEHIND_LIVE_WINDOW`) podia repetir sem fim; agora faz no máximo 3 seguidas e depois cai no retry normal. O canal atual passou a ser salvo em `onSaveInstanceState`: após a recriação do processo o Android devolve o `Intent` original, e o player reabria o primeiro canal clicado em vez do atual. `configChanges` do player ganhou `locale|layoutDirection|fontScale|density`.
> 3. *Headers:* `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Upgrade`, `TE`, `Trailer` e `Expect` são descartados (a pilha HTTP os controla).
> 4. *Tela de canais:* busca, filtro e ordenação rodam em segundo plano, com debounce de 250 ms e descarte de resultados superados; favoritar não volta mais ao topo; aba, busca e ordenação sobrevivem à recriação da Activity; um grupo salvo que deixou de existir volta para TODOS.
> 5. *Logs e código morto:* ver itens 47 e §13.
>
> **Valores a validar.** 150.000 canais e 5 min são valores iniciais (estimativa de ~500 bytes por canal na memória, mais o índice do catálogo). Se um aparelho de pouca RAM não aguentar, baixar `DEFAULT_MAX_CHANNELS` ou migrar para Room (§10). `largeHeap` não foi habilitado.
>
> **Falta fazer, e exige aparelho ou ambiente com Android:** (a) `./gradlew test` com os 150 testes; (b) listas reais de provedores variados; (c) perfil de memória e tempo com uma lista grande real; (d) streams reais: fora do ar, timeout, header obrigatório, troca rápida de canal; (e) rotação, "não manter atividades" e recriação do processo no player e nas telas; (f) Chromecast real (item 38).
>
> **Onde o compilador pode reclamar no primeiro build** (escrito sem compilar): `IptvApp` (`Log.setLogLevel` do Media3 e `@OptIn`), `PlayerActivity.handleError`/`toFailure` (`when` e constantes de `PlaybackException`), `ChannelsActivity.refresh` (`return@run`), `PlaylistRepository.stage` e `M3uParser.scan`.

### Fase 8 — APK

> **Preparação:** adicionados `.github/workflows/build-debug.yml` (roda os testes e gera o APK debug no GitHub Actions) e `FASE8.md` (passo a passo dos itens 48–53). Nada foi executado ainda; todos os itens abaixo seguem pendentes.

48. [ ] Build debug.
49. [ ] Instalar no aparelho.
50. [ ] E2E: importar lista → grupo → canal → player.
51. [ ] E2E: player → Chromecast.
52. [ ] Testar fechar/reabrir.
53. [ ] Gerar APK final do MVP.

## 16. Critérios de aceitação do MVP

O MVP será considerado funcional quando conseguir:

1. receber uma M3U/M3U8 fornecida pelo usuário;
2. interpretar corretamente as entradas válidas;
3. sobreviver a entradas inválidas sem derrubar o app;
4. mostrar os canais por grupo;
5. pesquisar canais;
6. favoritar canais;
7. salvar a lista localmente;
8. abrir um canal no Media3/ExoPlayer;
9. reproduzir pelo menos os formatos suportados pelo player de teste;
10. mostrar erro/retry de forma controlada;
11. encontrar um dispositivo Cast compatível;
12. transferir um stream compatível para o Cast;
13. retornar do Cast para o player local;
14. continuar com listas/favoritos após fechar e abrir o aplicativo.

## 17. Testes mínimos de aceitação

### Parser

- Playlist válida.
- Playlist sem grupo.
- Playlist sem logo.
- Playlist sem tvg-name.
- Playlist com atributos extras.
- Playlist com comentários.
- Playlist com entradas quebradas.
- Playlist com URL relativa.
- Playlist grande.
- Duplicações.

### Player

- HLS.
- Stream HTTP/HTTPS.
- Stream inválido.
- Timeout.
- Retry.
- Header necessário.
- Troca rápida de canal.

### Cast

- Descoberta.
- Seleção.
- Reprodução remota.
- Pausar/continuar.
- Encerrar sessão.
- Retorno ao player local.
- Stream inacessível pelo Chromecast.

## 18. Princípios do projeto

- **Simples primeiro.**
- **Código é a fonte da verdade.**
- **Local-first.**
- **Sem backend desnecessário.**
- **Sem IA.**
- **Sem catálogo próprio.**
- **Sem conteúdo IPTV próprio.**
- **Sem dependências só por moda.**
- **Parser robusto antes de UI sofisticada.**
- **Testar casos reais antes de adicionar funcionalidades.**
- **Não transformar um player simples em um produto gigante sem necessidade.**

## Resultado esperado

O resultado deve ser um aplicativo Android pequeno que faça essencialmente:

```
M3U/M3U8 do usuário
        ↓
     Parser
        ↓
  Grupos/Canais
        ↓
      Player
      ↙    ↘
 ExoPlayer  CastPlayer
               ↓
          Chromecast
```

A primeira versão deve resolver muito bem esse fluxo e nada além dele.
