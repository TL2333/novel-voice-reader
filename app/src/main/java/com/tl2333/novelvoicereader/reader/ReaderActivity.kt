package com.tl2333.novelvoicereader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tl2333.novelvoicereader.data.preferences.ReaderNavigationMode
import com.tl2333.novelvoicereader.data.preferences.ReaderTextAlignment
import com.tl2333.novelvoicereader.data.preferences.ReaderTheme
import com.tl2333.novelvoicereader.tts.kokoro.KokoroVoiceCatalog
import com.tl2333.novelvoicereader.tts.tokenizer.NarrationStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Full-screen reader shell around Readium's EPUB navigator and the custom narration controls. */
@SuppressLint("SetTextI18n")
class ReaderActivity : AppCompatActivity() {

    internal lateinit var readerViewModel: ReaderViewModel
        private set

    private lateinit var titleView: TextView
    private lateinit var readerContainer: FragmentContainerView
    private lateinit var statusView: TextView
    private lateinit var narrationStatusView: TextView
    private lateinit var previousChapterButton: Button
    private lateinit var nextChapterButton: Button
    private lateinit var themeButton: Button
    private lateinit var fontSizeButton: Button
    private lateinit var navigationModeButton: Button
    private lateinit var playPauseButton: Button
    private lateinit var previousUtteranceButton: Button
    private lateinit var nextUtteranceButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        /*
         * The EPUB itself is restored from its durable Readium Locator. Discarding the Activity's
         * fragment snapshot prevents Android from trying to instantiate EpubNavigatorFragment
         * before the asynchronously opened Publication is available to its FragmentFactory.
         */
        super.onCreate(null)

        val dependencies = (application as? ReaderDependenciesOwner)?.readerDependencies
            ?: ReaderDependencies.fallback(application)
        readerViewModel = ViewModelProvider(
            this,
            ReaderViewModel.Factory(application, dependencies),
        )[ReaderViewModel::class.java]

        buildContentView()
        observeReader()

        openFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)
    }

    private fun openFromIntent(intent: Intent) {
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)?.takeIf(String::isNotBlank)
        if (bookId == null) {
            Toast.makeText(this, "缺少书籍标识。", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        readerViewModel.open(
            ReaderRequest(
                bookId = bookId,
                privateEpubPath = intent.getStringExtra(EXTRA_PRIVATE_EPUB_PATH),
                initialLocatorJson = intent.getStringExtra(EXTRA_INITIAL_LOCATOR_JSON),
            ),
        )
    }

    private fun buildContentView() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.rgb(250, 250, 250))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        toolbar.addView(button("返回", "关闭阅读器") { finish() })
        previousChapterButton = button("上一章", "跳转到上一章") {
            readerViewModel.goToPreviousChapter()
        }
        toolbar.addView(previousChapterButton)
        toolbar.addView(button("目录", "打开章节目录") { showChapterDirectory() })
        titleView = TextView(this).apply {
            text = "正在打开…"
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        toolbar.addView(
            titleView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        nextChapterButton = button("下一章", "跳转到下一章") {
            readerViewModel.goToNextChapter()
        }
        toolbar.addView(nextChapterButton)
        toolbar.addView(button("书签", "收藏当前阅读位置") { readerViewModel.addBookmark() })
        root.addView(toolbar, matchWrap())

        readerContainer = FragmentContainerView(this).apply {
            id = READER_CONTAINER_ID
        }
        root.addView(
            readerContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        statusView = TextView(this).apply {
            text = "正在加载 EPUB…"
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        root.addView(statusView, matchWrap())

        val readingControls = horizontalControls().apply {
            val row = linearRow()
            themeButton = button("主题", "切换阅读主题") { readerViewModel.cycleTheme() }
            row.addView(themeButton)
            row.addView(button("A−", "减小字号") { readerViewModel.decreaseFontSize() })
            fontSizeButton = button("18sp", "当前字号") {}
            fontSizeButton.isEnabled = false
            row.addView(fontSizeButton)
            row.addView(button("A+", "增大字号") { readerViewModel.increaseFontSize() })
            navigationModeButton = button("滚动", "切换滚动或翻页模式") {
                readerViewModel.toggleNavigationMode()
            }
            row.addView(navigationModeButton)
            row.addView(button("阅读设置", "调整行距、边距、对齐和屏幕设置") {
                showReaderSettings()
            })
            addView(row)
        }
        root.addView(readingControls, matchWrap())

        val narrationControls = horizontalControls().apply {
            val row = linearRow()
            previousUtteranceButton = button("上一句", "朗读上一句") {
                readerViewModel.previousUtterance()
            }
            row.addView(previousUtteranceButton)
            playPauseButton = button("朗读", "播放或暂停朗读") {
                if (readerViewModel.narration.value.playing) {
                    readerViewModel.pauseNarration()
                } else {
                    readerViewModel.playNarration()
                }
            }
            row.addView(playPauseButton)
            nextUtteranceButton = button("下一句", "朗读下一句") {
                readerViewModel.nextUtterance()
            }
            row.addView(nextUtteranceButton)
            stopButton = button("停止", "停止朗读") { readerViewModel.stopNarration() }
            row.addView(stopButton)
            row.addView(button("朗读设置", "调整声音、语速、风格和跟随设置") {
                showNarrationSettings()
            })
            addView(row)
        }
        root.addView(narrationControls, matchWrap())

        narrationStatusView = TextView(this).apply {
            textSize = 13f
            maxLines = 2
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(6))
        }
        root.addView(narrationStatusView, matchWrap())

        setContentView(root)
    }

    private fun observeReader() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    readerViewModel.loadState.collect(::renderLoadState)
                }
                launch {
                    readerViewModel.chapter.collect { chapter ->
                        if (chapter.title.isNotBlank()) titleView.text = chapter.title
                        previousChapterButton.isEnabled = chapter.canGoPrevious
                        nextChapterButton.isEnabled = chapter.canGoNext
                    }
                }
                launch {
                    readerViewModel.preferences.collect { preferences ->
                        applyWindowPreferences(preferences)
                        themeButton.text = when (preferences.readerTheme) {
                            ReaderTheme.DAY -> "日间"
                            ReaderTheme.NIGHT -> "夜间"
                            ReaderTheme.EYE_CARE -> "护眼"
                        }
                        fontSizeButton.text = "${preferences.fontSizeSp.toInt()}sp"
                        navigationModeButton.text = when (preferences.navigationMode) {
                            ReaderNavigationMode.SCROLL -> "滚动"
                            ReaderNavigationMode.PAGINATED -> "翻页"
                        }
                    }
                }
                launch {
                    readerViewModel.narration.collect(::renderNarration)
                }
                launch {
                    readerViewModel.currentLocator.collect { locator ->
                        if (readerViewModel.loadState.value is ReaderLoadState.Ready) {
                            statusView.visibility = View.VISIBLE
                            statusView.text = locator?.locations?.totalProgression?.let {
                                "阅读进度 ${(it * 100).coerceIn(0.0, 100.0).toInt()}%"
                            } ?: "阅读位置已保存"
                        }
                    }
                }
                launch {
                    readerViewModel.messages.collect { message ->
                        Toast.makeText(this@ReaderActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun renderLoadState(state: ReaderLoadState) {
        when (state) {
            ReaderLoadState.Loading -> {
                removeReaderFragment()
                statusView.visibility = View.VISIBLE
                statusView.text = "正在加载 EPUB…"
            }

            is ReaderLoadState.Error -> {
                removeReaderFragment()
                statusView.visibility = View.VISIBLE
                statusView.text = state.message
                titleView.text = "无法打开"
            }

            is ReaderLoadState.Ready -> {
                statusView.visibility = View.GONE
                titleView.text = state.session.title
                if (supportFragmentManager.findFragmentByTag(EpubReaderFragment.TAG) == null) {
                    supportFragmentManager.commit {
                        setReorderingAllowed(true)
                        replace(
                            READER_CONTAINER_ID,
                            EpubReaderFragment(),
                            EpubReaderFragment.TAG,
                        )
                    }
                }
            }
        }
    }

    private fun removeReaderFragment() {
        val fragment = supportFragmentManager.findFragmentByTag(EpubReaderFragment.TAG) ?: return
        if (!supportFragmentManager.isStateSaved) {
            supportFragmentManager.commitNow {
                remove(fragment)
            }
        }
    }

    private fun renderNarration(state: TtsVisualSynchronizer.State) {
        val controlsEnabled = state.available && !state.preparing
        playPauseButton.isEnabled = controlsEnabled
        previousUtteranceButton.isEnabled = controlsEnabled
        nextUtteranceButton.isEnabled = controlsEnabled
        stopButton.isEnabled = state.available
        playPauseButton.text = when {
            state.preparing -> "准备中…"
            state.playing -> "暂停"
            else -> "朗读"
        }
        narrationStatusView.text = when {
            !state.error.isNullOrBlank() -> state.error
            !state.utterance.isNullOrBlank() -> state.utterance
            !state.available -> "当前安装未提供离线朗读引擎。"
            else -> ""
        }
    }

    private fun applyWindowPreferences(
        preferences: com.tl2333.novelvoicereader.data.preferences.ReaderTtsPreferences,
    ) {
        val requestedBrightness = if (preferences.screenBrightness < 0f) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            preferences.screenBrightness.coerceIn(0.05f, 1f)
        }
        val attributes = window.attributes
        if (attributes.screenBrightness != requestedBrightness) {
            attributes.screenBrightness = requestedBrightness
            window.attributes = attributes
        }

        if (preferences.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun showReaderSettings() {
        val current = readerViewModel.preferences.value
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        val lineSpacing = sliderControl(
            label = "行距",
            range = 10..30,
            initialValue = (current.lineSpacing * 10f).roundToInt(),
        ) { value -> String.format(Locale.getDefault(), "%.1f 倍", value / 10f) }
        content.addView(lineSpacing.view)

        val paragraphSpacing = sliderControl(
            label = "段落间距",
            range = 0..20,
            initialValue = (current.paragraphSpacing * 10f).roundToInt(),
        ) { value -> String.format(Locale.getDefault(), "%.1f", value / 10f) }
        content.addView(paragraphSpacing.view)

        val pageMargin = sliderControl(
            label = "页面边距",
            range = 0..16,
            initialValue = (current.pageMarginDp / 4f).roundToInt(),
        ) { value -> "${value * 4} dp" }
        content.addView(pageMargin.view)

        content.addView(settingHeading("文本对齐"))
        val alignmentIds = mutableMapOf<Int, ReaderTextAlignment>()
        val alignmentGroup = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        listOf(
            ReaderTextAlignment.PUBLISHER to "跟随出版物",
            ReaderTextAlignment.START to "左对齐",
            ReaderTextAlignment.JUSTIFY to "两端对齐",
        ).forEach { (alignment, label) ->
            val id = View.generateViewId()
            alignmentIds[id] = alignment
            alignmentGroup.addView(RadioButton(this).apply {
                this.id = id
                text = label
                isChecked = alignment == current.textAlignment
            })
        }
        content.addView(alignmentGroup, matchWrap())

        content.addView(settingHeading("屏幕"))
        val systemBrightness = CheckBox(this).apply {
            text = "使用系统亮度"
            isChecked = current.screenBrightness < 0f
        }
        content.addView(systemBrightness, matchWrap())
        val brightness = sliderControl(
            label = "阅读亮度",
            range = 1..20,
            initialValue = if (current.screenBrightness < 0f) {
                10
            } else {
                (current.screenBrightness * 20f).roundToInt()
            },
        ) { value -> "${value * 5}%" }
        fun updateBrightnessEnabled(useSystem: Boolean) {
            brightness.seekBar.isEnabled = !useSystem
            brightness.view.alpha = if (useSystem) 0.5f else 1f
        }
        updateBrightnessEnabled(systemBrightness.isChecked)
        systemBrightness.setOnCheckedChangeListener { _, checked ->
            updateBrightnessEnabled(checked)
        }
        content.addView(brightness.view)

        val keepScreenOn = CheckBox(this).apply {
            text = "阅读时保持屏幕常亮"
            isChecked = current.keepScreenOn
        }
        content.addView(keepScreenOn, matchWrap())

        val scrollView = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle("阅读设置")
            .setView(scrollView)
            .setPositiveButton("应用") { _, _ ->
                readerViewModel.updateAdvancedReader(
                    lineSpacing = lineSpacing.value / 10f,
                    paragraphSpacing = paragraphSpacing.value / 10f,
                    pageMarginDp = pageMargin.value * 4f,
                    textAlignment = alignmentIds[alignmentGroup.checkedRadioButtonId]
                        ?: current.textAlignment,
                    screenBrightness = if (systemBrightness.isChecked) {
                        -1f
                    } else {
                        brightness.value / 20f
                    },
                    keepScreenOn = keepScreenOn.isChecked,
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showNarrationSettings() {
        val current = readerViewModel.preferences.value
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        val voice = sliderControl(
            label = "声音",
            range = 3..102,
            initialValue = current.defaultVoiceSid,
        ) { sid ->
            val name = KokoroVoiceCatalog.bySid(sid)?.displayName ?: "中文声音"
            "$name（SID $sid）"
        }
        content.addView(voice.view)

        val speed = sliderControl(
            label = "语速",
            range = 10..40,
            initialValue = (current.narrationSpeed * 20f).roundToInt(),
        ) { value -> String.format(Locale.getDefault(), "%.2f 倍", value / 20f) }
        content.addView(speed.view)

        content.addView(settingHeading("朗读风格"))
        val styleIds = mutableMapOf<Int, NarrationStyle>()
        val styleGroup = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        listOf(
            NarrationStyle.NEUTRAL to "中性",
            NarrationStyle.HAPPY to "轻快",
            NarrationStyle.SAD to "低沉",
            NarrationStyle.ANGRY to "强烈",
            NarrationStyle.EXCITED to "激动",
        ).forEach { (style, label) ->
            val id = View.generateViewId()
            styleIds[id] = style
            styleGroup.addView(RadioButton(this).apply {
                this.id = id
                text = label
                isChecked = style == current.narrationStyle
            })
        }
        content.addView(styleGroup, matchWrap())

        val automaticStyle = CheckBox(this).apply {
            text = "根据文本自动调整风格"
            isChecked = current.automaticStyle
        }
        content.addView(automaticStyle, matchWrap())
        val automaticFollow = CheckBox(this).apply {
            text = "朗读时自动跟随并高亮当前句"
            isChecked = current.automaticFollow
        }
        content.addView(automaticFollow, matchWrap())

        val scrollView = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle("朗读设置")
            .setView(scrollView)
            .setPositiveButton("应用") { _, _ ->
                readerViewModel.updateNarration(
                    voiceSid = voice.value,
                    speed = speed.value / 20f,
                    style = styleIds[styleGroup.checkedRadioButtonId]
                        ?: current.narrationStyle,
                    automaticStyle = automaticStyle.isChecked,
                    automaticFollow = automaticFollow.isChecked,
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showChapterDirectory() {
        val session = (readerViewModel.loadState.value as? ReaderLoadState.Ready)?.session
            ?: return
        if (session.chapters.isEmpty()) {
            Toast.makeText(this, "这本书没有章节目录。", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = session.chapters.mapIndexed { index, link ->
            link.title?.takeIf(String::isNotBlank) ?: "第 ${index + 1} 章"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("章节目录")
            .setItems(labels) { dialog, index ->
                readerViewModel.goToChapterIndex(index)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroy() {
        if (isFinishing) readerViewModel.closeReader()
        super.onDestroy()
    }

    private fun button(label: String, description: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            contentDescription = description
            isAllCaps = false
            minWidth = dp(56)
            setOnClickListener { action() }
        }

    private fun horizontalControls(): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
    }

    private fun linearRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(6), 0, dp(6), 0)
    }

    private fun settingHeading(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 16f
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun sliderControl(
        label: String,
        range: IntRange,
        initialValue: Int,
        formatter: (Int) -> String,
    ): SliderControl {
        require(!range.isEmpty())
        val valueLabel = TextView(this).apply {
            textSize = 15f
        }
        val seekBar = SeekBar(this).apply {
            max = range.last - range.first
            progress = initialValue.coerceIn(range.first, range.last) - range.first
            contentDescription = label
        }
        fun renderValue() {
            valueLabel.text = "$label：${formatter(range.first + seekBar.progress)}"
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                renderValue()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        renderValue()
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(4))
            addView(valueLabel, matchWrap())
            addView(seekBar, matchWrap())
        }
        return SliderControl(view, seekBar, range.first)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class SliderControl(
        val view: LinearLayout,
        val seekBar: SeekBar,
        private val minimum: Int,
    ) {
        val value: Int
            get() = minimum + seekBar.progress
    }

    companion object {
        private const val EXTRA_BOOK_ID = "reader.book_id"
        private const val EXTRA_PRIVATE_EPUB_PATH = "reader.private_epub_path"
        private const val EXTRA_INITIAL_LOCATOR_JSON = "reader.initial_locator_json"
        private const val READER_CONTAINER_ID = 0x4e560101

        fun createIntent(
            context: Context,
            bookId: String,
            privateEpubPath: String? = null,
            initialLocatorJson: String? = null,
        ): Intent = Intent(context, ReaderActivity::class.java).apply {
            putExtra(EXTRA_BOOK_ID, bookId)
            privateEpubPath?.let { putExtra(EXTRA_PRIVATE_EPUB_PATH, it) }
            initialLocatorJson?.let { putExtra(EXTRA_INITIAL_LOCATOR_JSON, it) }
        }
    }
}
