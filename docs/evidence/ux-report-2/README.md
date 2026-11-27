# The second UX report

Five captures from the app's own composables, drawn under Robolectric's native graphics with
fixture data. They are JVM captures, not device photos: each proves what a composable draws,
which is what the report was about. `docs/ux-fixes.md` §11 is the written record.

| File | What it shows | Test |
| --- | --- | --- |
| `reply-preview-quote.png` | The quote above the composer: accent bar, the author in the accent colour, the gist beneath, the dismiss target on the right. | `ReplyPreviewRenderTest.theQuoteIsCaptured` |
| `reply-reference-with-quote.png` | The same information at one line's size in a message row, above the author's name: `Grace Hopper  the deploy is at four`, then Noah, then the answer. | `ReplyReferenceAuthorTest.theRowWithAReferenceIsCaptured` |
| `fullscreen-image-viewer.png` | The full-window viewer over a real 1200x800 picture, zoomed to 150% by its own zoom button: the close, the name and size, the two zoom controls, the zoom level in words, and **Open in browser**. | `FullscreenImageViewerTest.theViewerIsCaptured` |
| `message-video-player.png` | A video attachment in a message row: the platform's own player with its play button and scrub bar, under the sentence that names it. The file behind it does not exist in a JVM test, so the frame is black — what is proven is the player's shape and presence, not playback. | `VideoAttachmentRenderTest.theVideoTileIsCaptured` |
| `voice-screen-opaque.png` | A voice channel's screen over the instance's own fixture: its app bar, its mark, its explanation, on an opaque background that fills the window. Nothing of the channel list it is opened from shows through. | `VoiceScreenIsOpaqueTest.theVoiceScreenDrawsItsOwnOpaqueSurface` |

What these do **not** show: touch, a real decoder, audio, a device's own density, or the
gestures as a thumb makes them. Those are what the tests beside each capture assert on node
bounds and state, and what still needs the APK on a phone.
