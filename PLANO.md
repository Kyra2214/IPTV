# IPTV

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

- [ ] Android nativo.
- [ ] Kotlin.
- [ ] Estrutura simples e modular.
- [ ] UI pequena e direta.
- [ ] Persistência local.
- [ ] Sem backend próprio.
- [ ] Sem dependência de IA.
- [ ] Sem catálogo IPTV embutido.

### 2. Gerenciamento de listas

Entrada por:

- [ ] URL HTTP/HTTPS.
- [ ] Arquivo M3U/M3U8.
- [ ] Conteúdo M3U colado manualmente.

Gerenciamento:

- [ ] Salvar lista localmente.
- [ ] Nomear/renomear lista.
- [ ] Excluir lista.
- [ ] Atualizar lista por URL.
- [ ] Guardar a origem da lista.
- [ ] Guardar configurações básicas associadas à lista.

A lista deve continuar disponível após fechar e abrir o aplicativo.

## 3. Parser M3U/M3U8

O parser deve trabalhar com o padrão mais comum de playlists IPTV sem exigir que todos os campos estejam presentes.

### Campos prioritários

- [ ] `#EXTINF`.
- [ ] Nome exibido após a vírgula.
- [ ] `tvg-id`.
- [ ] `tvg-name`.
- [ ] `tvg-logo`.
- [ ] `group-title`.

### Comportamento esperado

- [ ] Aceitar `http://` e `https://`.
- [ ] Suportar URL relativa quando houver URL base.
- [ ] Tolerar atributos ausentes.
- [ ] Tolerar grupo ausente.
- [ ] Tolerar nome ausente.
- [ ] Ignorar linhas de comentário desconhecidas.
- [ ] Ignorar entradas inválidas sem interromper o restante da playlist.
- [ ] Não derrubar o aplicativo por uma entrada malformada.
- [ ] Preservar a ordem original quando possível.
- [ ] Evitar duplicações óbvias quando aplicável.
- [ ] Gerar identidade determinística para canais sem `tvg-id`.

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

- [ ] M3U mínima válida.
- [ ] M3U com vários grupos.
- [ ] M3U sem `group-title`.
- [ ] M3U sem `tvg-name`.
- [ ] M3U sem `tvg-logo`.
- [ ] M3U com atributos extras.
- [ ] M3U com aspas simples e/ou variações comuns de atributos.
- [ ] URL HTTP.
- [ ] URL HTTPS.
- [ ] URL relativa + URL base.
- [ ] Entrada sem URL.
- [ ] Linha de comentário entre EXTINF e URL.
- [ ] Entrada malformada.
- [ ] Playlist vazia.
- [ ] Playlist grande.
- [ ] Duplicações.
- [ ] Identidade determinística de canais.

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

- [ ] Todos.
- [ ] Favoritos.
- [ ] Grupos.
- [ ] Busca por nome.
- [ ] Ordenação simples.
- [ ] Grupo `Ungrouped` para entradas sem categoria.

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

## 7. Favoritos e histórico

- [ ] Favoritar/desfavoritar canal.
- [ ] Persistir favoritos.
- [ ] Manter histórico simples dos últimos canais assistidos.
- [ ] Não depender de UUID aleatório para persistência.

## 8. Reprodução

Usar **AndroidX Media3 / ExoPlayer**.

### Suporte inicial

- [ ] HTTP.
- [ ] HTTPS.
- [ ] HLS/M3U8.
- [ ] DASH quando suportado pelo Media3.
- [ ] Headers HTTP quando necessários e permitidos pelo stream.
- [ ] Redirects HTTP comuns.

### Controles

- [ ] Play/pause.
- [ ] Tela cheia.
- [ ] Indicador de buffering.
- [ ] Nome do canal.
- [ ] Erro de reprodução.
- [ ] Retry controlado.
- [ ] Canal anterior.
- [ ] Próximo canal.

Não criar um player proprietário.

## 9. Chromecast

### Estratégia

Usar **Media3 Cast / CastPlayer** para permitir reprodução local e transmissão para dispositivo Cast compatível.

A documentação oficial do Android mostra o `CastPlayer` como implementação Media3 capaz de alternar entre reprodução local e remota e recomenda o `MediaRouteButton` para descoberta/seleção de dispositivos Cast. citeturn0search0turn0search4

### Implementação

