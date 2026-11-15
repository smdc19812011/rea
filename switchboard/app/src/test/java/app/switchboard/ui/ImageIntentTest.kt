package app.switchboard.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageIntentTest {
    @Test fun recognisesPictureRequests() {
        listOf(
            "generate an image of a man fighting a dragon",
            "Create a picture of a cat in space",
            "please make me a logo for my bakery",
            "can you draw a castle at sunset",
            "draw me a robot",
            "Paint a portrait of an old fisherman",
            "give me a wallpaper with mountains",
        ).forEach { assertTrue(it, looksLikeImageRequest(it)) }
    }

    @Test fun leavesOtherRequestsToTheChatModel() {
        listOf(
            "create an SVG image of a star",
            "make a chart of my expenses",
            "draw a diagram of the network",
            "what is image compression?",
            "I took a picture yesterday, can you help me name it?",
            "generate a report about dragons",
            "write code that resizes an image",
        ).forEach { assertFalse(it, looksLikeImageRequest(it)) }
    }
}
