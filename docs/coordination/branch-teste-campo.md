# Branch de integração para teste em campo — `integracao/teste-campo`

- criada em: 2026-09-01, a partir de `development`
- propósito: pôr no aparelho, num APK só, as três coisas que o operador esperava ver
  e que não existiam em `development`.
- **`development` não foi tocada.** Nada foi mesclado nela, nada foi empurrado.
- descartável: se o teste em campo reprovar, a branch morre e não há nada a reverter.

## O que entrou

| origem | o que é | como se vê no app |
|---|---|---|
| `feature/epic-3-image-retention` | retenção de imagens recebidas | abrir uma conversa com foto: a imagem aparece no detalhe |
| `feature/epic-4-scheduled-messages` | mensagem armada por conversa | no detalhe, botão **"Enviar quando esta pessoa falar comigo"** |
| novo neste branch | botão de microfone | botão verde no rodapé da tela principal: tocar e falar o comando |

## Correções feitas aqui, antes de ir para o aparelho

**S1, S2 e S3** do concílio da EPIC 3 — os três achados que bloqueavam o merge.
Ver o commit `1b6d38c` para o detalhe. Em resumo: o strip de `"Remetente: "` passou a
exigir conversa de grupo (fora de grupo, `"Ana: fotos"` anexava foto alheia a mensagem
de texto); janela, estabilização e escada de retry passaram a ser derivadas em código em
vez de alinhadas por comentário; e a `DetailActivity` passou a usar a mesma regra do
resto do app.

**O conflito semântico que o `git merge-tree` não acusa** (item 4 do RESUME da 017):
`development` tinha ganhado `isDuplicateRepost`, um early-return de 2 s por texto que
rodava **antes** da extração da imagem. O WhatsApp publica o rótulo primeiro e só depois
atualiza a notificação com o bitmap/URI, **com texto idêntico** — então o dedup matava
justamente a notificação que trazia a imagem. Resolvido em `ddc3761`: o dedup passou a
rodar depois da extração e a distinguir três casos (nova / repost puro / repost que traz
imagem que o primeiro não tinha). O terceiro anexa a imagem à linha que já existe, em vez
de inserir uma linha duplicada.

## Verificado nesta máquina

- 64 testes JVM, 0 falhas, 0 erros.
- `:app:assembleRelease` verde. APK release (assinado com a chave de debug, `debuggable false`,
  como a SEC-0066 exige para uso diário).

## NÃO verificado

**Nada foi exercitado em aparelho.** O celular foi desconectado antes da instalação.
Os 16 achados vivos que sobraram (9 da EPIC 4, 7 da EPIC 3 depois de S1/S2/S3) continuam
esperando decisão — ver os `RESUME.md` das issues 017 e 018.

O item que **envia mensagem de verdade** pelo WhatsApp do operador nunca foi disparado, e
não será sem ele escolher o destinatário.

## Confirmado no aparelho antes de ele sair (leitura, sem alterar nada)

- ação **"Responder"** existe em `com.whatsapp.w4b` e é `startService`, não `startActivity`
  — o disparo em segundo plano não cai na restrição de background activity launch;
- `Person` vem **preenchido** em conversa 1-a-1 (`android.isGroupConversation=false`);
- a notificação traz `uri=content://…/item/…` com `type=image/jpeg`;
- `android.text` é literalmente `📷 Foto`;
- `WhatsApp Images` guarda os `.jpg` **na raiz**, sem subpasta de semana (só `Sent/` e
  `Private/`, que a varredura já exclui por exigir nome numérico);
- a pasta do Business é `WhatsApp Business Images`, como o código supunha;
- o bitmap da notificação é miniatura de **115×115**.