- [ ] Adicionar dependência Media3 Cast.
- [ ] Configurar `DefaultCastOptionsProvider` ou equivalente mínimo.
- [ ] Criar `CastPlayer` usando o ExoPlayer como player local.
- [ ] Adicionar botão Cast na tela do player.
- [ ] Detectar dispositivos Cast disponíveis.
- [ ] Selecionar dispositivo.
- [ ] Transferir a reprodução do stream.
- [ ] Exibir estado local/remoto.
- [ ] Encerrar Cast e retornar ao aparelho.
- [ ] Testar com Chromecast/Google TV/Android TV compatível.

A prioridade é **Cast do stream**, não espelhamento da tela inteira.

### Observação importante

O Chromecast precisa conseguir acessar diretamente a URL do stream. Se a fonte exigir algo que o receptor não consiga reproduzir ou autenticar, o Cast pode falhar mesmo que a reprodução local funcione.

Não criar proxy próprio apenas para contornar isso no MVP.

## 10. Persistência

Primeiro escolher a solução mais simples que suporte o tamanho real das listas.

### Requisitos

- [ ] Listas persistentes.
- [ ] Favoritos persistentes.
- [ ] Histórico persistente.
- [ ] Configurações básicas persistentes.

### Estratégia

Para listas pequenas/médias, armazenamento local simples pode ser suficiente.

Para listas grandes, usar banco local (Room/SQLite) e processamento incremental/paginado quando necessário.

**Não carregar uma playlist gigantesca inteira em memória sem necessidade.**

Não adicionar Hilt, Clean Architecture ou outras camadas apenas por padrão. Toda dependência deve resolver uma necessidade concreta.

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

- [ ] Nunca executar conteúdo da playlist como código.
- [ ] Validar URLs antes de abrir.
- [ ] Não registrar tokens/senhas/URLs sensíveis em logs de produção.
- [ ] Limitar tamanho de entrada quando apropriado.
- [ ] Tratar timeouts e erros de rede.
- [ ] Evitar travamento da UI durante download/parsing.
- [ ] Processar listas grandes fora da thread principal.
- [ ] Não incluir credenciais de listas de teste no repositório.

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

1. [ ] Criar projeto Android.
2. [ ] Configurar Kotlin/Gradle.
3. [ ] Configurar Media3.
4. [ ] Criar modelos.
5. [ ] Criar estrutura mínima.

### Fase 2 — Parser

6. [ ] Implementar `M3uParser`.
7. [ ] Implementar identidade determinística.
8. [ ] Implementar tolerância a campos ausentes.
9. [ ] Implementar URL relativa.
10. [ ] Criar testes unitários.
11. [ ] Testar playlists pequenas, médias e grandes.

### Fase 3 — Listas

12. [ ] Importar M3U por URL.
13. [ ] Importar arquivo.
14. [ ] Colar M3U.
15. [ ] Persistir lista.
16. [ ] Atualizar lista.
17. [ ] Excluir/renomear.

### Fase 4 — Conteúdo

18. [ ] Tela de listas.
19. [ ] Tela de grupos.
20. [ ] Tela de canais.
21. [ ] Busca.
22. [ ] Favoritos.
23. [ ] Histórico.

### Fase 5 — Player

24. [ ] Integrar ExoPlayer.
25. [ ] Reprodução HTTP/HTTPS.
26. [ ] HLS.
27. [ ] DASH quando aplicável.
28. [ ] Headers.
29. [ ] Loading/error/retry.
30. [ ] Controles básicos.
31. [ ] Troca rápida de canal.

### Fase 6 — Chromecast

32. [ ] Integrar Media3 Cast.
33. [ ] Configurar CastPlayer.
34. [ ] Adicionar MediaRouteButton.
35. [ ] Descoberta de dispositivos.
36. [ ] Transferência local → Cast.
37. [ ] Retorno Cast → local.
38. [ ] Testes reais.

### Fase 7 — Robustez

39. [ ] Testar playlists reais variadas.
40. [ ] Testar playlist grande.
41. [ ] Testar URLs inválidas.
42. [ ] Testar streams indisponíveis.
43. [ ] Testar headers.
44. [ ] Testar rotação/recriação da Activity.
45. [ ] Verificar consumo de memória.
46. [ ] Corrigir travamentos.
47. [ ] Limpar logs e código morto.

### Fase 8 — APK

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
