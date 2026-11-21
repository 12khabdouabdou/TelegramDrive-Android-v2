package com.tgdrive.feature.browser

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.index.ZipIndexService
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.zip.ZipEntryInfo
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZipBrowserViewModelTest {
    private val telegramClient: TelegramClient = mockk(relaxed = true)
    private val downloadManager: DownloadManager = mockk(relaxed = true)
    private val fileCacheDao: FileCacheDao = mockk(relaxed = true)
    private val appContext: Context = mockk(relaxed = true)
    private val zipUploader: TestZipUploader = TestZipUploader()
    private val zipIndexService: ZipIndexService = mockk(relaxed = true)
    private val cacheDir: File = createTempDir(prefix = "ziptest")

    private lateinit var dispatchers: DispatchersProvider

    @BeforeEach
    fun setUp() {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        dispatchers =
            object : DispatchersProvider {
                override val io: CoroutineDispatcher = testDispatcher
                override val default: CoroutineDispatcher = testDispatcher
                override val main: CoroutineDispatcher = testDispatcher
                override val unconfined: CoroutineDispatcher = testDispatcher
            }
        // Reset shared test double state between tests (PER_CLASS instance
        // would otherwise leak setEntries / setExtractFailure across tests).
        zipUploader.reset()
        io.mockk.every { appContext.cacheDir } returns cacheDir
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        cacheDir.deleteRecursively()
    }

    @Test
    fun `initial state is idle with no entries and no error`() =
        runTest {
            val vm = newViewModel()
            val s = vm.state.value
            assertThat(s.entries).isEmpty()
            assertThat(s.error).isNull()
            assertThat(s.loading).isFalse()
            assertThat(s.zipReady).isFalse()
            assertThat(s.passwordRequired).isFalse()
        }

    @Test
    fun `load populates entries from zip lister`() =
        runTest {
            zipUploader.setEntries(
                listOf(
                    ZipEntryInfo(
                        "photos/2024.jpg",
                        isDirectory = false,
                        compressedSize = 100,
                        uncompressedSize = 200,
                        isEncrypted = false,
                    ),
                    ZipEntryInfo(
                        "photos/",
                        isDirectory = true,
                        compressedSize = 0,
                        uncompressedSize = 0,
                        isEncrypted = false,
                    ),
                    ZipEntryInfo(
                        "readme.txt",
                        isDirectory = false,
                        compressedSize = 50,
                        uncompressedSize = 80,
                        isEncrypted = false,
                    ),
                ),
            )
            val vm = newViewModel()
            vm.load("/tmp/fake.zip")
            advanceUntilIdle()

            val s = vm.state.value
            assertThat(s.entries).hasSize(3)
            assertThat(s.entries.map { it.name }).containsExactly("photos/2024.jpg", "photos/", "readme.txt")
            assertThat(s.zipReady).isTrue()
            assertThat(s.loading).isFalse()
            assertThat(s.error).isNull()
            assertThat(s.passwordRequired).isFalse()
        }

    @Test
    fun `load sets passwordRequired when any entry is encrypted`() =
        runTest {
            zipUploader.setEntries(
                listOf(
                    ZipEntryInfo(
                        "secret.txt",
                        isDirectory = false,
                        compressedSize = 10,
                        uncompressedSize = 20,
                        isEncrypted = true,
                    ),
                    ZipEntryInfo(
                        "public.txt",
                        isDirectory = false,
                        compressedSize = 5,
                        uncompressedSize = 10,
                        isEncrypted = false,
                    ),
                ),
            )
            val vm = newViewModel()
            vm.load("/tmp/encrypted.zip")
            advanceUntilIdle()

            val s = vm.state.value
            assertThat(s.passwordRequired).isTrue()
            assertThat(s.entries).hasSize(2)
            assertThat(s.entries.first { it.name == "secret.txt" }.isEncrypted).isTrue()
            assertThat(s.entries.first { it.name == "public.txt" }.isEncrypted).isFalse()
        }

    @Test
    fun `load sets error when lister throws`() =
        runTest {
            zipUploader.setEntries(emptyList(), throwOnList = java.io.IOException("corrupt zip"))
            val vm = newViewModel()
            vm.load("/tmp/bad.zip")
            advanceUntilIdle()

            val s = vm.state.value
            assertThat(s.error).contains("corrupt zip")
            assertThat(s.loading).isFalse()
            assertThat(s.entries).isEmpty()
        }

    @Test
    fun `extractEntry sets extractedPath on success`() =
        runTest {
            zipUploader.setEntries(
                listOf(
                    ZipEntryInfo(
                        "readme.txt",
                        isDirectory = false,
                        compressedSize = 5,
                        uncompressedSize = 10,
                        isEncrypted = false,
                    ),
                ),
            )
            val vm = newViewModel()
            vm.load("/tmp/fake.zip")
            advanceUntilIdle()
            vm.extractEntry("readme.txt")
            advanceUntilIdle()

            val s = vm.state.value
            assertThat(s.extractedPath).isNotNull()
            assertThat(s.extractingEntry).isNull()
            assertThat(s.error).isNull()
        }

    @Test
    fun `extractEntry sets error when extractor returns null`() =
        runTest {
            zipUploader.setEntries(
                listOf(
                    ZipEntryInfo(
                        "readme.txt",
                        isDirectory = false,
                        compressedSize = 5,
                        uncompressedSize = 10,
                        isEncrypted = false,
                    ),
                ),
            )
            zipUploader.setExtractFailure(true)
            val vm = newViewModel()
            vm.load("/tmp/fake.zip")
            advanceUntilIdle()
            vm.extractEntry("readme.txt")
            advanceUntilIdle()

            val s = vm.state.value
            assertThat(s.error).isNotNull()
            assertThat(s.extractedPath).isNull()
            assertThat(s.extractingEntry).isNull()
        }

    @Test
    fun `reset clears state back to idle`() =
        runTest {
            zipUploader.setEntries(
                listOf(
                    ZipEntryInfo(
                        "a.txt",
                        isDirectory = false,
                        compressedSize = 1,
                        uncompressedSize = 1,
                        isEncrypted = false,
                    ),
                ),
            )
            val vm = newViewModel()
            vm.load("/tmp/fake.zip")
            advanceUntilIdle()
            vm.reset()

            val s = vm.state.value
            assertThat(s.entries).isEmpty()
            assertThat(s.zipReady).isFalse()
            assertThat(s.loading).isFalse()
        }

    private fun newViewModel(): ZipBrowserViewModel =
        ZipBrowserViewModel(
            telegramClient = telegramClient,
            downloadManager = downloadManager,
            fileCacheDao = fileCacheDao,
            zipIndexService = zipIndexService,
            dispatchers = dispatchers,
            appContext = appContext,
            zipLister = zipUploader,
            zipExtractor = zipUploader,
        )
}
