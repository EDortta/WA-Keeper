# 🔴 ALERTA DE SEGURANÇA — SEC-0066 (WaNotifKeeper) — [open]

**Status:** aberto · **Severidade:** alta · **Branch da correção:** WK-20260704-security-fixes

Contexto: o app grava remetente + texto integral de mensagens do WhatsApp/WhatsApp
Business em SQLite sem criptografia (`wanotif.db`).

## Já feito (código — contenção desta onda)
- `android:allowBackup="false"` + `android:fullBackupContent="false"` no manifest —
  banco fora do auto-backup (Google Drive), de `adb backup` e da migração
  device-to-device em Android ≤ 11.
- `android:dataExtractionRules="@xml/data_extraction_rules"` excluindo todos os
  domínios de cloud-backup e device-transfer (Android 12+) — defesa em profundidade.
- `debuggable false` explícito no buildType `release` (APK debug permite extrair o
  banco via `run-as` sem root; release não).

## Pendente — trabalho de código (fase posterior, NÃO iniciado)
- [ ] **Criptografia em repouso**: migrar o Room para SQLCipher (`SupportFactory`)
      com chave gerada/guardada no Android Keystore, incluindo migração dos dados
      existentes do `wanotif.db` em claro (e wipe seguro do arquivo antigo).
- [ ] Opcional (recomendado na issue): bloqueio biométrico/credencial na abertura
      do app, já que a UI expõe todo o histórico de conversas sem controle de acesso.

## Pendente — AÇÃO DO OPERADOR
- [ ] Criar keystore de release (fora do repo — nunca commitar) + `signingConfig`,
      gerar APK release assinado e **substituir o `app-debug.apk` no aparelho**.
      Enquanto o build debug estiver instalado, o banco continua extraível via
      USB/adb (`run-as br.com.wanotifkeeper cat databases/wanotif.db`).
- [ ] **Decisão jurídica (LGPD)**: definir base legal para retenção de conteúdo de
      mensagens de terceiros e política de retenção/expurgo correspondente
      (relacionado: SEC-0247, retenção ilimitada de PII).
