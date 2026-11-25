#include "protocol.h"
#include "ggml-alloc.h"
#include <algorithm>
#include <cmath>
#include <iostream>
#include <limits>

using namespace motoai;

// Independently evaluate represented K/V with the original F32 Q. Indexed rows
// must select both K and V; padding must never become a cache row or a weight.
static Json attention_case(ggml_backend_t backend, ggml_type type, bool indexed, int queries) {
    constexpr int dim=64, cells=19, heads=2, columns=13;
    const int slices=indexed?2:1, keys=indexed?columns:cells;
    std::unique_ptr<ggml_context,decltype(&ggml_free)> context(
        ggml_init({ggml_tensor_overhead()*16+ggml_graph_overhead_custom(32,false),nullptr,true}),ggml_free);
    if(!context)throw std::runtime_error("Attention context allocation failed");
    auto * q=ggml_new_tensor_4d(context.get(),GGML_TYPE_F32,dim,queries,heads,slices);
    auto * k=ggml_new_tensor_4d(context.get(),type,dim,cells,1,1);
    auto * v=ggml_new_tensor_4d(context.get(),type,dim,cells,1,1);
    auto * mask=ggml_new_tensor_4d(context.get(),GGML_TYPE_F16,keys,queries,1,slices);
    auto * rows=indexed?ggml_new_tensor_2d(context.get(),GGML_TYPE_I32,keys,slices):nullptr;
    const float scale=1.0f/std::sqrt(float(dim));
    auto * out=indexed?ggml_flash_attn_ext_rows(context.get(),q,k,v,mask,rows,scale,0,0):
        ggml_flash_attn_ext(context.get(),q,k,v,mask,scale,0,0);
    ggml_prec_set_acc(out,GGML_PREC_F32);
    auto * graph=ggml_new_graph_custom(context.get(),32,false);
    ggml_build_forward_expand(graph,out);
    std::unique_ptr<ggml_backend_buffer,decltype(&ggml_backend_buffer_free)> buffer(
        ggml_backend_alloc_ctx_tensors(context.get(),backend),ggml_backend_buffer_free);
    if(!buffer || !ggml_backend_supports_op(backend,out))throw std::runtime_error("Attention CPU graph unavailable");
    std::vector<float> query(ggml_nelements(q)), key(ggml_nelements(k)), value(ggml_nelements(v));
    for(size_t i=0;i<query.size();++i)query[i]=2.1f*std::sin(float(i*17+3)*0.137f)+0.00013f;
    for(size_t i=0;i<key.size();++i)key[i]=1.7f*std::cos(float(i*11+7)*0.111f);
    for(size_t i=0;i<value.size();++i)value[i]=0.9f*std::sin(float(i*7+5)*0.173f);
    ggml_backend_tensor_set(q,query.data(),0,query.size()*sizeof(float));
    auto upload=[&](ggml_tensor * tensor,std::vector<float> & represented) {
        if(type==GGML_TYPE_F32)ggml_backend_tensor_set(tensor,represented.data(),0,represented.size()*sizeof(float));
        else {
            std::vector<ggml_fp16_t> encoded(represented.size());
            ggml_fp32_to_fp16_row(represented.data(),encoded.data(),encoded.size());
            ggml_backend_tensor_set(tensor,encoded.data(),0,encoded.size()*sizeof(ggml_fp16_t));
            ggml_fp16_to_fp32_row(encoded.data(),represented.data(),encoded.size());
        }
    };
    upload(k,key);upload(v,value);
    std::vector<int32_t> mapping(keys*slices);
    for(int s=0;s<slices;++s)for(int c=0;c<keys;++c)
        mapping[s*keys+c]=indexed?(c==3 || c==keys-1?-1:(c*7+s*5+4)%cells):c;
    if(rows)ggml_backend_tensor_set(rows,mapping.data(),0,mapping.size()*sizeof(int32_t));
    std::vector<ggml_fp16_t> masks(ggml_nelements(mask));
    for(int s=0;s<slices;++s)for(int n=0;n<queries;++n)for(int c=0;c<keys;++c) {
        const float bias=mapping[s*keys+c]<0 || (c+n+s)%7==0?-std::numeric_limits<float>::infinity():-0.0625f*((c+2*n)%5);
        masks[(s*queries+n)*keys+c]=ggml_fp32_to_fp16(bias);
    }
    ggml_backend_tensor_set(mask,masks.data(),0,masks.size()*sizeof(ggml_fp16_t));
    if(ggml_backend_graph_compute(backend,graph)!=GGML_STATUS_SUCCESS)throw std::runtime_error("Attention evaluation failed");
    std::vector<float> actual(ggml_nelements(out));
    ggml_backend_tensor_get(out,actual.data(),0,actual.size()*sizeof(float));
    double max_error=0;
    bool finite=true;
    for(int s=0;s<slices;++s)for(int h=0;h<heads;++h)for(int n=0;n<queries;++n) {
        std::vector<double> logits(keys,-std::numeric_limits<double>::infinity());
        double maximum=-std::numeric_limits<double>::infinity();
        for(int c=0;c<keys;++c) {
            const auto row=mapping[s*keys+c];
            const auto bias=ggml_fp16_to_fp32(masks[(s*queries+n)*keys+c]);
            if(row<0 || !std::isfinite(bias))continue;
            double dot=0;
            for(int d=0;d<dim;++d)dot+=double(query[((s*heads+h)*queries+n)*dim+d])*key[row*dim+d];
            logits[c]=dot*scale+bias;maximum=std::max(maximum,logits[c]);
        }
        double denominator=0;
        for(auto & logit:logits){logit=std::exp(logit-maximum);denominator+=logit;}
        for(int d=0;d<dim;++d) {
            double reference=0;
            for(int c=0;c<keys;++c)if(mapping[s*keys+c]>=0)reference+=logits[c]*value[mapping[s*keys+c]*dim+d];
            reference/=denominator;
            const auto result=actual[((s*queries+n)*heads+h)*dim+d];
            finite=finite && std::isfinite(result);
            max_error=std::max(max_error,std::abs(double(result)-reference));
        }
    }
    return Json{{"kv_type",ggml_type_name(type)},{"indexed",indexed},{"queries",queries},
        {"negative_padding",indexed},{"reference","double softmax, represented KV, original F32 Q"},
        {"max_absolute_error",max_error},{"limit",2e-6},{"passed",finite && max_error<=2e-6}};
}

