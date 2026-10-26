#pragma once
#include <algorithm>
#include <string>
#include <vector>

namespace motoai {
// Hold only a trailing incomplete UTF-8 sequence. Completed invalid byte
// sequences are replaced by the existing JSON boundary, never repeatedly reparsed.
inline std::string utf8_complete_prefix(const std::string & text) {
    if (text.empty()) return text;
    size_t start=text.size()-1;
    while(start && (static_cast<unsigned char>(text[start])&0xc0)==0x80) --start;
    const unsigned char lead=text[start];
    const size_t expected=lead>=0xc2 && lead<=0xdf?2:lead>=0xe0 && lead<=0xef?3:lead>=0xf0 && lead<=0xf4?4:1;
    return text.size()-start<expected?text.substr(0,start):text;
}
struct StoppedText { std::string text; bool stopped=false; };
inline StoppedText stop_text(const std::string & content,const std::vector<std::string> & stops,bool final) {
    size_t found=std::string::npos;
    for(const auto & stop:stops) found=std::min(found,content.find(stop));
    if(found!=std::string::npos)return {content.substr(0,found),true};
    size_t hold=0;
    if(!final)for(const auto & stop:stops)for(size_t n=1;n<stop.size() && n<=content.size();++n)
        if(content.compare(content.size()-n,n,stop,0,n)==0)hold=std::max(hold,n);
    return {content.substr(0,content.size()-hold),false};
}
}
