// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.core.crash

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.rune.mt.forum.R

/**
 * The crash screen.
 *
 * A fatal exception used to just close the app, leaving the owner with 「闪退」 and no reason. The
 * handler now starts this activity with the stack trace before the process dies, so the failure is
 * visible, copyable and shareable on the device itself.
 *
 * Deliberately built from framework views (no Compose, no Koin, no theme): it has to render on the
 * way down, when app-level infrastructure may be exactly what just failed.
 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stack = intent.getStringExtra(EXTRA_STACK).orEmpty()
        val time = intent.getStringExtra(EXTRA_TIME).orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.crash_title)
            textSize = 22f
            setTextColor(Color.parseColor("#D32F2F"))
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.crash_hint)
            textSize = 13f
            setPadding(0, 24, 0, 24)
            setTextColor(Color.DKGRAY)
        })

        val scroll = ScrollView(this)
        scroll.addView(TextView(this).apply {
            text = ("$time\n\n$stack").trim()
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(button(getString(R.string.crash_copy)) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("crash", "$time\n\n$stack"))
            Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
        })
        root.addView(button(getString(R.string.crash_share)) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "$time\n\n$stack")
            }
            startActivity(Intent.createChooser(send, getString(R.string.crash_share)))
        })
        root.addView(button(getString(R.string.crash_restart)) {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) startActivity(launch)
            finish()
            Runtime.getRuntime().exit(0)
        })

        setContentView(root)
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 16 }
        }

    companion object {
        const val EXTRA_STACK = "stack"
        const val EXTRA_TIME = "time"

        fun intent(context: Context, time: String, stack: String): Intent =
            Intent(context, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(EXTRA_STACK, stack)
                putExtra(EXTRA_TIME, time)
            }
    }
}
