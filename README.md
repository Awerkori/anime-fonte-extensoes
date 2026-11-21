<p align="center">
  <img src="https://raw.githubusercontent.com/ProjectNox-org/.github/main/assets/brand/nox-emblem.webp" alt="Emblema oficial do Project Nox" width="96" />
</p>

<h1 align="center">Project Nox · Código-fonte das Extensões de Anime</h1>

<p align="center">
  Código-fonte em Kotlin, parsers e extractors para <strong>Aniyomi, Anikku e forks</strong> (base Yūzōnō / Aniyomi).
</p>

> [!IMPORTANT]
> **Quer apenas instalar as extensões para assistir no seu aplicativo?**
> Você **não precisa baixar este repositório** — ele guarda apenas o código-fonte usado por desenvolvedores. As extensões prontas para instalar ficam no repositório oficial de distribuição:
> [**Project Nox · Extensões de Anime**](https://github.com/ProjectNox-org/anime-extensoes) (`anime-extensoes`)
>
> Para pedir uma extensão nova ou relatar um problema, use a [Central de Pedidos](https://github.com/ProjectNox-org/project-nox-requests) e acompanhe no [Tracker](https://projectnox-org.github.io/project-nox-tracker/).

## Sobre o projeto

O ecossistema de anime do **Project Nox** desenvolve e mantém extensões para reprodução de vídeo com foco na comunidade brasileira (PT-BR), a partir da base upstream do **Yūzōnō / Aniyomi**:

- **Fontes e parsers em Kotlin:** implementação de catálogos, paginação, filtros, listas de episódios, temporadas e resolução de fontes de vídeo para Aniyomi, Anikku e forks.
- **Extractors de vídeo e HLS/M3U8:** bibliotecas reutilizáveis para decodificação de players e processamento de streams HLS/M3U8 via proxy HTTP local (`m3u8server`).
- **Compilação e sincronização upstream:** builds modulares em Gradle via GitHub Actions e sincronização contínua com `yuzono/anime-extensions` no modo `Nox-wins`, preservando extensões protegidas em `.github/nox-protected.txt`.
- **Publicação automática:** alterações na branch `main` compilam os módulos afetados e publicam os APKs assinados e os catálogos atualizados em [`anime-extensoes`](https://github.com/ProjectNox-org/anime-extensoes).

## Estrutura do repositório

| Caminho | Finalidade |
|---|---|
| `src/` | Extensões organizadas por idioma (`pt/`, `all/`, entre outros). |
| `lib/` | Extractors de vídeo e bibliotecas compartilhadas (`bloggerextractor`, `mixdropextractor`, `m3u8server`, `hlsdash`, `playlistutils`, etc.). |
| `lib-multisrc/` | Temas multisource compartilhados (`animestream`, `dooplay`, `anikototheme`, etc.). |
| `core/` e `extensions-lib/` | Utilitários centrais (`keiyoushi.utils`, `keiyoushi.network`), testes unitários e API das extensões. |
| `common/` e `compiler/` | Infraestrutura compartilhada e geração de código via KSP. |
| `.github/` | Workflows (`build_push.yml`, `sync_upstream.yml`), scripts de catálogo/merge e `nox-protected.txt`. |
| `ext-bootstrap.py`, `build.gradle.kts`, `settings.gradle.kts` | Criação de módulos e configuração modular do Gradle. |

## Desenvolvimento e compilação

### Pré-requisitos

**Ferramentas necessárias:**

- [JDK 17+](https://adoptium.net/) configurado no `JAVA_HOME` (usado no CI e compatível com o Gradle/AGP do projeto).
- [Android SDK](https://developer.android.com/studio) com Platform-Tools e Build-Tools (recomendado via Android Studio).

**Configurações do projeto** (`gradle/kei.versions.toml`):

- `compileSdk` e `targetSdk`: `37` | `minSdk`: `24` | compatibilidade de bytecode Java: `11`.

### Clonar e limitar módulos locais

```bash
git clone https://github.com/ProjectNox-org/anime-fonte-extensoes.git
cd anime-fonte-extensoes
```

O repositório contém centenas de módulos. Para compilar rápido localmente, comente `loadAllIndividualExtensions()` em `settings.gradle.kts` e inclua apenas a extensão desejada com `loadIndividualExtension("pt", "anitube")` (sem commitar essa alteração), ou siga o guia de clone parcial em [`CONTRIBUTING.md`](CONTRIBUTING.md).

### Compilar, testar e formatar

Os exemplos abaixo usam os módulos reais `:src:pt:anitube` e `:lib:bloggerextractor`. Substitua pelo módulo de extensão ou biblioteca que estiver modificando:

```bash
# Verificação rápida de compilação Kotlin (sem empacotar APK)
./gradlew :src:pt:anitube:compileDebugKotlin

# Compilar APK de debug (saída em src/pt/anitube/build/outputs/apk/debug/)
./gradlew :src:pt:anitube:assembleDebug

# Compilar uma biblioteca ou extractor compartilhado
./gradlew :lib:bloggerextractor:assembleDebug

# Testes unitários existentes (core, unpacker e megaextractor)
./gradlew --continue :core:testDebugUnitTest :lib:unpacker:testDebugUnitTest :lib:megaextractor:testDebugUnitTest

# Aplicar ou verificar formatação padrão (Spotless / ktlint)
./gradlew :src:pt:anitube:spotlessApply
./gradlew :src:pt:anitube:spotlessCheck
```

Consulte [`CONTRIBUTING.md`](CONTRIBUTING.md) e [`AGENTS.md`](AGENTS.md) para regras de desenvolvimento, `extensions-lib` 16, helpers de rede e checklist de PR.

### Publicação e catálogos

O pipeline de publicação em [`anime-extensoes`](https://github.com/ProjectNox-org/anime-extensoes) mantém sincronizados:

- [`index.pb`](https://raw.githubusercontent.com/ProjectNox-org/anime-extensoes/repo/index.pb) — catálogo principal em Protobuf compatível com o esquema oficial do Aniyomi (`isTorrent=8`, `sources=9`).
- [`index.json`](https://raw.githubusercontent.com/ProjectNox-org/anime-extensoes/repo/index.json) e [`index.min.json`](https://raw.githubusercontent.com/ProjectNox-org/anime-extensoes/repo/index.min.json) — catálogos legados para clientes antigos e link de instalação automática.
- [`repo.json`](https://raw.githubusercontent.com/ProjectNox-org/anime-extensoes/repo/repo.json) — metadados do repositório apontando para `index.pb` via `index_v2`.

Para validar e republicar apenas os catálogos sem recompilar APKs, execute o workflow manual `CI` (`build_push.yml`) com `catalog_only=true` e `rebuild_all=false`.

## Como contribuir

1. **Verifique pedidos existentes:** consulte a [Central de Pedidos](https://github.com/ProjectNox-org/project-nox-requests) antes de começar.
2. **Crie uma branch a partir da `main`:** use um nome descritivo (ex.: `fix/animefire-player` ou `feat/novo-extractor`).
3. **Reutilize extractors:** se o site usar um servidor de vídeo comum a outras fontes, implemente ou ajuste o módulo compartilhado em `lib/`.
4. **Valide antes de enviar:** teste a reprodução no aplicativo, atualize `versionCode` (ou `baseVersionCode` em temas), execute `spotlessApply` no módulo alterado e abra o Pull Request exclusivamente em [`ProjectNox-org/anime-fonte-extensoes`](https://github.com/ProjectNox-org/anime-fonte-extensoes) — nunca nos repositórios upstream.

## Suporte e solicitações

- **Central de Pedidos ([`project-nox-requests`](https://github.com/ProjectNox-org/project-nox-requests)):** abrir pedidos de novas extensões e relatar falhas em fontes ou players.
- **Tracker ([Project Nox Tracker](https://projectnox-org.github.io/project-nox-tracker/)):** consultar e acompanhar o status das solicitações.
- **Issues técnicas ([`issues`](https://github.com/ProjectNox-org/anime-fonte-extensoes/issues)):** dúvidas de desenvolvimento, extractors e compilação neste repositório.

## Projetos relacionados

- [**Project Nox · Extensões de Anime**](https://github.com/ProjectNox-org/anime-extensoes) — Distribuição oficial de extensões prontas para Aniyomi e forks.
- [**Project Nox · Código-fonte das Extensões de Mangá**](https://github.com/ProjectNox-org/manga-fonte-extensoes) — Código-fonte das extensões de mangá.
- [**Project Nox · Extensões de Mangá**](https://github.com/ProjectNox-org/manga-extensoes) — Distribuição oficial das extensões de mangá para Mihon e forks.

## Créditos e atribuições

- **Felipe GFA** ([@FelipeGFA](https://github.com/FelipeGFA)): migração estrutural da suíte de anime, desenvolvimento e depuração de parsers (como RedeCanais e BetterAnime) e consolidação dos fluxos de compilação contínua.
- **Alex** ([@Alex17273747576777](https://github.com/Alex17273747576777)): proposta inicial do catálogo Protobuf para anime, usada como base para esta implementação com o esquema oficial do Aniyomi.
- **Yūzōnō** ([@yuzono](https://github.com/yuzono)) e **Aniyomi** ([@aniyomiorg](https://github.com/aniyomiorg)): base upstream, extractors de vídeo e arquitetura do ecossistema (incluindo compatibilidade com Anikku e forks).
- **Javier Tomás** e demais colaboradores históricos do ecossistema open-source e do Project Nox.

## Licenças e informações legais

Este repositório distribui um trabalho combinado sob a **GNU Affero General Public License v3.0** (`AGPL-3.0-only`), preservando os direitos e avisos das bases upstream:

- **Código upstream (Yūzōnō / Aniyomi):** licenciado sob a [Apache License 2.0](LICENSE-APACHE).
- **Contribuições do Project Nox:** licenciadas sob a [GNU AGPL v3.0](LICENSE) (`AGPL-3.0-only`).
- **Trabalho combinado:** distribuído sob GNU AGPL v3.0 em compatibilidade com a Apache 2.0, preservando os avisos de copyright e atribuições originais, sem reivindicação de titularidade exclusiva sobre código de terceiros.
- **Sincronização e governança:** atualizações upstream são incorporadas downstream mantendo créditos e licenças originais; o desenvolvimento do Project Nox permanece exclusivo à organização `ProjectNox-org`, sem envio de PRs ao upstream (Yūzōnō / Aniyomi).
- **Interação remota de rede (AGPLv3, Seção 13):** conforme os termos da licença, quem operar versão modificada acessível pela rede com interação remota de usuários deve disponibilizar o Código-Fonte Correspondente (*Corresponding Source*).
- **Identidade e marca (AGPLv3, Seção 7(e) / Apache 2.0, Seção 6):** as licenças de software não concedem direito de uso dos nomes ou marcas "Project Nox", "Awerkori" ou de terceiros para sugerir endosso ou representação oficial.
- **Isenção de responsabilidade:** software livre para interoperabilidade técnica e pesquisa; o projeto não hospeda, armazena nem distribui conteúdos audiovisuais protegidos por direitos autorais.

Consulte [`NOTICE`](NOTICE) para o inventário completo de atribuições e componentes de terceiros (como `jsunpacker` sob Apache 2.0 em `lib/unpacker/`).

```text
Copyright 2015 Javier Tomás
Copyright (c) Yūzōnō Contributors & Aniyomi Contributors
Copyright (c) Felipe GFA
Copyright (C) 2026 Project Nox Contributors & Awerkori

Licensed under the GNU Affero General Public License, Version 3.0 (for Project Nox contributions
and combined work) and the Apache License, Version 2.0 (for upstream Yūzōnō/Aniyomi components).
```
