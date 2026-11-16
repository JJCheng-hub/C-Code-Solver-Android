# C-Code Solver 2.0 — Android 离线版

为 HONOR 70 / Snapdragon 778G Plus / arm64-v8a 设计的 Android Studio 项目。

## 这是什么

这是一个原生 Android 项目源码，不是浏览器网页。设计目标是在模型与 OCR 语言包导入后，断网拍照识题并在手机本机生成 C 语言代码。

**运行时不需要互联网：**
- AndroidManifest 没有申请 `INTERNET` 权限。
- OCR 使用 Tesseract4Android，在设备本机识别。
- 模型使用 llama.cpp，在设备本机推理。
- 图片、题目和生成结果不上传服务器。

**重要：当前交付的是 Android Studio 源码项目，不是已编译 APK。** 当前构建环境没有 Android SDK/NDK，无法在这里产出并实机验证 APK。请按下面步骤构建；首次构建需要网络下载 Android 构建依赖和 llama.cpp 源码。

## 为 HONOR 70 选择的配置

官方 HONOR 70 规格包含 Snapdragon 778G Plus、Adreno 642L、8GB+256GB 版本。该项目只构建 `arm64-v8a`，模型采用 CPU 推理，默认上下文 3072，4 个推理线程。

推荐模型：
- Qwen2.5-Coder-1.5B-Instruct-GGUF
- 量化：Q4_K_M
- 模型文件约 1.12 GB
- 模型页：https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF

如遇到内存不足、发热或系统杀后台，可在 app/src/main/cpp/native-lib.cpp 中把 `n_ctx = 3072` 改为 `2048`，并重新构建。若仍不稳定，可换 Q3_K_M 量化，但代码能力可能下降。不要选择 7B 模型作为这台手机的首选。

实际速度、温度和可用内存需要在你的手机上测试，不能仅凭芯片型号保证。

## 一、构建 APK

### 方案 A：Android Studio

1. 在电脑安装 Android Studio。
2. 安装 JDK 17。
3. 在 SDK Manager 中安装：
   - Android SDK Platform 35
   - Android SDK Build-Tools 35.0.0
   - Android NDK 27.2.12479018
   - CMake 3.22.1
4. 打开本项目文件夹。
5. 等待 Gradle 同步。首次构建会下载 Tesseract4Android、Gradle 依赖和 llama.cpp 源码，因此这一步需要网络。
6. 选择 `Build > Build Bundle(s) / APK(s) > Build APK(s)`。
7. APK 输出路径：
   `app/build/outputs/apk/debug/app-debug.apk`
8. 把 APK 传到荣耀 70 安装。按系统提示允许本次安装来源。

### 方案 B：GitHub Actions 云构建

项目已包含 `.github/workflows/build-apk.yml`。

1. 在 GitHub 新建一个空仓库。
2. 将本项目全部文件上传到仓库的 `main` 分支。
3. 打开仓库的 `Actions`。
4. 选择 `Build C-Code Solver APK`，点击 `Run workflow`。
5. 构建成功后，在该次运行页面的 Artifacts 中下载 `C-Code-Solver-2.0-debug-apk`。
6. 解压得到 `app-debug.apk`，传到手机安装。

## 二、提前下载三个离线资源

### 1. C 语言模型（约 1.12 GB）

打开模型页：
https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF

下载文件：
`qwen2.5-coder-1.5b-instruct-q4_k_m.gguf`

### 2. 中文 OCR 语言包

下载：
https://github.com/tesseract-ocr/tessdata_fast/raw/refs/heads/main/chi_sim.traineddata

文件名必须是：
`chi_sim.traineddata`

### 3. 英文 OCR 语言包

下载：
https://github.com/tesseract-ocr/tessdata_fast/raw/refs/heads/main/eng.traineddata

文件名必须是：
`eng.traineddata`

建议在电脑上下载好，再通过 USB、局域网文件传输或手机浏览器保存到荣耀 70 的“下载”目录。

## 三、首次安装与离线初始化

1. 打开 C-Code Solver。
2. 点击“导入 GGUF 模型文件”，选择 `qwen2.5-coder-1.5b-instruct-q4_k_m.gguf`。
3. 点击“导入中文包”，选择 `chi_sim.traineddata`。
4. 点击“导入英文包”，选择 `eng.traineddata`。
5. 点击“加载 / 重新加载模型”。第一次载入需要等待，模型加载完成后状态会显示“本地模型已加载”。
6. 拍摄一道印刷清楚的 C 语言题目，或从相册选择。
7. 点击“离线识别文字”，检查 OCR 结果，手动修正数字、运算符、括号、数组下标和输入输出样例。
8. 点击“生成 C 语言答案”。
9. 可点击“再次复核代码”进行第二轮模型检查。

## 四、必须做一次真正的断网测试

在考试前完成以下测试：

1. 关闭 Wi-Fi、移动数据，打开飞行模式。
2. 强制关闭 app 后重新打开。
3. 加载模型。
4. 拍摄一题 C 语言练习题并识别。
5. 生成代码，再执行一次代码复核。
6. 连续测试至少 5 道题，包括循环、数组、字符串、函数和输入输出格式。

如果离线测试失败，不要等到考试当天再排查。

## 五、已知限制

- OCR 对模糊、反光、倾斜图片，以及 `0/O`、`1/l`、`-`、`=`、括号等字符可能误识别；必须人工核对。
- 1.5B 是为手机资源做的折中模型，不等同于大型云端模型。复杂算法题可能出错。
- 代码复核是同一个本地模型的二次审查，不是独立证明。
- 当前版本没有内置完整 C 编译器，因此不能宣称代码已经真实编译或运行通过。
- 推理速度取决于手机温度、电量策略、剩余 RAM 和系统后台占用。
- 生成代码前建议关闭高耗电应用、保持电量充足，并避免长时间高温运行。

## 技术依赖与许可

- llama.cpp：MIT License
- Qwen2.5-Coder：Apache-2.0 License
- Tesseract4Android：Apache-2.0 License
- Tesseract tessdata_fast：按其仓库许可使用

本项目使用上游 llama.cpp 的 Git 源码作为 CMake FetchContent 依赖。构建时需要网络；安装完成并导入模型后，app 运行不调用网络。
