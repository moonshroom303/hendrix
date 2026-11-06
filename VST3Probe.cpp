// Copyright 2026 Moonshroom Studio. MIT License.
// Offline audio -> MIDI smoke test using the official VST3 hosting helpers.
#include "public.sdk/source/vst/hosting/module.h"
#include "public.sdk/source/vst/hosting/hostclasses.h"
#include "public.sdk/source/vst/hosting/eventlist.h"
#include "public.sdk/source/vst/hosting/parameterchanges.h"
#include "pluginterfaces/vst/ivstaudioprocessor.h"
#include "pluginterfaces/vst/ivstcomponent.h"
#include "pluginterfaces/vst/ivsteditcontroller.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
using namespace Steinberg;
using namespace Steinberg::Vst;
static void require(tresult result, const char* operation) {
  if (result != kResultOk) { std::fprintf(stderr, "%s: %d\n", operation, int(result)); std::exit(1); }
}
int main(int argc, char** argv) {
  if (argc != 2) return 2;
  std::string error;
  auto module = VST3::Hosting::Module::create(std::filesystem::absolute(argv[1]).string(), error);
  if (!module) { std::fprintf(stderr, "%s\n", error.c_str()); return 3; }
  auto host = owned(new HostApplication);
  module->getFactory().setHostContext(host);
  IPtr<IComponent> component;
  for (auto& info : module->getFactory().classInfos()) if (info.category() == kVstAudioEffectClass) component = module->getFactory().createInstance<IComponent>(info.ID());
  if (!component) return 4;
  require(component->initialize(host), "Initialize");
  FUnknownPtr<IAudioProcessor> processor(component);
  if (!processor) return 5;
  SpeakerArrangement stereo = SpeakerArr::kStereo;
  require(processor->setBusArrangements(&stereo, 1, &stereo, 1), "Stereo buses");
  require(component->activateBus(kAudio, kInput, 0, true), "Audio input");
  require(component->activateBus(kAudio, kOutput, 0, true), "Audio output");
  require(component->activateBus(kEvent, kOutput, 0, true), "MIDI output");
  ProcessSetup setup{kRealtime, kSample64, 128, 48000};
  require(processor->setupProcessing(setup), "Setup");
  require(component->setActive(true), "Activate");
  require(processor->setProcessing(true), "Start processing");
  double input[2][128]{}, output[2][128]{};
  double* inputs[2]{input[0], input[1]}; double* outputs[2]{output[0], output[1]};
  AudioBusBuffers in{}, out{}; in.numChannels=out.numChannels=2; in.channelBuffers64=inputs; out.channelBuffers64=outputs;
  auto events = owned(new EventList(512));
  auto changes = owned(new ParameterChanges);
  auto put = [&](ParamID id, ParamValue value) { int32 index=0, point=0; auto* q=changes->addParameterData(id,index); q->addPoint(0,value,point); };
  put(0,0); put(8,.5); put(13,0);
  ProcessData data{}; data.processMode=kRealtime;data.symbolicSampleSize=kSample64;data.numSamples=128;data.numInputs=data.numOutputs=1;data.inputs=&in;data.outputs=&out;data.outputEvents=events;data.inputParameterChanges=changes;
  int noteOn=0,noteOff=0; double peak=0;
  for(int block=0;block<300;++block){
    for(int channel=0;channel<2;++channel)for(int n=0;n<128;++n)input[channel][n]=block<160?.4*std::sin((block*128+n)*2.0*3.141592653589793*440.0/48000.0):0;
    events->clear();require(processor->process(data), "Process"); changes->clearQueue();
    for(int n=0;n<events->getEventCount();++n){Event e{};events->getEvent(n,e);if(e.type==Event::kNoteOnEvent&&e.noteOn.pitch==69)++noteOn;if(e.type==Event::kNoteOffEvent&&e.noteOff.pitch==69)++noteOff;}
    for(auto& channel:output)for(auto sample:channel){if(!std::isfinite(sample))return 6;peak=std::max(peak,std::abs(sample));}
  }
  require(processor->setProcessing(false), "Stop processing");require(component->setActive(false), "Deactivate");require(component->terminate(), "Terminate");
  std::printf("VST3: note-on A4=%d, note-off A4=%d, audio peak=%.3f\n",noteOn,noteOff,peak);
  return noteOn>0&&noteOff>0&&peak>.39?0:7;
}
