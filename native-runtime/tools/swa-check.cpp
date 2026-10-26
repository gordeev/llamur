// Rejected experimental route: see bench/results/compact-swa-host-rejected-20261026/.
// Supported Runtime now refuses compact SWA; reproduce with the saved experimental sources.
#include "protocol.h"
#include <algorithm>
#include <cmath>
#include <fstream>
#include <iostream>
using namespace motoai;
int main(int argc,char ** argv) {
    Json report{{"passed",false},{"scope","Host full-vs-compact SWA teacher-forcing quality only; original GGUF/F16 KV, no phone/speed/profile acceptance"}};
    try {
        if(argc!=4)throw std::invalid_argument("MODEL public-fixture numeric-gates required");
        std::ifstream fixture_file(argv[2]),gate_file(argv[3]);const auto spec=Json::parse(fixture_file),gates=Json::parse(gate_file);
        auto request=parse_request(spec.at("request"));request.trace=true;request.check_logits=true;
        if(request.score_continuation_ids.empty())throw std::invalid_argument("Public teacher-forcing fixture required");
        Config config;config.context=4096;config.threads=4;config.load_mode=LLAMA_LOAD_MODE_MMAP;
        Runtime::initialize();Runtime runtime;
        Result results[2];std::string outputs[2];
        for(int mode=0;mode<2;++mode) {
            config.swa_full=mode==0;
            runtime.load(argv[1],config,runtime.ticket());
            results[mode]=runtime.generate(request,runtime.ticket(),[&](const std::string & text){outputs[mode]+=text;});
            report[mode==0?"full":"compact"]={{"result",result_json(results[mode])},{"allocations",memory_json(runtime.memory())}};
        }
        const auto & a=results[0];const auto & b=results[1];
        if(a.prompt_ids!=b.prompt_ids || a.generated_ids!=b.generated_ids || outputs[0]!=outputs[1] || a.token_nll.size()!=b.token_nll.size() || a.token_nll.empty())
            throw std::runtime_error("SWA trace/continuation coverage differs");
        double sum=0,maximum=0,logits=0;auto top_a=a.first_top_ids,top_b=b.first_top_ids;
        std::sort(top_a.begin(),top_a.end());std::sort(top_b.begin(),top_b.end());const bool same_top=top_a==top_b;
        for(size_t i=0;i<a.token_nll.size();++i){const auto error=std::abs(a.token_nll[i]-b.token_nll[i]);sum+=error;maximum=std::max(maximum,error);}
        for(size_t i=0;i<a.first_top_ids.size();++i) {
            const auto found=std::find(b.first_top_ids.begin(),b.first_top_ids.end(),a.first_top_ids[i]);
            if(found!=b.first_top_ids.end())logits=std::max(logits,double(std::abs(a.first_top_logits[i]-b.first_top_logits[found-b.first_top_ids.begin()])));
        }
        report.update({{"mean_absolute_token_nll_error",sum/a.token_nll.size()},{"maximum_absolute_token_nll_error",maximum},
            {"first_top8_set_matches",same_top},{"first_top8_max_shared_logit_absolute_error",logits}});
        report["passed"]=sum/a.token_nll.size()<=gates.at("mean_absolute_token_nll_error_max").get<double>() &&
            maximum<=gates.at("maximum_absolute_token_nll_error_max").get<double>() &&
            (!gates.at("first_top8_set_must_match").get<bool>() || same_top) && logits<=gates.at("first_top8_max_shared_logit_absolute_error").get<double>();
    }catch(const std::exception & error){report["error"]=error.what();}
    std::cout<<report.dump(2)<<'\n';return report.at("passed").get<bool>()?0:1;
}
