#include <jni.h>
#include <string>
#include <vector>
#include "llama.h"
static llama_model* M=nullptr; static llama_context* C=nullptr; static const llama_vocab* V=nullptr;
extern "C" JNIEXPORT jboolean JNICALL Java_dev_apkforge_llm_LlmEngine_nLoad(JNIEnv* e,jobject,jstring p,jint ctx,jint th){
  llama_backend_init();
  const char* path=e->GetStringUTFChars(p,0);
  auto mp=llama_model_default_params(); M=llama_model_load_from_file(path,mp); e->ReleaseStringUTFChars(p,path);
  if(!M) return false; V=llama_model_get_vocab(M);
  auto cp=llama_context_default_params(); cp.n_ctx=ctx; cp.n_threads=th; cp.n_threads_batch=th;
  C=llama_init_from_model(M,cp); return C!=nullptr;
}
extern "C" JNIEXPORT void JNICALL Java_dev_apkforge_llm_LlmEngine_nFree(JNIEnv*,jobject){
  if(C) llama_free(C); if(M) llama_model_free(M); C=nullptr; M=nullptr;
}
// Applies the model's own chat template. roles/contents are parallel arrays.
extern "C" JNIEXPORT jstring JNICALL Java_dev_apkforge_llm_LlmEngine_nTemplate(JNIEnv* e,jobject,jobjectArray r,jobjectArray c){
  int n=e->GetArrayLength(r); std::vector<std::string> rs(n),cs(n); std::vector<llama_chat_message> msgs(n);
  for(int i=0;i<n;i++){ auto a=(jstring)e->GetObjectArrayElement(r,i),b=(jstring)e->GetObjectArrayElement(c,i);
    const char*x=e->GetStringUTFChars(a,0);rs[i]=x;e->ReleaseStringUTFChars(a,x);
    const char*y=e->GetStringUTFChars(b,0);cs[i]=y;e->ReleaseStringUTFChars(b,y);
    msgs[i]={rs[i].c_str(),cs[i].c_str()}; }
  const char* tmpl=llama_model_chat_template(M,nullptr);
  std::vector<char> buf(1<<16); int len=llama_chat_apply_template(tmpl,msgs.data(),n,true,buf.data(),buf.size());
  if(len>(int)buf.size()){buf.resize(len);llama_chat_apply_template(tmpl,msgs.data(),n,true,buf.data(),buf.size());}
  return e->NewStringUTF(std::string(buf.data(),len<0?0:len).c_str());
}
// Streams tokens back through cb.onToken(String); returns full text.
extern "C" JNIEXPORT jstring JNICALL Java_dev_apkforge_llm_LlmEngine_nGenerate(JNIEnv* e,jobject,jstring jp,jint maxTok,jfloat temp,jobject cb){
  const char* p=e->GetStringUTFChars(jp,0); std::string prompt(p); e->ReleaseStringUTFChars(jp,p);
  llama_memory_clear(llama_get_memory(C),true);
  int n=-llama_tokenize(V,prompt.c_str(),prompt.size(),nullptr,0,true,true);
  std::vector<llama_token> toks(n); llama_tokenize(V,prompt.c_str(),prompt.size(),toks.data(),n,true,true);
  auto sp=llama_sampler_chain_default_params(); auto* s=llama_sampler_chain_init(sp);
  llama_sampler_chain_add(s,llama_sampler_init_top_p(0.9f,1)); llama_sampler_chain_add(s,llama_sampler_init_temp(temp));
  llama_sampler_chain_add(s,llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
  jmethodID on=e->GetMethodID(e->GetObjectClass(cb),"onToken","(Ljava/lang/String;)Z");
  std::string out; llama_batch b=llama_batch_get_one(toks.data(),toks.size()); llama_token t;
  for(int i=0;i<maxTok;i++){
    if(llama_decode(C,b)) break;
    t=llama_sampler_sample(s,C,-1); if(llama_vocab_is_eog(V,t)) break;
    char piece[256]; int k=llama_token_to_piece(V,t,piece,sizeof piece,0,true); std::string ps(piece,k<0?0:k); out+=ps;
    jstring js=e->NewStringUTF(ps.c_str()); jboolean go=e->CallBooleanMethod(cb,on,js); e->DeleteLocalRef(js);
    if(!go) break; b=llama_batch_get_one(&t,1);
  }
  llama_sampler_free(s); return e->NewStringUTF(out.c_str());
}
