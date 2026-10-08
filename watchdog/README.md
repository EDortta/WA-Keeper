# Goals Kit Watchdog — MVP local

Aplicação Python modular com CLI e navegador, sem dependências de terceiros. **Não altera o WA-Keeper nem o Git.** O protótipo HTML anterior em `watchdog/prototype/` permanece intacto.

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
- API HTTP local em `127.0.0.1`, somente `GET`; respostas a POST = 405. Sem subprocessos com argumentos controlados como shell.

## Limites conhecidos

- O grafo apresenta pais e ramificações com layout preliminar limitado a 90 commits; não é ainda um renderer GitGraph completo. O catálogo de domínios não é historicamente reconstruído e seu status permanece **proposto**.
- Sem indexação de símbolos, integração com testes, estados editáveis, diff, busca, autenticação multiusuário, escrita de Markdown ou sincronização com docs guardrails.
- Somente arquivos Markdown de `docs/` podem ser lidos via API.
- O servidor é local: **não exponha por proxy público**, nem execute sobre repositórios não confiáveis.
- `localStorage` armazena somente o tema visual. Nenhuma credencial vai para a interface.

O contrato de CLI/API fica no núcleo Python em `watchdog/`; a UI em `watchdog/web/` não replica regras de Git.
