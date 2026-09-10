# Berry Animes 14.1 — desenvolvimento

Versão de teste local com login em duas etapas e reprodução HLS autenticada. Sem publicação.

## Verificado em 2026-09-09

- Build debug, Spotless e lint executados; APK instalado no Anikku 0.1.7.
- Popular: seção pública “Populares no Brasil”, sem paginação própria.
- Busca HTTP: One Piece, Naruto, Solo Leveling e consulta sem resultado.
- API `/api/animes`: paginação observada no Chromium; parâmetros `q`, `type`, `take`, `page`, `locale` copiados do frontend.
- Anikku: Popular, busca, capas, detalhes e episódios de Witch Hat Atelier (13), Naruto (219) e One Piece (335).
- One Piece: temporadas 19, 20 e 21. O HTML limita cada temporada a 24 cartões; a extensão lê a lista completa das propriedades RSC de EpisodeGrid, usando o helper compartilhado existente.
- Dub/Leg preservados no episódio. Reprodução interna confirmada em DAN DA DAN T2 E12/E13, com imagem e áudio japonês. Troca de episódio e seek até 07:04 confirmados, com avanço posterior a 07:15. A suspensão do Waydroid interferiu temporariamente no teste.
- Preferências no Anikku: formulário nativo, validação de campos vazios, consulta “Desconectado” e limpeza de sessão sem conta conectada.
- “Criar conta” abre `https://sso.berryanimes.com/register` no navegador. Cadastro e desafio de segurança confirmados na tela; conta criada pelo próprio usuário.
- Ícone obtido de `/icons/icon-512.png` do próprio site.

## Autenticação e pendências

- Login implementado conforme formulário público: POST multipart ao SSO, campos ocultos obtidos dinamicamente, e-mail/senha e Origin/Referer do formulário.
- Confirmado com credenciais autorizadas, sem registro de segredos: a senha válida redireciona para `/verify`. O login exige código de seis dígitos enviado por e-mail, válido por 15 minutos.
- Implementada segunda etapa nativa: preserva os campos ocultos `email`, `mode` e Server Action da resposta real; envia `code` ao formulário de verificação. Reenvio usa o segundo formulário observado e respeita espera de 60 segundos. Nenhuma senha ou código fica em preferências.
- Incluída ação “Informar código de verificação” para retomar uma verificação pendente durante a mesma sessão do aplicativo.
- Senha não persistida; cookies ficam sob responsabilidade do CookieJar do aplicativo. A consulta pública de sessão usa `/api/user/achievements/unread`, observada no frontend (401 sem sessão).
- Código válido aceito no Anikku. Cookie persistente e sessão HTTP 200 confirmados após reinício/reinstalação. Configurações agora consultam a sessão ao abrir; antes, o texto inicial não refletia o login salvo. Expiração/refresh, código incorreto/expirado e logout autenticado ainda precisam de testes.
- Watch sem sessão redireciona ao SSO. A fonte informa necessidade de login; o Anikku também registra “No available videos”.
- Fluxo implementado: cookie SSO → GET `/api/watch/{token}/{episodio}` → HLS assinado em `media-cdn.berryanimes.com` → variantes/segmentos. Headers provider/context e cookies obtidos da resposta e do CookieJar, sem hardcode. PlaylistUtils reutilizado sem modificações.
- Qualidades reais: 1080p, 720p e 480p, ordenadas da maior para a menor. Faixas externas Dub/Leg quando declaradas no master; áudio multiplexado permanece selecionável no player. Legendas ASS da API preservadas, pt-BR primeiro. Seleção individual de dublado e renderização de ASS no mpv ainda precisam de validação específica.
- WebView: My Hero Academia: Vigilantes reproduziu em 1920×1080. API da conta testada informou `adFree.active=false`, `preRoll=false`, `postRoll=false`. Não foram simulados anúncios. Quando a API exige pré/pós-anúncio, a extensão informa necessidade do player oficial.
- Instrumentação temporária de sessão e depuração WebView removida da entrega.
- Correção de obras sem `streamUrl`: One Piece utiliza `provider=blogger`, com `/video.g` nos campos de embed. A extensão reutiliza BloggerExtractor e retorna MP4 de Googlevideo, sem enviar cookies/contexto Berry ao Blogger. Alternativas falham isoladamente; qualidades mantêm ordenação decrescente.
- Anikku: One Piece T21 E1165 reproduziu com imagem em movimento e duração 23:34 após a correção. Áudio e seek desse servidor ainda não foram medidos separadamente. Build, Spotless e lint passaram; nenhum helper compartilhado modificado.
- Recentes desativado: não foi identificada lista cronológica verificável. Carrosséis editoriais não são usados como substitutos.
- O filtro oficial MOVIE retorna também séries (por exemplo, Bleach/One Piece). Não corrigimos a classificação inventando metadados. O modelo suporta filme de episódio único somente quando há metadata Movie e botão Watch reais; esse caso ainda precisa de teste com uma obra correspondente.
- Nenhum helper compartilhado modificado. Lint sem erros; avisos herdados de QuickJS/manifesto e aviso visual de ícone.

## Verificação local

```sh
./gradlew :src:pt:berryanimes:spotlessCheck :src:pt:berryanimes:assembleDebug :src:pt:berryanimes:lintDebug
```

APK: `build/outputs/apk/debug/aniyomi-pt.berryanimes-v14.1-debug.apk`.
Sem commit, push ou publicação.
