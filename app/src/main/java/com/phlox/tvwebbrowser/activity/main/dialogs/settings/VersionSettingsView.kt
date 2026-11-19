package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.text.Html
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ScrollView
import androidx.webkit.WebViewCompat
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.IncognitoModeMainActivity
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.activity.main.SettingsModel
import com.phlox.tvwebbrowser.databinding.ViewSettingsVersionBinding
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.utils.activity
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import androidx.core.net.toUri

@SuppressLint("SetTextI18n")
class VersionSettingsView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    companion object {
        private const val URL_SUPPORT_AUTHOR = "https://donatello.to/truefedex"
        private const val URL_LICENSE =
            "https://raw.githubusercontent.com/truefedex/tv-bro/refs/heads/master/LICENSE.md"
        private const val URL_PRIVACY_POLICY =
            "https://raw.githubusercontent.com/truefedex/tv-bro/refs/heads/master/PRIVACY.md"
    }
    private var vb = ViewSettingsVersionBinding.inflate(LayoutInflater.from(getContext()), this, true)
    var config = AppContext.provideConfig()
    var settingsModel = ActiveModelsRepository.get(SettingsModel::class, activity!!)
    var callback: Callback? = null

    interface Callback {
        fun onNeedToCloseSettings()
    }

    init {
        vb.tvVersion.text = context.getString(R.string.version_s, BuildConfig.VERSION_NAME) +
                "  ·  Build " + BuildConfig.VERSION_CODE

        vb.tvBuildFlavor.text = context.getString(
            R.string.build_flavor_s,
            BuildConfig.FLAVOR_appstore,
            BuildConfig.FLAVOR_webengine
        )

        val engineVersion = "Engine: " + WebEngineFactory.getWebEngineVersionString()
        vb.tvWebViewVersion.text = engineVersion

        //License requirement: modified builds must state that they use TV Bro's sources
        vb.tvDescription.text = context.getString(R.string.web_browser_optimized_for_tvs) +
                "\n\n" + context.getString(R.string.sofabrowse_credit)

        vb.tvLink.text = Html.fromHtml("<p><u>https://github.com/truefedex/tv-bro</u></p>",
            Html.FROM_HTML_MODE_LEGACY)
        vb.tvLink.setOnClickListener {
            loadUrl(vb.tvLink.text.toString())
        }

        vb.tvSupportAuthor.text = context.getString(R.string.support_the_author)
        vb.tvSupportAuthor.setOnClickListener {
            loadUrl(URL_SUPPORT_AUTHOR)
        }

        vb.tvLicense.text = context.getString(R.string.view_license)
        vb.tvLicense.setOnClickListener {
            loadUrl(URL_LICENSE)
        }

        vb.tvPrivacy.text = context.getString(R.string.privacy_policy)
        vb.tvPrivacy.setOnClickListener {
            loadUrl(URL_PRIVACY_POLICY)
        }

        vb.tvUkraine.setOnClickListener {
            loadUrl("https://tv-bro-3546c.web.app/msg001.html")
        }

        //Lumen updates come from the GitHub Releases of the repository the builds are made in
        vb.chkAutoCheckUpdates.isChecked = config.lumenAutoCheckUpdates
        vb.chkAutoCheckUpdates.setOnCheckedChangeListener { _, isChecked -> config.lumenAutoCheckUpdates = isChecked }
        vb.btnUpdate.setOnClickListener {
            val a = activity ?: return@setOnClickListener
            callback?.onNeedToCloseSettings()
            com.phlox.tvwebbrowser.sofa.LumenUpdater.check(a, quiet = false)
        }
        vb.btnUpdateSource.setOnClickListener { showUpdateSourceDialog() }
        refreshUpdateSource()
    }

    private fun refreshUpdateSource() {
        vb.tvUpdateSource.text = context.getString(R.string.lumen_update_source_s, config.lumenUpdateRepo) +
                if (com.phlox.tvwebbrowser.sofa.LumenUpdater.hasToken()) context.getString(R.string.lumen_update_token_saved) else ""
    }

    private fun showUpdateSourceDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(context)
        box.orientation = android.widget.LinearLayout.VERTICAL
        box.setPadding(pad, pad / 2, pad, 0)
        val repo = android.widget.EditText(context)
        repo.hint = "owner/repository"
        repo.setSingleLine()
        repo.setText(config.lumenUpdateRepo)
        val token = android.widget.EditText(context)
        token.hint = context.getString(R.string.lumen_update_token_hint)
        token.setSingleLine()
        token.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val help = android.widget.TextView(context)
        help.text = context.getString(R.string.lumen_update_token_help)
        help.alpha = 0.7f
        box.addView(repo)
        box.addView(token)
        box.addView(help)
        android.app.AlertDialog.Builder(context)
            .setTitle(R.string.lumen_update_source)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val r = repo.text.toString().trim().removePrefix("https://github.com/").trim('/')
                if (r.matches(Regex("""[\w.-]+/[\w.-]+"""))) config.lumenUpdateRepo = r
                val t = token.text.toString().trim()
                if (t.isNotEmpty()) com.phlox.tvwebbrowser.sofa.LumenUpdater.setToken(t)
                refreshUpdateSource()
            }
            .setNeutralButton(R.string.lumen_update_token_clear) { _, _ ->
                com.phlox.tvwebbrowser.sofa.LumenUpdater.setToken(null)
                refreshUpdateSource()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadUrl(url: String) {
        callback?.onNeedToCloseSettings()
        val activityClass = if (settingsModel.config.incognitoMode)
            IncognitoModeMainActivity::class.java else MainActivity::class.java
        val intent = Intent(activity, activityClass)
        intent.data = url.toUri()
        activity?.startActivity(intent)
    }
}
