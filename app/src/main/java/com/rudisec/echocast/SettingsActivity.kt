package com.rudisec.echocast

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import android.widget.Switch
import android.widget.LinearLayout
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import android.text.InputType
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.util.Log
import android.content.res.Configuration
import android.widget.ImageButton

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        if (savedInstanceState == null) {
            supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.settings, SettingsFragment())
                    .commit()
        }

        val statusImageEnabled = findViewById<ImageView>(R.id.status_image_enabled)
        val statusImageDisabled = findViewById<ImageView>(R.id.status_image_disabled)
        val shareButton = findViewById<ImageButton>(R.id.share_button)

        shareButton.setOnClickListener {
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
                putExtra(Intent.EXTRA_TEXT, "Check out ${getString(R.string.app_name)} - An app to play audio during your calls!")
            }
            startActivity(Intent.createChooser(shareIntent, "Share via"))
        }

        if (statusImageEnabled == null || statusImageDisabled == null) {
            Log.e("SettingsActivity", "ImageView not found. Check layout inflation and IDs.")
        } else {
            val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val imageResource = if (isDarkTheme) R.drawable.audio_white else R.drawable.audio_black
            
            statusImageEnabled.setImageResource(imageResource)
            statusImageDisabled.setImageResource(imageResource)

            statusImageEnabled.visibility = View.GONE
            statusImageDisabled.visibility = View.VISIBLE
        }
    }

    class SettingsFragment : PreferenceFragmentCompat(), 
        SharedPreferences.OnSharedPreferenceChangeListener,
        Preference.OnPreferenceClickListener {
        private lateinit var prefs: Preferences
        private lateinit var prefEnabled: SwitchPreferenceCompat
        private lateinit var prefVersion: Preference
        private lateinit var soundboardPlayer: SoundboardPlayer
        private lateinit var soundboardPreference: SoundboardPreference
        private var statusImageEnabled: ImageView? = null
        private var statusImageDisabled: ImageView? = null

        private val requestPermissionRequired =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
                if (granted.all { it.key !in Permissions.REQUIRED || it.value }) {
                    prefs.isEnabled = true
                    prefEnabled.isChecked = true
                    updateEnabledTitle(true)
                    updateStatusImage(true)
                } else {
                    startActivity(Permissions.getAppInfoIntent(requireContext()))
                }
            }

        private val requestSafSoundboardFile =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    showSoundNameDialog(uri)
                }
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            val context = requireContext()
            prefs = Preferences(context)
            soundboardPlayer = SoundboardPlayer(context)

            prefEnabled = findPreference(Preferences.PREF_ENABLED)!!
            prefEnabled.setOnPreferenceChangeListener { _, newValue ->
                val isEnabled = newValue as Boolean
                if (isEnabled && !Permissions.haveRequired(context)) {
                    requestPermissionRequired.launch(Permissions.REQUIRED)
                    false
                } else {
                    updateEnabledTitle(isEnabled)
                    updateStatusImage(isEnabled)
                    prefs.isEnabled = isEnabled
                    true
                }
            }
            updateEnabledTitle(prefEnabled.isChecked)

            prefVersion = findPreference(Preferences.PREF_VERSION)!!
            prefVersion.onPreferenceClickListener = this
            prefVersion.summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})"

            soundboardPreference = findPreference("soundboard_container")!!
            soundboardPreference.initialize(prefs, soundboardPlayer) {
                requestSafSoundboardFile.launch(arrayOf("audio/*"))
            }

            val category = findPreference<androidx.preference.PreferenceCategory>("main_category")
            category?.layoutResource = R.layout.custom_preference_category

            category?.let { cat ->
                class CustomPreferenceCategory : androidx.preference.PreferenceCategory(context) {
                    override fun onBindViewHolder(holder: PreferenceViewHolder) {
                        super.onBindViewHolder(holder)
                        statusImageEnabled = holder.itemView.findViewById(R.id.status_image_enabled)
                        statusImageDisabled = holder.itemView.findViewById(R.id.status_image_disabled)
                        updateStatusImage(prefEnabled.isChecked)
                    }
                }
                
                val customCategory = CustomPreferenceCategory()
                customCategory.layoutResource = R.layout.custom_preference_category
                preferenceScreen.removePreference(cat)
                preferenceScreen.addPreference(customCategory)
            }
        }

        private fun updateStatusImage(isEnabled: Boolean) {
            val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val imageResource = if (isDarkTheme) R.drawable.audio_white else R.drawable.audio_black
            
            statusImageEnabled?.setImageResource(imageResource)
            statusImageDisabled?.setImageResource(imageResource)

            statusImageEnabled?.visibility = if (isEnabled) View.VISIBLE else View.GONE
            statusImageDisabled?.visibility = if (isEnabled) View.GONE else View.VISIBLE
        }

        private fun showSoundNameDialog(uri: Uri) {
            val context = requireContext()
            
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    resources.getDimensionPixelSize(R.dimen.dialog_padding),
                    resources.getDimensionPixelSize(R.dimen.dialog_padding),
                    resources.getDimensionPixelSize(R.dimen.dialog_padding),
                    resources.getDimensionPixelSize(R.dimen.dialog_padding)
                )
            }

            val inputLayout = TextInputLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
                boxStrokeWidth = 0
                boxStrokeWidthFocused = 0
                setBoxCornerRadii(8f, 8f, 8f, 8f)
                hint = getString(R.string.sound_name_hint)
            }

            val input = TextInputEditText(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                inputType = InputType.TYPE_CLASS_TEXT
                setPadding(
                    paddingLeft,
                    resources.getDimensionPixelSize(R.dimen.dialog_input_padding),
                    paddingRight,
                    resources.getDimensionPixelSize(R.dimen.dialog_input_padding)
                )
            }

            inputLayout.addView(input)
            layout.addView(inputLayout)

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.sound_name_dialog_title)
                .setView(layout)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val soundName = input.text?.toString()
                    if (!soundName.isNullOrBlank()) {
                        prefs.addSoundboardSound(soundName, uri)
                        soundboardPreference.updateSoundboard()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun updateEnabledTitle(isEnabled: Boolean) {
            prefEnabled.title = getString(
                if (isEnabled) R.string.pref_enabled_name_on
                else R.string.pref_enabled_name_off
            )
        }

        override fun onStart() {
            super.onStart()
            preferenceScreen.sharedPreferences!!.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onStop() {
            super.onStop()
            preferenceScreen.sharedPreferences!!.unregisterOnSharedPreferenceChangeListener(this)
            soundboardPlayer.stopAll()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
            when (key) {
                prefEnabled.key -> {
                    val isEnabled = sharedPreferences.getBoolean(key, false)
                    updateEnabledTitle(isEnabled)
                    updateStatusImage(isEnabled)
                }
            }
        }

        override fun onPreferenceClick(preference: Preference): Boolean {
            when (preference) {
                prefVersion -> {
                    val uri = Uri.parse("https://github.com/rudisec/EchoCast")
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    return true
                }
            }

            return false
        }
    }
}
