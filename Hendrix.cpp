#include "Hendrix.h"
#include "IPlug_include_in_plug_src.h"
#include <algorithm>
#include <cmath>
#include <set>
#ifdef APP_API
#include "IPlugAPP_host.h"
#include "resource.h"
#endif
using nlohmann::json;
namespace {
const char* Names[20]={"Detection mode","MIDI send","Input threshold","Stability","Release","Lowest note","Highest note","Voices","Reference A4","Transpose","Velocity (0 = dynamic)","Verification","Audio passthrough","Mono bends","Bend range","Bend smoothing","Acquisition window","Retrigger","Pick threshold","Minimum interval"};
const char* Units[20]={"","","dB","ms","ms","","","","Hz","st","","","","","","ms","","","dB","ms"};
const double Min[20]={0,0,-72,0,10,24,36,1,400,-24,0,0,0,0,0,0,0,0,.5,10};
const double Max[20]={1,1,-6,80,300,96,108,6,480,24,127,2,1,1,2,80,3,1,18,150};
const double Defaults[20]={1,1,-72,20.5,69.1,27,108,1,431.4,0,0,1,1,1,0,4.9,0,1,3,10};
json initial(){return {{"version",1},{"language","en"},{"route",0},{"default",uint64_t(1)},{"presets",json::array({{{"id",uint64_t(1)},{"name","My Guitar"},{"values",std::vector<double>(Defaults,Defaults+20)}}})}};}
bool valuesValid(const json& v){if(!v.is_array()||v.size()!=20)return false;for(int i=0;i<20;++i)if(!v[i].is_number()||!std::isfinite(v[i].get<double>())||v[i].get<double>()<Min[i]||v[i].get<double>()>Max[i])return false;return true;}
}
Hendrix::Hendrix(const InstanceInfo& info):Plugin(info,MakeConfig(20,1)),mState(initial()) {
    for(int i=0;i<20;++i){if(i==0||i==1||i==5||i==6||i==7||i==9||i==10||i==11||i==12||i==13||i==14||i==16||i==17)GetParam(i)->InitInt(Names[i],(int)Defaults[i],(int)Min[i],(int)Max[i],Units[i]);else GetParam(i)->InitDouble(Names[i],Defaults[i],Min[i],Max[i],0.01,Units[i]);}
    mEngine=hendrix_create();mOutput=hendrix_output_handle(mEngine);mVirtual=hendrix_virtual_create();
    char defaultText[262144];int defaultSize=hendrix_load_default(defaultText,sizeof(defaultText));
    if(defaultSize>0){auto bank=json::parse(defaultText,defaultText+defaultSize,nullptr,false);if(ValidateBank(bank)){mState=bank;for(const auto& p:bank["presets"])if(p["id"]==bank["default"])for(int i=0;i<20;++i)GetParam(i)->Set(p["values"][i].get<double>());}}

#ifdef APP_API
    char saved[262144];int n=hendrix_load_app_state(saved,sizeof(saved));if(n>0){json state=json::parse(saved,saved+n,nullptr,false);if(ValidateBank(state)){mState=state;mRoute.store(state.value("route",0));if(state.contains("current")&&valuesValid(state["current"]))for(int i=0;i<20;++i)GetParam(i)->Set(state["current"][i].get<double>());}}
#endif
#ifdef WEBVIEW_EDITOR_DELEGATE
    SetMaxJSStringLength(131072);
    mEditorInitFunc=[this](){LoadIndexHtml(__FILE__,GetBundleID());EnableScroll(true);};
#endif
    if(!mVirtual)mRoute.store(0);
    MakeDefaultPreset("My Guitar",1);
}
Hendrix::~Hendrix(){Persist();if(mEngine){hendrix_reset(mEngine,Emit,this);hendrix_destroy(mEngine);}hendrix_release_output(mOutput);hendrix_virtual_destroy(mVirtual);}
void Hendrix::Emit(void* ptr,int offset,uint8_t status,uint8_t a,uint8_t b){auto* self=static_cast<Hendrix*>(ptr);if(self->mAudioRoute==1)hendrix_virtual_send(self->mVirtual,status,a,b);else self->SendMidiMsg(IMidiMsg(offset,status,a,b));}
void Hendrix::ProcessBlock(sample** inputs,sample** outputs,int frames){
    static_assert(sizeof(sample)==sizeof(double),"Hendrix bridge expects double samples");
    int route=mRoute.load(std::memory_order_relaxed);if(route!=mAudioRoute){hendrix_reset(mEngine,Emit,this);mAudioRoute=route;}
    float values[20];for(int i=0;i<20;++i)values[i]=(float)GetParam(i)->Value();
    hendrix_process(mEngine,const_cast<const double*const*>(inputs),outputs,frames,NInChansConnected(),NOutChansConnected(),values,Emit,this);
}
void Hendrix::OnReset(){hendrix_reset(mEngine,Emit,this);hendrix_prepare(mEngine,GetSampleRate());}
void Hendrix::OnActivate(bool active){if(!active)hendrix_reset(mEngine,Emit,this);}
void Hendrix::OnUIOpen(){mOpen=true;Plugin::OnUIOpen();SendState();}
void Hendrix::OnUIClose(){mOpen=false;Persist();}
void Hendrix::CloseWindow(){OnUIClose();Plugin::CloseWindow();}
void Hendrix::OnIdle(){if(!mOpen)return;if(mStatePending.exchange(false))SendState();if(++mIdle%2)return;float metrics[5]={};uint8_t notes[128]={};hendrix_snapshot(mOutput,metrics,notes);json j={{"level",metrics[0]},{"window",metrics[1]},{"bend",metrics[2]},{"hz",metrics[3]},{"attacks",metrics[4]},{"notes",json::array()}};for(int n=0;n<128;++n)if(notes[n])j["notes"].push_back({n,notes[n]});auto text=j.dump();SendArbitraryMsgFromDelegate(101,(int)text.size(),text.c_str());}
bool Hendrix::ValidateBank(const json& state) const {
    if(!state.is_object()||(!state.contains("version")||!state["version"].is_number_integer()||state["version"]!=1)||!state.contains("language")||!state["language"].is_string()||(state["language"]!="en"&&state["language"]!="de")||!state.contains("presets")||!state["presets"].is_array()||state["presets"].size()>128||!state.contains("default")||!state["default"].is_number_unsigned()||!state.contains("route")||!state["route"].is_number_integer()||state["route"].get<int>()<0||state["route"].get<int>()>1)return false;
    std::set<uint64_t> ids;std::set<std::string> names;
    for(const auto& p:state["presets"]){if(!p.is_object()||!p.contains("id")||!p["id"].is_number_unsigned()||p["id"].get<uint64_t>()==0||!ids.insert(p["id"].get<uint64_t>()).second||!p.contains("name")||!p["name"].is_string()||!p.contains("values")||!valuesValid(p["values"]))return false;auto name=p["name"].get<std::string>();if(name.empty()||name.size()>160||!names.insert(name).second||std::any_of(name.begin(),name.end(),[](unsigned char c){return c<32;}))return false;}
    auto def=state["default"].get<uint64_t>();return (def==0||ids.count(def))&&(!state.contains("current")||valuesValid(state["current"]));
}
void Hendrix::SendState(){json j;{std::lock_guard<std::mutex>lock(mStateMutex);j=mState;}j["route"]=mRoute.load();j["virtualAvailable"]=mVirtual!=nullptr;j["current"]=json::array();for(int i=0;i<20;++i)j["current"].push_back(GetParam(i)->Value());
#ifdef APP_API
    j["standalone"]=true;
#else
    j["standalone"]=false;
#endif
    auto text=j.dump();SendArbitraryMsgFromDelegate(102,(int)text.size(),text.c_str());
}
void Hendrix::Persist(){
#ifdef APP_API
    json j;{std::lock_guard<std::mutex>lock(mStateMutex);j=mState;}j["route"]=mRoute.load();j["current"]=json::array();for(int i=0;i<20;++i)j["current"].push_back(GetParam(i)->Value());auto text=j.dump();hendrix_save_app_state(text.c_str());
#endif
}
bool Hendrix::OnMessage(int tag,int,int size,const void* data){
    if(tag==903){
#ifdef APP_API
        SendMessage(gHWND,WM_COMMAND,ID_PREFERENCES,0);
#endif
        return true;
    }
    if(size<0||size>65536||!data)return false;
    auto q=json::parse((const char*)data,(const char*)data+size,nullptr,false);if(!q.is_object())return false;
    if(tag==900){if(q.contains("link")&&q["link"].is_number_integer()){int i=q["link"].get<int>();if(i>=0&&i<4)hendrix_open_link(i);}return true;}
    if(tag!=901)return false;
    if(q.contains("op")&&!q["op"].is_string())return false;auto op=q.value("op",std::string("state"));json loaded;bool changed=false;
    {std::lock_guard<std::mutex>lock(mStateMutex);json next=mState;
        if(op=="language"){if(q.contains("language")&&!q["language"].is_string())return false;auto language=q.value("language",std::string("en"));if(language!="en"&&language!="de")return false;next["language"]=language;changed=true;}
        else if(op=="route"){if(q.contains("route")&&!q["route"].is_number_integer())return false;int route=q.value("route",0);if(route<0||route>1||(route==1&&!mVirtual))return false;next["route"]=route;mRoute.store(route);changed=true;}
        else if(op=="save"||op=="default"){
            if(q.contains("name")&&!q["name"].is_string())return false;std::string name=q.value("name",std::string(""));if(name.empty()||name.size()>160||std::any_of(name.begin(),name.end(),[](unsigned char c){return c<32;}))return false;
            uint64_t id=0,maxid=0;for(auto& p:next["presets"]){auto pid=p["id"].get<uint64_t>();maxid=std::max(maxid,pid);if(p["name"]==name)id=pid;}
            json values=json::array();for(int i=0;i<20;++i)values.push_back(GetParam(i)->Value());
            if(id==0){if(next["presets"].size()>=128||maxid==UINT64_MAX)return false;id=maxid+1;next["presets"].push_back({{"id",id},{"name",name},{"values",values}});}else for(auto& p:next["presets"])if(p["id"]==id)p["values"]=values;
            if(op=="default")next["default"]=id;changed=true;
        }else if(op=="load"||op=="delete"){
            if(!q.contains("id")||!q["id"].is_number_unsigned())return false;uint64_t id=q["id"].get<uint64_t>();
            for(const auto& p:next["presets"])if(p["id"]==id)loaded=p["values"];
            if(op=="delete"){auto& list=next["presets"];list.erase(std::remove_if(list.begin(),list.end(),[id](const json& p){return p["id"]==id;}),list.end());if(next["default"]==id)next["default"]=uint64_t(0);loaded=nullptr;changed=true;}
        }
        if(changed&&ValidateBank(next))mState=std::move(next);
    }
    if(valuesValid(loaded)){for(int i=0;i<20;++i){BeginInformHostOfParamChangeFromUI(i);SendParameterValueFromUI(i,GetParam(i)->ToNormalized(loaded[i].get<double>()));EndInformHostOfParamChangeFromUI(i);SendParameterValueFromDelegate(i,GetParam(i)->Value(),false);}}
    if(op=="default"){json defaults;{std::lock_guard<std::mutex>lock(mStateMutex);defaults=mState;}defaults["route"]=0;defaults.erase("current");auto text=defaults.dump();hendrix_save_default(text.c_str());}
    Persist();SendState();return true;
}
bool Hendrix::SerializeState(IByteChunk& chunk) const {json j;{std::lock_guard<std::mutex>lock(mStateMutex);j=mState;}j["route"]=mRoute.load();auto text=j.dump();return chunk.PutStr(text.c_str())>0&&SerializeParams(chunk);}
int Hendrix::UnserializeState(const IByteChunk& chunk,int pos){if(chunk.Size()>262144||pos<0)return -1;WDL_String text;int next=chunk.GetStr(text,pos);if(next<0||chunk.Size()-next<20*(int)sizeof(double))return -1;auto j=json::parse(text.Get(),nullptr,false);if(!ValidateBank(j))return -1;int end=UnserializeParams(chunk,next);if(end<0)return -1;{std::lock_guard<std::mutex>lock(mStateMutex);mState=j;}mRoute.store(mVirtual?j.value("route",0):0);mStatePending.store(true);return end;}
