# Features validadas e protegidas

Este arquivo registra contratos de produto que não podem ser modificados sem autorização explícita do usuário.

## PROTECTED

### Transcrição offline de áudio

Status: PROTECTED

Contrato:

- transcrição acontece offline no Android;
- suporte aos métodos Whisper Base INT8 e Whisper Small INT8 durante a fase de decisão;
- o método usado precisa ficar visível;
- registrar duração original do áudio;
- registrar tempo gasto;
- registrar RTF quando disponível;
- usuário pode avaliar a qualidade como Incompreensível, Aceitável, Boa ou Excelente;
- histórico de transcrições deve permanecer visível até definição final do modelo;
- não remover um método nem trocar o modelo padrão sem autorização explícita;
- não apagar histórico nem métricas durante atualização normal.

### Guarda e reprodução de áudio recebido

Status: PROTECTED

Contrato:

- áudio recebido já capturado deve continuar disponível após atualizações normais;
- controles play/pause/stop continuam funcionais;
- compartilhar arquivo continua disponível quando houver arquivo capturado.

### Mensagens agendadas

Status: PROTECTED

Contrato:

- mensagens agendadas continuam persistentes;
- múltiplas mensagens para o mesmo destino devem ser enviadas em sequência;
- mudanças não podem apagar a fila existente.

### Backup e restauração de dados locais

Status: PROTECTED

Contrato:

- backup não pode apagar os dados antes de confirmar integridade;
- restauração deve preservar banco, preferências e arquivos privados previstos;
- scripts não podem desinstalar o app se a criação/verificação do backup falhar.

## Ainda não protegido

Uma feature nova só entra aqui depois de validação explícita do usuário.

O tema claro/escuro/auto ainda deve ser tratado como EM VALIDAÇÃO até passar pelos testes sem regressão das features PROTECTED.
