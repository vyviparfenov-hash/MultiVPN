package com.amneziaclient.simple.ui.profiles

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.amneziaclient.simple.R
import com.amneziaclient.simple.databinding.ItemProfileBinding
import com.amneziaclient.simple.vpn.manager.StoredProfile
import com.amneziaclient.simple.vpn.plugin.VpnProtocolType

/** [isActive]/[isConnected]/[isSelectionMode]/[isSelected] должны быть
 *  частью сравниваемых DiffUtil'ом данных — иначе при их смене строки не
 *  перерисовываются (RecyclerView не знает, что именно у НИХ что-то
 *  изменилось, если сам StoredProfile не поменялся). */
data class ProfileRow(
    val profile: StoredProfile,
    val isActive: Boolean,
    val isConnected: Boolean,
    val isSelectionMode: Boolean = false,
    val isSelected: Boolean = false
)

class ProfileListAdapter(
    private val onClick: (StoredProfile) -> Unit,
    private val onEdit: (StoredProfile) -> Unit,
    private val onDelete: (StoredProfile) -> Unit,
    private val onExport: (StoredProfile) -> Unit,
    private val onToggleSelect: (StoredProfile) -> Unit
) : ListAdapter<ProfileRow, ProfileListAdapter.ProfileViewHolder>(DIFF) {

    companion object {
        private const val SELECTION_SCALE = 0.96f
        private const val SCALE_ANIM_DURATION_MS = 150L

        private val DIFF = object : DiffUtil.ItemCallback<ProfileRow>() {
            override fun areItemsTheSame(old: ProfileRow, new: ProfileRow) = old.profile.id == new.profile.id
            override fun areContentsTheSame(old: ProfileRow, new: ProfileRow) = old == new
        }

        private fun colorFor(protocol: VpnProtocolType): Int = when (protocol) {
            VpnProtocolType.AMNEZIAWG -> R.color.protocol_amneziawg
            VpnProtocolType.WIREGUARD -> R.color.protocol_wireguard
            VpnProtocolType.OPENVPN -> R.color.protocol_openvpn
            VpnProtocolType.IKEV2 -> R.color.protocol_ikev2
            VpnProtocolType.L2TP -> R.color.protocol_l2tp
            VpnProtocolType.SSTP -> R.color.protocol_sstp
            VpnProtocolType.SOFTETHER -> R.color.protocol_softether
            VpnProtocolType.VLESS -> R.color.protocol_vless
        }

        /** Разные протоколы — разные значки (не только цвет кружка), чтобы
         *  профили визуально отличались друг от друга с первого взгляда.
         *  Не копируем чужие товарные знаки — простые обобщённые значки по
         *  смыслу протокола (ключ, замок, молния и т.д.). */
        private fun iconFor(protocol: VpnProtocolType): Int = when (protocol) {
            VpnProtocolType.AMNEZIAWG -> R.drawable.ic_protocol_amneziawg
            VpnProtocolType.WIREGUARD -> R.drawable.ic_shield
            VpnProtocolType.OPENVPN -> R.drawable.ic_protocol_openvpn
            VpnProtocolType.IKEV2 -> R.drawable.ic_protocol_ikev2
            VpnProtocolType.L2TP -> R.drawable.ic_protocol_l2tp
            VpnProtocolType.SSTP -> R.drawable.ic_protocol_sstp
            VpnProtocolType.SOFTETHER -> R.drawable.ic_protocol_softether
            VpnProtocolType.VLESS -> R.drawable.ic_protocol_vless
        }
    }

    inner class ProfileViewHolder(val binding: ItemProfileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val binding = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ProfileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        val row = getItem(position)
        val profile = row.profile
        val context = holder.binding.root.context

        holder.binding.textName.text = profile.name
        holder.binding.textProtocol.text = profile.subtitle.ifBlank { profile.protocol.name }

        holder.binding.protocolIconBg.backgroundTintList =
            ContextCompat.getColorStateList(context, colorFor(profile.protocol))
        holder.binding.protocolIcon.setImageResource(iconFor(profile.protocol))

        // Рамка вокруг карточки — единственный признак "этот профиль сейчас
        // активен" (будет использован при следующем нажатии Старт). Текст
        // "Готов" для остальных профилей убран — он ничего не добавлял.
        holder.binding.root.setBackgroundResource(
            if (row.isActive) R.drawable.bg_card_active else R.drawable.bg_card
        )

        if (row.isConnected) {
            holder.binding.textStatus.text = context.getString(R.string.profile_status_connected)
            holder.binding.textStatus.visibility = View.VISIBLE
        } else {
            holder.binding.textStatus.visibility = View.GONE
        }

        // Плавное уменьшение карточек при входе/выходе из режима выбора для
        // экспорта — анимируем сами, а не мгновенным изменением масштаба.
        val targetScale = if (row.isSelectionMode) SELECTION_SCALE else 1f
        if (holder.binding.root.scaleX != targetScale) {
            holder.binding.root.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(SCALE_ANIM_DURATION_MS)
                .start()
        }

        if (row.isSelectionMode) {
            holder.binding.buttonMenu.visibility = View.GONE
            holder.binding.checkboxSelect.visibility = View.VISIBLE
            holder.binding.checkboxSelect.isChecked = row.isSelected
        } else {
            holder.binding.buttonMenu.visibility = View.VISIBLE
            holder.binding.checkboxSelect.visibility = View.GONE
        }

        holder.binding.root.setOnClickListener {
            if (row.isSelectionMode) onToggleSelect(profile) else onClick(profile)
        }
        holder.binding.root.setOnLongClickListener {
            if (!row.isSelectionMode) showContextMenu(context, holder.binding.root, profile)
            true
        }
        holder.binding.buttonMenu.setOnClickListener { anchor ->
            showContextMenu(context, anchor, profile)
        }
    }

    private fun showContextMenu(context: Context, anchor: View, profile: StoredProfile) {
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, 0, 0, R.string.action_edit)
        popup.menu.add(0, 1, 1, R.string.action_export_profile)
        popup.menu.add(0, 2, 2, R.string.action_delete_active)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> onEdit(profile)
                1 -> onExport(profile)
                2 -> onDelete(profile)
            }
            true
        }
        popup.show()
    }
}
