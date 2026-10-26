#include "protocol.h"
#include "response-stream.h"
#include "common.h"
#include <iostream>
#include <algorithm>
#include <cctype>

int main(int argc, char ** argv) {
    if (argc != 2) return 2;
    try {
        const std::string utf8 = u8"я🦉";
        const size_t complete[] = {0,0,2,2,2,2,6};
        for(size_t n=0;n<=utf8.size();++n) if(motoai::utf8_complete_prefix(utf8.substr(0,n))!=utf8.substr(0,complete[n]))
            throw std::runtime_error("Incomplete UTF8 boundary emitted");
        const std::vector<std::string> stops={"END",u8"🦉"};
        if(motoai::stop_text("abcEN",stops,false).text!="abc" || motoai::stop_text("abcEN",stops,true).text!="abcEN" ||
           !motoai::stop_text("abcENDtail",stops,false).stopped || motoai::stop_text("abcENDtail",stops,false).text!="abc")
            throw std::runtime_error("Split stop boundary/flush failed");
        motoai::Runtime::initialize();
        motoai::Runtime runtime;
        motoai::Config config;
        config.context = 128;
        config.threads = 2;
        config.batch = 128;
        config.micro_batch = 64;
        runtime.load(argv[1], config, runtime.ticket());
        motoai::Request request;
        request.chat = false;
        request.raw_prompt = "Once upon a time";
        request.max_tokens = 16;
        request.trace = true;
        auto generate = [&] {
            std::string text;
            auto result = runtime.generate(request, runtime.ticket(), [&](const std::string & piece) { text += piece; });
            if (result.prompt_ids.size() != static_cast<size_t>(result.prompt_tokens) ||
                result.generated_ids.size() != static_cast<size_t>(result.generated_tokens) ||
                result.inter_token_us.size() != static_cast<size_t>(result.generated_tokens - 1) ||
                result.rendered_prompt != request.raw_prompt || result.perf.n_eval != 15) {
                throw std::runtime_error("Invalid token/performance trace");
            }
            if (result.generated_tokens != 16 || text.empty()) throw std::runtime_error("Incomplete tiny fixture");
            return text;
        };
        bool invalid_type = false;
        try { motoai::parse_request(motoai::Json{{"max_tokens", 1.5}}); }
        catch (const std::invalid_argument &) { invalid_type = true; }
        if (!invalid_type) throw std::runtime_error("Fractional output budget accepted");
        auto vocab_params = llama_model_default_params();
        vocab_params.vocab_only = true;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> vocab_model(llama_model_load_from_file(argv[1], vocab_params), llama_model_free);
        if (!vocab_model) throw std::runtime_error("Vocabulary fixture failed");
        auto templates = common_chat_templates_init(vocab_model.get(), "");
        motoai::Request marker_request;
        common_chat_msg marker_message;
        marker_message.role = "user";
        const auto * fixture_vocab = llama_model_get_vocab(vocab_model.get());
        marker_message.content = common_token_to_piece(fixture_vocab, llama_vocab_bos(fixture_vocab), true);
        marker_request.messages = {marker_message};
        marker_request.max_tokens = 1;
#ifdef LLAMA_MOTOAI_INPUT_RANGES
        const auto marker_prompt = motoai::prepare_prompt(vocab_model.get(), templates.get(), marker_request, 2048);
        if (marker_prompt.text.find(marker_message.content) == std::string::npos)
            throw std::runtime_error("Literal content text was altered");
        auto without_marker = marker_request;
        without_marker.messages.front().content = "plain";
        const auto plain_prompt = motoai::prepare_prompt(vocab_model.get(), templates.get(), without_marker, 2048);
        const auto marker_id = llama_vocab_bos(fixture_vocab);
        if (std::count(marker_prompt.tokens.begin(), marker_prompt.tokens.end(), marker_id) !=
            std::count(plain_prompt.tokens.begin(), plain_prompt.tokens.end(), marker_id))
            throw std::runtime_error("Literal content became a BOS control token");
#else
        bool marker_rejected = false;
        try { motoai::prepare_prompt(vocab_model.get(), templates.get(), marker_request, 2048); }
        catch (const std::invalid_argument &) { marker_rejected = true; }
        if (!marker_rejected) throw std::runtime_error("Literal control marker was interpreted as template syntax");
#endif
        const auto first = generate();
        if (!motoai::parse_config(motoai::Json::object()).swa_full) throw std::runtime_error("SWA default changed");
        auto compact_swa=config;compact_swa.swa_full=false;bool compact_rejected=false;
        try {runtime.load(argv[1],compact_swa,runtime.ticket());}catch(const std::invalid_argument &){compact_rejected=true;}
        if(!compact_rejected || generate()!=first)throw std::runtime_error("Rejected compact SWA replaced the active model");
        const auto planned = runtime.plan(argv[1], config, runtime.ticket());
        if (planned.buffers.empty() || planned.layers <= 0 || planned.gpu_model_bytes ||
            !planned.context_bytes || !planned.compute_bytes || !planned.largest_cpu_tensor_bytes ||
            planned.largest_gpu_source_bytes || generate() != first)
            throw std::runtime_error("Metadata-only planning damaged the loaded model or returned invalid CPU geometry");
        if(motoai::parse_config(motoai::Json::object()).prefix_cache_mib!=0)
            throw std::runtime_error("Prefix cache default changed");
        auto unsupported_cache=config;unsupported_cache.prefix_cache_mib=128;
        bool cache_rejected=false;
        try {runtime.load(argv[1],unsupported_cache,runtime.ticket());}catch(const std::invalid_argument &){cache_rejected=true;}
        if(!cache_rejected || generate()!=first)throw std::runtime_error("Unsupported prefix-cache model damaged active model");
        for (const auto & invalid : std::vector<motoai::Json>{
                {{"backend", "cuda"}}, {{"gpu_layers", 1}}, {{"backend", "vulkan"}},
                {{"backend", "opencl"}, {"gpu_layers", -1}}, {{"gpu_layers", 1.5}}}) {
            bool rejected = false;
            try { motoai::parse_config(invalid); } catch (const std::invalid_argument &) { rejected = true; }
            if (!rejected) throw std::runtime_error("Malformed GPU profile accepted");
        }
        auto absent_gpu = config;
        absent_gpu.backend = "vulkan"; absent_gpu.gpu_layers = 1;
        bool gpu_refused = false;
        try { runtime.load(argv[1], absent_gpu, runtime.ticket()); } catch (const std::invalid_argument &) { gpu_refused = true; }
        if (!gpu_refused || generate() != first) throw std::runtime_error("Unavailable GPU silently fell back or damaged the live CPU model");

        for (const auto & invalid : std::vector<motoai::Json>{
                {{"threads_batch", -1}}, {{"threads_batch", 65}}, {{"threads_batch", 1.5}},
                {{"cpu_affinity", "3-6"}}, {{"cpu_affinity", {-1}}}, {{"cpu_affinity", {128}}},
                {{"cpu_affinity", {3, 3}}}, {{"cpu_affinity", {1.5}}}, {{"cpu_affinity", {true}}}}) {
            bool rejected = false;
            try { motoai::parse_config(invalid); } catch (const std::invalid_argument &) { rejected = true; }
            if (!rejected) throw std::runtime_error("Malformed scheduling control accepted");
        }
        auto scheduled = config;
        scheduled.threads_batch = 1;
        runtime.load(argv[1], scheduled, runtime.ticket());
        if (generate() != first || generate() != first) throw std::runtime_error("Separate CPU pools changed output");
        scheduled.threads_batch = 65;
        bool rejected_threads = false;
        try { runtime.load(argv[1], scheduled, runtime.ticket()); } catch (const std::invalid_argument &) { rejected_threads = true; }
        if (!rejected_threads || generate() != first) throw std::runtime_error("Invalid scheduling damaged loaded model");
        runtime.load(argv[1], config, runtime.ticket());
        if (motoai::parse_config(motoai::Json::object()).transient_repack) throw std::runtime_error("Transient default changed");
        auto transient = motoai::parse_config(motoai::Json{{"transient_repack", true}});
        if (!transient.transient_repack || motoai::config_json(transient, 0).at("cpu_workspace_reserve_bytes") != 81ULL * 1024 * 1024)
            throw std::runtime_error("Transient workspace was not explicitly budgeted");
#ifdef LLAMA_MOTOAI_TRANSIENT_MIN_ROWS
        if (motoai::config_json(transient, 0).at("transient_min_rows") != LLAMA_MOTOAI_TRANSIENT_MIN_ROWS)
            throw std::runtime_error("Transient row policy differs from compiled context");
#endif
        transient = config;
        transient.transient_repack = true;
        bool invalid_transient = false;
        try { runtime.load(argv[1], transient, runtime.ticket()); }
        catch (const std::invalid_argument &) { invalid_transient = true; }
        if (!invalid_transient || generate() != first) throw std::runtime_error("Invalid transient mode damaged existing model");
        auto system_info = std::string(llama_print_system_info());
        system_info.erase(std::remove_if(system_info.begin(), system_info.end(),
            [](unsigned char c) { return std::isspace(c); }), system_info.end());
        if (system_info.find("MATMUL_INT8=1") == std::string::npos) {
            transient.load_mode = LLAMA_LOAD_MODE_MMAP;
            invalid_transient = false;
            try { runtime.load(argv[1], transient, runtime.ticket()); }
            catch (const std::invalid_argument &) { invalid_transient = true; }
            if (!invalid_transient || generate() != first) throw std::runtime_error("Missing transient support silently fell back");
        }
        if (!motoai::parse_config(motoai::Json::object()).mmap_prefault) throw std::runtime_error("Prefault default changed");
        auto demand = config;
        demand.mmap_prefault = false;
        bool invalid_demand = false;
        try { runtime.load(argv[1], demand, runtime.ticket()); }
        catch (const std::invalid_argument &) { invalid_demand = true; }
        if (!invalid_demand || generate() != first) throw std::runtime_error("Invalid demand-paging mode damaged model");
        demand.load_mode = LLAMA_LOAD_MODE_MMAP;
#ifdef LLAMA_MOTOAI_MMAP_PREFAULT_CONTROL
        runtime.load(argv[1], demand, runtime.ticket());
        if (generate() != first || motoai::config_json(demand, 0).at("mmap_prefault") != false) {
            throw std::runtime_error("Demand paging changed greedy output or policy");
        }
#else
        invalid_demand = false;
        try { runtime.load(argv[1], demand, runtime.ticket()); }
        catch (const std::invalid_argument &) { invalid_demand = true; }
        if (!invalid_demand || generate() != first) throw std::runtime_error("Missing demand API silently fell back");
#endif
        bool invalid_weights = false;
        try { motoai::parse_config(motoai::Json{{"weight_buffers", "kleidiai"}}); }
        catch (const std::invalid_argument &) { invalid_weights = true; }
        if (!invalid_weights) throw std::runtime_error("Unknown weight policy accepted");
        if (motoai::parse_config(motoai::Json::object()).weight_buffers != motoai::WeightBuffers::Native ||
            motoai::config_json(config, 0).at("all_extra_buffers_disabled") != true) {
            throw std::runtime_error("Default no-extra-buffer policy changed");
        }
        auto invalid_repack = config;
        invalid_repack.weight_buffers = motoai::WeightBuffers::CpuRepack;
        invalid_repack.load_mode = LLAMA_LOAD_MODE_NONE;
        invalid_weights = false;
        try { runtime.load(argv[1], invalid_repack, runtime.ticket()); }
        catch (const std::invalid_argument &) { invalid_weights = true; }
        if (!invalid_weights || generate() != first) throw std::runtime_error("Invalid repack mode damaged existing model");
        const auto q4 = motoai::parse_config(motoai::Json{{"weight_buffers", "cpu_repack_q4"}});
        if (q4.weight_buffers != motoai::WeightBuffers::CpuRepackQ4 || motoai::config_json(q4, 0).at("repack_type_filter") != "q4_K") {
            throw std::runtime_error("Q4-only policy was not explicitly represented");
        }
        invalid_repack.weight_buffers = motoai::WeightBuffers::CpuRepackQ4;
        invalid_weights = false;
        try { runtime.load(argv[1], invalid_repack, runtime.ticket()); }
        catch (const std::invalid_argument &) { invalid_weights = true; }
        if (!invalid_weights || generate() != first) throw std::runtime_error("Invalid Q4 repack mode damaged existing model");
        if (std::string(llama_print_system_info()).find("MATMUL_INT8=1") == std::string::npos) {
            invalid_repack.load_mode = LLAMA_LOAD_MODE_MMAP;
            invalid_weights = false;
            try { runtime.load(argv[1], invalid_repack, runtime.ticket()); }
            catch (const std::exception &) { invalid_weights = true; }
            if (!invalid_weights || generate() != first) throw std::runtime_error("Unsupported Q4 CPU path fell back or damaged model");
        }
#ifndef LLAMA_MOTOAI_REPACK_FILTER
        invalid_repack.weight_buffers = motoai::WeightBuffers::CpuRepackQ6;
        invalid_repack.load_mode = LLAMA_LOAD_MODE_MMAP;
        invalid_weights = false;
        try { runtime.load(argv[1], invalid_repack, runtime.ticket()); }
        catch (const std::invalid_argument &) { invalid_weights = true; }
        if (!invalid_weights || generate() != first) throw std::runtime_error("Missing selective API fell back or damaged model");
#endif
        request.diagnostics = true;
        request.check_logits = true;
        int64_t last_elapsed = 0;
        int progress_events = 0;
        std::string diagnostic_output;
        const auto diagnostic = runtime.generate(request, runtime.ticket(), [&](const std::string & text) { diagnostic_output += text; }, {},
            [&](const motoai::Progress & progress) {
                if (progress.elapsed_us < last_elapsed || progress.completed < 0 || progress.completed > progress.total) {
                    throw std::runtime_error("Invalid progress event");
                }
                last_elapsed = progress.elapsed_us;
                ++progress_events;
            });
        if (diagnostic_output != first || diagnostic.logits_checks != 16 || diagnostic.first_top_ids.size() != 8 ||
            diagnostic.prefill_chunks.empty() || diagnostic.prefill_chunks.back().end != diagnostic.prompt_tokens || progress_events < 2) {
            throw std::runtime_error("Diagnostic mode changed output or lost coverage");
        }
        request.diagnostics = false;
        request.check_logits = false;
        request.score_continuation_ids = diagnostic.generated_ids;
        std::string forced_output;
        const auto scored = runtime.generate(request, runtime.ticket(), [&](const std::string & text) { forced_output += text; });
        if (forced_output != first || scored.token_nll.size() != 16 || scored.logits_checks != 16 ||
            motoai::result_json(scored).at("finish_reason") != "scored") {
            throw std::runtime_error("Teacher forcing changed the known greedy trajectory");
        }
        request.score_continuation_ids.clear();
        // A BOS-only raw prefix can have an empty rendered string and still needs its trace.
        auto bos_request = request;
        bos_request.raw_prompt.clear();
        bos_request.max_tokens = 1;
        const auto bos_trace = runtime.generate(bos_request, runtime.ticket(), {});
        if (!bos_trace.rendered_prompt.empty() || bos_trace.prompt_ids.empty() || !motoai::result_json(bos_trace).contains("trace")) {
            throw std::runtime_error("BOS-only trace was omitted");
        }
        if (generate() != first) throw std::runtime_error("Fresh request reused dirty state");
        const auto short_prompt = request.raw_prompt;
        request.raw_prompt = std::string(20000, 'a');
        bool overflow = false;
        try { generate(); } catch (const motoai::ContextOverflow &) { overflow = true; }
        if (!overflow) throw std::runtime_error("Overflow was silently accepted");
        request.raw_prompt = short_prompt;
        if (generate() != first) throw std::runtime_error("Overflow damaged state");
        const auto stale = runtime.ticket();
        runtime.cancel();
        bool cancelled = false;
        try { runtime.generate(request, stale, {}); } catch (const motoai::Cancelled &) { cancelled = true; }
        if (!cancelled) throw std::runtime_error("Queued cancellation was lost");
        cancelled = false;
        try {
            runtime.generate(request, runtime.ticket(), [&](const std::string &) { runtime.cancel(); });
        } catch (const motoai::Cancelled &) { cancelled = true; }
        if (!cancelled) throw std::runtime_error("Decode cancellation was lost");
        if (generate() != first) throw std::runtime_error("Cancellation damaged state");
        runtime.unload();
        runtime.unload();
        bool rejected = false;
        try { generate(); } catch (const std::runtime_error &) { rejected = true; }
        if (!rejected) throw std::runtime_error("Unloaded generation was accepted");
        runtime.load(argv[1], config, runtime.ticket());
        if (generate() != first) throw std::runtime_error("Reload changed greedy output");
        config.load_mode = LLAMA_LOAD_MODE_NONE;
        runtime.load(argv[1], config, runtime.ticket());
        if (generate() != first) throw std::runtime_error("Read/mmap modes changed fixture output");
        config.tensor_probe = true;
        runtime.load(argv[1], config, runtime.ticket());
        std::string observed_output;
        const auto observed = runtime.generate(request, runtime.ticket(), [&](const std::string & text) { observed_output += text; });
        if (observed_output != first || observed.node_probes.empty() ||
            !std::any_of(observed.node_probes.begin(), observed.node_probes.end(), [](const motoai::NodeProbe & probe) {
                return probe.status == "captured" && probe.finite && !probe.fingerprint.empty();
            })) throw std::runtime_error("Tensor observation changed the fixture or captured no finite node");
        std::cout << "Lifecycle, overflow, queued/decode cancellation and reload: PASS\n" << first << '\n';
        return 0;
    } catch (const std::exception & error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
