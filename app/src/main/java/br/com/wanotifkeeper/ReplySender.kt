package br.com.wanotifkeeper

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

/** Resultado de uma tentativa de envio. Nunca há um terceiro estado "provavelmente foi". */
sealed class ReplyResult {
    /**
     * O mecanismo **aceitou** o envio. Isto é o que o Android consegue afirmar: o
     * `PendingIntent` do WhatsApp foi disparado sem exceção. Não é prova de que o
     * destinatário recebeu — e a #18 pede exatamente esta fronteira, não mais.
     */
    object Accepted : ReplyResult()

    /**
     * Não deu. [reason] é registrado como está, para a UI mostrar sem inventar sucesso.
     *
     * [consumesAttempt] separa "o envio foi tentado e falhou" de "não havia por onde
     * tentar". A segunda é condição do ambiente (o cache de ações nasce vazio a cada
     * reinício do processo) e não pode gastar a cota de tentativas.
     */
    data class Rejected(val reason: String, val consumesAttempt: Boolean = true) : ReplyResult()
}

/**
 * Fronteira do mecanismo de envio.
 *
 * Existe por causa do passo zero da #18: a viabilidade do `RemoteInput` +
 * `PendingIntent` foi concluída **por leitura de código e da documentação do
 * Android**, sem aparelho para confirmar (ver `RESUME.md` da 018). Se a verificação
 * no aparelho derrubar a hipótese, troca-se a implementação daqui sem tocar na
 * máquina de estados, na tabela nem na UI.
 */
interface ReplySender {
    suspend fun send(packageName: String, sender: String, text: String): ReplyResult
}

/**
 * Cache das ações de resposta rápida publicadas pelo próprio WhatsApp.
 *
 * Vive fora do `NotifListenerService` para que o envio não precise de uma referência
 * ao serviço — e para que a frente que mexe no listener e esta frente não briguem
 * pelo mesmo trecho de arquivo.
 *
 * Só em memória, de propósito: um `PendingIntent` não sobrevive (nem faria sentido
 * sobreviver) a um reinício do processo. A chave é `"pacote|remetente"`, para que o
 * mesmo nome de exibição no WhatsApp e no Business não se confunda.
 */
object ReplyActionRegistry {

    /**
     * ATENÇÃO (concílio, rodada 1): `send()` retornar sem exceção prova que o Android
     * aceitou **despachar** o intent — não que o WhatsApp processou a resposta. Se
     * alguma build rotear a resposta por uma activity, a restrição de background
     * activity launch faz o disparo virar no-op silencioso. O overload de `send` com
     * `OnFinished` reduziria a dúvida e não foi usado nesta janela: sem aparelho, não
     * havia como distinguir os casos. Pendência registrada no `RESUME.md` da 018.
     */

    class CachedReply(
        val actionIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
        val resultKey: String,
        val notificationKey: String
    )

    private val cache = ConcurrentHashMap<String, CachedReply>()

    fun key(packageName: String, sender: String) = "$packageName|$sender"

    /**
     * Guarda a ação de resposta desta notificação, se ela trouxer uma.
     *
     * Notificação mais nova substitui a mais velha; se esta em particular não trouxer
     * ação (acontece em reposts de atualização), a entrada anterior fica como estava.
     * Devolve o `resultKey` guardado, ou `null` quando não havia ação — é assim que o
     * chamador sabe se esta versão do WhatsApp expõe resposta direta.
     */
    fun remember(
        packageName: String,
        sender: String,
        notificationKey: String,
        actions: Array<Notification.Action>?
    ): String? {
        val action = actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return null
        val remoteInputs = action.remoteInputs ?: return null
        val actionIntent = action.actionIntent ?: return null
        val resultKey = remoteInputs.first().resultKey
        cache[key(packageName, sender)] = CachedReply(
            actionIntent = actionIntent,
            remoteInputs = remoteInputs,
            resultKey = resultKey,
            notificationKey = notificationKey
        )
        return resultKey
    }

    /** A notificação sumiu (lida, apagada) — o `PendingIntent` cacheado não vale mais. */
    fun forget(notificationKey: String) {
        val stale = cache.entries.firstOrNull { it.value.notificationKey == notificationKey }?.key ?: return
        cache.remove(stale)
    }

    fun get(packageName: String, sender: String): CachedReply? = cache[key(packageName, sender)]

    fun clear() = cache.clear()
}

/**
 * Envio real: preenche o `RemoteInput` da ação de resposta do WhatsApp e dispara o
 * `PendingIntent` dele.
 *
 * O `PendingIntent` executa com a identidade e as permissões do **WhatsApp**, não do
 * WA-Keeper — nenhuma permissão nova é necessária além do acesso a notificações que o
 * app já tem. Não há automação de tela em lugar nenhum deste caminho, como a #18 exige.
 */
class NotificationReplySender(private val context: Context) : ReplySender {

    override suspend fun send(packageName: String, sender: String, text: String): ReplyResult {
        val cached = ReplyActionRegistry.get(packageName, sender)
            ?: return ReplyResult.Rejected(NO_ACTION, consumesAttempt = false)

        return runCatching {
            val intent = Intent()
            val results = Bundle().apply { putCharSequence(cached.resultKey, text) }
            RemoteInput.addResultsToIntent(cached.remoteInputs, intent, results)
            cached.actionIntent.send(context, 0, intent)
            ReplyResult.Accepted as ReplyResult
        }.getOrElse { e ->
            // CanceledException: a notificação morreu entre o cache e o disparo. É
            // recuperável — o próximo contato traz uma ação nova.
            ReplyActionRegistry.forget(cached.notificationKey)
            ReplyResult.Rejected("${e.javaClass.simpleName}: ${e.message ?: "sem detalhe"}")
        }
    }

    companion object {
        /**
         * Motivo registrado quando a notificação daquela conversa não expõe ação de
         * resposta compatível. É a "impossibilidade" que a #18 manda registrar em vez
         * de simular sucesso — fica no `lastError` da linha e aparece na lista.
         */
        const val NO_ACTION = "notificação sem ação de resposta compatível (RemoteInput ausente)"
    }
}
