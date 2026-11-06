// Copyright 2026 Moonshroom Studio. MIT License.
// Registers Hendrix in this test process only; no plugin installation is needed.
#include <AudioToolbox/AudioToolbox.h>
#include <CoreMIDI/CoreMIDI.h>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <vector>

static void require(OSStatus result, const char* operation) {
  if (result) { std::fprintf(stderr, "%s: %d\n", operation, int(result)); std::exit(1); }
}
struct Test {
  uint64_t sample = 0;
  bool sound = true;
  int noteOn = 0, noteOff = 0;
  std::vector<float> input[2];
};
static OSStatus input(void* context, AudioUnitRenderActionFlags*, const AudioTimeStamp*, UInt32, UInt32 frames, AudioBufferList* buffers) {
  auto& t = *static_cast<Test*>(context);
  for (UInt32 channel = 0; channel < buffers->mNumberBuffers; ++channel) {
    auto* audio = static_cast<float*>(buffers->mBuffers[channel].mData);
    if (!audio) audio = t.input[channel].data();
    buffers->mBuffers[channel].mData = audio;
    buffers->mBuffers[channel].mDataByteSize = frames * sizeof(float);
    for (UInt32 i = 0; i < frames; ++i) audio[i] = t.sound ? float(0.4 * std::sin((t.sample + i) * 2.0 * 3.141592653589793 * 440.0 / 48000.0)) : 0.0f;
  }
  t.sample += frames;
  return noErr;
}
static OSStatus midi(void* context, const AudioTimeStamp*, UInt32, const MIDIPacketList* packets) {
  auto& t = *static_cast<Test*>(context);
  auto* p = &packets->packet[0];
  for (UInt32 i = 0; i < packets->numPackets; ++i) {
    for (UInt16 k = 0; k + 2 < p->length; k += 3) {
      auto* b = p->data + k;
      if ((b[0] & 0xf0) == 0x90 && b[1] == 69 && b[2]) ++t.noteOn;
      if (((b[0] & 0xf0) == 0x80 || ((b[0] & 0xf0) == 0x90 && !b[2])) && b[1] == 69) ++t.noteOff;
    }
    p = MIDIPacketNext(p);
  }
  return noErr;
}
int main(int argc, char** argv) {
  if (argc != 2) { std::fprintf(stderr, "Usage: AudioUnitProbe /path/Hendrix.component\n"); return 2; }
  auto path = std::filesystem::absolute(argv[1]).string();
  auto url = CFURLCreateFromFileSystemRepresentation(nullptr, reinterpret_cast<const UInt8*>(path.c_str()), path.size(), true);
  auto bundle = CFBundleCreate(nullptr, url); CFRelease(url);
  if (!bundle || !CFBundleLoadExecutable(bundle)) return 3;
  auto factory = reinterpret_cast<AudioComponentFactoryFunction>(CFBundleGetFunctionPointerForName(bundle, CFSTR("Hendrix_Factory")));
  if (!factory) return 4;
  AudioComponentDescription description{'aumf','Hndx','Mshr',0,0};
  auto component = AudioComponentRegister(&description, CFSTR("Hendrix Test"), 256, factory);
  AudioUnit unit = nullptr; require(AudioComponentInstanceNew(component, &unit), "Instantiate");
  AudioStreamBasicDescription format{48000, kAudioFormatLinearPCM, kAudioFormatFlagIsFloat | kAudioFormatFlagIsNonInterleaved | kAudioFormatFlagIsPacked, 4, 1, 4, 2, 32, 0};
  require(AudioUnitSetProperty(unit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Input, 0, &format, sizeof(format)), "Input format");
  require(AudioUnitSetProperty(unit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Output, 0, &format, sizeof(format)), "Output format");
  Test test; test.input[0].resize(512); test.input[1].resize(512);
  AURenderCallbackStruct source{input, &test};
  require(AudioUnitSetProperty(unit, kAudioUnitProperty_SetRenderCallback, kAudioUnitScope_Input, 0, &source, sizeof(source)), "Audio input callback");
  AUMIDIOutputCallbackStruct destination{midi, &test};
  require(AudioUnitSetProperty(unit, kAudioUnitProperty_MIDIOutputCallback, kAudioUnitScope_Global, 0, &destination, sizeof(destination)), "MIDI callback");
  require(AudioUnitSetParameter(unit, 0, kAudioUnitScope_Global, 0, 0, 0), "Mono");
  require(AudioUnitSetParameter(unit, 8, kAudioUnitScope_Global, 0, 440, 0), "Reference tuning");
  require(AudioUnitSetParameter(unit, 13, kAudioUnitScope_Global, 0, 0, 0), "Disable bend for note assertion");
  require(AudioUnitInitialize(unit), "Initialize");
  float audio[2][128]{};
  struct { UInt32 count; AudioBuffer channel[2]; } storage{2, {{1, sizeof(audio[0]), audio[0]}, {1, sizeof(audio[1]), audio[1]}}};
  double maximum = 0;
  for (int block = 0; block < 300; ++block) {
    test.sound = block < 160;
    AudioTimeStamp time{}; time.mSampleTime = block * 128; time.mFlags = kAudioTimeStampSampleTimeValid;
    AudioUnitRenderActionFlags flags = 0;
    require(AudioUnitRender(unit, &flags, &time, 0, 128, reinterpret_cast<AudioBufferList*>(&storage)), "Render");
    for (auto& channel : audio) for (float sample : channel) { if (!std::isfinite(sample)) return 5; maximum = std::max(maximum, double(std::abs(sample))); }
  }
  CFPropertyListRef state = nullptr; UInt32 size = sizeof(state);
  require(AudioUnitGetProperty(unit, kAudioUnitProperty_ClassInfo, kAudioUnitScope_Global, 0, &state, &size), "Save plugin state");
  require(AudioUnitSetParameter(unit, 2, kAudioUnitScope_Global, 0, -24, 0), "Change threshold");
  require(AudioUnitSetProperty(unit, kAudioUnitProperty_ClassInfo, kAudioUnitScope_Global, 0, &state, sizeof(state)), "Restore plugin state");
  AudioUnitParameterValue restored = 0;
  require(AudioUnitGetParameter(unit, 2, kAudioUnitScope_Global, 0, &restored), "Read restored threshold");
  CFRelease(state);
  require(AudioUnitUninitialize(unit), "Uninitialize");
  require(AudioComponentInstanceDispose(unit), "Dispose");
  CFRelease(bundle);
  std::printf("AU: note-on A4=%d, note-off A4=%d, audio peak=%.3f, restored threshold=%.1f dB\n", test.noteOn, test.noteOff, maximum, restored);
  return test.noteOn > 0 && test.noteOff > 0 && maximum > 0.39 && std::abs(restored + 72) < .01 ? 0 : 6;
}
