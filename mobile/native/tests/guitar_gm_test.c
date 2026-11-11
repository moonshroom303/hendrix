#include "session.h"
#include "gm_synth.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
typedef struct { GmSynth *synth; int on, off; } Target;
static void midi(void *context,uint64_t sample,uint8_t status,uint8_t a,uint8_t b) {
    (void)sample; Target *target=context;
    if((status&0xf0)==0x90 && b) target->on++;
    if((status&0xf0)==0x80 || ((status&0xf0)==0x90 && !b)) target->off++;
    gm_midi(target->synth,0,status,a,b);
}
int main(int argc,char **argv) {
    assert(argc==2);
    HendrixHub *hub=hub_create(); assert(hub); assert(hub_set_audio(hub,48000,440,false));
    float values[20]={0,1,-60,20.5,69.1,27,108,1,440,0,100,1,0,1,1,4.9,0,1,3,10};
    hub_set_guitar_parameters(hub,values,20);
    Target target={gm_create(argv[1],48000),0,0}; assert(target.synth); gm_program(target.synth,0,24);
    assert(hub_start_take(hub,0));
    float input[256],output[512]; double energy=0;
    for(int offset=0;offset<96000;offset+=256) {
        for(int i=0;i<256;i++) {
            double t=(offset+i)/48000.0;
            input[i]=offset<72000 ? (float)(.3*sin(6.283185307179586*220*t)+.09*sin(6.283185307179586*440*t)) : 0;
        }
        hub_feed(hub,input,256,(uint64_t)offset/48,midi,&target);
        gm_render(target.synth,output,256);
        for(int i=0;i<512;i++) energy+=output[i]*output[i];
    }
    hub_reset_audio(hub,2100,midi,&target);
    assert(target.on>0 && target.off>0 && energy>1e-5);
    assert(hub_stop_take(hub,2200));
    size_t length=hub_export(hub,NULL,0); assert(length>32);
    unsigned char *bytes=malloc(length); assert(hub_export(hub,bytes,length)==length);
    const unsigned char range[]={0,0xb1,101,0,0,0xb1,100,0,0,0xb1,6,12};
    int found=0; for(size_t i=0;i+sizeof(range)<=length;i++) if(!memcmp(bytes+i,range,sizeof(range))) found=1;
    assert(found);
    FILE *file=fopen("build/guitar-gm-take.mid","wb"); assert(file); assert(fwrite(bytes,1,length,file)==length); fclose(file);
    printf("Audio -> actual Hendrix detector -> GM: %d note-ons/%d note-offs, audible output energy %.3f; recorded MIDI includes bend range.\n",target.on,target.off,energy);
    free(bytes); hub_destroy(hub); gm_destroy(target.synth);
}
