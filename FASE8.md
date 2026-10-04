# Fase 8 — APK: passo a passo

Nada aqui foi executado ainda: o ambiente onde o código foi escrito não tem Gradle, SDK Android nem rede.

## 48. Build debug (via GitHub Actions)
1. Suba o projeto para o GitHub (a pasta `IPTV/` é a raiz do repositório; `.github/workflows/build-debug.yml` já está incluído).
2. Aba **Actions → Build debug → Run workflow** (ou faça um push).
3. O job roda `testDebugUnitTest` (150 testes) e depois `assembleDebug`.
4. Baixe o artefato **iptv-debug-apk** (e **relatorios-de-teste**, se algo falhar).
5. Se o compilador reclamar, cole aqui o erro. Os pontos mais prováveis estão no PLANO.md ("Onde o compilador pode reclamar"): `IptvApp`, `PlayerActivity.handleError/toFailure`, `ChannelsActivity.refresh`, `PlaylistRepository.stage`, `M3uParser.scan`.

## 49. Instalar no aparelho
Copie o `app-debug.apk` para o celular e instale (permitir fontes desconhecidas), ou `adb install -r app-debug.apk`.

## 50. E2E: lista → grupo → canal → player
Importar por URL, por arquivo e colando; abrir um grupo; tocar um canal (HLS e HTTP simples); favoritar; buscar.

## 51. E2E: player → Chromecast (também fecha o item 38)
Chromecast/Google TV na mesma rede; botão Cast; transmitir; pausar/continuar; trocar de canal; "Parar transmissão"; stream que o Chromecast não alcança.

## 52. Fechar/reabrir
Listas, favoritos e histórico persistem; rotação no player; "Não manter atividades" ligado nas opções de desenvolvedor.

## 53. APK final do MVP
Só depois de 48–52 passarem. Exige keystore de assinatura (release); não incluir a keystore no repositório.
