package lol.mony.harmony.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import lol.mony.harmony.data.repo.MediaMetadata
import lol.mony.harmony.data.repo.MessageMedia
import lol.mony.harmony.data.repo.MessageMediaRepository
import lol.mony.harmony.ui.components.humanSize

/** Where one attachment is in its life, as the composer draws it. */
sealed interface AttachmentState {
    /** The bytes are being sent. */
    data object Uploading : AttachmentState

    /** The object is stored and a message may name it. */
    data object Ready : AttachmentState

    /** The bytes never arrived, or the instance refused them. */
    data class Failed(val reason: String) : AttachmentState

    /** The person removed it; the upload is cancelled and the object, if any, deleted. */
    data object Cancelled : AttachmentState
}

/**
 * One file in the composer, before and while it is sent.
 *
 * The bytes are held here rather than re-read at send time: what was uploaded, what the preview
 * drew and what the metadata report describes have to be the same file. [details] is what the
 * stripper found, so the composer can say "location removed" truthfully instead of claiming it
 * for a file it could not read.
 */
data class Attachment(
    val id: String,
    /** The bytes exactly as they were picked, so the details switch can be honoured either way. */
    val original: MessageMediaRepository.Picked,
    /** The bytes that are sent: [original], with its metadata removed unless it was kept. */
    val picked: MessageMediaRepository.Picked,
    val state: AttachmentState = AttachmentState.Uploading,
    val progress: Float = 0f,
    val details: MediaMetadata.Details? = null,
    val stripped: Boolean = false,
    /** What the account chose for this file: keep its photo details rather than remove them. */
    val keepDetails: Boolean = false,
    /** The stored object, once [state] is [AttachmentState.Ready]. */
    val uploaded: MessageMediaRepository.Uploaded? = null,
) {
    val name: String get() = picked.name
    val isImage: Boolean get() = MessageMedia.fileTypeOf(picked.mimeType) == "image"
    val isReady: Boolean get() = state is AttachmentState.Ready && uploaded != null
    val isFailed: Boolean get() = state is AttachmentState.Failed
    val isBusy: Boolean get() = state is AttachmentState.Uploading

    /**
     * The line under the file's name: what is happening to it, or what was done to it.
     *
     * A photo whose details were removed says so; one whose container this build cannot rewrite
     * says the details were **not checked**, which is the truth and what the reference client
     * tells a person too.
     */
    val note: String
        get() {
            // The state wins over the file's own description: a refusal is the reason it was
            // refused, and a file still going up is the percentage, whatever the stripper found.
            when (val current = state) {
                AttachmentState.Uploading -> return progressLabel()
                is AttachmentState.Failed -> return current.reason
                AttachmentState.Cancelled -> return "Removed"
                AttachmentState.Ready -> Unit
            }
            val size = humanSize(picked.size)
            val found = details ?: return size
            val suffix = when {
                found.unknown && !keepDetails -> " · details not checked"
                keepDetails && found.location -> " · location kept"
                keepDetails && found.details -> " · photo details kept"
                stripped && found.location -> " · location removed"
                stripped && found.details -> " · photo details removed"
                found.location -> " · contains a location"
                found.details -> " · contains photo details"
                else -> ""
            }
            return size + suffix
        }

    private fun progressLabel(): String = "Uploading… " + (progress * 100).toInt() + "%"

    /**
     * True when the person should be warned before sending: the bytes that will go up still say
     * where the picture was taken.
     *
     * That is the file they chose to keep the details of, or one this build could not rewrite --
     * *not* a file whose location was just removed. Warning about a position that has already
     * been taken out, next to a line saying it was taken out, is the kind of contradiction that
     * teaches a person to ignore the warning.
     */
    val hasLocation: Boolean get() = details?.location == true && (keepDetails || !stripped)
}

/**
 * The files waiting to be sent, above the message field.
 *
 * A picked file is shown with its own picture when it is one, so a person sees what they are
 * about to send before they send it -- and, when the stripper found something, a line that says
 * what was done with it. Each row carries its own remove, which cancels a running upload and
 * deletes an object that already landed, so removing an attachment never leaves a file behind
 * that no message names.
 */
