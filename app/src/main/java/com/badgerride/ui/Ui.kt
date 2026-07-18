package com.badgerride.ui

import android.view.Window
import android.view.WindowInsetsController
import android.widget.TextView

/** m:ss under an hour, h:mm:ss above. */
internal fun fmtTime(sec: Int): String =
    if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
    else "%d:%02d".format(sec / 60, sec % 60)

/** Dark system-bar icons on the light paper background. */
internal fun Window.lightSystemBars() {
    val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    insetsController?.setSystemBarsAppearance(mask, mask)
}

/** Avoids relayout churn from re-setting an unchanged string every render pass. */
internal fun TextView.setTextIfChanged(s: String) {
    if (text.toString() != s) text = s
}
