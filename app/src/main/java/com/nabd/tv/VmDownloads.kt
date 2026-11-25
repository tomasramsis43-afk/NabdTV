package com.nabd.tv

import java.io.File

/** Plays a finished download from the device (same path as a finished recording). */
fun AppViewModel.playDownload(d: DownloadItem) {
    val f = d.file ?: return
    if (!File(f).exists()) { say(tx(R.string.dl_missing)); return }
    play(Channel(-1, d.title, "file://$f", d.poster, tx(R.string.tab_downloads)), PlayMode.Recording, 0)
}
