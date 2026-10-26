#pragma once
#include <cstdio>
#include <memory>
#include <stdexcept>
#include <string>
#if defined(__ANDROID__)
#include <cerrno>
#include <cstring>
#include <limits>
#include <unistd.h>
#endif

namespace motoai {
// A granted Android FUSE descriptor may be readable/mappable while reopening its
// /proc link is denied. Duplicate the capability itself; never resolve its path.
inline bool descriptor_model_path(const std::string & path) {
#if defined(__ANDROID__)
    constexpr const char * prefix="/proc/self/fd/";
    return path.compare(0,14,prefix)==0;
#else
    (void)path;return false;
#endif
}
using ModelFile=std::unique_ptr<FILE,decltype(&fclose)>;
inline ModelFile open_model_file(const std::string & path) {
    FILE * stream=nullptr;
#if defined(__ANDROID__)
    if(descriptor_model_path(path)) {
        const auto digits=path.substr(14);
        if(digits.empty() || digits.size()>10 || digits.find_first_not_of("0123456789")!=std::string::npos)
            throw std::invalid_argument("Invalid model descriptor");
        const auto value=std::stoll(digits);
        if(value>std::numeric_limits<int>::max())throw std::invalid_argument("Invalid model descriptor");
        const int fd=dup(static_cast<int>(value));
        if(fd<0)throw std::runtime_error("Cannot duplicate model descriptor");
        stream=fdopen(fd,"rb");
        if(!stream)close(fd);
    } else
#endif
    stream=fopen(path.c_str(),"rb");
    ModelFile result(stream,fclose);
    if(!result || fseeko(result.get(),0,SEEK_SET)!=0)throw std::runtime_error("Cannot read seekable model file");
    return result;
}
}
