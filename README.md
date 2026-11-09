# Project Nox · Código-fonte das Extensões de Anime

Repositório central de desenvolvimento, código-fonte em Kotlin, desenvolvimento de extractors de vídeo e automação de compilação das extensões de anime do **Project Nox** para **Aniyomi e forks**.

---

> [!IMPORTANT]
> **Procurando apenas assistir e instalar as extensões no seu player?**  
> Este repositório destina-se a desenvolvedores e contém o código-fonte em Kotlin para manutenção e criação de fontes. Para instalar os catálogos compilados diretamente no seu aplicativo, acesse o repositório de distribuição oficial:  
> ➡️ [**Project Nox · Extensões de Anime (Distribuição)**](https://github.com/Awerkori/anime-extensoes)

---

## 🎯 Sobre o Projeto

O ecossistema de anime do **Project Nox** gerencia extensões voltadas à reprodução fluida de mídias audiovisuais com prioridade para a comunidade brasileira (PT-BR). As principais frentes de atuação deste repositório incluem:

- **Extractors de Vídeo e Servidores Locais:** Desenvolvimento e manutenção de módulos especializados em contornar protetores de links, decodificar tokens efêmeros de players e processar manifestos HLS/M3U8 via proxy local (`m3u8server`), prevenindo travamentos e erros de buffer no player.
- **Engenharia de Fontes em Kotlin:** Módulos que mapeiam catálogos, paginações, filtros por gênero/temporada e listas de episódios de sites populares de streaming.
- **Pipeline de Integração Contínua (CI):** Compilação modular automatizada via Gradle e GitHub Actions, com testes sintéticos, empacotamento de APKs assinados e publicação de índices.
- **Distribuição Sincronizada:** Cada merge aprovado na branch `main` dispara o empacotamento e a entrega contínua dos artefatos ao repositório [`anime-extensoes`](https://github.com/Awerkori/anime-extensoes).

---

## 🏗️ Estrutura do Repositório

O projeto segue arquitetura modular em Gradle:

```
├── src/                    # Extensões organizadas por idioma
│   ├── pt/                 # Extensões em Português (AnimeFire, RedeCanais, Anikyuu, etc.)
│   └── ...                 # Outros módulos de exibição
├── lib/                    # Extractors de vídeo e bibliotecas reutilizáveis
│   ├── bloggerextractor/   # Decodificador de streams hospedados no Google/Blogger
│   ├── mixdropextractor/   # Parser de links MixDrop e mirrors
│   ├── m3u8server/         # Servidor HTTP local para servir e desfragmentar streams HLS
│   └── ...                 # Outros extractors dedicados
├── .github/
│   ├── workflows/          # Pipelines de automação (build_push.yml, sync_upstream.yml)
│   └── scripts/            # Scripts em Python para consolidação do catálogo e merge
└── build.gradle.kts        # Configurações globais de compilação
```

---

## 💻 Como Compilar e Desenvolver Localmente

### Pré-requisitos
- **JDK 17** ou superior instalado e configurado no `JAVA_HOME`.
- **Android SDK** com Platform-Tools e Build-Tools recentes (API 30+).

### Clonar o repositório
```bash
git clone https://github.com/Awerkori/anime-fonte-extensoes.git
cd anime-fonte-extensoes
```

### Compilar Extensões e Extractors

Para compilar uma extensão específica em modo debug:
```bash
./gradlew :src:pt:<nome-da-extensao>:assembleDebug
```
O APK resultante fica disponível em:  
`src/pt/<nome-da-extensao>/build/outputs/apk/debug/`

Para compilar e testar um extractor de vídeo específico:
```bash
./gradlew :lib:bloggerextractor:assembleDebug
```

### Formatação de Código
```bash
# Validar e aplicar as diretrizes de código Spotless
./gradlew spotlessApply
```

---

## 🤝 Como Contribuir

Contribuições da comunidade são muito apreciadas:

1. **Verifique os Chamados:** Acesse a [Central de Pedidos](https://github.com/Awerkori/project-nox-requests) para checar fontes reportadas com instabilidade ou novos pedidos prioritários.
2. **Crie uma branch descritiva:** Ex: `fix/animefire-player` ou `feat/novo-extractor`.
3. **Reutilização de Extractors:** Caso um novo site utilize um host de vídeo comum a outras fontes, implemente a lógica dentro do diretório `lib/` para reaproveitamento modular.
4. **Envie seu Pull Request:** Explique a solução adotada, os métodos de bypass de player aplicados e os testes de reprodução realizados.

---

## 🛠️ Suporte e Reporte de Erros

Identificou uma extensão de anime quebrada, falha ao carregar episódios ou erro no player?

- 🎫 **Abrir solicitação oficial:** [Project Nox · Central de Pedidos](https://github.com/Awerkori/project-nox-requests)
- 📊 **Acompanhar status em tempo real:** [Project Nox · Tracker](https://awerkori.github.io/project-nox-tracker/)

---

## 🔗 Projetos Relacionados

- 🎬 [**Project Nox · Extensões de Anime**](https://github.com/Awerkori/anime-extensoes) — Repositório de distribuição (APKs compilados e catálogo JSON para Aniyomi e forks).
- 📦 [**Project Nox · Extensões de Mangá**](https://github.com/Awerkori/manga-extensoes) — Distribuição de fontes de leitura para Mihon e forks.
- 💻 [**Project Nox · Código-fonte das Extensões de Mangá**](https://github.com/Awerkori/manga-fonte-extensoes) — Código em Kotlin das extensões de mangá.
- 🌐 [**Project Nox · Plataforma Web de Mangá**](https://github.com/Awerkori/project-nox-manga) — Plataforma web oficial.
- ⚙️ [**Project Nox · Importer**](https://github.com/Awerkori/project-nox-importer) — Serviço de ingestão autônoma 24/7.

---

## 🌟 Créditos e Reconhecimento

- **Felipe GFA** ([@FelipeGFA](https://github.com/FelipeGFA)): Pela migração estrutural da suíte de anime, desenvolvimento e depuração de parsers avançados (RedeCanais, BetterAnime, etc.) e consolidação dos fluxos de compilação contínua.
- **Yūzōnō** ([@yuzono](https://github.com/yuzono)) e equipe **Aniyomi** ([@aniyomiorg](https://github.com/aniyomiorg)): Pela arquitetura upstream dos extractors de vídeo e ecossistema de extensões de reprodução.
- **Javier Tomás** e comunidade open-source de desenvolvedores.

---

## ⚖️ Licença e Copyright

O trabalho combinado deste repositório é distribuído sob os termos da **GNU Affero General Public License versão 3.0** (`AGPL-3.0-only`), respeitando a compatibilidade e preservando integralmente os direitos dos autores originais:

- **Código Upstream (Yūzōnō / Aniyomi):** Licenciado sob a [Apache License 2.0](LICENSE-APACHE). Todos os avisos de direitos autorais originais foram integralmente mantidos.
- **Contribuições Originais do Project Nox:** Licenciadas sob a [GNU AGPL v3.0](LICENSE) (`AGPL-3.0-only`).
- **Trabalho Combinado:** Distribuído sob a **GNU AGPL v3.0** em virtude da compatibilidade unidirecional Apache 2.0 → AGPLv3.

Consulte o arquivo [NOTICE](NOTICE) para o inventário completo de atribuições, componentes incorporados e diretrizes jurídicas.

```
Copyright 2015 Javier Tomás
Copyright (c) Yūzōnō Contributors & Aniyomi Contributors
Copyright (c) Felipe GFA
Copyright (C) 2026 Project Nox Contributors & Awerkori

Licensed under the GNU Affero General Public License, Version 3.0 (for Project Nox contributions
and combined work) and the Apache License, Version 2.0 (for upstream Yūzōnō/Aniyomi components).
```

### 🔄 Sincronização Upstream e Governança
- O Project Nox sincroniza periodicamente atualizações do ecossistema de extensões de vídeo mantendo os créditos e licenças Apache 2.0 originais intactos.
- Todo o ciclo de vida, extractors e novos recursos do Project Nox são mantidos exclusivamente dentro da organização `Awerkori`. Nenhum Pull Request é aberto ou enviado para os repositórios upstream de Yūzōnō ou Aniyomi, garantindo estabilidade e autonomia operacional downstream.

### 📌 Interação Remota de Rede (GNU AGPLv3 Seção 13)
O Project Nox adota oficialmente a licença **GNU AGPL versão 3.0**. Nos termos da Seção 13 da AGPLv3, qualquer versão modificada deste software operada em servidores ou instâncias acessíveis remotamente através de uma rede de computadores deve oferecer a todos os usuários remotos a oportunidade de obter o Código-Fonte Correspondente (Corresponding Source) da versão em execução, sem custos e através de meio padrão de disponibilização de software em rede.

### 🛡️ Proteção de Identidade e Marca (AGPLv3 Seção 7(e))
A concessão das licenças de software livre refere-se estritamente ao código. Ela **não concede** autorização ou licença de uso de marcas, nomes ou logotipos oficiais do "Project Nox", de "Awerkori" ou de terceiros para insinuar endosso, homologação ou representação oficial do projeto sem consentimento prévio por escrito.

### ⚖️ Isenção de Responsabilidade
O **Project Nox** desenvolve exclusivamente soluções de software livre para fins educacionais e de pesquisa em interoperabilidade técnica. Não armazenamos, hospedamos nem comercializamos materiais audiovisuais protegidos. As extensões atuam estritamente como ferramentas de consulta programática a sites públicos externos.
