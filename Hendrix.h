#pragma once
#include "IPlug_include_in_plug_hdr.h"
#include "HendrixCore.h"
#include "json.hpp"
#include <atomic>
#include <mutex>
using namespace iplug;
class Hendrix final: public Plugin {
public:
 explicit Hendrix(const InstanceInfo&);~Hendrix() override;
 void ProcessBlock(sample**,sample**,int) override;void OnReset() override;void OnActivate(bool) override;
 void OnUIOpen() override;void OnUIClose() override;void OnIdle() override;void CloseWindow() override;
 bool OnMessage(int,int,int,const void*) override;
 bool SerializeState(IByteChunk&) const override;int UnserializeState(const IByteChunk&,int) override;
private:
 void SendState();void Persist();bool ValidateBank(const nlohmann::json&) const;
 static void Emit(void*,int,uint8_t,uint8_t,uint8_t);
 void* mEngine=nullptr;const void* mOutput=nullptr;void* mVirtual=nullptr;
 std::atomic<bool> mStatePending{false};std::atomic<int> mRoute{0};int mAudioRoute=0;bool mOpen=false;int mIdle=0;
 mutable std::mutex mStateMutex;nlohmann::json mState;
};
