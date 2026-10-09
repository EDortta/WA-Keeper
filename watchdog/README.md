# Goals Kit Watchdog — MVP local

Aplicação Python modular com CLI e navegador, sem dependências de terceiros. **Lê o Git sem modificações, exceto quando o operador solicita explicitamente Atualizar e reiniciar.** O protótipo HTML anterior em `watchdog/prototype/` permanece intacto.

## Iniciar

Execute na raiz do clone WA-Keeper:

```bash
python3 watchdog.py --root . open --port 8765
```

Abra `http://127.0.0.1:8765`. Para apontar a outro checkout, use `--root /caminho/para/repositorio`.

Comandos CLI:

```bash
python3 watchdog.py --root . history
python3 watchdog.py --root . map
python3 watchdog.py --root . features
```

Testes:

```bash
python3 -m unittest discover -s watchdog/tests -v
```

## Escopo efetivo

- Histórico Git real (commits, datas, pais, referências); seleção de revisão por SHA.
- Mapa de domínios **candidato, inicialmente estático**, explicitamente não validado como histórico real. Evitamos inventar datas de surgimento de domínios.
- Features extraídas de `docs/validated-features.md` **da revisão selecionada**. A presença documental não implica implementação verificada.
- Leitura de Markdown e árvore de títulos, sem modificar o arquivo.
- Coluna Domínio fixa, ocultação/redimensionamento de colunas, gestão de domínios/features e tema light/dark com preferência armazenada pelo navegador.
- API HTTP local em `127.0.0.1`: GET para inspeção e POST autenticado local para atualização explicitamente solicitada. Sem subprocessos com argumentos controlados como shell.

## Limites conhecidos

- O grafo apresenta pais e ramificações com layout preliminar limitado a 90 commits; não é ainda um renderer GitGraph completo. O catálogo de domínios não é historicamente reconstruído e seu status permanece **proposto**.
- Sem indexação de símbolos, integração com testes, estados editáveis, diff, busca, autenticação multiusuário, escrita de Markdown ou sincronização com docs guardrails.
- Somente arquivos Markdown de `docs/` podem ser lidos via API.
- O servidor é local: **não exponha por proxy público**, nem execute sobre repositórios não confiáveis.
- `localStorage` armazena somente o tema visual. Nenhuma credencial vai para a interface.

O contrato de CLI/API fica no núcleo Python em `watchdog/`; a UI em `watchdog/web/` não replica regras de Git.


## Atualizar e reiniciar pelo navegador

O botão **Atualizar e reiniciar** executa `git pull --ff-only` no checkout local e reinicia o processo Python na **mesma porta**, sem precisar usar Ctrl+C. A aplicação aguarda a nova instância e recarrega a página.

A ação requer um token de sessão, host e origem locais. Em caso de erro do Git, o servidor atual continua no ar e apresenta o erro, sem forçar merge. O reinício somente é solicitado depois de um pull bem-sucedido. Não substitui `git fetch` manual quando é necessário atualizar informações de outros ramos sem pull.

A API continua somente leitura para documentação e histórico; a única ação mutável é a atualização explícita do checkout por esse botão. O botão não é destinado a repositórios com mudanças locais que impeçam o avanço simples.


## Scan determinístico de Markdown (fase inicial)

- **Scan Markdown** percorre arquivos `.md` do checkout em qualquer pasta do projeto, ignorando `.git`, dependências e artefatos de compilação. Não usa LLM nem rede.
- O reconhecimento é **conservador**: títulos `## Domínio: Nome`, `## Feature: Nome`, seções de `# Mapa de domínios` / `# Features`, e documentos individuais em pastas `domains/`, `dominios/` ou `features/`. Markdown arbitrário sem sinais explícitos não é interpretado semanticamente.
- O resultado fica em `.git/watchdog/catalog.json` (não versionado). Cada scan compara a definição atual com a anterior. Itens desaparecidos são apresentados como **AUSENTE NO SCAN**, não apagados automaticamente.
- **Criar mapa Markdown** cria, somente por pedido explícito, `docs/domains.md` com instruções e sem inventar domínios. Não sobrescreve arquivos.
- No HEAD, o catálogo reflete os arquivos do checkout, incluindo Markdown ainda não commitado. A seleção histórica continua lendo o Git da revisão escolhida; o catálogo do scan atual não é projetado retroativamente sobre revisões antigas.
- A persistência da árvore e da rolagem continua local ao navegador, identificada por projeto, revisão e item. O ID do scanner deriva de tipo, caminho e título, sem depender do número da linha.
- Diferentes documentos com o mesmo nome geram fontes distintas, para evitar fusões sem prova. Unificação de entidades entre fontes e análise semântica de quebras ainda não estão implementadas.

Testes previstos:

```bash
python3 -m unittest discover -s watchdog/tests -v
```


## Evidências do Scan Markdown

Cada clique em **Scan Markdown** registra sucesso ou falha na fila do repositório compartilhado
\`EDortta/development-evidences\`, usando o contrato de manifesto \`schema: 1\`.
O identificador da execução aparece na barra inferior do Watchdog.

- Fila local: \`~/.local/state/development-evidences/outbox/\` (ou \`$XDG_STATE_HOME/development-evidences/outbox/\`).
- Projeto, branch e SHA são obtidos diretamente do Git. O manifesto contém duração,
  contagem de documentos, domínios e features, etapa e categoria da falha.
- O upload não acontece durante a requisição do scan: um servidor Git/SSH indisponível
  não pode travar a interface. O publicador compartilhado faz o \`flush\` posteriormente.
- Nunca são publicados nomes de documentos, conteúdo Markdown, logs brutos,
  textos de exceção, credenciais ou dados pessoais.
- A fila continua disponível mesmo se o reconhecimento de domínios falhar.
  Se a própria criação da fila local falhar, a interface retorna erro sem alegar publicação.

Com uma cópia atualizada de \`scripts/evidence.py\` contendo o comando \`flush\`:

\`\`\`bash
python3 scripts/evidence.py flush
python3 -m unittest discover -s watchdog/tests -v
\`\`\`

\`flush\` requer acesso SSH ao repositório privado e pode ser executado posteriormente.
