// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerSharePolicyTest {
    @Test fun telegramUsesGifEvenWhenItAdvertisesWebp() {
        listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta")
            .forEach { assertTrue(StickerSharePolicy.prefersGif(it, true, true)) }
    }

    @Test fun existingGifReceiversAreUnchanged() {
        listOf("com.facebook.orca", "com.facebook.mlite", "com.facebook.katana", "com.instagram.android",
            "com.instagram.barcelona", "com.snapchat.android", "com.discord")
            .forEach { assertTrue(StickerSharePolicy.prefersGif(it, true, true)) }
    }

    @Test fun whatsappSignalAndPhoneMessagesKeepWebp() {
        listOf("com.whatsapp", "com.whatsapp.w4b", "org.thoughtcrime.securesms", "com.google.android.apps.messaging")
            .forEach { assertFalse(StickerSharePolicy.prefersGif(it, true, true)) }
    }

    @Test fun gifOnlyReceiverGetsGif() {
        assertTrue(StickerSharePolicy.prefersGif("example.chat", false, true))
    }

    @Test fun unsupportedReceiverDoesNotGetForcedGif() {
        assertFalse(StickerSharePolicy.prefersGif("example.chat", false, false))
        assertFalse(StickerSharePolicy.prefersGif("example.chat", true, false))
    }

    @Test fun packageMatchingIsExact() {
        assertFalse(StickerSharePolicy.prefersGif("example.org.telegram.messenger", true, true))
    }
}
