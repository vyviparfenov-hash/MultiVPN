package com.amneziaclient.simple.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.amneziaclient.simple.ui.apps.AppsFragment
import com.amneziaclient.simple.ui.home.HomeFragment
import com.amneziaclient.simple.ui.profiles.ProfilesFragment
import com.amneziaclient.simple.ui.settings.SettingsFragment

/** Порядок страниц ЗАФИКСИРОВАН и должен совпадать с порядком пунктов в
 *  bottom_nav_menu.xml (см. MainActivity.tabPositions). */
class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> HomeFragment()
        1 -> ProfilesFragment()
        2 -> AppsFragment()
        3 -> SettingsFragment()
        else -> throw IllegalArgumentException("Unknown page position: $position")
    }
}
