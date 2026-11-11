#include "gm_synth.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <time.h>
static float buffer[512];
static double energy(GmSynth *s,int frames) {
    double sum=0;
    for(int remaining=frames;remaining>0;remaining-=256) {
        int count=remaining<256?remaining:256;
        gm_render(s,buffer,count);
        for(int i=0;i<count*2;i++) { assert(isfinite(buffer[i])); assert(fabsf(buffer[i])<=1); sum+=buffer[i]*buffer[i]; }
    }
    return sum;
}
int main(int argc,char **argv) {
    assert(argc==2); GmSynth *s=gm_create(argv[1],48000); assert(s);
    assert(energy(s,1024)==0);
    clock_t start=clock();
    for(int program=0;program<128;program++) {
        gm_program(s,0,program); gm_midi(s,0,0x91,60,100);
        double audible=energy(s,48000);
        if(audible<=1e-8) { fprintf(stderr,"Silent GM program %d\n",program+1); return 1; }
        gm_midi(s,0,0x81,60,0); gm_panic(s,0); energy(s,2048);
        assert(energy(s,1024)<1e-8);
    }
    gm_program(s,0,19); gm_program(s,1,19);
    gm_midi(s,0,0x90,60,100); gm_midi(s,1,0x91,67,100);
    assert(energy(s,4800)>0); gm_panic(s,1); energy(s,2048);
    assert(energy(s,4800)>0); // Pattern stop must not silence live voices.
    gm_panic(s,0); energy(s,2048); assert(energy(s,1024)<1e-8);
    gm_midi(s,0,0xb0,64,127); gm_midi(s,0,0x90,60,100); energy(s,1024);
    gm_midi(s,0,0x80,60,0); assert(energy(s,4800)>0);
    gm_midi(s,0,0xb0,64,0); gm_panic(s,0); energy(s,2048); assert(energy(s,1024)<1e-8);
    gm_program(s,0,24); gm_midi(s,0,0xb1,1,127); gm_midi(s,0,0xe1,127,127); gm_midi(s,0,0x91,64,100);
    assert(energy(s,4800)>0); gm_panic(s,0); energy(s,2048); assert(energy(s,1024)<1e-8);
    gm_midi(s,1,0x99,38,100); assert(energy(s,4800)>0); gm_panic(s,1);
    printf("GM synthesis: all 128 instruments, drum channel, independent live/pattern voices, sustain, bend/modulation and panic passed. CPU %.2fs for >128s audio.\n",(double)(clock()-start)/CLOCKS_PER_SEC);
    gm_destroy(s); return 0;
}
