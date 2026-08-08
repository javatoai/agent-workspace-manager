package com.snowball.taskwt.desktop

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class NativePathPickerTest {
    @Test
    fun `cancel retains current values and selections replace them`() = runBlocking {
        val cancelled = PathSelectionCoordinator(FakeNativePathPicker())
        assertEquals("C:/tasks", cancelled.directory("C:/tasks"))
        assertEquals("C:/idea.exe", cancelled.file("C:/idea.exe"))
        assertEquals(emptyList(), cancelled.directories("C:/tasks"))

        val selected = PathSelectionCoordinator(
            FakeNativePathPicker("D:/tasks", "D:/tools/idea.exe", listOf("D:/repo-a", "D:/repo-b")),
        )
        assertEquals("D:/tasks", selected.directory("C:/tasks"))
        assertEquals("D:/tools/idea.exe", selected.file("C:/idea.exe"))
        assertEquals(listOf("D:/repo-a", "D:/repo-b"), selected.directories("C:/tasks"))
    }
}

private class FakeNativePathPicker(
    private val directory: String? = null,
    private val file: String? = null,
    private val directories: List<String>? = null,
) : NativePathPicker {
    override suspend fun pickDirectory(initialPath: String?): String? = directory
    override suspend fun pickDirectories(initialPath: String?): List<String>? = directories
    override suspend fun pickFile(initialPath: String?, extensions: List<String>): String? = file
}
