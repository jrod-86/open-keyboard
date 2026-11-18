// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

/** Receiver-specific animation support is narrower than its advertised image MIME types. */
internal object StickerSharePolicy {
    private val gifApps = setOf(
        "com.facebook.orca", "com.facebook.mlite", "com.facebook.katana", "com.instagram.android",
        "com.instagram.barcelona", "com.snapchat.android", "com.discord",
        // Telegram advertises WebP but treats it as a static image; GIF uses its animation path.
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta",
    )

    fun prefersGif(packageName: String, takesWebp: Boolean, takesGif: Boolean): Boolean =
        packageName in gifApps || (!takesWebp && takesGif)
}
