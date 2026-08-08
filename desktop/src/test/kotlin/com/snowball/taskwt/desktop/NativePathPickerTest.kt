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

        val selected = PathSelectionCoordinator(FakeNativePathPicker("D:/tasks", "D:/tools/idea.exe"))
        assertEquals("D:/tasks", selected.directory("C:/tasks"))
        assertEquals("D:/tools/idea.exe", selected.file("C:/idea.exe"))
    }
}

private class FakeNativePathPicker(
    private val directory: String? = null,
    private val file: String? = null,
) : NativePathPicker {
    override suspend fun pickDirectory(initialPath: String?): String? = directory
    override suspend fun pickFile(initialPath: String?, extensions: List<String>): String? = file
}
