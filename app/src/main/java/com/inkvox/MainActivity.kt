package com.inkvox

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var title: TextView
    private lateinit var description: TextView
    private lateinit var privacy: TextView
    private lateinit var primaryAction: Button
    private lateinit var secondaryAction: Button
    private lateinit var version: TextView
    private lateinit var cloudConfig: LinearLayout
    private lateinit var workspaceId: EditText
    private lateinit var apiKey: EditText
    private lateinit var saveConfig: Button
    private lateinit var configStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)

        title = findViewById(R.id.title)
        description = findViewById(R.id.description)
        privacy = findViewById(R.id.privacy)
        primaryAction = findViewById(R.id.primary_action)
        secondaryAction = findViewById(R.id.secondary_action)
        version = findViewById(R.id.version)
        cloudConfig = findViewById(R.id.cloud_config)
        workspaceId = findViewById(R.id.workspace_id)
        apiKey = findViewById(R.id.api_key)
        saveConfig = findViewById(R.id.save_config)
        configStatus = findViewById(R.id.config_status)

        if (intent.getBooleanExtra(EXTRA_REQUEST_MICROPHONE, false)) {
            showPermissionScreen(savedInstanceState == null)
        } else {
            showSetupScreen()
        }
    }

    private fun showSetupScreen() {
        cloudConfig.visibility = View.VISIBLE
        title.setText(R.string.app_name)
        description.setText(R.string.app_intro)
        privacy.setText(R.string.privacy_notice)
        primaryAction.setText(R.string.enable_ime)
        primaryAction.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        secondaryAction.setText(R.string.select_ime)
        secondaryAction.setOnClickListener {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
        CloudConfig.load(this)?.let {
            workspaceId.setText(it.workspaceId)
            apiKey.setText(it.apiKey)
            configStatus.setText(R.string.cloud_config_saved)
        }
        saveConfig.setOnClickListener {
            val workspace = workspaceId.text.toString().trim()
            val key = apiKey.text.toString().trim()
            configStatus.setText(
                when {
                    !CloudConfig.isValidWorkspaceId(workspace) -> R.string.invalid_workspace_id
                    !CloudConfig.isValidApiKey(key) -> R.string.invalid_api_key
                    CloudConfig.save(this, key, workspace) -> R.string.cloud_config_saved
                    else -> R.string.cloud_config_save_failed
                },
            )
        }
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0"
        version.text = getString(R.string.version_format, versionName)
    }

    private fun showPermissionScreen(requestImmediately: Boolean) {
        cloudConfig.visibility = View.GONE
        title.setText(R.string.permission_title)
        description.setText(R.string.permission_explanation)
        privacy.setText(R.string.privacy_notice)
        version.text = ""
        primaryAction.setText(R.string.grant_permission)
        primaryAction.setOnClickListener { requestMicrophonePermission() }
        secondaryAction.setText(R.string.open_settings)
        secondaryAction.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish()
        } else if (requestImmediately) {
            requestMicrophonePermission()
        }
    }

    private fun requestMicrophonePermission() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MICROPHONE_PERMISSION_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            finish()
        }
    }

    companion object {
        const val EXTRA_REQUEST_MICROPHONE = "com.inkvox.REQUEST_MICROPHONE"
        private const val MICROPHONE_PERMISSION_REQUEST = 1
    }
}
