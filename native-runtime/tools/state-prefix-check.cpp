#include "protocol.h"
#include "common.h"
#include "llama-ext.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <fstream>
#include <iostream>
#include <memory>
using namespace motoai;
static int64_t now_us() { return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count(); }
int main(int argc, char ** argv) {
    Json report{{"passed", false}, {"scope", "Host-only CPU full sequence-state preservation at a512-token boundary; no production cache, chat/GPU/phone/speed acceptance"}};
    try {
        if (argc!=3 && argc!=4) throw std::invalid_argument("MODEL public-teacher-force-fixture.json [--whole-context|--keep-layout] required");
        const bool whole=argc==4 && std::string(argv[3])=="--whole-context";
        const bool keep=argc==4 && std::string(argv[3])=="--keep-layout";
        if(argc==4 && !whole && !keep) throw std::invalid_argument("Unknown state scope");
        report["state_scope"]=whole?"whole_context":(keep?"sequence_0_keep_layout":"sequence_0");
        llama_state_seq_flags seqflags=0;
#ifdef LLAMA_MOTOAI_KEEP_KV_LAYOUT
        if(keep) seqflags=LLAMA_STATE_SEQ_FLAGS_KEEP_KV_LAYOUT;
#else
        if(keep) throw std::invalid_argument("Exact-layout API not compiled");
#endif
        Runtime::initialize();
        std::ifstream file(argv[2]); const auto spec=Json::parse(file);
        const auto request=parse_request(spec.at("request"));
        if (request.score_continuation_ids.empty()) throw std::invalid_argument("Teacher-forcing fixture required");
        auto mp=llama_model_default_params(); mp.n_gpu_layers=0; mp.use_extra_bufts=false;
        ggml_backend_dev_t no_gpu[]={nullptr}; mp.devices=no_gpu; mp.load_mode=LLAMA_LOAD_MODE_MMAP;
        std::unique_ptr<llama_model,decltype(&llama_model_free)> model(llama_model_load_from_file(argv[1],mp),llama_model_free);
        if (!model) throw std::runtime_error("Model load failed");
        auto templates=common_chat_templates_init(model.get(),"");
        const auto prepared=prepare_prompt(model.get(),templates.get(),request,4096);
        const auto & tokens=prepared.tokens;
        const size_t boundary=(tokens.size()-1)/512*512;
        if (boundary<512) throw std::invalid_argument("Need a nonempty common full512-token chunk");
        auto cp=llama_context_default_params();cp.n_ctx=4096;cp.n_batch=512;cp.n_ubatch=128;
        cp.n_threads=4;cp.n_threads_batch=4;cp.type_k=GGML_TYPE_F16;cp.type_v=GGML_TYPE_F16;cp.no_perf=false;
        std::unique_ptr<llama_context,decltype(&llama_free)> context(llama_init_from_model(model.get(),cp),llama_free);
        if (!context) throw std::runtime_error("Context allocation failed");
        common_batch batch(context.get());std::vector<uint8_t> state;
        int64_t capture_us=0; std::vector<std::vector<double>> losses;
        std::vector<std::vector<float>> probes;
        auto process=[&]() {
            if (llama_process(context.get(),LLAMA_PROCESS_TYPE_DECODE,batch.get())!=0) throw std::runtime_error("Evaluation failed");
        };
        for (int trial=0;trial<3;++trial) {
            llama_memory_clear(llama_get_memory(context.get()),true);
            int64_t restore_us=0;size_t begin=0;
            if (trial==1) {
#ifdef LLAMA_MOTOAI_KEEP_KV_LAYOUT
                if (keep) {
                    auto reject = [&](const char * label, const uint8_t * data, size_t length, llama_state_seq_flags flags) {
                        if (llama_state_seq_set_data_ext(context.get(), data, length, 0, flags) != 0)
                            throw std::runtime_error(std::string("Invalid state accepted: ")+label);
                        llama_memory_clear(llama_get_memory(context.get()), true);
                        report["negative_controls"].push_back(label);
                    };
                    reject("truncated_header", state.data(), 3, seqflags);
                    reject("truncated_payload", state.data(), state.size()-1, seqflags);
                    reject("wrong_restore_flag", state.data(), state.size(), 0);
                    reject("partial_combination", state.data(), state.size(), seqflags|LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
                    reject("on_device_combination", state.data(), state.size(), seqflags|LLAMA_STATE_SEQ_FLAGS_ON_DEVICE);
                    if (llama_state_seq_get_size_ext(context.get(), 1, seqflags) != 0 ||
                        llama_state_seq_get_size_ext(context.get(), 0, seqflags|LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) != 0)
                        throw std::runtime_error("Invalid capture geometry/flags accepted");
                    auto invalid=state; invalid[0]^=1;
                    reject("invalid_magic", invalid.data(), invalid.size(), seqflags);
                    report["negative_controls"].push_back("invalid_capture_flags_and_sequence");
                    const auto compact_size=llama_state_seq_get_size_ext(context.get(),0,0);
                    std::vector<uint8_t> compact(compact_size);
                    if (!compact_size || llama_state_seq_get_data_ext(context.get(),compact.data(),compact.size(),0,0)!=compact_size)
                        throw std::runtime_error("Legacy control capture failed");
                    reject("legacy_with_layout_flag", compact.data(), compact.size(), seqflags);
                    auto multi_params=cp; multi_params.n_seq_max=2;
                    std::unique_ptr<llama_context,decltype(&llama_free)> multi(llama_init_from_model(model.get(),multi_params),llama_free);
                    if (!multi || llama_state_seq_get_size_ext(multi.get(),0,seqflags)!=0)
                        throw std::runtime_error("Multisequence exact-layout capture accepted");
                    report["negative_controls"].push_back("multiple_sequences");
                }
#endif
                const auto start=now_us();
                const auto restored=whole?llama_state_set_data(context.get(),state.data(),state.size()):
                    llama_state_seq_set_data_ext(context.get(),state.data(),state.size(),0,seqflags);
                restore_us=now_us()-start;
                if (restored!=state.size()) throw std::runtime_error("State restore did not consume exact full snapshot");
                const auto again_size=whole?llama_state_get_size(context.get()):llama_state_seq_get_size_ext(context.get(),0,seqflags);
                if(again_size>512u*1024*1024) throw std::runtime_error("Roundtrip snapshot exceeds research budget");
                std::vector<uint8_t> again(again_size);
                const auto again_written=whole?llama_state_get_data(context.get(),again.data(),again.size()):
                    llama_state_seq_get_data_ext(context.get(),again.data(),again.size(),0,seqflags);
                report["roundtrip_snapshot_size"]=again_size;
                report["roundtrip_snapshot_exact"]=again_written==again.size() && state==again;
                size_t first_difference=0;
                while(first_difference<std::min(state.size(),again.size()) && state[first_difference]==again[first_difference])++first_difference;
                report["roundtrip_first_byte_difference"]=first_difference;
                begin=boundary;
            }
            const auto start=now_us();
            for (size_t offset=begin;offset<tokens.size();offset+=512) {
                batch.clear();const auto count=std::min<size_t>(512,tokens.size()-offset);
                for (size_t i=0;i<count;++i) batch.add(tokens[offset+i],offset+i,0,offset+i+1==tokens.size());
                process();
                if (trial==0 && offset+count==boundary) {
                    const auto before=now_us();const auto bytes=whole?llama_state_get_size(context.get()):llama_state_seq_get_size_ext(context.get(),0,seqflags);
                    if (!bytes || bytes>512u*1024*1024) throw std::runtime_error("Snapshot missing or exceeds512MiB research budget");
                    state.resize(bytes);
                    const auto captured=whole?llama_state_get_data(context.get(),state.data(),state.size()):
                        llama_state_seq_get_data_ext(context.get(),state.data(),state.size(),0,seqflags);
                    if (captured!=bytes) throw std::runtime_error("Incomplete state capture");
                    capture_us=now_us()-before;
                }
            }
            const auto prefill_us=now_us()-start;
            std::vector<double> nll;std::vector<float> probe;
            const auto * vocab=llama_model_get_vocab(model.get());const int size=llama_vocab_n_tokens(vocab);
            for (size_t i=0;i<request.score_continuation_ids.size();++i) {
                const auto * logits=llama_get_logits_ith(context.get(),-1);
                if (!logits) throw std::runtime_error("Missing logits");
                double maximum=-INFINITY;
                for (int j=0;j<size;++j) {
                    if (!std::isfinite(logits[j])) throw std::runtime_error("Nonfinite output logit");
                    maximum=std::max(maximum,double(logits[j]));
                }
                double sum=0;for(int j=0;j<size;++j)sum+=std::exp(double(logits[j])-maximum);
                nll.push_back(maximum+std::log(sum)-logits[request.score_continuation_ids[i]]);
                // All full raw logits, not a selected top-k view, are compared.
                probe.insert(probe.end(),logits,logits+size);
                if(i+1<request.score_continuation_ids.size()) {
                    batch.clear();batch.add(request.score_continuation_ids[i],tokens.size()+i,0,true);process();
                }
            }
            losses.push_back(std::move(nll));probes.push_back(std::move(probe));
            report["trials"].push_back(Json{{"mode",trial==1?"restored":"full_reset"},{"prompt_tokens",tokens.size()},
                {"reused_tokens",begin},{"prefill_us",prefill_us},{"restore_us",restore_us},{"scored_tokens",request.score_continuation_ids.size()}});
        }
        double max_nll=0,max_logit=0;
        for (size_t trial=1;trial<losses.size();++trial) {
            double trial_nll=0,trial_logit=0;
            for(size_t i=0;i<losses[0].size();++i)trial_nll=std::max(trial_nll,std::abs(losses[0][i]-losses[trial][i]));
            for(size_t i=0;i<probes[0].size();++i)trial_logit=std::max(trial_logit,double(std::abs(probes[0][i]-probes[trial][i])));
            report["trials"][trial]["maximum_nll_difference_from_first"]=trial_nll;
            report["trials"][trial]["maximum_raw_logit_difference_from_first"]=trial_logit;
            max_nll=std::max(max_nll,trial_nll);max_logit=std::max(max_logit,trial_logit);
        }
        report["snapshot_bytes"]=state.size();report["capture_us"]=capture_us;
        report["maximum_nll_difference"]=max_nll;report["maximum_raw_logit_difference"]=max_logit;
        report["passed"]=max_nll==0 && max_logit==0;
    } catch(const std::exception & error) {report["error"]=error.what();}
    std::cout<<report.dump(2)<<'\n';return report.at("passed").get<bool>()?0:1;
}
