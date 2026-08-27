package dev.opencode.mobile.core.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Feature 9 — file tabs with unsaved state that survives screen navigation.
 *
 * The editor keeps each open buffer's text, saved text and caret positions in
 * this app-lifetime store, so switching tabs (or leaving and returning to the
 * editor) never loses in-progress typing, while a dirty buffer stays visibly
 * marked "unsaved" until it is written to disk. Deliberately not persisted:
 * unsaved scratch state dies with the process on purpose — checkpoints cover
 * anything the agent changed.
 */
class EditorTabsStore {

    data class Buffer(
        val path: String,
        val text: String,
        val savedText: String,
        val selectionStart: Int,
        val selectionEnd: Int,
    ) {
        val dirty: Boolean get() = text != savedText
    }

    private val _tabs = MutableStateFlow<List<String>>(emptyList())
    val tabs: StateFlow<List<String>> = _tabs.asStateFlow()

    private val _activePath = MutableStateFlow<String?>(null)
    val activePath: StateFlow<String?> = _activePath.asStateFlow()

    private val buffers = LinkedHashMap<String, Buffer>()

    /** Opens [path] as a tab (no-op if already open) and focuses it. */
    fun open(path: String) {
        if (path.isBlank()) return
        if (path !in buffers) buffers[path] = Buffer(path, "", "", 0, 0)
        if (_tabs.value.none { it == path }) {
            _tabs.value = _tabs.value + path
        }
        _activePath.value = path
    }

    fun buffer(path: String): Buffer? = buffers[path]

    fun activeBuffer(): Buffer? = _activePath.value?.let { buffers[it] }

    /** Saves in-progress editor state back into the store. */
    fun snapshot(path: String, text: String, savedText: String, selStart: Int, selEnd: Int) {
        if (path.isBlank()) return
        buffers[path] = Buffer(
            path = path,
            text = text,
            savedText = savedText,
            selectionStart = selStart.coerceIn(0, text.length),
            selectionEnd = selEnd.coerceIn(0, text.length),
        )
    }

    /** Marks the buffer clean after a successful disk write. */
    fun markSaved(path: String, newSavedText: String) {
        val current = buffers[path] ?: return
        buffers[path] = current.copy(savedText = newSavedText, text = newSavedText)
    }

    /** Discards the cached buffer and closes its tab. Returns true when dirty. */
    fun close(path: String, discardBuffer: Boolean): Boolean {
        val wasDirty = buffers[path]?.dirty == true
        if (discardBuffer || !wasDirty) buffers.remove(path)
        _tabs.value = _tabs.value.filterNot { it == path }
        if (_activePath.value == path) {
            _activePath.value = _tabs.value.lastOrNull()
        }
        return wasDirty && !discardBuffer
    }

    fun focus(path: String) {
        if (path in buffers) _activePath.value = path
    }

    fun dirtyCount(): Int = buffers.values.count { it.dirty }
}
