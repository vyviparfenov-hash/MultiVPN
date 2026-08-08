package com.amneziaclient.simple.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.amneziaclient.simple.R
import com.google.android.material.bottomsheet.BottomSheetDialog

data class BottomSheetOption(
    @DrawableRes val iconRes: Int,
    val title: String,
    val subtitle: String? = null,
    val onClick: () -> Unit
)

/**
 * Единый стиль для списков-выборов вроде "Добавить профиль" / "Выбрать
 * протокол" — выезжающий снизу лист с иконками вместо обычного
 * AlertDialog.setItems(), под дизайн приложения.
 */
fun showOptionsBottomSheet(context: Context, title: String, options: List<BottomSheetOption>) {
    val dialog = BottomSheetDialog(context, R.style.AppBottomSheetDialog)
    val inflater = LayoutInflater.from(context)
    val sheetView = inflater.inflate(R.layout.bottom_sheet_options, null, false)

    sheetView.findViewById<TextView>(R.id.sheetTitle).text = title

    val container = sheetView.findViewById<LinearLayout>(R.id.sheetOptionsContainer)
    options.forEach { option ->
        val rowView = inflater.inflate(R.layout.item_bottom_sheet_option, container, false)
        rowView.findViewById<ImageView>(R.id.rowIcon).setImageResource(option.iconRes)
        rowView.findViewById<TextView>(R.id.rowTitle).text = option.title
        val subtitleView = rowView.findViewById<TextView>(R.id.rowSubtitle)
        if (option.subtitle.isNullOrBlank()) {
            subtitleView.visibility = View.GONE
        } else {
            subtitleView.text = option.subtitle
        }
        rowView.setOnClickListener {
            dialog.dismiss()
            option.onClick()
        }
        container.addView(rowView)
    }

    sheetView.findViewById<View>(R.id.sheetCancelButton).setOnClickListener {
        dialog.dismiss()
    }

    dialog.setContentView(sheetView)
    dialog.show()
}
