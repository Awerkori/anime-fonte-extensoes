# Aniture PT — auditoria e validação

Testado em 08/09/2026 no Anikku 0.1.7, Android/Waydroid x86_64, com o player interno mpv.

## Catálogo

O site usa WordPress/DooPlay modificado (`aniture_theme`). Detalhes, temporadas,
episódios e iframes vêm do HTML. Os episódios observados usam `.source-box .pframe iframe`.
O tema também contém suporte AJAX/REST, mas os players testados não o exigiram.

- Popular: `/trending/`, 30 obras por página, 6 páginas anunciadas. Páginas 1, 2 e 6
  verificadas: 30, 30 e 9 obras; sem repetição entre 1/2; última página sem próximo link.
- Recentes: busca WordPress vazia com `post_type[]=tvshows`, `post_type[]=movies`,
  `orderby=date`, `order=DESC`. Inclui séries e filmes por data de publicação.
  Páginas 1, 2 e 7: 30, 30 e 28 obras; sem repetição entre 1/2; término correto.
  Não representa a data de lançamento de novos episódios de uma série antiga.
- Rolagem real no Anikku confirmou carregamento das páginas seguintes em Popular e Recentes.
- A categoria “Lançamentos” continua como filtro; seus 14 resultados não limitam Recentes.
- Busca Naruto: 4 resultados de séries/filmes. Filtro Todos usa o catálogo completo.
- Rick & Morty: 9 temporadas, 91 episódios. Mao: 1 temporada, 13 episódios.
- One Piece legendado: 1.159 episódios disponíveis no HTML. Lacunas da numeração são do site.
- Capas, descrições, categorias e datas vêm do site. Status ausente permanece desconhecido.
- Ícone: favicon PNG de 192 px do próprio site, substituindo explicitamente o ícone do tema.
  Confirmado visualmente na lista de fontes do Anikku.

## Players

Blogger tem prioridade. O wrapper reutiliza `lib/bloggerextractor`, incluindo seu fluxo RPC,
e mantém somente MP4 progressivo com áudio/vídeo em `googlevideo.com/videoplayback`.
Qualidades observadas: 360p e 720p. Há mapeamento de itags para 480p/1080p quando oferecidos;
não foram inventadas essas qualidades nos episódios testados. Referer preservado.
Rick & Morty S9E10 e One Piece 1177 reproduziram no Anikku; seek confirmado em Rick.
Também foi auditado o iframe Blogger do episódio antigo de Rick (S1E1).

O filme **Gintama: Yoshiwara em Chamas** não oferece Blogger. Seus iframes são Byse,
MixDrop e Streamtape. MixDrop foi corrigido no helper compartilhado: `autoUnpacker`
trata o empacotamento atual base 36; uma resposta sem URL não gera um vídeo inválido.
MP4 H.264/AAC 720p reproduzido no Anikku, duração 2:04:39, resposta parcial HTTP 206.

STRP2P foi implementado depois da validação real do Blogger. A auditoria do navegador mostrou:

1. iframe → `/api/v1/info?id=…` → `/api/v1/video?id=…`;
2. configuração ofuscada via AES-CBC com constantes públicas do JavaScript;
3. entrega Tiktok ativa: reescrita `/hls/` → `/hlsmod/<domain>/`, preservando parâmetros;
4. master → variantes `index-f1-v1-a1.m3u8` → segmentos `.image`;
5. segmentos tinham prefixo PNG de 120 bytes antes de MPEG-TS. O acesso direto fazia mpv
   identificar vídeo PNG; `lib/m3u8server` remove esse prefixo para o player interno.

Master/variantes HTTP 200 e segmentos HTTP 200/206 foram verificados em uma sessão HTTP nova.
Reprodução STRP2P 1080p confirmada no Anikku, incluindo seek e continuação em 7:15/23:35.
720p também retornado e validado até o primeiro segmento.

O navegador também chama `/api/v1/player?t=…` periodicamente. A entrega `/v4/` usa credenciais
renováveis `k/kx`; essa rota não é exposta pela extensão. Não se assume que o problema antigo
master 200/variante 404 esteja resolvido em todas as entregas do host. O caminho validado é Tiktok.
Byse, P2PPlay e Streamtape não foram implementados como servidores adicionais.

Cada extractor falha isoladamente. Por padrão, Blogger utilizável encerra a extração.
“Mostrar servidores alternativos” permite listar STRP2P mesmo havendo Blogger.
Sem Blogger utilizável, STRP2P e depois MixDrop são tentados automaticamente.
Logs da fonte usam `ANITURE_DEBUG`/`ANITURE_VIDEO` somente em debug, sem tokens.
Logs do proxy compartilhado foram saneados para não registrar URLs assinadas e headers.

## Build

```sh
ANDROID_HOME=/home/awerkori/.android-sdk ANDROID_SDK_ROOT=/home/awerkori/.android-sdk ./gradlew \
  :src:pt:aniture:assembleDebug :src:pt:aniture:spotlessCheck \
  :lib:mixdropextractor:spotlessCheck :lib:m3u8server:spotlessCheck \
  :src:pt:aniture:lintDebug :lib:unpacker:test
```

Build/Spotless: aprovados. Unpacker: 128 testes, zero falhas.
Lint: zero erros; dois avisos herdados (QuickJS sem alinhamento 16 KB e configuração de backup do core).
APK: `build/outputs/apk/debug/aniyomi-pt.aniture-v14.5-debug.apk`.
Compilação via Gradle CLI; Android Studio e aparelhos ARM/ExoPlayer não foram usados nesta validação.
Nada foi publicado, commitado ou enviado por push.
