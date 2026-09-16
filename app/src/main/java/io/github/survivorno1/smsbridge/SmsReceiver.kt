package io.github.survivorno1.smsbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** 开关打开时才收集；关着的时候短信到了也当没看见。 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!BridgeService.running) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val from = parts[0].displayOriginatingAddress ?: ""
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        val ts = parts[0].timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        Store.init(context)
        Store.add(Msg(ts, from, body))
    }
}
