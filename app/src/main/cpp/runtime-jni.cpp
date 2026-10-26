#include "protocol.h"
#include "model-file.h"
#include "gguf.h"
#include <sys/stat.h>
#include <cstdio>
#include <cerrno>
#include <cstring>
#include "nlohmann/json.hpp"
#include <jni.h>
#include <algorithm>
#include <limits>
#include <unistd.h>

using json = nlohmann::ordered_json;
namespace {
motoai::Runtime runtime;
struct JavaFailure {};

std::string input(JNIEnv * env, jbyteArray bytes) {
    if (!bytes) throw std::invalid_argument("Missing input");
    const auto size = env->GetArrayLength(bytes);
    if (size > 2 * 1024 * 1024) throw std::invalid_argument("Input exceeds transport limit");
    std::string result(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte *>(result.data()));
    if (env->ExceptionCheck()) throw JavaFailure();
    return result;
}

jbyteArray bytes(JNIEnv * env, const std::string & value) {
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (!result) throw JavaFailure();
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
    if (env->ExceptionCheck()) throw JavaFailure();
    return result;
}

template<typename F> jbyteArray guarded(JNIEnv * env, F function) {
    json reply;
    try { reply = function(); }
    catch (const JavaFailure &) { return nullptr; }
    catch (const motoai::Cancelled & error) { reply = {{"ok", false}, {"kind", "cancelled"}, {"message", error.what()}}; }
    catch (const motoai::ContextOverflow & error) { reply = {{"ok", false}, {"kind", "context_overflow"}, {"message", error.what()}, {"prompt_tokens", error.prompt_tokens}, {"output_reserve", error.output_reserve}, {"context", error.context}}; }
    catch (const std::exception & error) { reply = {{"ok", false}, {"kind", "runtime"}, {"message", error.what()}}; }
    catch (...) { reply = {{"ok", false}, {"kind", "runtime"}, {"message", "Unknown native failure"}}; }
    if (env->ExceptionCheck()) return nullptr;
    try { return bytes(env, reply.dump(-1, ' ', false, json::error_handler_t::replace)); }
    catch (...) { return nullptr; }
}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_motoai_runtime_NativeRuntime_initNative(JNIEnv * env, jobject, jbyteArray directory, jbyteArray backend) {
    return guarded(env, [&] {
        const auto path = input(env, directory);
        if (path.size() > 4096 || path.find('\0') != std::string::npos) throw std::invalid_argument("Invalid backend directory");
        motoai::Runtime::initialize(path, input(env, backend));
        json registries = json::array();
        for (size_t i = 0; i < ggml_backend_reg_count(); ++i) registries.push_back(ggml_backend_reg_name(ggml_backend_reg_get(i)));
        json devices = json::array();
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            auto device = ggml_backend_dev_get(i);
            devices.push_back({{"name", ggml_backend_dev_name(device)},
                {"backend", ggml_backend_reg_name(ggml_backend_dev_backend_reg(device))},
                {"type", static_cast<int>(ggml_backend_dev_type(device))}, {"description", ggml_backend_dev_description(device)}});
        }
        return json{{"ok", true}, {"system_info", llama_print_system_info()},
                    {"backend_profile", motoai::Runtime::backend_profile(input(env, backend))},
                    {"page_size", sysconf(_SC_PAGESIZE)}, {"backend_registries", registries}, {"devices", devices}};
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_motoai_runtime_NativeRuntime_ticketNative(JNIEnv *, jobject) {
    return static_cast<jlong>(runtime.ticket());
}

extern "C" JNIEXPORT void JNICALL
Java_org_motoai_runtime_NativeRuntime_cancelNative(JNIEnv *, jobject) { runtime.cancel(); }

extern "C" JNIEXPORT void JNICALL
Java_org_motoai_runtime_NativeRuntime_unloadNative(JNIEnv * env, jobject) {
    try { runtime.unload(); }
    catch (...) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Native unload failed"); }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_motoai_runtime_NativeRuntime_loadNative(JNIEnv * env, jobject, jbyteArray payload, jlong ticket) {
    return guarded(env, [&] {
        const auto data = json::parse(input(env, payload));
        const auto path = data.at("path").get<std::string>();
        if (path.size() > 4096 || path.find('\0') != std::string::npos) throw std::invalid_argument("Invalid model path");
        const auto config = motoai::parse_config(data);
        const auto elapsed = runtime.load(path, config, static_cast<uint64_t>(ticket));
        auto reply = motoai::config_json(config, elapsed);
        reply["buffer_allocations"] = motoai::memory_json(runtime.memory());
        return reply;
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_motoai_runtime_NativeRuntime_generateNative(JNIEnv * env, jobject, jbyteArray payload, jlong ticket, jobject sink) {
    return guarded(env, [&] {
        if (!sink) throw std::invalid_argument("Missing output sink");
        jclass type = env->GetObjectClass(sink);
        const auto method = env->GetMethodID(type, "onBytes", "([B)V");
        if (!method || env->ExceptionCheck()) { env->DeleteLocalRef(type); throw JavaFailure(); }
        const auto phase_method = env->GetMethodID(type, "onPhase", "(Ljava/lang/String;)V");
        const auto progress_method = env->GetMethodID(type, "onProgress", "([B)V");
        const auto chat_method = env->GetMethodID(type, "onChat", "([B)V");
        env->DeleteLocalRef(type);
        if (!phase_method || !progress_method || !chat_method || env->ExceptionCheck()) throw JavaFailure();
        const auto data = json::parse(input(env, payload));
        const auto request = motoai::parse_request(data);
        auto result = runtime.generate(request, static_cast<uint64_t>(ticket), [&](const std::string & piece) {
            auto chunk = bytes(env, piece);
            env->CallVoidMethod(sink, method, chunk);
            env->DeleteLocalRef(chunk);
            if (env->ExceptionCheck()) throw JavaFailure();
        }, [&](const char * phase) {
            // Fixed ASCII phase names; user/model text always crosses as UTF-8 byte arrays.
            auto name = env->NewStringUTF(phase);
            if (!name) throw JavaFailure();
            env->CallVoidMethod(sink, phase_method, name);
            env->DeleteLocalRef(name);
            if (env->ExceptionCheck()) throw JavaFailure();
        }, [&](const motoai::Progress & progress) {
            auto payload = bytes(env, json{{"phase", progress.phase}, {"completed", progress.completed},
                {"total", progress.total}, {"elapsed_us", progress.elapsed_us}, {"eval_us", progress.eval_us}}.dump());
            env->CallVoidMethod(sink, progress_method, payload);
            env->DeleteLocalRef(payload);
            if (env->ExceptionCheck()) throw JavaFailure();
        }, [&](const common_chat_msg & message) {
            auto payload = bytes(env, json{{"role", "assistant"}, {"content", message.content}, {"reasoning_content", message.reasoning_content}}.dump(-1, ' ', false, json::error_handler_t::replace));
            env->CallVoidMethod(sink, chat_method, payload);
            env->DeleteLocalRef(payload);
            if (env->ExceptionCheck()) throw JavaFailure();
        });
        return motoai::result_json(result);
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_motoai_runtime_NativeRuntime_prepareNative(JNIEnv * env, jobject, jbyteArray payload) {
    return guarded(env, [&] {
        const auto request = motoai::parse_request(json::parse(input(env, payload)));
        const auto prepared = runtime.prepare(request);
        return json{{"ok", true}, {"prompt_tokens", prepared.tokens.size()}, {"output_reserve", request.max_tokens}};
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_motoai_runtime_NativeRuntime_planNative(JNIEnv * env, jobject, jbyteArray payload, jlong ticket) {
    return guarded(env, [&] {
        const auto data = json::parse(input(env, payload));
        const auto path = data.at("path").get<std::string>();
        if (path.empty() || path.size() > 4096 || path.find('\0') != std::string::npos)
            throw std::invalid_argument("Invalid model path");
        return motoai::plan_json(runtime.plan(path, motoai::parse_config(data), static_cast<motoai::Runtime::Ticket>(ticket)));
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_motoai_runtime_NativeRuntime_inspectNative(JNIEnv * env,jobject,jbyteArray payload) {
    return guarded(env,[&] {
        const auto path=input(env,payload);
        if(path.size()>4096 || path.find('\0')!=std::string::npos)throw std::invalid_argument("Invalid GGUF descriptor path");
        struct stat status{};
        if(stat(path.c_str(),&status)!=0 || !S_ISREG(status.st_mode) || status.st_size<24)throw std::invalid_argument("GGUF requires a regular seekable file");
        gguf_init_params params{};params.no_alloc=true;params.ctx=nullptr;
        auto stream=motoai::open_model_file(path);
        std::unique_ptr<gguf_context,decltype(&gguf_free)> file(gguf_init_from_file_ptr(stream.get(),params),gguf_free);
        if(!file)throw std::invalid_argument("Invalid GGUF metadata/tensor descriptors");
        const auto offset=gguf_get_data_offset(file.get());
        if(offset>static_cast<uint64_t>(status.st_size))throw std::invalid_argument("GGUF data offset exceeds file");
        const auto available=static_cast<uint64_t>(status.st_size)-offset;
        std::vector<std::pair<size_t,size_t>> spans;
        for(int64_t i=0;i<gguf_get_n_tensors(file.get());++i) {
            const auto start=gguf_get_tensor_offset(file.get(),i),bytes=gguf_get_tensor_size(file.get(),i);
            if(start>available || bytes>available-start)throw std::invalid_argument("Truncated GGUF tensor payload");
            spans.emplace_back(start,start+bytes);
        }
        std::sort(spans.begin(),spans.end());
        for(size_t i=1;i<spans.size();++i)if(spans[i].first<spans[i-1].second)throw std::invalid_argument("Overlapping GGUF tensors");
        const auto arch=gguf_find_key(file.get(),"general.architecture");
        if(arch<0 || gguf_get_kv_type(file.get(),arch)!=GGUF_TYPE_STRING)throw std::invalid_argument("Missing GGUF architecture");
        return json{{"ok",true},{"architecture",gguf_get_val_str(file.get(),arch)},{"tensors",gguf_get_n_tensors(file.get())},
            {"data_offset",offset},{"file_bytes",status.st_size},{"scope","Bounded GGUF structure/payload validation, no model weights allocated"}};
    });
}
