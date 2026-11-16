#include <jni.h>
#include <android/log.h>
#include <llama.h>

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "CCodeSolver"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex g_mutex;
static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;
static bool g_backend_ready = false;

static std::string fromJString(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

static jstring toJString(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
}

static void unloadLocked() {
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_csolver_mobile_LlamaBridge_loadModel(
        JNIEnv * env, jobject /* thiz */, jstring modelPath, jint requestedThreads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    unloadLocked();

    const std::string path = fromJString(env, modelPath);
    if (path.empty()) return toJString(env, "模型路径为空。");

    if (!g_backend_ready) {
        llama_backend_init();
        g_backend_ready = true;
    }

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0; // Honor 70: conservative CPU-only path.
    g_model = llama_model_load_from_file(path.c_str(), modelParams);
    if (g_model == nullptr) {
        return toJString(env, "模型加载失败。请确认文件是完整的 GGUF 模型，且剩余内存充足。");
    }

    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = 3072;
    contextParams.n_batch = 512;
    contextParams.n_ubatch = 256;

    const int hardwareThreads = static_cast<int>(std::thread::hardware_concurrency());
    const int fallbackThreads = hardwareThreads > 2 ? hardwareThreads - 2 : 2;
    const int threads = requestedThreads > 0
            ? std::min(static_cast<int>(requestedThreads), 6)
            : std::min(fallbackThreads, 6);
    contextParams.n_threads = threads;
    contextParams.n_threads_batch = threads;

    g_ctx = llama_init_from_model(g_model, contextParams);
    if (g_ctx == nullptr) {
        unloadLocked();
        return toJString(env, "模型权重已读取，但推理上下文创建失败。请关闭其他应用后重试。");
    }

    return toJString(env, "OK");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_csolver_mobile_LlamaBridge_generate(
        JNIEnv * env, jobject /* thiz */, jstring rawPrompt, jint maxTokens) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_model == nullptr || g_ctx == nullptr) {
        return toJString(env, "请先导入并加载 GGUF 模型。");
    }

    const std::string prompt = fromJString(env, rawPrompt);
    if (prompt.empty()) return toJString(env, "题目内容为空。");

    llama_memory_clear(llama_get_memory(g_ctx), true);

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens(prompt.size() + 64);

    int32_t tokenCount = llama_tokenize(
            vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            false,  // ChatML special tokens are already present in the prompt.
            true
    );

    if (tokenCount < 0) {
        const int32_t required = -tokenCount;
        tokens.resize(static_cast<size_t>(required) + 8);
        tokenCount = llama_tokenize(
                vocab,
                prompt.c_str(),
                static_cast<int32_t>(prompt.size()),
                tokens.data(),
                static_cast<int32_t>(tokens.size()),
                false,
                true
        );
    }

    if (tokenCount <= 0) {
        return toJString(env, "题目分词失败，请缩短题目文字后重试。");
    }
    tokens.resize(static_cast<size_t>(tokenCount));

    const uint32_t contextSize = llama_n_ctx(g_ctx);
    if (static_cast<uint32_t>(tokenCount) >= contextSize - 64) {
        return toJString(env, "题目太长，超过了本机模型的上下文容量。请删去无关描述或分段提问。");
    }

    // Decode the prompt in chunks no larger than the configured batch size.
    constexpr int32_t kBatch = 512;
    for (int32_t offset = 0; offset < tokenCount; offset += kBatch) {
        const int32_t count = std::min(kBatch, tokenCount - offset);
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, count);
        const int32_t result = llama_decode(g_ctx, batch);
        if (result != 0) {
            return toJString(env, "题目编码失败。请减少题目长度，或重新加载模型。");
        }
    }

    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.90f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.15f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

    int32_t limit = maxTokens > 0 ? std::min(static_cast<int>(maxTokens), 1000) : 700;
    limit = std::min(limit, static_cast<int>(contextSize - tokenCount - 8));

    std::string output;
    output.reserve(static_cast<size_t>(limit) * 5);
    std::vector<char> pieceBuffer(512);

    for (int32_t i = 0; i < limit; ++i) {
        const llama_token token = llama_sampler_sample(sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        int32_t pieceLength = llama_token_to_piece(
                vocab, token, pieceBuffer.data(),
                static_cast<int32_t>(pieceBuffer.size()), 0, false
        );

        if (pieceLength < 0) {
            pieceBuffer.resize(static_cast<size_t>(-pieceLength) + 8);
            pieceLength = llama_token_to_piece(
                    vocab, token, pieceBuffer.data(),
                    static_cast<int32_t>(pieceBuffer.size()), 0, false
            );
        }

        if (pieceLength > 0) {
            output.append(pieceBuffer.data(), static_cast<size_t>(pieceLength));
        }

        llama_batch nextBatch = llama_batch_get_one(
                const_cast<llama_token *>(&token), 1
        );
        if (llama_decode(g_ctx, nextBatch) != 0) break;
    }

    llama_sampler_free(sampler);

    if (output.empty()) {
        return toJString(env, "模型没有生成内容。请重试，或缩短题目。");
    }
    return toJString(env, output);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_csolver_mobile_LlamaBridge_unload(
        JNIEnv * /* env */, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_mutex);
    unloadLocked();
}
