# Uploading an attachment

A picture in a chat is four things that have to agree: the bytes, the object they are stored
under, the message part that names it, and the URL a reader draws it from. When one of them is
wrong the message still sends, so the failure is invisible until somebody else opens the
channel -- which is exactly what happened before this existed: the composer had no attach
button at all, and a file part this client built went out as `{"type":"file"}` with no path in
it, because `ContentPart`'s serialiser wrote only text, url, name and emoji.

## The pipeline

`MessageMediaRepository.upload` runs the same four steps the reference client's
`startMessageMediaUpload` does, in this order:

1. **Refuse what cannot be stored.** SVG is refused on the device -- it can carry script, and
   the bucket's MIME list is the second line of defence, not the first. A file over the
   account's own limit (`get_my_upload_limit`) is refused with both numbers in the sentence, so
   a person is told "4 MB, over this account's 2 MB limit" rather than waiting for a minute and
   reading a row-level-security error. An instance from before that function existed answers
   nothing, which is not an error: the bucket's limit governs instead.
2. **Strip the metadata**, unless the person asked to keep it. `MediaMetadata` walks the file's
   own structure and copies the parts that are not metadata, so nothing is decoded or
   re-encoded and the stored bytes are the camera's bytes minus the position.
3. **Upload** to `<room>/<uploader>/<uuid>.<ext>` in `message_media`, reporting progress and
   stopping when the coroutine is cancelled. The name is not a preference: the bucket's policies
   read the first two folders as the room and the third as the uploader, so any other name is
   refused at upload and invisible to the room afterwards.
4. **Sign a URL** for the new part, which older clients render.

Nothing is left behind on failure. An upload that fails, is cancelled, or whose attachment is
removed after it landed has its object deleted, because the bucket's own sweep only finds an
unreferenced object after its window and an instance should not accumulate files nobody can see.

## The part, and the two URLs

A `file` part carries both:

| Field | What it is | Who reads it |
|---|---|---|
| `path` | The `message_media` object name, e.g. `c/<channel>/<authUID>/<uuid>.png` | This client, and any client that knows to sign |
| `url` | A signed URL valid **seven days** from upload | Clients that predate `path` |

This client draws `path` and never `url`, because a channel read a week after it was written has
a `url` that expired and a `path` that has not. A page of history is signed in one batch
(`AppState.signMedia`), cached per object for five hours, and a failed signing is retried after
a minute rather than on every recomposition. An object the account may not read draws a
placeholder that says so; it never falls back to the expired `url`.

A signed URL that points at another host is discarded: the answer comes from the instance, but
the instance is not trusted to choose where the client fetches a picture from.

## What the stripper removes

Lossless, per container:

| Container | Removed | Kept |
|---|---|---|
| JPEG | Exif, XMP, IPTC, comments, the other APPn, a JFXX thumbnail, bytes after EOI | Pixels, the ICC profile, Adobe's colour transform, a non-normal Exif orientation (as a 36-byte block) |
| PNG | `eXIf`, `tEXt`, `iTXt`, `zTXt`, `tIME`, private chunks, bytes after IEND | Pixels, colour chunks, APNG's animation chunks |
| WebP | `EXIF`, `XMP`, unknown chunks, the VP8X `E`/`X` flags, bytes after the RIFF payload | Pixels, alpha, animation, the ICC profile |
| GIF | Comment extensions, application extensions other than the three that carry animation and colour | Frames, the global colour table |
| ISOBMFF video | `udta`/`meta` boxes and the XMP/USMT/PROF `uuid` boxes, rewritten as `free` boxes of the same size | Everything else, byte for byte -- no offset moves, so the sample tables stay valid |

**Not handled, uploaded unchanged, and reported as unchecked:** HEIF still images (HEIC/AVIF),
Matroska/WebM, TIFF/DNG, JPEG XL, and audio. Their metadata lives in structures this walker
cannot rewrite without moving bytes that address the image itself. The composer says "details
not checked" for those files rather than "removed", because a person deciding whether to send a
photo of their home needs the truth, not reassurance.

An orientation other than 1 is kept: dropping it turns a portrait photo on its side, and it
describes the picture rather than the photographer.

## What the composer says

Each file in the strip reports its own state, and the words are claims about the bytes:

- `Uploading… 45%`, with a bar;
- `46.9 KB · location removed` -- a position was found and is gone;
- `46.9 KB · contains a location` -- a position is still there and the file is not being
  stripped (it is being kept, or it was already sent);
- `46.9 KB · details not checked` -- a container this build does not rewrite;
- the instance's own refusal, in its own numbers.

A warning line appears **only** when the bytes being sent still carry a position. Warning about
a position that was just removed, next to a line saying it was removed, teaches a person to
ignore the warning -- and the render capture in `docs/evidence/render/attachment-composer.png`
is what caught that contradiction.

Send waits until no file is still uploading: a message that names an object still in flight
arrives with a broken attachment, which is worse than a second of waiting. It waits for a file
that *failed* too, and the strip says what to do about it: the strip is cleared on send, so
letting the message go would drop that file without a word -- the silent failure this whole
pipeline exists to end.

## What this does not do

- **No resumable transport.** A file goes up in one request. Above about 6 MB the reference
  client switches to TUS with resume after a stall; this one restarts, and a stall is a
  failure. That is the largest gap against M01.
- **No Drive integration.** No SHA-256, no hash dedup, no `ephemeral`, no `photo_details`
  column -- so an instance with Drive cannot say when a file was already stored, and the
  account's Drive usage is not refreshed by a chat upload.
- **No alt text.** The `altText` field is carried and drawn, but the composer has no field to
  type one.
- **No transfers indicator** that outlives the composer, and no retry button per failed file:
  removing and picking again is the recovery.
- **No upload anywhere but a chat.** Posts, emoji, stickers, server branding and voice messages
  still have no uploader.
- **No device journey.** The contracts and the renders are JVM evidence; no run has picked a
  real photo on a device and read it back from storage.

## Where it lives

- `data/repo/MessageMedia.kt` -- the object name, the room, the part.
- `data/repo/MediaMetadata.kt` -- the byte walker.
- `data/repo/MessageMediaRepository.kt` -- refuse, strip, upload, sign, delete.
- `data/api/RestApi.kt` -- upload with progress, batch signing, deletion.
- `data/model/Message.kt` -- `ContentPart`'s serialiser, which is what lost `path` before.
- `ui/screens/chat/ComposerAttachments.kt` -- the strip and its words.
- `ui/screens/chat/PickedFile.kt` -- reading a picked URI once.
- `ui/screens/chat/RoomScreen.kt` -- the composer, the upload jobs, the viewer.
- `ui/components/MessageContent.kt` -- drawing a part from its signed path.
- `MediaMetadataTest`, `MessageMediaContractTest`, `AttachmentRenderTest` -- the regressions.
