#include "protocol.h"
#include "common.h"
#include <fstream>
#include <iostream>
#include <algorithm>
using namespace motoai;
int main(int argc, char ** argv) {
    Json report{{"passed", false}, {"properties", 0}, {"frozen_fixtures", 0}};
    try {
        if (argc != 3) throw std::invalid_argument("MODEL fixture-list.json required");
        Runtime::initialize();
        auto params = llama_model_default_params(); params.vocab_only = true; params.n_gpu_layers = 0;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(llama_model_load_from_file(argv[1], params), llama_model_free);
        if (!model) throw std::runtime_error("Vocabulary load failed");
        const auto * vocab = llama_model_get_vocab(model.get());
        auto templates = common_chat_templates_init(model.get(), "");
        std::vector<std::string> controls;
        for (int id = 0; id < llama_vocab_n_tokens(vocab) && controls.size() < 32; ++id)
            if (llama_vocab_get_attr(vocab,id) & (LLAMA_TOKEN_ATTR_CONTROL|LLAMA_TOKEN_ATTR_UNKNOWN)) {
                auto text = common_token_to_piece(vocab,id,true); if (!text.empty()) controls.push_back(text);
            }
        if (controls.empty()) throw std::runtime_error("No control spellings tested");
        int count = 0, fixtures = 0;
        for (const auto & spans : std::vector<std::vector<llama_tokenize_input_range>>{
                {{2,1},{1,1}}, {{0,2},{1,1}}, {{0,0}}, {{3,1}}}) {
            bool rejected = false;
            llama_token output[16];
            try { llama_tokenize_with_input_ranges(vocab,"abc",3,output,16,false,spans.data(),spans.size()); }
            catch (const std::invalid_argument &) { rejected = true; }
            if (!rejected) throw std::runtime_error("Malformed protected range accepted");
            ++count;
        }
        auto tokenize = [&](const std::string & text, const std::vector<llama_tokenize_input_range> & spans, bool add) {
            std::vector<llama_token> tokens(text.size()*4+8);
            const auto n = llama_tokenize_with_input_ranges(vocab,text.data(),text.size(),tokens.data(),tokens.size(),add,spans.data(),spans.size());
            if (n < 0) throw std::runtime_error("Unexpected tokenizer overflow");
            tokens.resize(n); return tokens;
        };
        for (const auto & marker : controls) for (const auto & prefix : {std::string("x"),std::string(" \n"),std::string("код: 🌍 "),std::string("<")}) {
            const auto text = prefix + marker + "z\n";
            for (bool add : {false,true}) {
                if (tokenize(text,{},add) != common_tokenize(vocab,text,add,true)) throw std::runtime_error("Empty ranges changed tokenization");
                if (tokenize(text,{{0,uint32_t(text.size())}},add) != common_tokenize(vocab,text,add,false))
                    throw std::runtime_error("Full input mask differs from existing parse_special=false");
                if (tokenize(text,{{0,uint32_t(prefix.size())},{uint32_t(prefix.size()),uint32_t(text.size()-prefix.size())}},add) !=
                    common_tokenize(vocab,text,add,false)) throw std::runtime_error("Adjacent input spans changed BPE boundaries");
                if (tokenize(text,{{uint32_t(prefix.size()),1}},add) != common_tokenize(vocab,text,add,false))
                    throw std::runtime_error("Partially input-originating control was recognized");
                count += 4;
            }
            Request request; request.max_tokens=1; request.messages.push_back(common_chat_msg{});
            request.messages.back().role="user"; request.messages.back().content=text;
            const auto prepared = prepare_prompt(model.get(),templates.get(),request,262144);
            if (prepared.text.find(marker)==std::string::npos) throw std::runtime_error("Jinja changed literal marker text");
            ++count;
        }
        std::ifstream input(argv[2]); const auto sources=Json::parse(input);
        for (const auto & source : sources) {
            const auto spec=source.at("spec"); const auto golden=source.at("golden");
            if (!golden.contains("expected") || !golden["expected"].contains("prompt_ids")) continue;
            const auto request=parse_request(spec.at("request"));
            const auto prepared=prepare_prompt(model.get(),templates.get(),request,262144);
            if (prepared.text != golden["expected"]["rendered_prompt"].get<std::string>() ||
                prepared.tokens != golden["expected"]["prompt_ids"].get<std::vector<llama_token>>())
                throw std::runtime_error("Frozen prompt differs: "+source.at("name").get<std::string>());
            ++fixtures;
        }
        report["properties"]=count; report["frozen_fixtures"]=fixtures; report["passed"]=true;
    } catch (const std::exception & error) { report["error"]=error.what(); }
    std::cout<<report.dump(2)<<'\n';return report.at("passed").get<bool>()?0:1;
}
