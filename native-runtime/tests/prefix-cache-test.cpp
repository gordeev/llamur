#include "protocol.h"
#include <fstream>
#include <iostream>
using namespace motoai;
int main(int argc,char ** argv) {
    Json report{{"passed",false},{"scope","Host runtime prefix-cache experiment; not phone speed/profile acceptance"}};
    try {
        if(argc<3 || argc>5)throw std::invalid_argument("MODEL public-teacher-force-fixture [cache_mib] required");
        const uint32_t budget=argc>=4?std::stoul(argv[3]):128;
        const bool compact=argc==5 && std::string(argv[4])=="--compact-swa";
        if(argc==5 && !compact)throw std::invalid_argument("Unknown mode");
        if(budget<64 || budget>256)throw std::invalid_argument("Cache budget out of bounds");
        report["cache_budget_mib"]=budget;
        std::ifstream source(argv[2]);const auto fixture=Json::parse(source);
        auto request=parse_request(fixture.at("request"));
        if(request.score_continuation_ids.empty())throw std::invalid_argument("Scored public fixture required");
        request.trace=true;request.check_logits=true;
        Config config;config.context=4096;config.threads=4;config.batch=512;config.micro_batch=128;
        config.load_mode=LLAMA_LOAD_MODE_MMAP;config.swa_full=!compact;
        report["swa_full"]=config.swa_full;
        Runtime::initialize();Runtime runtime;
        runtime.load(argv[1],config,runtime.ticket());
        auto run=[&](const char * label,const Request & item) {
            std::string output;
            auto result=runtime.generate(item,runtime.ticket(),[&](const std::string & text){output+=text;});
            report["trials"].push_back(Json{{"name",label},{"result",result_json(result)}});
            return std::make_pair(std::move(result),std::move(output));
        };
        auto equal=[](const auto & a,const auto & b) {
            if(a.second!=b.second || a.first.prompt_ids!=b.first.prompt_ids || a.first.generated_ids!=b.first.generated_ids ||
               a.first.token_nll!=b.first.token_nll || a.first.first_top_ids!=b.first.first_top_ids ||
               a.first.first_top_logits!=b.first.first_top_logits || a.first.eog!=b.first.eog)
                throw std::runtime_error("Cached/runtime reference numeric or output mismatch");
        };
        const auto baseline=run("cache_disabled",request);
        config.prefix_cache_mib=budget;runtime.load(argv[1],config,runtime.ticket());
        request.cache_session="host:public-A";
        const auto miss=run("first_miss",request);equal(baseline,miss);
        if(miss.first.reused_prompt_tokens || !miss.first.prefix_cache_bytes || miss.first.prefix_cache_bytes>uint64_t(budget)*1024*1024)
            throw std::runtime_error("Missing/budget-invalid initial cache snapshot");
        const auto hit=run("exact_prefix_hit",request);equal(baseline,hit);
        if(hit.first.reused_prompt_tokens<512 || hit.first.cache_status!="hit")throw std::runtime_error("No actual cache hit");
        request.cache_session="host:public-B";
        const auto isolated=run("different_session_miss",request);equal(baseline,isolated);
        if(isolated.first.reused_prompt_tokens)throw std::runtime_error("Cross-session state reuse");
        // Same session but changed first token must replay all input.
        auto changed=request;changed.raw_prompt="Changed notes:\n"+changed.raw_prompt;
        const auto changed_miss=run("changed_prefix_miss",changed);
        if(changed_miss.first.reused_prompt_tokens)throw std::runtime_error("Changed prefix reused");
        changed.cache_session.clear();const auto changed_fresh=run("changed_prefix_fresh",changed);equal(changed_miss,changed_fresh);
        // A stale ticket cancels before reuse and must not damage the next run.
        const auto stale=runtime.ticket();runtime.cancel();bool cancelled=false;
        try {runtime.generate(request,stale,[](const std::string &){});}catch(const Cancelled &){cancelled=true;}
        if(!cancelled)throw std::runtime_error("Stale-ticket cancellation lost");
        const auto recovery=run("cancel_recovery",request);equal(baseline,recovery);
        const auto recovery_hit=run("cancel_recovery_hit",request);equal(baseline,recovery_hit);
        if(recovery_hit.first.reused_prompt_tokens<512)throw std::runtime_error("Recovery did not restore a cache hit");
        runtime.unload();runtime.load(argv[1],config,runtime.ticket());
        const auto after_unload=run("unload_clears_state",request);equal(baseline,after_unload);
        if(after_unload.first.reused_prompt_tokens)throw std::runtime_error("Unloaded model retained a cache");
        report["passed"]=true;
    } catch(const std::exception & error) {report["error"]=error.what();}
    std::cout<<report.dump(2)<<'\n';return report.at("passed").get<bool>()?0:1;
}
