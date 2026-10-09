"""Domain catalog is an explicit proposal, not proof of implemented behavior."""
from pathlib import Path

DOMAINS = [
    ("capture", "Captura", "Receber e filtrar notificações", "Eventos de entrada", "Identidade e transcrição", "Notificações Android", "notification_captured", "Preservar eventos persistidos"),
    ("identity", "Identidade", "Reconhecer contatos, conversas e entidades", "Chaves de conversa e vínculos", "Envio de mensagens", "Títulos, contatos, números", "conversation_resolved", "Não misturar conversas"),
    ("storage", "Persistência", "Preservar mensagens e associações", "Registros locais", "Interfaces de apresentação", "Eventos normalizados", "message_persisted", "Integridade das relações"),
    ("media", "Mídia", "Capturar, reproduzir e reter arquivos", "Mídia e retenção", "Identidade de contatos", "Áudio e imagens", "media_captured", "Arquivo disponível após atualização"),
    ("voice", "Transcrição e voz", "Transcrever e processar comandos", "Transcrição e métricas", "Identidade do remetente", "Arquivos de áudio", "transcription_completed", "Método aprovado preservado"),
    ("schedule", "Agendamento", "Persistir e executar envios programados", "Fila, tentativas e falhas", "Cadastro de contatos", "Destino e horário", "message_scheduled, message_sent", "Fila persistente e ordenada"),
    ("backup", "Backup", "Proteger e restaurar dados", "Cópias e integridade", "Regras de conversa", "Banco, arquivos e preferências", "backup_completed", "Verificação antes da restauração"),
    ("ui", "Interface", "Apresentar e operar recursos", "Interação e preferências", "Regras dos domínios", "Estado de domínio", "user_action_requested", "Não ocultar controles aprovados"),
]
FIELDS = ["id", "domain", "purpose", "owns", "excludes", "inputs", "outputs", "invariants"]


def domains(repo, sha):
    # Initial proposal predates the historical catalog; historical presence is UNKNOWN, not false.
    return [dict(zip(FIELDS, row), status="proposto", historicity="unknown") for row in DOMAINS]


def features(repo, sha):
    path = "docs/validated-features.md"
    if not repo.exists_at(sha, path):
        return []
    import re
    body = repo.read_at(sha, path)
    return [dict(id=f"validated-{i}", name=m.group(1).strip(),
                 status="PROTECTED", path=path, historicity="documented")
            for i, m in enumerate(re.finditer(r"^###\s+(.+)$", body, re.M), 1)]


def markdown_tree(source):
    """Balanced heading hierarchy without losing original Markdown source."""
    import re
    root = {"title": "Documento", "level": 0, "children": [], "body": ""}
    stack = [root]
    for line in source.splitlines():
        m = re.match(r"^(#{1,6})\s+(.+)$", line)
        if m:
            level = len(m.group(1))
            while len(stack) > 1 and stack[-1]["level"] >= level:
                stack.pop()
            node = {"title": m.group(2), "level": level, "children": [], "body": ""}
            stack[-1]["children"].append(node)
            stack.append(node)
        else:
            stack[-1]["body"] += line + "\n"
    return root
