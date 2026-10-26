#include "protocol.h"
#include "response-stream.h"
#include "common.h"
#include <fstream>
#include <iostream>
using namespace motoai;
int main(int argc,char ** argv) {
    try {
        if(argc!=4)throw std::invalid_argument("MODEL request.json result.json required");
        std::ifstream request_file(argv[2]),result_file(argv[3]);const auto spec=Json::parse(request_file),record=Json::parse(result_file);
        auto request=parse_request(spec.at("request"));request.parse_response=true;
        auto params=llama_model_default_params();params.vocab_only=true;params.n_gpu_layers=0;
        std::unique_ptr<llama_model,decltype(&llama_model_free)> model(llama_model_load_from_file(argv[1],params),llama_model_free);
        if(!model)throw std::runtime_error("Vocabulary load failed");
        const auto * vocab=llama_model_get_vocab(model.get());auto templates=common_chat_templates_init(model.get(),"");
        const auto prepared=prepare_prompt(model.get(),templates.get(),request,spec.at("config").value("context",4096));
        const auto & result=record.at("result");common_chat_input input;std::string content,reasoning;
        auto accept=[&](common_chat_msg m,bool final) {
            if(!final){m.content=utf8_complete_prefix(m.content);m.reasoning_content=utf8_complete_prefix(m.reasoning_content);}
            if(m.content.compare(0,content.size(),content)!=0 || m.reasoning_content.compare(0,reasoning.size(),reasoning)!=0)
                throw std::runtime_error("Parser revised emitted prefix");
            content=m.content;reasoning=m.reasoning_content;
        };
        for(const auto & value:result.at("trace").at("generated_ids")) {
            const auto id=value.get<llama_token>();input.append(common_token_to_piece(vocab,id,true),id);
            accept(common_chat_parse(input,true,prepared.parser),false);
        }
        accept(common_chat_parse(input,result.at("finish_reason")!="eog",prepared.parser),true);
        const auto expected=result.at("assistant");
        if(content!=expected.at("content") || reasoning!=expected.at("reasoning_content"))throw std::runtime_error("Replay differs from final structured result");
        std::cout<<Json{{"passed",true},{"content_bytes",content.size()},{"reasoning_bytes",reasoning.size()},{"token_boundaries",result.at("trace").at("generated_ids").size()}}.dump()<<'\n';
    }catch(const std::exception & e){std::cout<<Json{{"passed",false},{"error",e.what()}}.dump()<<'\n';return 1;}
}