int main() {
    Json report{{"passed",false},{"cases",Json::array()}};
    try {
        Runtime::initialize();
        std::unique_ptr<ggml_backend,decltype(&ggml_backend_free)> backend(
            ggml_backend_init_by_type(GGML_BACKEND_DEVICE_TYPE_CPU,nullptr),ggml_backend_free);
        if(!backend)throw std::runtime_error("No CPU backend");
        auto reg=ggml_backend_dev_backend_reg(ggml_backend_get_device(backend.get()));
        auto set_threads=reinterpret_cast<ggml_backend_set_n_threads_t>(ggml_backend_reg_get_proc_address(reg,"ggml_backend_set_n_threads"));
        bool passed=true;
        for(int threads:{1,4}) {
            if(set_threads)set_threads(backend.get(),threads);
            for(auto type:{GGML_TYPE_F16,GGML_TYPE_F32})for(bool indexed:{false,true})for(int queries:{1,8}) {
                auto item=attention_case(backend.get(),type,indexed,queries);item["threads"]=threads;
                passed=passed && item.at("passed").get<bool>();report["cases"].push_back(std::move(item));
            }
        }
        report["passed"]=passed;
    }catch(const std::exception & error){report["error"]=error.what();}
    std::cout<<report.dump(2)<<'\n';return report.at("passed").get<bool>()?0:1;
}
