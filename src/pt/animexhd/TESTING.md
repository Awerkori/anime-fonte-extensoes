# AnimeXHD 14.1

Auditoria e testes: 08/09/2026. Repositório local `anime-fonte-extensoes`, branch `main`;
origin `Awerkori/anime-fonte-extensoes`. Nenhuma alteração fora de `src/pt/animexhd`
foi feita nesta tarefa. Trabalhos preexistentes foram preservados. Sem commit/push/publicação.

## Implementação

- `extVersionCode = 1`, APK confirmado com `versionName = 14.1`. DooPlay é dependência
  explícita, sem herdar o contador de versões do tema. Nenhum helper compartilhado modificado.
- Popular: `/trending/`, 30 resultados por página, 13 páginas anunciadas na auditoria.
- Recentes e busca: busca WordPress restrita a `tvshows`/`movies`, ordenada por data de
  publicação. Recentes são obras adicionadas, não uma lista de episódios novos.
- Páginas 1/2 de Popular e Recentes: 30 obras distintas em cada página, próximo link presente,
  sem sobreposição entre as páginas. Busca Megalo: um resultado.
- Filtros reais de animes, filmes, OVAs, dublado/legendado; gêneros e anos carregados pela REST.
  Arquivos dublado, legendado e filmes verificados com 30 resultados cada.
- Metadados via `/wp-json/wp/v2/tvshows|movies?slug=…&_embed=…`: capa, sinopse sem o texto
  promocional acrescentado pelo tema, gêneros, tags de idioma e ano. Status de exibição não
  fornecido: permanece desconhecido. `status=publish` da API não é status do anime.
- Temporadas/episódios pelo DOM DooPlay. Nomes incluem T/E e título real; datas sem hora
  são interpretadas no fuso brasileiro. Filmes são um episódio único.
- Ícone: PNG 192 px original do site (`cropped-25364-192x192.png`), em drawable-xxxhdpi,
  com substituição explícita do ícone herdado do tema.

## Player

Fluxo observado em HTTP e Chromium:

`episódio/filme → myembed.biz → iframe#video-player em playerflix.ink → /inc/Ajax.php`

A API recebe `type=tv|movie`, `id`, e, para séries, `season`/`episode`.
Usa `X-Requested-With: XMLHttpRequest` e Referer da página Playerflix. O Referer do AnimeXHD
é necessário no primeiro iframe; sem ele MyEmbed entrega uma página de outro conteúdo.
Nenhum token/cookie de reprodução foi fixado. Não foi necessário WebView para Blogger/VIP.

As opções JSON fornecem `embed` e `lang`. A interface do próprio Playerflix mapeia `pt-br`
para Dublado e `en-us` para Legendado. Isso tem precedência sobre o rótulo genérico do site.

- Blogger: reutiliza `BloggerExtractor`, MP4 progressivo googlevideo.com, 360p/720p observados.
- VIP: reutiliza `FireplayerExtractor` em embedplayer2.xyz, POST `player/index.php?do=getVideo`,
  `securedLink` → HLS com variantes 360p/720p observadas. Referer/Origin do helper preservados.
- Falhas/timeouts isolados por opção; preservam vídeos dos demais servidores.
- Qualidade padrão **Maior disponível**. Ordenação numérica decrescente, estável mesmo quando
  o Anikku chama `sort()` novamente. Dublado/Blogger desempata resoluções iguais.
  Preferências também permitem selecionar uma resolução; se ausente, usa a maior disponível.
- Logs debug `ANIMEXHD_DEBUG`/`ANIMEXHD_VIDEO`: contagens, hosts, idiomas e qualidades;
  não registram tokens nem URLs temporárias.

## Verificação real

Anikku 0.1.7 no Android/Waydroid x86_64, player interno mpv:

- Megalo Box: metadados e 26 episódios em 2 temporadas; T1E1 reproduziu.
- Após a correção, seleção automática **MyEmbed - Dublado - 720p (Blogger)** confirmada
  visualmente na janela de qualidades, sem selecionar essa qualidade manualmente.
- Navegação pelo botão próximo passou de T1E1 para T1E2; reprodução continuou.
- Seek observado em Megalo e Your Name. Your Name reproduziu pelo VIP; duração 1:46:36,
  imagem/posição observadas em 11:48. Há opções dubladas e legendadas.
- Death Note: HTML validado com 1 temporada/37 episódios. Reprodução desse título não foi
  verificada individualmente no aplicativo pelo agente.
- O usuário também relatou que seus testes estavam funcionando, pedindo apenas a correção
  da qualidade inicial. Áudio não foi capturado/analisado separadamente pelo agente.

Limitação real: JoJo 6x12 e vários filmes de Code Geass consultados oferecem somente
Superflix Premium. O HTTP retornou bloqueio Cloudflare e o iframe no Chromium exigiu
verificação humana Turnstile. Essa opção não foi implementada como vídeo direto.
Logo, essas obras não têm reprodução validada nesta extensão. Outros hosts como WatchPlayer
não são resolvidos. Não se afirma cobertura total do catálogo.

## Build

`./gradlew :src:pt:animexhd:assembleDebug :src:pt:animexhd:spotlessCheck :src:pt:animexhd:lintDebug`

Executado com ANDROID_HOME e ANDROID_SDK_ROOT apontando para `~/.android-sdk`.
Build/Spotless aprovados; lint sem erros, dois avisos herdados (QuickJS/alinhamento 16 KB,
backup do core). Não houve mudanças em extractors/unpackers compartilhados nem criação
ou execução de nova suíte unitária; validação por build, páginas reais e aplicativo.

APK: `build/outputs/apk/debug/aniyomi-pt.animexhd-v14.1-debug.apk`.
