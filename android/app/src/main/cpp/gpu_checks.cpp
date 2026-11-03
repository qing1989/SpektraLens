// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
// Debug instrumentation of actual Vulkan kernels; never used by the editor.
#include "gpu/gpu.hpp"
#include "pipeline/tiling.hpp"
#include "spektrafilm/spk_engine.h"
#include "json.hpp"
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {
using namespace spk;
struct Env {
    std::string key,old;bool had;
    Env(const char* k,const char* value):key(k),had(std::getenv(k)!=nullptr){if(had)old=std::getenv(k);if(value)setenv(k,value,1);else unsetenv(k);}
    ~Env(){if(had)setenv(key.c_str(),old.c_str(),1);else unsetenv(key.c_str());}
};
void require(bool ok,const std::string& message){if(!ok)throw std::runtime_error(message);}
std::string str(JNIEnv* env,jstring value){const char* s=env->GetStringUTFChars(value,nullptr);std::string out=s?s:"";if(s)env->ReleaseStringUTFChars(value,s);return out;}
std::unique_ptr<gpu::Gpu> device(const std::string& resources){std::string error;auto g=std::unique_ptr<gpu::Gpu>(gpu::Gpu::create_vulkan(resources+"/vulkan",error));require(bool(g),error);require(g->check_math_mode(error),error);return g;}
std::vector<float> read(gpu::Gpu* g,const gpu::BufferRef& b,size_t n){std::string error;std::vector<float> out(n);require(g->read(b.get(),0,out.data(),n*sizeof(float),error),error);return out;}

void windows(const std::string& resources){
    Env cap("SPEKTRAFILM_STORAGE_LIMIT","131072"),fold("SPK_FOLD_X",nullptr);
    auto g=device(resources);std::string error;
    require(g->max_storage_buffer_range()==131072,"reported test range must remain enforced");
    g->trim_pool(0);
    {auto big=g->alloc(1048576,error);require(bool(big),error);}
    {auto small=g->alloc(52,error);require(g->size_bytes(small.get())==52,"reused buffer leaked allocation capacity into logical extent");}
    constexpr uint32_t w=65,h=143;
    Image shape;shape.w=w;shape.h=h;shape.c=3;
    std::vector<float> input(size_t(w)*h*3);
    for(size_t i=0;i<input.size();++i)input[i]=float((i*13)%1021)/1024.f;
    auto src=g->upload(input.data(),input.size()*4,error);
    const float identity[9]={1,0,0,0,1,0,0,0,1};auto matrix=g->upload(identity,sizeof identity,error);
    const uint32_t n=w*h;
    // Deliberately expose a guard region in the last band's output window.
    // Any padding invocation must leave these sentinel words unchanged.
    std::vector<float> guard(input.size()+256*3,-1234.f);
    auto out=g->upload(guard.data(),guard.size()*4,error);
    std::vector<gpu::Arg> args={gpu::Arg::buf(src),gpu::Arg::buf(matrix),gpu::Arg::inline_bytes(&n,1),gpu::Arg::buf(out)};
    {
        Env chunk("SPK_FOLD_X","1");
        require(g->dispatch("spk_matmul3",args,n,error),error);
    }
    auto got=read(g.get(),out,guard.size());
    require(std::equal(input.begin(),input.end(),got.begin()),"chunked matrix output differs from host identity");
    require(std::all_of(got.begin()+input.size(),got.end(),[](float x){return x==-1234.f;}),"last chunk overwrote sentinel words");
    auto reference=got;
    for(uint32_t rows:{1u,65u,129u}){
        gpu::TilePlan plan{h,w,rows,{12,0,0,12},{}};
        require(g->dispatch_banded("spk_matmul3",args,n,plan,error),error);
        got=read(g.get(),out,guard.size());require(got==reference,"odd-width row windows differ or overwrite padding");
    }
    // Radius-three vertical FIR: the input halo starts before a band and must
    // still meet descriptor-offset alignment. Compare against the whole kernel.
    const float weights[21]={.125,.125,.125,.25,.125,.125,.125,.125,.125,.125,.25,.125,.125,.125,.125,.125,.125,.25,.125,.125,.125};
    const float one[3]={1,1,1};const uint32_t meta[6]={h,w,3,0,0,0};
    auto wt=g->upload(weights,sizeof weights,error),mix=g->upload(one,sizeof one,error);
    std::vector<gpu::Arg> fir={gpu::Arg::buf(src),gpu::Arg::buf(wt),gpu::Arg::buf(src),gpu::Arg::buf(mix),gpu::Arg::inline_bytes(meta,6),gpu::Arg::buf(out)};
    require(g->dispatch("spk_sep_fir_acc",fir,n,error),error);reference=read(g.get(),out,input.size());
    for(uint32_t rows:{1u,65u,129u}){
        gpu::TilePlan plan{h,w,rows,{12,0,12,0,0,12},{3,0,0,0,0,0}};
        require(g->dispatch_banded("spk_sep_fir_acc",fir,n,plan,error),error);
        require(read(g.get(),out,input.size())==reference,"FIR halo changes across an aligned band boundary");
    }
    // A real binding-range limit, rather than a preferred number of rows.
    Image large;large.w=65;large.h=257;large.c=3;
    std::vector<float> data(large.elements());for(size_t i=0;i<data.size();++i)data[i]=float(i%257);
    auto a=g->upload(data.data(),data.size()*4,error),b=g->alloc(data.size()*4,error);
    const uint32_t count=uint32_t(large.pixels());
    require(dispatch_tiled(g.get(),"spk_matmul3",{gpu::Arg::buf(a),gpu::Arg::buf(matrix),gpu::Arg::inline_bytes(&count,1),gpu::Arg::buf(b)},{12,0,0,12},large,count,error),error);
    require(read(g.get(),b,data.size())==data,"binding-limited image differs from host identity");
}

const char* params=R"({"film_stock":"kodak_portra_400","print_stock":"kodak_portra_endura","input_color_space":"sRGB","input_cctf_decoding":true,"output_color_space":"sRGB","output_cctf_encoding":true,"auto_exposure":false,"grain_active":true,"grain_amount":1,"halation_active":false,"glare_active":false,"dir_couplers_active":false})";
std::vector<uint16_t> picture(const std::string& resources,const char* rows,bool destroy_first){
    Env band("SPK_FORCE_BAND_ROWS",rows);
    spk_engine* e=spk_engine_create(resources.c_str(),nullptr);require(e!=nullptr,spk_last_error(nullptr));
    spk_result result{};
    try{
        const uint32_t w=65,h=143;std::vector<float> input(size_t(w)*h*3);
        for(size_t i=0;i<input.size();++i)input[i]=.1f+float((i*11)%800)/1000.f;
        spk_image image{input.data(),w,h,3};
        auto session=spk_open(e,&image,params,nullptr);require(session!=nullptr,spk_last_error(e));
        require(spk_render(session,"full",&result)==SPK_OK,spk_last_error(e));
        require(result.owner!=nullptr,"mapped result must have an independent lifetime token");
        if(destroy_first){spk_engine_destroy(e);e=nullptr;}
        std::vector<uint16_t> out;out.reserve(size_t(w)*h*4);
        for(uint32_t y=0;y<h;++y)out.insert(out.end(),result.rgba16+size_t(y)*result.row_stride_px*4,result.rgba16+size_t(y)*result.row_stride_px*4+w*4);
        spk_result_free(&result);spk_result_free(&result);if(e)spk_engine_destroy(e);
        return out;
    }catch(...){spk_result_free(&result);if(e)spk_engine_destroy(e);throw;}
}
void transfers(const std::string& resources){
    Env staging("SPEKTRAFILM_VULKAN_STAGING_BYTES","4096"),split("SPEKTRAFILM_VULKAN_FORCE_SPLIT","1");
    auto g=device(resources);std::string error;require(g->check_math_mode(error)&&error.find("exact Dekker")!=std::string::npos,"split path was not independently validated");
    std::vector<double> input(24581);std::vector<float> want(input.size());
    for(size_t i=0;i<input.size();++i){input[i]=(double(i%997)-500.0)/257.0;want[i]=float(input[i]);}
    auto b=g->upload_f32(input.data(),input.size(),error);require(bool(b),error);
    require(read(g.get(),b,want.size())==want,"bounded upload/readback changed a narrowed sample");
}
}
extern "C" JNIEXPORT void JNICALL Java_app_spektrafilm_NativeEngine_gpuTestStorageLimit(JNIEnv*,jclass,jlong bytes){
    // The driver clamps this to its reported cap; this debug-only hook cannot raise it.
    if(bytes>0)setenv("SPEKTRAFILM_STORAGE_LIMIT",std::to_string(bytes).c_str(),1);
}
extern "C" JNIEXPORT jstring JNICALL Java_app_spektrafilm_NativeEngine_gpuCheck(JNIEnv* env,jclass,jstring path,jstring name){
    try{
        const std::string resources=str(env,path),kind=str(env,name);
        if(kind=="windows")windows(resources);
        else if(kind=="grain"){auto whole=picture(resources,nullptr,false);for(auto rows:{"1","65","129"})require(picture(resources,rows,false)==whole,"grain or spectral output changes with band size");}
        else if(kind=="lifetime")require(picture(resources,nullptr,true)==picture(resources,nullptr,false),"result lost pixels after engine destruction");
        else if(kind=="transfers")transfers(resources);
        else throw std::runtime_error("unknown GPU check");
        return env->NewStringUTF("{\"passed\":true}");
    }catch(const std::exception& ex){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),ex.what());return nullptr;}
}
