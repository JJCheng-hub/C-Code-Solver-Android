package com.csolver.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private enum class ImportKind { MODEL, CHINESE, ENGLISH }

    private lateinit var statusView: TextView
    private lateinit var modelView: TextView
    private lateinit var ocrView: TextView
    private lateinit var questionInput: EditText
    private lateinit var answerView: TextView
    private lateinit var codeView: TextView
    private lateinit var imagePreview: ImageView
    private lateinit var modeSpinner: Spinner
    private lateinit var progress: ProgressBar

    private var pendingImport = ImportKind.MODEL
    private var modelLoaded = false
    private var currentImageUri: Uri? = null
    private var cameraUri: Uri? = null
    private var latestCode = ""

    private val prefs by lazy { getSharedPreferences("csolver", MODE_PRIVATE) }

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importSelectedFile(uri, pendingImport)
    }

    private val chooseImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            currentImageUri = uri
            imagePreview.setImageURI(uri)
            imagePreview.visibility = View.VISIBLE
            setStatus("图片已选择。点击“离线识别文字”。")
        }
    }

    private val takePhoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraUri != null) {
            currentImageUri = cameraUri
            imagePreview.setImageURI(cameraUri)
            imagePreview.visibility = View.VISIBLE
            setStatus("拍照完成。点击“离线识别文字”。")
        } else {
            setStatus("没有获取到照片。")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        refreshResourceStatus()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(245, 247, 250))
            isFillViewport = true
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        scroll.addView(page)

        val brand = TextView(this).apply {
            text = "C-Code Solver 2.0"
            textSize = 25f
            setTextColor(Color.rgb(18, 32, 51))
            typeface = Typeface.DEFAULT_BOLD
        }
        page.addView(brand)
        page.addView(text("HONOR 70 · 本地离线 C 语言助手", 12, 0xFF738194.toInt()).apply {
            setPadding(0, dp(5), 0, dp(14))
        })

        statusView = text("正在检查本地资源…", 12, 0xFF168A75.toInt())
        page.addView(card(statusView))

        // Model setup
        page.addView(sectionTitle("01  本地 AI 模型"))
        modelView = text("尚未导入模型文件", 12, 0xFF718092.toInt())
        page.addView(card(modelView))
        page.addView(button("导入 GGUF 模型文件") {
            pendingImport = ImportKind.MODEL
            openDocument.launch(arrayOf("*/*"))
        })
        page.addView(button("加载 / 重新加载模型") { loadModel() })

        // OCR setup
        page.addView(sectionTitle("02  离线 OCR 语言包"))
        ocrView = text("需要导入 chi_sim.traineddata 和 eng.traineddata", 12, 0xFF718092.toInt())
        page.addView(card(ocrView))
        val ocrButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val chineseButton = button("导入中文包") {
            pendingImport = ImportKind.CHINESE
            openDocument.launch(arrayOf("*/*"))
        }
        val englishButton = button("导入英文包") {
            pendingImport = ImportKind.ENGLISH
            openDocument.launch(arrayOf("*/*"))
        }
        ocrButtons.addView(chineseButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginEnd = dp(5)
        })
        ocrButtons.addView(englishButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(5)
        })
        page.addView(ocrButtons)

        // Image input
        page.addView(sectionTitle("03  拍照 / 上传题目"))
        imagePreview = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(0xFFFFFFFF.toInt(), 12)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        page.addView(imagePreview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        val imageButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        imageButtons.addView(button("拍照") { startCamera() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        imageButtons.addView(button("从相册选择") { chooseImage.launch("image/*") },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        page.addView(imageButtons)
        page.addView(button("离线识别文字") { recognizeImage() })

        page.addView(sectionTitle("04  检查 / 修改题目文字"))
        questionInput = EditText(this).apply {
            hint = "OCR 识别结果会显示在这里。也可以直接输入或粘贴题目…"
            textSize = 14f
            setTextColor(Color.rgb(45, 59, 75))
            setHintTextColor(Color.rgb(155, 165, 176))
            gravity = Gravity.TOP
            minLines = 7
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = rounded(Color.WHITE, 12, 0xFFE2E8EF.toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        page.addView(questionInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        page.addView(text("回答方式", 12, 0xFF415368.toInt()).apply {
            setPadding(0, dp(6), 0, dp(6))
        })
        modeSpinner = Spinner(this)
        val modes = listOf("完整代码 + 解题思路", "只输出完整 C 代码")
        modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        page.addView(modeSpinner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ))

        page.addView(button("生成 C 语言答案") { generateAnswer() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(10) })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        page.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(5)
        ).apply { topMargin = dp(10) })

        page.addView(sectionTitle("05  代码结果"))
        codeView = text("生成的 C 代码会显示在这里。", 12, 0xFFE6EDF4.toInt()).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(0xFF111D2F.toInt(), 12)
            setTextIsSelectable(true)
        }
        codeView.minHeight = dp(120)
        page.addView(codeView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        page.addView(button("复制代码") { copyCode() })
        page.addView(button("再次复核代码") { reviewCode() })

        answerView = text("解题说明和复核结果会显示在这里。", 12, 0xFF647487.toInt()).apply {
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = rounded(Color.WHITE, 12, 0xFFE3E9EF.toInt())
            setTextIsSelectable(true)
        }
        page.addView(answerView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        page.addView(text(
            "完全离线：本应用没有申请 INTERNET 权限。请在有网时提前安装 APK、导入模型和 OCR 语言包，并开启飞行模式完成实测。",
            10, 0xFF929EAA.toInt()
        ).apply { setPadding(dp(2), dp(18), dp(2), 0) })

        setContentView(scroll)
    }

    private fun sectionTitle(title: String): TextView =
        text(title, 15, 0xFF172B43.toInt()).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(9))
        }

    private fun text(value: String, size: Int, color: Int): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    private fun card(child: View): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = rounded(Color.WHITE, 12, 0xFFE7ECF1.toInt())
        }
        box.addView(child)
        return box
    }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(0xFF168F7A.toInt(), 11)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(6); bottomMargin = dp(4) }
        }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun setStatus(message: String) {
        statusView.text = message
    }

    private fun refreshResourceStatus() {
        val model = File(filesDir, "models/csolver-model.gguf")
        modelView.text = if (model.exists()) {
            "模型文件已导入：${model.name}（${model.length() / (1024 * 1024)} MB）。点击加载模型。"
        } else {
            "尚未导入模型。推荐 Qwen2.5-Coder-1.5B-Instruct Q4_K_M。"
        }

        val tessDir = File(filesDir, "tesseract/tessdata")
        val hasZh = File(tessDir, "chi_sim.traineddata").exists()
        val hasEn = File(tessDir, "eng.traineddata").exists()
        ocrView.text = "中文包：${if (hasZh) "已就绪" else "未导入"}；英文包：${if (hasEn) "已就绪" else "未导入"}"
        if (modelLoaded) setStatus("本地模型已加载，可以离线解题。")
        else setStatus("本地模式。请先导入模型并加载。")
    }

    private fun importSelectedFile(uri: Uri, kind: ImportKind) {
        lifecycleScope.launch {
            showBusy(true)
            try {
                val target = when (kind) {
                    ImportKind.MODEL -> File(filesDir, "models/csolver-model.gguf")
                    ImportKind.CHINESE -> File(filesDir, "tesseract/tessdata/chi_sim.traineddata")
                    ImportKind.ENGLISH -> File(filesDir, "tesseract/tessdata/eng.traineddata")
                }
                withContext(Dispatchers.IO) {
                    target.parentFile?.mkdirs()
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("无法读取所选文件")
                    input.use { source ->
                        FileOutputStream(target).use { output -> source.copyTo(output) }
                    }
                }
                if (kind == ImportKind.MODEL) {
                    prefs.edit().putString("model_path", target.absolutePath).apply()
                    modelLoaded = false
                }
                refreshResourceStatus()
                setStatus("文件导入完成：${target.name}")
            } catch (e: Exception) {
                setStatus("导入失败：${e.message}")
            } finally {
                showBusy(false)
            }
        }
    }

    private fun loadModel() {
        val modelFile = File(filesDir, "models/csolver-model.gguf")
        if (!modelFile.exists()) {
            setStatus("请先导入 .gguf 模型文件。")
            return
        }
        lifecycleScope.launch {
            showBusy(true)
            setStatus("正在加载本地模型。首次加载可能需要几十秒…")
            try {
                val result = withContext(Dispatchers.Default) {
                    LlamaBridge.loadModel(modelFile.absolutePath, 4)
                }
                modelLoaded = result == "OK"
                setStatus(if (modelLoaded) "模型加载成功。现在可以断网生成 C 代码。" else result)
            } catch (e: Throwable) {
                modelLoaded = false
                setStatus("模型加载失败：${e.message}")
            } finally {
                showBusy(false)
            }
        }
    }

    private fun startCamera() {
        try {
            val photo = File(cacheDir, "question-${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                this, "com.csolver.mobile.fileprovider", photo
            )
            cameraUri = uri
            takePhoto.launch(uri)
        } catch (e: Exception) {
            setStatus("无法打开相机：${e.message}")
        }
    }

    private fun recognizeImage() {
        val uri = currentImageUri
        if (uri == null) {
            setStatus("请先拍照或从相册选择题目图片。")
            return
        }

        val tessRoot = File(filesDir, "tesseract")
        val tessData = File(tessRoot, "tessdata")
        val zh = File(tessData, "chi_sim.traineddata")
        val en = File(tessData, "eng.traineddata")
        if (!zh.exists() || !en.exists()) {
            setStatus("OCR 语言包不完整。请分别导入 chi_sim.traineddata 和 eng.traineddata。")
            return
        }

        lifecycleScope.launch {
            showBusy(true)
            setStatus("正在本机识别图片文字…")
            try {
                val recognized = withContext(Dispatchers.IO) {
                    val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
                        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                        BitmapFactory.decodeStream(stream, null, options)
                    } ?: throw IllegalStateException("图片读取失败")

                    val prepared = if (bitmap.width > 2400) {
                        val newHeight = (bitmap.height * (2400f / bitmap.width)).toInt()
                        Bitmap.createScaledBitmap(bitmap, 2400, newHeight, true)
                    } else bitmap

                    val api = TessBaseAPI()
                    try {
                        if (!api.init(tessRoot.absolutePath, "chi_sim+eng")) {
                            throw IllegalStateException("Tesseract 初始化失败，请重新导入 OCR 语言包。")
                        }
                        api.setImage(prepared)
                        api.getUTF8Text()?.trim().orEmpty()
                    } finally {
                        api.recycle()
                        if (prepared !== bitmap) prepared.recycle()
                        bitmap.recycle()
                    }
                }
                questionInput.setText(recognized)
                setStatus(if (recognized.isBlank()) {
                    "没有识别到文字。请换一张更清晰、正对题目的照片，或手动输入题目。"
                } else {
                    "识别完成。请重点检查数字、括号、运算符、数组下标和输入输出样例。"
                })
            } catch (e: Exception) {
                setStatus("OCR 失败：${e.message}")
            } finally {
                showBusy(false)
            }
        }
    }

    private fun buildPrompt(question: String, codeOnly: Boolean): String {
        val system = if (codeOnly) {
            """
你是大学 C 语言程序设计助教。请严格解答题目，只输出完整、可编译的标准 C11 代码。
要求：
1. 必须包含必要头文件和完整 main 函数，不要省略代码。
2. 严格遵守题目输入输出格式，不额外输出提示语。
3. 优先使用初学者学过的基础语法。
4. 检查循环边界、数组下标、变量类型、整数溢出和 scanf/printf 格式符。
5. 如果题意不清楚，先在代码注释中标出假设，不要编造输入条件。
6. 代码必须放在一个 ```c 代码块中。
            """.trimIndent()
        } else {
            """
你是严谨的大学 C 语言程序设计助教。请用中文解答，按以下结构：
一、题目理解
二、完整 C11 代码（必须包含头文件和 main，放在 ```c 代码块中）
三、核心思路
四、关键变量和循环说明
五、边界情况与样例验证
要求：遵循题目输入输出格式；优先使用初学者基础语法；检查数组边界、循环终止条件、变量类型、格式符和特殊情况。不得声称实际编译通过，除非确实执行了编译。
            """.trimIndent()
        }
        return "<|im_start|>system\n$system<|im_end|>\n" +
                "<|im_start|>user\n请解答以下题目：\n$question<|im_end|>\n" +
                "<|im_start|>assistant\n"
    }

    private fun generateAnswer() {
        val question = questionInput.text.toString().trim()
        if (question.length < 3) {
            setStatus("请先输入题目，或拍照识别题目。")
            return
        }
        if (!modelLoaded) {
            setStatus("请先导入并加载本地模型。")
            return
        }

        val codeOnly = modeSpinner.selectedItemPosition == 1
        lifecycleScope.launch {
            showBusy(true)
            setStatus("本地模型正在分析题目…请勿切换或关闭应用。")
            try {
                val answer = withContext(Dispatchers.Default) {
                    LlamaBridge.generate(buildPrompt(question, codeOnly), 850)
                }
                latestCode = extractCode(answer)
                codeView.text = latestCode.ifBlank { "未能自动提取代码块，请查看完整回答。" }
                answerView.text = answer
                setStatus("生成完成。请核对 OCR 文字及题目输出格式。")
            } catch (e: Throwable) {
                setStatus("生成失败：${e.message}")
            } finally {
                showBusy(false)
            }
        }
    }

    private fun reviewCode() {
        val question = questionInput.text.toString().trim()
        if (question.isBlank() || latestCode.isBlank() || !modelLoaded) {
            setStatus("请先生成代码，再进行复核。")
            return
        }
        val system = """
你是严格的 C11 代码审查员。对照题目检查代码的逻辑、输入输出格式、变量类型、
循环边界、数组越界、未初始化变量、整数溢出、scanf/printf 格式符和特殊情况。
先列出发现的问题；若有问题，给出修正后的完整 C11 代码块。
如果未发现明显问题，也要说明仍需用编译器和样例验证的部分。不要声称已经实际编译或运行。
        """.trimIndent()
        val prompt = "<|im_start|>system\n$system<|im_end|>\n" +
                "<|im_start|>user\n【题目】\n$question\n\n【待审查代码】\n```c\n$latestCode\n```<|im_end|>\n" +
                "<|im_start|>assistant\n"

        lifecycleScope.launch {
            showBusy(true)
            setStatus("正在使用本地模型复核代码…")
            try {
                val review = withContext(Dispatchers.Default) {
                    LlamaBridge.generate(prompt, 700)
                }
                answerView.text = "【代码复核】\n\n$review"
                val corrected = extractCode(review)
                if (corrected.isNotBlank()) {
                    latestCode = corrected
                    codeView.text = corrected
                }
                setStatus("复核完成。")
            } catch (e: Throwable) {
                setStatus("复核失败：${e.message}")
            } finally {
                showBusy(false)
            }
        }
    }

    private fun extractCode(answer: String): String {
        val fenced = Regex("```(?:c|C)?\\s*([\\s\\S]*?)```").find(answer)
        if (fenced != null) return fenced.groupValues[1].trim()
        val start = answer.indexOf("#include")
        return if (start >= 0) answer.substring(start).trim() else ""
    }

    private fun copyCode() {
        if (latestCode.isBlank()) {
            setStatus("当前没有可复制的代码。")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("C source", latestCode))
        setStatus("代码已复制。")
    }

    private fun showBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        try {
            LlamaBridge.unload()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}
