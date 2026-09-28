package com.appgate.tv.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

/**
 * Without a PromptDelegate a GeckoSession silently swallows every <select>, confirm(),
 * alert() and HTTP-auth dialog — which is exactly how a "pick a 2-step method" dropdown or a
 * Make/Model select turns into a dead screen. When a person is looking at the session we show
 * real dialogs; when the engine is running headless we resolve prompts safely on its behalf.
 */
class BrainPromptDelegate(private val activityProvider: () -> Activity?) : GeckoSession.PromptDelegate {

    override fun onChoicePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ChoicePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val activity = activityProvider() ?: return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val flat = ArrayList<GeckoSession.PromptDelegate.ChoicePrompt.Choice>()
        fun flatten(list: Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>) {
            for (c in list) { if (c.separator) continue; if (c.items != null && c.items!!.isNotEmpty()) flatten(c.items!!) else flat += c }
        }
        flatten(prompt.choices)
        val labels = flat.map { it.label }.toTypedArray()
        val builder = AlertDialog.Builder(activity).setTitle(prompt.title ?: prompt.message ?: "Choose")
        if (prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
            val checked = BooleanArray(flat.size) { flat[it].selected }
            builder.setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            builder.setPositiveButton(android.R.string.ok) { _, _ ->
                val ids = flat.indices.filter { checked[it] }.map { flat[it].id }.toTypedArray()
                result.complete(prompt.confirm(ids))
            }
        } else {
            val selected = flat.indexOfFirst { it.selected }
            builder.setSingleChoiceItems(labels, selected) { dialog, which ->
                result.complete(prompt.confirm(flat[which]))
                dialog.dismiss()
            }
        }
        builder.setNegativeButton(android.R.string.cancel) { _, _ -> result.complete(prompt.dismiss()) }
        builder.setOnCancelListener { if (!prompt.isComplete) result.complete(prompt.dismiss()) }
        builder.show()
        return result
    }

    override fun onAlertPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.AlertPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val activity = activityProvider() ?: return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        AlertDialog.Builder(activity).setTitle(prompt.title ?: "").setMessage(prompt.message ?: "")
            .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.dismiss()) }
            .setOnCancelListener { result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onButtonPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ButtonPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val activity = activityProvider() ?: return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val listener = DialogInterface.OnClickListener { _, which ->
            when (which) {
                DialogInterface.BUTTON_POSITIVE -> result.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE))
                DialogInterface.BUTTON_NEGATIVE -> result.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE))
                else -> result.complete(prompt.dismiss())
            }
        }
        AlertDialog.Builder(activity).setTitle(prompt.title ?: "").setMessage(prompt.message ?: "")
            .setPositiveButton(android.R.string.ok, listener).setNegativeButton(android.R.string.cancel, listener)
            .setOnCancelListener { if (!prompt.isComplete) result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onTextPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.TextPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val activity = activityProvider() ?: return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val input = EditText(activity).apply { setText(prompt.defaultValue ?: "") }
        AlertDialog.Builder(activity).setTitle(prompt.title ?: "").setMessage(prompt.message ?: "").setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.confirm(input.text.toString())) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.complete(prompt.dismiss()) }
            .setOnCancelListener { if (!prompt.isComplete) result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onAuthPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.AuthPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        // HTTP basic auth: only a person may type credentials, and they are handed straight to Gecko.
        val activity = activityProvider() ?: return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        val onlyPassword = (prompt.authOptions.flags and GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) != 0
        val username = EditText(activity).apply { hint = "Username"; setText(prompt.authOptions.username ?: "") }
        if (!onlyPassword) container.addView(username)
        val password = EditText(activity).apply { hint = "Password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        container.addView(password)
        AlertDialog.Builder(activity).setTitle(prompt.title ?: "Sign in").setMessage(prompt.message ?: "").setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                result.complete(if (onlyPassword) prompt.confirm(password.text.toString()) else prompt.confirm(username.text.toString(), password.text.toString()))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.complete(prompt.dismiss()) }
            .setOnCancelListener { if (!prompt.isComplete) result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onPopupPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.PopupPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
        GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))   // sign-in providers open popups; onNewSession decides what to do with them

    override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
        GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))

    override fun onRepostConfirmPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
        GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))    // never re-submit a form on the user's behalf
}
