package app.svan.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.svan.svaramanas.Svaramanas
import app.svan.svaramanas.TuningSignature
import app.svan.svaramanas.TuningSignatures

/**
 * Nine saved "tuning signatures": each one keeps what "Learn this sound" measured (bass depth, brightness, width,
 * dynamics; never audio) so the listener can switch between learned sounds. Tapping a slot only selects it; using,
 * replacing and deleting are explicit actions, and deleting asks for a second tap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TuningSignaturesSection(onMessage: (String) -> Unit) {
    val slots by Svaramanas.signatures.collectAsStateWithLifecycle()
    val taste by Svaramanas.taste.collectAsStateWithLifecycle()
    val hasTaste = TuningSignatures.isValid(taste)
    val inUse = if (hasTaste) TuningSignatures.indexMatching(slots, taste) else null
    var selected by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val free = TuningSignatures.firstFree(slots)
    val unsaved = hasTaste && inUse == null

    SectionLabel("Saved signatures · ${TuningSignatures.count(slots)} of ${TuningSignatures.SLOTS}")
    Text(
        "Keep up to nine learned sounds and switch between them. A signature stores only the measured balance, never audio.",
        style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted,
    )
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 3) {
                    val slot = row * 3 + col
                    SignatureTile(
                        slot = slot, signature = slots.getOrNull(slot), selected = selected == slot, inUse = inUse == slot,
                        modifier = Modifier.weight(1f),
                    ) { selected = slot; confirmDelete = false }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))

    val slot = selected
    val chosen = slot?.let { slots.getOrNull(it) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (slot == null) {
            if (unsaved && free != null) Pill("Save current sound to slot ${free + 1}", false,
                { selected = free; onMessage(Svaramanas.saveSignature(free)) }, accent = Svan.Tulsi)
        } else if (chosen == null) {
            Pill("Save current sound here", false, { onMessage(Svaramanas.saveSignature(slot)) }, accent = Svan.Tulsi, enabled = hasTaste)
        } else {
            Pill("Use", inUse == slot, { onMessage(Svaramanas.useSignature(slot)) }, accent = Svan.Tulsi, enabled = inUse != slot)
            Pill("Rename", false, { renaming = true })
            Pill("Replace with current sound", false, { onMessage(Svaramanas.saveSignature(slot)) }, enabled = hasTaste && inUse != slot)
            Pill(if (confirmDelete) "Tap again to delete" else "Delete", confirmDelete,
                { if (confirmDelete) { onMessage(Svaramanas.deleteSignature(slot)); confirmDelete = false } else confirmDelete = true },
                accent = Svan.Ember)
        }
    }
    val hint = when {
        slot == null && !hasTaste -> "Learn a sound above, then save it into a slot."
        slot == null && unsaved && free == null -> "All nine slots are full. Select one to replace it with your current sound."
        slot == null -> "Select a slot to use, rename, replace or delete it."
        chosen == null && !hasTaste -> "Learn a sound first, then save it here."
        chosen != null && unsaved && inUse != slot -> "Your current learned sound is not saved. Using this signature replaces it."
        else -> null
    }
    if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint, modifier = Modifier.padding(top = 6.dp))

    if (renaming && slot != null && chosen != null) RenameDialog(chosen.name,
        onDismiss = { renaming = false }, onConfirm = { onMessage(Svaramanas.renameSignature(slot, it)); renaming = false })
}

@Composable
private fun SignatureTile(
    slot: Int,
    signature: TuningSignature?,
    selected: Boolean,
    inUse: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val border = when { inUse -> Svan.Tulsi; selected -> Svan.Gold; else -> Svan.Outline }
    val fill = if (inUse) Svan.Tulsi.copy(alpha = 0.12f) else if (selected) Svan.Gold.copy(alpha = 0.10f) else Svan.SurfaceHigh
    val description = if (signature == null) "Signature slot ${slot + 1}, empty"
    else "Signature slot ${slot + 1}, ${signature.name}, learned from ${signature.tracks} reference tracks" + if (inUse) ", in use" else ""
    Box(
        modifier
            .heightIn(min = 68.dp)
            .clip(shape)
            .background(fill)
            .border(if (selected || inUse) 1.5.dp else 1.dp, border, shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (signature == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${slot + 1}", style = MaterialTheme.typography.labelLarge, color = Svan.TextFaint)
                Text("Empty", style = MaterialTheme.typography.labelSmall, color = Svan.TextFaint)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(signature.name, style = MaterialTheme.typography.labelLarge, color = if (inUse) Svan.Tulsi else Svan.Text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (inUse) "In use" else "${signature.tracks} track${if (signature.tracks == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall, color = if (inUse) Svan.Tulsi else Svan.TextMuted)
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Svan.SurfaceHigh,
        title = { Text("Name this signature") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(TuningSignatures.MAX_NAME) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
