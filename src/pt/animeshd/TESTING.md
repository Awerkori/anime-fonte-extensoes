# Animes HD 14.1

Verificação: 2026-09-08, Anikku 0.1.7 / Waydroid, player interno mpv.

## Site e implementação

- WordPress/DooPlay. `/wp-json/` existe, mas não expõe um catálogo útil de `tvshows`; a rota REST do player retornou corpo vazio. O AJAX público do tema funciona.
- Popular usa o arquivo completo `/animes/`, pois não foi encontrado ranking público. Recentes usa a busca WordPress de obras, ordenada por publicação (`post_type=tvshows&orderby=date&order=DESC`), não a lista de episódios.
- Catálogo: 30 obras por página; páginas 1 e 2 sem sobreposição. Popular e Recentes carregaram páginas seguintes no Anikku. Busca Naruto: quatro obras, confirmadas no aplicativo. Capas absolutas, sinopse, gêneros, ano e duração vêm do HTML. Status permanece desconhecido quando ausente.
- Temporadas e episódios vêm de `#seasons`; número da temporada preservado no nome, número do episódio e data do site preservados. One Piece contém 675 links: não são sintetizados episódios ausentes.
- Player: episódio → `doo_player_ajax` → Blogger → RPC `WcwnYd` pelo `BloggerExtractor` compartilhado → `*.googlevideo.com/videoplayback`. MP4 progressivo com áudio, 360p e 720p encontrados. Maior resolução primeiro por padrão; cada opção falha isoladamente.
- Idioma vem do rótulo real da opção do player. Opções como “P1” permanecem “Idioma não informado”.
- Ícone: favicon original do site, `/wp-content/uploads/2024/08/cropped-favicon-192x192.png`.

## Reprodução

| Caso | Resultado |
| --- | --- |
| Uzumaki, uma temporada / quatro episódios | E1 reproduziu; 720p selecionado automaticamente, 360p também disponível |
| Próximo episódio | Botão do player abriu Uzumaki E2; imagem, duração e progresso confirmados |
| Áudio | Faixa de áudio selecionada e sinal PCM não silencioso na saída exclusiva do Waydroid |
| Super Cube Dublado | E1 reproduziu; opções Dublado 720p/360p; seek para 06:17 com imagem e reprodução |
| KAMUI: He's Behind You, recente | E3 reproduziu legendado, 720p/360p |
| Tower of God | Duas temporadas / 39 episódios; T2E1 reproduziu no Anikku, imagem e progresso confirmados; 720p/360p |
| One Piece, obra longa | 675 episódios; E1 resolvido por HTTP com 720p/360p |

Outros episódios auditados por HTTP: Jujutsu Kaisen T3E7, Lord of Mysteries dublado e Tougen Anki dublado. Todos os embeds amostrados eram Blogger; não foi necessário WebView.

## Limitação comprovada

Tower of God T1E1: o Blogger responde HTTP 200, mas o RPC devolve o resultado de mídia nulo, com código interno `[5]`, sem URLs. O mesmo acontece fora do aplicativo. T2E1 retorna streams normalmente. Não se atribui significado não documentado ao código nem se fabrica URL alternativa.

## Build

`ANDROID_HOME` e `ANDROID_SDK_ROOT`: `/home/awerkori/.android-sdk`.

```sh
./gradlew :src:pt:animeshd:assembleDebug :src:pt:animeshd:spotlessCheck :src:pt:animeshd:lintDebug
```

Build e Spotless aprovados. Lint sem erros, com dois avisos herdados: alinhamento de `libquickjs.so` e regras de backup do manifesto-base. Nenhum helper compartilhado alterado.

APK instalado: `build/outputs/apk/debug/aniyomi-pt.animeshd-v14.1-debug.apk`; versão pública `14.1`, código `1`, conforme a convenção de extensão nova.

Logs somente em DEBUG: `ANIMESHD_DEBUG` e `ANIMESHD_VIDEO`, sem tokens ou URLs de mídia.
