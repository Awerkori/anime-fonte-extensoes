# Project Nox · Código-fonte das Extensões de Anime

Repositório central de código-fonte, desenvolvimento de extractors de vídeo e automação de compilação das extensões de anime do **Project Nox** para **Aniyomi**, **Anikku** e forks compatíveis.

---

> [!NOTE]
> **Procurando apenas assistir e instalar as extensões no seu player?**  
> Este repositório contém o código-fonte em Kotlin para desenvolvimento e manutenção técnica. Para instalar os catálogos compilados no aplicativo, acesse:  
> ➡️ [**Project Nox · Extensões de Anime (Distribuição)**](https://github.com/Awerkori/anime-extensoes)

---

## 🎯 Sobre o Projeto

O ecossistema de anime do Project Nox gerencia e aperfeiçoa extensões focadas em streaming com suporte à comunidade lusófona (PT-BR). As responsabilidades deste projeto englobam:

- **Extractors de Vídeo Dedicados:** Parsers customizados para contornar proteções, resolver redirecionamentos de players e processar playlists HLS/M3U8 com estabilidade.
- **Pipeline de Integração Contínua (CI):** Compilação automatizada via Gradle e GitHub Actions, com testes sintéticos, empacotamento de APKs e assinatura digital.
- **Publicação Automática:** Cada atualização aprovada na branch principal gera os pacotes correspondentes e atualiza o catálogo JSON no repositório de distribuição [`anime-extensoes`](https://github.com/Awerkori/anime-extensoes).

---

## 🏗️ Estrutura do Repositório

- `src/`: Extensões organizadas por idioma (ex: `src/pt/` para fontes em português, como AnimeFire, RedeCanais e Anikyuu).
- `lib/`: Bibliotecas compartilhadas e extractors comuns de hospedagem de vídeo (Blogger, MixDrop, servidores locais M3U8, etc.).
- `.github/workflows/`: Workflows do GitHub Actions responsáveis pela compilação modular e publicação do repositório.
- `.github/scripts/`: Scripts em Python para merge, catalogação e sincronização com a branch `repo`.

---

## 💻 Como Compilar Localmente

### Pré-requisitos
- **JDK 17** ou superior instalado e configurado no `JAVA_HOME`.
- **Android SDK** com Build-Tools e plataformas Android 30+.

### Clonar o repositório
```bash
git clone https://github.com/Awerkori/anime-fonte-extensoes.git
cd anime-fonte-extensoes
```

### Compilar uma extensão específica
```bash
# Compilar o APK da extensão em modo debug
./gradlew :src:pt:<nome-da-extensao>:assembleDebug
```

Os APKs gerados ficam localizados em `src/pt/<nome-da-extensao>/build/outputs/apk/debug/`.

---

## 🤝 Como Contribuir

Contribuições da comunidade são bem-vindas:

1. **Consulte a Central de Pedidos:** Verifique os chamados existentes na [Central de Pedidos](https://github.com/Awerkori/project-nox-requests) para priorizar correções demandadas.
2. **Crie uma branch:** Nomeie de forma clara (ex: `fix/animefire-player` ou `feat/novo-extractor`).
3. **Modularidade nos Extractors:** Se um player for utilizado por múltiplos sites, implemente-o ou atualize-o dentro do módulo `lib/`.
4. **Envie um Pull Request:** Detalhe a correção realizada, os fluxos testados e o comportamento esperado.

---

## 🔗 Projetos Relacionados

- 🎬 [**Project Nox · Extensões de Anime**](https://github.com/Awerkori/anime-extensoes) — Repositório de distribuição (APKs compilados e catálogo JSON).
- 📦 [**Project Nox · Extensões de Mangá**](https://github.com/Awerkori/manga-extensoes) — Catálogo de extensões de leitura para Mihon.
- 💻 [**Project Nox · Código-fonte das Extensões de Mangá**](https://github.com/Awerkori/manga-fonte-extensoes) — Código-fonte das extensões de mangá.
- 🎫 [**Project Nox · Central de Pedidos**](https://github.com/Awerkori/project-nox-requests) — Reporte de links quebrados e pedidos de fontes.
- 📊 [**Project Nox · Tracker**](https://awerkori.github.io/project-nox-tracker/) — Painel de status em tempo real.
- 🌐 [**Project Nox · Site**](https://manga.project-nox-awerkori.workers.dev) — Leitor web oficial.

---

## ⚖️ Licença e Créditos

Distribuído sob a licença **Apache 2.0**. Consulte o arquivo [LICENSE](LICENSE) para obter mais informações.

```
Copyright 2015 Javier Tomás
Copyright (c) Yūzōnō Contributors & Project Nox Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

### Isenção de Responsabilidade
O **Project Nox** desenvolve e disponibiliza ferramentas para fins educacionais e de interoperabilidade. Não armazenamos, modificamos nem intermediamos arquivos multimídia nos servidores do projeto. Todo o conteúdo é transmitido diretamente de serviços externos públicos. Este projeto não possui afiliação oficial com a equipe do Aniyomi ou Anikku.
