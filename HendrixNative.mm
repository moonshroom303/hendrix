#import <Cocoa/Cocoa.h>
#import <CoreMIDI/CoreMIDI.h>
#include "HendrixCore.h"
#include <atomic>
#include <cstring>
extern "C" void hendrix_open_link(int index){
    static NSString* const urls[]={@"https://moonshroom.moonshroom303.chatgpt.site/",@"https://www.soundcloud.com/moonshroom303",@"https://www.youtube.com/@moonshroom303",@"https://shroomsloops-studio-231026.moonshroom303.chatgpt.site/#"};
    if(index<0||index>3)return;NSURL* url=[NSURL URLWithString:urls[index]];dispatch_async(dispatch_get_main_queue(),^{[[NSWorkspace sharedWorkspace]openURL:url];});
}
struct VirtualMidi{MIDIClientRef client=0;MIDIEndpointRef source=0;};
extern "C" void* hendrix_virtual_create(){auto* m=new VirtualMidi;static std::atomic<int>serial{0};NSString* name=[NSString stringWithFormat:@"Hendrix MIDI Out %d",++serial];
    if(MIDIClientCreate((CFStringRef)name,nullptr,nullptr,&m->client)!=noErr||MIDISourceCreate(m->client,(CFStringRef)name,&m->source)!=noErr){if(m->client)MIDIClientDispose(m->client);delete m;return nullptr;}return m;
}
extern "C" void hendrix_virtual_send(void* raw,uint8_t status,uint8_t a,uint8_t b){auto* m=static_cast<VirtualMidi*>(raw);if(!m||!m->source)return;MIDIPacketList list;const Byte data[3]={status,a,b};auto* packet=MIDIPacketListInit(&list);if(MIDIPacketListAdd(&list,sizeof(list),packet,0,3,data))MIDIReceived(m->source,&list);}
extern "C" void hendrix_virtual_destroy(void* raw){auto* m=static_cast<VirtualMidi*>(raw);if(!m)return;if(m->source)MIDIEndpointDispose(m->source);if(m->client)MIDIClientDispose(m->client);delete m;}
extern "C" int hendrix_load_app_state(char* out,int capacity){@autoreleasepool{NSUserDefaults* prefs=[[NSUserDefaults alloc]initWithSuiteName:@"studio.moonshroom.hendrix"];NSString* saved=[prefs stringForKey:@"state-v1"];const char* bytes=saved.UTF8String;int size=bytes?(int)strlen(bytes):0;if(size>0&&size<capacity)memcpy(out,bytes,size);else size=0;[prefs release];return size;}}
extern "C" void hendrix_save_app_state(const char* bytes){if(!bytes||strlen(bytes)>262144)return;@autoreleasepool{NSUserDefaults* prefs=[[NSUserDefaults alloc]initWithSuiteName:@"studio.moonshroom.hendrix"];NSString* saved=[NSString stringWithUTF8String:bytes];if(saved)[prefs setObject:saved forKey:@"state-v1"];[prefs release];}}

extern "C" int hendrix_load_default(char* out,int capacity){@autoreleasepool{NSUserDefaults* prefs=[[NSUserDefaults alloc]initWithSuiteName:@"studio.moonshroom.hendrix"];NSString* saved=[prefs stringForKey:@"default-v1"];const char* bytes=saved.UTF8String;int size=bytes?(int)strlen(bytes):0;if(size>0&&size<capacity)memcpy(out,bytes,size);else size=0;[prefs release];return size;}}
extern "C" void hendrix_save_default(const char* bytes){if(!bytes||strlen(bytes)>262144)return;@autoreleasepool{NSUserDefaults* prefs=[[NSUserDefaults alloc]initWithSuiteName:@"studio.moonshroom.hendrix"];NSString* saved=[NSString stringWithUTF8String:bytes];if(saved)[prefs setObject:saved forKey:@"default-v1"];[prefs release];}}
