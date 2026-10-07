package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("RemSubs Playground", appName)
  }

  @Test
  fun `test openEncodeDialog`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val viewModel = com.example.ui.AxiSubViewModel(context)
    viewModel.openEncodeDialog()
    assertEquals(true, viewModel.uiState.value.showEncodeDialog)
  }

  @Test
  fun `test undo redo stack and cue manipulation`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val viewModel = com.example.ui.AxiSubViewModel(context)
    val initialCount = viewModel.uiState.value.subtitles.size

    // Add cue
    viewModel.addNewCueAtCurrentPosition()
    assertEquals(initialCount + 1, viewModel.uiState.value.subtitles.size)
    assertEquals(true, viewModel.uiState.value.canUndo)

    // Undo addition
    viewModel.undo()
    assertEquals(initialCount, viewModel.uiState.value.subtitles.size)
    assertEquals(true, viewModel.uiState.value.canRedo)

    // Redo addition
    viewModel.redo()
    assertEquals(initialCount + 1, viewModel.uiState.value.subtitles.size)

    // Test duplicate
    val lastCue = viewModel.uiState.value.subtitles.last()
    viewModel.duplicateCue(lastCue)
    assertEquals(initialCount + 2, viewModel.uiState.value.subtitles.size)
  }

  @Test
  fun `test manifest intent filters resolve for ass, srt and mp4`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val pm = context.packageManager

    // 1. ASS with MIME text/x-ssa
    val assIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
      setDataAndType(android.net.Uri.parse("content://media/external/files/123"), "text/x-ssa")
      addCategory(android.content.Intent.CATEGORY_DEFAULT)
    }
    val assResolvers = pm.queryIntentActivities(assIntent, 0)
    org.junit.Assert.assertTrue("Manifest should match .ass with text/x-ssa", assResolvers.any { it.activityInfo.name == MainActivity::class.java.name })

    // 2. SRT with MIME application/x-subrip
    val srtIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
      setDataAndType(android.net.Uri.parse("content://media/external/files/456"), "application/x-subrip")
      addCategory(android.content.Intent.CATEGORY_DEFAULT)
    }
    val srtResolvers = pm.queryIntentActivities(srtIntent, 0)
    org.junit.Assert.assertTrue("Manifest should match .srt with application/x-subrip", srtResolvers.any { it.activityInfo.name == MainActivity::class.java.name })

    // 3. MP4 with MIME video/mp4
    val mp4Intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
      setDataAndType(android.net.Uri.parse("content://media/external/video/media/789"), "video/mp4")
      addCategory(android.content.Intent.CATEGORY_DEFAULT)
    }
    val mp4Resolvers = pm.queryIntentActivities(mp4Intent, 0)
    org.junit.Assert.assertTrue("Manifest should match .mp4 with video/mp4", mp4Resolvers.any { it.activityInfo.name == MainActivity::class.java.name })
  }

  @Test
  fun `test detectFileType detects ass, srt, mp4 and content sniffing`() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    // Extension matching
    val assUri = android.net.Uri.parse("content://com.android.providers.downloads.documents/document/test_sub.ass")
    assertEquals(ExternalFileType.ASS, detectFileType(context, assUri, null))

    val srtUri = android.net.Uri.parse("file:///storage/emulated/0/Download/movie.srt")
    assertEquals(ExternalFileType.SRT, detectFileType(context, srtUri, null))

    val mp4Uri = android.net.Uri.parse("content://media/external/video/media/clip.mp4")
    assertEquals(ExternalFileType.MP4, detectFileType(context, mp4Uri, null))

    // MIME matching
    val genericUri = android.net.Uri.parse("content://com.provider/doc1")
    assertEquals(ExternalFileType.ASS, detectFileType(context, genericUri, "text/x-ssa"))
    assertEquals(ExternalFileType.SRT, detectFileType(context, genericUri, "application/x-subrip"))
    assertEquals(ExternalFileType.MP4, detectFileType(context, genericUri, "video/mp4"))
  }

  @Test
  fun `test MainActivity opens ASS and navigates to editor`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tempAss = java.io.File(context.cacheDir, "sample_test.ass").apply {
      writeText(
        """
        [Script Info]
        Title: Test Script
        PlayResX: 1920
        PlayResY: 1080
        
        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,2,2,10,10,10,1
        
        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:05.00,Default,,0,0,0,,Merhaba ASS Dunyasi!
        """.trimIndent(),
        Charsets.UTF_8
      )
    }

    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
      setDataAndType(android.net.Uri.fromFile(tempAss), "text/x-ssa")
    }

    val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java, intent).setup()
    val activity = controller.get()
    val vm = androidx.lifecycle.ViewModelProvider(activity)[com.example.ui.AxiSubViewModel::class.java]

    assertEquals(com.example.ui.AppScreen.EDITOR, vm.uiState.value.currentScreen)
  }

  @Test
  fun `test MainActivity onNewIntent updates editor when already running`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tempVideo = java.io.File(context.cacheDir, "sample_video.mp4").apply {
      writeBytes(ByteArray(100))
    }

    // Launch activity initially with launcher
    val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
    val activity = controller.get()
    val vm = androidx.lifecycle.ViewModelProvider(activity)[com.example.ui.AxiSubViewModel::class.java]

    // Initially in MAIN_MENU
    assertEquals(com.example.ui.AppScreen.MAIN_MENU, vm.uiState.value.currentScreen)

    // Send new intent with MP4 video
    val newIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
      setDataAndType(android.net.Uri.fromFile(tempVideo), "video/mp4")
    }
    controller.newIntent(newIntent)

    // Now switched to EDITOR and video loaded
    assertEquals(com.example.ui.AppScreen.EDITOR, vm.uiState.value.currentScreen)
    assertEquals(android.net.Uri.fromFile(tempVideo), vm.uiState.value.videoUri)
  }
}

