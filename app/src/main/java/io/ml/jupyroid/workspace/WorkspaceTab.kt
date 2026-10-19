package io.ml.jupyroid.workspace

/** Anything that can sit in a tab: a notebook, a text file, a terminal. */
interface WorkspaceTab {
    val id: String
    val title: String
    /** Server path to reopen the tab after a restart; null for tabs that can't be restored. */
    val persistPath: String?
    val dirty: Boolean
    var message: String?

    fun save(quiet: Boolean)
    suspend fun saveNow(quiet: Boolean)
    fun dispose()
}
