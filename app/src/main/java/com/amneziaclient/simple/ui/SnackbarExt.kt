package com.amneziaclient.simple.ui

import android.view.Gravity
import android.view.View
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar

/**
 * Обычный Snackbar встаёт снизу и перекрывает нижнюю навигацию/кнопки —
 * поднимаем его наверх через gravity в LayoutParams. Используется вместо
 * прямого Snackbar.make(...) везде в приложении, для единообразия.
 */
fun View.showTopSnackbar(text: CharSequence, duration: Int = Snackbar.LENGTH_LONG): Snackbar {
    val snackbar = Snackbar.make(this, text, duration)
    anchorSnackbarAtTop(snackbar)
    snackbar.show()
    return snackbar
}

fun View.showTopSnackbar(@StringRes textRes: Int, duration: Int = Snackbar.LENGTH_LONG): Snackbar {
    val snackbar = Snackbar.make(this, textRes, duration)
    anchorSnackbarAtTop(snackbar)
    snackbar.show()
    return snackbar
}

private fun anchorSnackbarAtTop(snackbar: Snackbar) {
    val view = snackbar.view
    when (val params = view.layoutParams) {
        is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams -> {
            params.gravity = Gravity.TOP
            view.layoutParams = params
        }
        is android.widget.FrameLayout.LayoutParams -> {
            params.gravity = Gravity.TOP
            view.layoutParams = params
        }
    }
    view.translationY = 0f
}
