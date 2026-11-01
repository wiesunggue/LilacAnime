package com.lilac.anime.player

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

class SubtitleTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
