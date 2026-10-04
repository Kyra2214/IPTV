# IPTV

Aplicativo Android simples para reprodução de listas IPTV fornecidas pelo próprio usuário.

## Objetivo

Criar um player IPTV enxuto, local-first e sem backend próprio, com foco em:

- adicionar listas M3U/M3U8 manualmente;
- interpretar e organizar os canais;
- separar conteúdo por grupo/tipo;
- reproduzir streams no aparelho;
- transmitir o stream para Chromecast;
- manter as listas e configurações localmente.

O projeto não terá catálogo, servidor ou conteúdo IPTV próprio.

## Escopo da primeira versão

### 1. Base do aplicativo
- [ ] Projeto Android nativo.
- [ ] Kotlin.
- [ ] Estrutura simples e modular.
- [ ] Persistência local.
- [ ] Tema/interface básica.

### 2. Gerenciamento de listas
- [ ] Adicionar lista por URL.
- [ ] Importar arquivo M3U/M3U8.
- [ ] Colar conteúdo M3U manualmente.
- [ ] Salvar listas localmente.
- [ ] Renomear lista.
- [ ] Excluir lista.
- [ ] Atualizar lista por URL.

### 3. Parser M3U
- [ ] Ler entradas #EXTINF.
- [ ] Extrair nome do canal.
- [ ] Extrair URL do stream.
- [ ] Extrair group-title.
- [ ] Extrair tvg-name.
- [ ] Extrair tvg-logo.
- [ ] Tolerar campos ausentes.
- [ ] Ignorar entradas inválidas sem derrubar a lista.

### 4. Organização
- [ ] Tela Todos.
- [ ] Separação por grupos.
- [ ] Busca por nome.
- [ ] Favoritos básicos.
- [ ] Ordenação simples.
- [ ] Tratamento de grupos desconhecidos.

### 5. Reprodução
- [ ] Integrar Media3/ExoPlayer.
- [ ] Tela de reprodução simples.
- [ ] Play/pause.
- [ ] Tela cheia.
- [ ] Indicador de carregamento.
- [ ] Mensagem de erro de reprodução.
- [ ] Troca rápida de canal.

### 6. Chromecast
- [ ] Integrar Google Cast SDK.
- [ ] Detectar dispositivos Cast disponíveis.
- [ ] Selecionar Chromecast.
- [ ] Enviar URL do stream ao Chromecast.
- [ ] Exibir estado da sessão Cast.
- [ ] Permitir encerrar transmissão.

A prioridade é transmitir o stream diretamente para o Chromecast, e não fazer espelhamento de tela.

## Arquitetura inicial

```
UI
├── Listas
├── Categorias
├── Canais
└── Player
       │
       ├── Media3 / ExoPlayer
       └── Chromecast
       
Dados locais
├── Listas
├── Canais
└── Favoritos

Core
└── M3U Parser
```

## Telas previstas

### Tela 1 — Listas
- Listas cadastradas.
- Adicionar lista.
- Editar/excluir.

### Tela 2 — Conteúdo
- Todos.
- Grupos.
- Busca.
- Favoritos.

### Tela 3 — Player
- Vídeo.
- Controles básicos.
- Nome do canal.
- Botão Chromecast.
- Tela cheia.

## Persistência

Nenhum backend será necessário na primeira versão.

Os dados do usuário ficam no aparelho:

- origem da lista;
- nome da lista;
- canais processados;
- favoritos;
- configurações básicas.

Room/SQLite será utilizado se necessário para listas grandes; a implementação deve evitar carregar listas gigantes inteiras na memória sem necessidade.

## Fora do escopo inicial

- EPG.
- Gravação.
- Timeshift.
- Login.
- Sistema de usuários.
- Assinaturas.
- Painel web.
- Servidor/proxy próprio.
- Catálogo IPTV próprio.
- Download de conteúdo.
- IA.
- Recomendações.
- Sincronização em nuvem.

## Ordem de implementação

1. Criar estrutura Android.
2. Criar modelo de dados.
3. Implementar parser M3U.
4. Implementar importação de lista.
5. Implementar armazenamento local.
6. Criar tela de listas.
7. Criar tela de categorias/canais.
8. Integrar Media3.
9. Criar player funcional.
10. Integrar Chromecast.
11. Testar listas pequenas e grandes.
12. Testar reprodução de diferentes formatos.
13. Testar Chromecast.
14. Fazer limpeza e estabilização.
15. Gerar APK de teste.

## Critérios mínimos da primeira versão

A versão inicial estará funcional quando conseguir:

1. receber uma lista M3U fornecida pelo usuário;
2. interpretar os canais;
3. mostrar os canais separados por grupo;
4. abrir um canal no player;
5. reproduzir o stream;
6. enviar o stream para um Chromecast;
7. manter a lista salva após fechar o aplicativo.

## Princípios

- Simples primeiro.
- Local-first.
- Sem backend desnecessário.
- Sem dependência de IA.
- Sem catálogo próprio.
- Código pequeno e fácil de manter.
- Não implementar funcionalidades antes de serem necessárias.
