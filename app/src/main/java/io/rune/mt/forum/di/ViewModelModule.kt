// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.di

import io.rune.mt.forum.ui.page.forum.ForumViewModel
import io.rune.mt.forum.ui.page.login.LoginViewModel
import io.rune.mt.forum.ui.page.main.settings.AccountViewModel
import io.rune.mt.forum.ui.page.main.settings.SettingsSharedViewModel
import io.rune.mt.forum.ui.page.main.settings.home.HomePageViewModel
import io.rune.mt.forum.ui.page.main.settings.preferred.PreferredViewModel
import io.rune.mt.forum.ui.page.main.settings.preferred.about.AboutViewModel
import io.rune.mt.forum.ui.page.main.settings.preferred.theme.ThemeSettingsViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModelOf(::SettingsSharedViewModel)
    viewModelOf(::HomePageViewModel)
    viewModelOf(::PreferredViewModel)
    viewModelOf(::ThemeSettingsViewModel)
    viewModelOf(::AboutViewModel)
    viewModelOf(::ForumViewModel)
    viewModelOf(::LoginViewModel)
    viewModelOf(::AccountViewModel)
}
