package com.nabd.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/**
 * Long press on a movie / series card: a full-screen card with the picture, title, a few facts and the synopsis on one side,
 * and the actions (watch, watch later, favourites, history, hide, external player) plus the same title from other sources on the other.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TitleMenu(vm: AppViewModel, c: Channel) {
    val kind = kindOf(c)
    val ctx = LocalContext.current
    val close = { vm.titleMenu = null }
    val info by produceState(vm.repo.cachedInfo(c, kind), c.url) {
        if (value == null) value = runCatching { vm.repo.vodInfo(c, kind) }.getOrNull()
    }
    val alts = remember(c.url, vm.rev) { vm.alternativesOf(c) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    androidx.compose.ui.window.Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xEB07080C))) {
            val wide = maxWidth >= 720.dp
            val about: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(if (wide) 340.dp else 260.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color(0x1FFFFFFF))) {
                        AdaptiveArtImage(vm, c, landscape = true, modifier = Modifier.fillMaxSize(), cropFallback = true)
                    }
                    Text(c.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val facts = listOfNotNull(
                        c.group.takeIf { it.isNotBlank() },
                        info?.year?.takeIf { it.isNotBlank() },
                        (ratingBadge(c) ?: info?.rating?.takeIf { it.isNotBlank() })?.let { "★ $it" },
                        info?.duration?.takeIf { it.isNotBlank() }
                    )
                    if (facts.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        facts.forEach { MetaChip(it) }
                    }
                    info?.plot?.takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 14.sp, color = Color(0xCCFFFFFF), maxLines = if (wide) 5 else 3, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                    }
                }
            }
            val actions: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    @Composable fun row(label: String, mod: Modifier = Modifier, act: () -> Unit) =
                        Focusable(onClick = { close(); act() }, modifier = mod.fillMaxWidth()) {
                            Text(label, fontSize = 16.sp, color = Ink, maxLines = 1, modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp))
                        }
                    row(tr(R.string.watch), Modifier.focusRequester(first)) { vm.open(c) }
                    if (kind == Kind.Movie) row(tr(R.string.details)) { vm.openDetail(c) }
                    row(if (c.url in vm.laterUrls) tr(R.string.x_later_remove) else tr(R.string.x_later_add)) { vm.toggleLater(c) }
                    row(if (c.url in vm.favs) tr(R.string.menu_fav_remove) else tr(R.string.menu_fav_add)) { vm.toggleFav(c) }
                    if (vm.historyByUrl[c.url] != null) row(tr(R.string.menu_remove_history)) { vm.removeFromHistory(c) }
                    row(tr(R.string.menu_hide)) { vm.hideChannel(c) }
                    if (kind == Kind.Movie) row(tr(R.string.menu_external)) { vm.openExternally(ctx, c) }
                    if (alts.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(tr(R.string.alt_sources).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0x99FFFFFF))
                        alts.forEach { a ->
                            Focusable(onClick = { close(); if (kind == Kind.Movie) vm.play(a) else vm.open(a) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                    Text(a.name, fontSize = 15.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (a.group.isNotBlank()) Text(a.group, fontSize = 11.sp, color = Color(0x99FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            if (wide) Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 36.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                about(Modifier.weight(1f).align(Alignment.CenterVertically))
                actions(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()))
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                about(Modifier.fillMaxWidth())
                actions(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Opens the stream in another installed player (the chooser lists them). */
fun AppViewModel.openExternally(ctx: android.content.Context, c: Channel) {
    runCatching {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(c.url)).setType("video/*")
        ctx.startActivity(android.content.Intent.createChooser(i, tx(R.string.a_a332848d)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { say(tx(R.string.a_34286b07)) }
}
