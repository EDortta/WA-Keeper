# Processo de publicação

## Estados

1. DISCOVERY — levantar comportamento real, permissões, dependências e fluxos.
2. PLAY-COMPAT — adaptar a variante Play.
3. PRIVACY — fechar política de privacidade e Data Safety.
4. STORE — preparar textos, ícone, feature graphic e screenshots.
5. TEST — gerar AAB, instalar via track de teste e validar fluxos.
6. REVIEW — enviar para revisão e registrar respostas/evidências.
7. RELEASE — liberar produção e registrar versão publicada.

## Gate técnico

Antes de gerar o AAB:

- targetSdk e compileSdk compatíveis com exigência vigente da Play.
- assinatura release definitiva.
- nenhuma chave privada no repositório.
- build não-debuggable.
- permissões especiais justificadas ou removidas.
- Accessibility Service estritamente limitado ao caso declarado.
- comportamento sem dependência de interação oculta ou automação não declarada.
- política de privacidade publicada.
- Data Safety revisado contra o binário.
- screenshots sem dados pessoais reais.
- textos da loja sem alegações incompatíveis com o comportamento real.

## Gate de publicação

A publicação só avança quando `scripts/check-play-readiness.sh` não reportar bloqueadores conhecidos e os itens de `RISKS.md` estiverem classificados como ACEITO, REMOVIDO ou MITIGADO.

## Evidência

Salvar em `publisher/evidence/YYYY-MM-DD/`:

- saída do script de readiness;
- versão, versionCode e commit;
- lista de permissões do AAB/APK;
- hash do artefato;
- screenshots finais;
- cópia das respostas dadas na Play Console;
- decisão/revisão recebida do Google.
