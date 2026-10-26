#include "protocol.h"
#include "common.h"
#include <fstream>
#include <iostream>
#include <algorithm>

int main(int argc, char ** argv) {
    using namespace motoai;
    if (argc < 3 || argc > 4) { std::cerr << "Usage: runtime-run MODEL REQUEST.json [--inspect]\n"; return 2; }
    try {
        std::ifstream source(argv[2], std::ios::binary | std::ios::ate);
        if (!source || source.tellg() > 2 * 1024 * 1024) throw std::invalid_argument("Invalid request file");
        source.seekg(0);
        auto data = Json::parse(source);
        const auto config = parse_config(data.at("config"));
        auto request = parse_request(data.at("request"));
        Runtime::initialize("", config.backend);
        Json report{{"system_info", llama_print_system_info()}};
        if (argc == 4) {
            if (std::string(argv[3]) != "--inspect") throw std::invalid_argument("Unknown mode");
            auto params = llama_model_default_params();
            params.vocab_only = true;
            params.n_gpu_layers = 0;
            std::unique_ptr<llama_model, decltype(&llama_model_free)> model(llama_model_load_from_file(argv[1], params), llama_model_free);
            if (!model) throw std::runtime_error("Vocabulary load failed");
            auto templates = common_chat_templates_init(model.get(), "");
            if (data.contains("teacher_forcing_text")) {
                if (request.chat) throw std::invalid_argument("Corpus scoring requires raw text mode");
                const auto * vocab = llama_model_get_vocab(model.get());
                const auto prefix = common_tokenize(vocab, request.raw_prompt, true, true);
                const auto full = common_tokenize(vocab, request.raw_prompt + data.at("teacher_forcing_text").get<std::string>(), true, true);
                if (full.size() <= prefix.size() || !std::equal(prefix.begin(), prefix.end(), full.begin())) {
                    throw std::invalid_argument("Corpus split changes a BPE boundary");
                }
                request.score_continuation_ids.assign(full.begin() + prefix.size(), full.end());
                request.max_tokens = static_cast<int32_t>(request.score_continuation_ids.size());
                data["request"]["score_continuation_ids"] = request.score_continuation_ids;
                data["request"]["max_tokens"] = request.max_tokens;
                data["request"]["check_logits"] = true;
                data.erase("teacher_forcing_text");
                report["normalized_spec"] = data;
            }
            if (data.contains("target_prompt_tokens")) {
                const int target = data.at("target_prompt_tokens").get<int>();
                if (target < 64 || target > 262000 || request.messages.empty()) throw std::invalid_argument("Invalid synthetic target");
                int low = 0, high = target * 2;
                bool found = false;
                while (low <= high) {
                    const int count = low + (high - low) / 2;
                    std::string content = data.value("synthetic_prefix", "Context for a coding task:\n");
                    for (int i = 0; i < count; ++i) {
                        if (i == count/2 && data.contains("synthetic_middle")) content += data.at("synthetic_middle").get<std::string>();
                        content += "a ";
                    }
                    content += data.value("synthetic_suffix", "\nTask: Write a Python function that adds two integers.");
                    request.messages.back().content = std::move(content);
                    const auto candidate = prepare_prompt(model.get(), templates.get(), request, 262144);
                    if (static_cast<int>(candidate.tokens.size()) == target) { found = true; break; }
                    if (static_cast<int>(candidate.tokens.size()) < target) low = count + 1;
                    else high = count - 1;
                }
                if (!found) throw std::runtime_error("Cannot synthesize exact prompt token count");
                data["request"]["messages"].back()["content"] = request.messages.back().content;
                data.erase("target_prompt_tokens");
                report["normalized_spec"] = data;
            }
            auto prepared = prepare_prompt(model.get(), templates.get(), request, config.context);
            report["trace"] = {{"rendered_prompt", prepared.text}, {"prompt_ids", prepared.tokens}};
            report["scope"] = "vocabulary/template only; no tensor inference";
        } else {
            Runtime runtime;
            const auto load_us = runtime.load(argv[1], config, runtime.ticket());
            report["load"] = config_json(config, load_us);
            std::string output;
            auto result = runtime.generate(request, runtime.ticket(), [&](const std::string & piece) { output += piece; });
            report["result"] = result_json(result);
            report["output"] = output;
        }
        report["ok"] = true;
        std::cout << report.dump(2, ' ', false, Json::error_handler_t::replace) << '\n';
        return 0;
    } catch (const ContextOverflow & error) {
        std::cout << Json{{"ok", false}, {"kind", "context_overflow"}, {"message", error.what()}}.dump() << '\n';
    } catch (const std::exception & error) {
        std::cout << Json{{"ok", false}, {"kind", "runtime"}, {"message", error.what()}}.dump() << '\n';
    }
    return 1;
}