@Composable
fun AttachmentStrip(
    attachments: List<Attachment>,
    onRemove: (Attachment) -> Unit,
    onKeepDetails: (Attachment, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Column(modifier.fillMaxWidth().testTag("attachment-strip")) {
        for (attachment in attachments) {
            AttachmentRow(attachment, onRemove, onKeepDetails)
        }
        val warning = attachments.firstOrNull { it.hasLocation }
        if (warning != null) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Text(
                    "This file still has the place it was taken. Send it anyway?",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("attachment-location-warning"),
                )
            }
        }
        // Send waits for every file, so a file that failed would hold it back without saying
        // why. The row above already carries the reason; this says what to do about it.
        if (attachments.any { it.isFailed }) {
            Text(
                "Remove the file that could not be sent, or pick it again.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp).testTag("attachment-failed-hint"),
            )
        }
    }
}

@Composable
private fun AttachmentRow(
    attachment: Attachment,
    onRemove: (Attachment) -> Unit,
    onKeepDetails: (Attachment, Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag("attachment-" + attachment.id),
    ) {
        Column(Modifier.padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AttachmentThumb(attachment)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        attachment.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("attachment-name-" + attachment.id),
                    )
                    Text(
                        attachment.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (attachment.isFailed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("attachment-note-" + attachment.id),
                    )
                }
                IconButton(onClick = { onRemove(attachment) }, modifier = Modifier.testTag("attachment-remove-" + attachment.id)) {
                    Icon(Icons.Default.Close, "Remove " + attachment.name, Modifier.size(18.dp))
                }
            }
            if (attachment.isBusy) {
                LinearProgressIndicator(
                    progress = { attachment.progress },
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp).height(3.dp)
                        .testTag("attachment-progress-" + attachment.id),
                )
            }
            // Only offered when the file actually carries something: a switch for a file with no
            // details in it would be a control that does nothing.
            val details = attachment.details
            if (details != null && details.details && !attachment.isFailed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (attachment.keepDetails) "Keeping this file's photo details" else "Removing this file's photo details",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButtonSmall(
                        label = if (attachment.keepDetails) "Remove instead" else "Keep them",
                        onClick = { onKeepDetails(attachment, !attachment.keepDetails) },
                        tag = "attachment-details-" + attachment.id,
                    )
                }
            }
        }
    }
}

/** A picture when the file is one, otherwise the kind's own glyph. */
@Composable
private fun AttachmentThumb(attachment: Attachment) {
    val size = 40.dp
    val box = Modifier.size(size).clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    when {
        attachment.isImage -> AsyncImage(
            // The picked bytes are drawn from memory: the grant on the URI may not outlive this
            // screen, and a preview that needs the network is not a preview.
            model = attachment.picked.bytes,
            contentDescription = attachment.name,
            contentScale = ContentScale.Crop,
            modifier = box.testTag("attachment-preview-" + attachment.id),
        )
        else -> Box(box, contentAlignment = Alignment.Center) {
            Icon(
                when (MessageMedia.fileTypeOf(attachment.picked.mimeType)) {
                    "video" -> Icons.Default.Movie
                    "audio" -> Icons.Default.MusicNote
                    else -> Icons.Default.Description
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** A small text action, sized to sit inside an attachment row rather than beside the composer. */
@Composable
private fun TextButtonSmall(label: String, onClick: () -> Unit, tag: String) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(vertical = 2.dp).widthIn(min = 0.dp).testTag(tag),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** The sheet a long press on the attach button opens: a photo, a video, or any file. */
enum class PickKind { Image, Video, Any }

/** The attach button's own menu, as a list of the three kinds of file a phone can offer. */
@Composable
fun AttachMenu(
    onPick: (PickKind) -> Unit,
    onDismiss: () -> Unit,
    onCamera: (() -> Unit)? = null,
) {
    androidx.compose.material3.DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Photo") },
            leadingIcon = { Icon(Icons.Default.Photo, null) },
            onClick = { onPick(PickKind.Image) },
            modifier = Modifier.testTag("attach-photo"),
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Video") },
            leadingIcon = { Icon(Icons.Default.Movie, null) },
            onClick = { onPick(PickKind.Video) },
            modifier = Modifier.testTag("attach-video"),
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("File") },
            leadingIcon = { Icon(Icons.Default.Description, null) },
            onClick = { onPick(PickKind.Any) },
            modifier = Modifier.testTag("attach-file"),
        )
        if (onCamera != null) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Take a photo") },
                leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
                onClick = onCamera,
                modifier = Modifier.testTag("attach-camera"),
            )
        }
    }
}
