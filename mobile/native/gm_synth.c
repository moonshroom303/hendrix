#include "gm_synth.h"
#define TSF_IMPLEMENTATION
#include "vendor/tsf.h"
#include <math.h>

// Independent live/pattern voices share the sample bank. All calls are owned
// by the audio output thread, including construction and destruction.
struct GmSynth { tsf *part[2]; unsigned char mod[2][16]; double phase; int rate; };
GmSynth *gm_create(const char *path, int rate) {
    if (!path || rate < 8000 || rate > 192000) return NULL;
    GmSynth *s = calloc(1, sizeof(*s));
    if (!s) return NULL;
    s->rate = rate;
    s->part[0] = tsf_load_filename(path);
    if (!s->part[0]) { free(s); return NULL; }
    for (int program=0; program<128; program++)
        if (tsf_get_presetindex(s->part[0],0,program)<0) { gm_destroy(s); return NULL; }
    s->part[1] = tsf_copy(s->part[0]);
    if (!s->part[1]) { gm_destroy(s); return NULL; }
    for (int p=0;p<2;p++) {
        tsf_set_output(s->part[p], TSF_STEREO_INTERLEAVED, rate, -9.0f);
        if (!tsf_set_max_voices(s->part[p], 48)) { gm_destroy(s); return NULL; }
        gm_program(s,p,0);
    }
    return s;
}
void gm_destroy(GmSynth *s) { if(s) { if(s->part[1]) tsf_close(s->part[1]); if(s->part[0]) tsf_close(s->part[0]); free(s); } }
void gm_program(GmSynth *s,int part,int program) {
    if(!s || part<0 || part>1 || program<0 || program>127) return;
    for(int ch=0;ch<16;ch++) tsf_channel_set_presetnumber(s->part[part],ch,ch==9?0:program,ch==9);
}
void gm_midi(GmSynth *s,int part,int status,int a,int b) {
    if(!s || part<0 || part>1 || status<0x80 || status>0xef || a<0 || a>127 || b<0 || b>127) return;
    tsf *f=s->part[part]; int ch=status&15;
    switch(status&0xf0) {
        case 0x80: tsf_channel_note_off(f,ch,a); break;
        case 0x90: if(b) tsf_channel_note_on(f,ch,a,b/127.0f); else tsf_channel_note_off(f,ch,a); break;
        case 0xb0:
            if(a==1) s->mod[part][ch]=(unsigned char)b;
            if(a==121) s->mod[part][ch]=0;
            // Instrument choice comes from Hendrix; bank messages cannot select
            // presets outside the included GM bank.
            if(a!=0 && a!=32) tsf_channel_midi_control(f,ch,a,b);
            break;
        case 0xe0: tsf_channel_set_pitchwheel(f,ch,a|(b<<7)); break;
        default: break;
    }
}
void gm_panic(GmSynth *s,int part) {
    if(!s || part<0 || part>1) return;
    for(int ch=0;ch<16;ch++) {
        if (part == 1) tsf_channel_midi_control(s->part[part],ch,121,0);
        tsf_channel_sounds_off_all(s->part[part],ch);
        tsf_channel_set_sustain(s->part[part],ch,0);
        tsf_channel_set_pitchwheel(s->part[part],ch,8192);
        tsf_channel_set_tuning(s->part[part],ch,0);
        s->mod[part][ch]=0;
    }
}
void gm_render(GmSynth *s,float *stereo,int frames) {
    if(!s || !stereo || frames<=0) return;
    for(int offset=0;offset<frames;offset+=64) {
        int count=frames-offset<64?frames-offset:64;
        float vibrato=(float)sin(s->phase)*0.35f;
        for(int p=0;p<2;p++) for(int ch=0;ch<16;ch++)
            tsf_channel_set_tuning(s->part[p],ch,vibrato*s->mod[p][ch]/127.0f);
        tsf_render_float(s->part[0],stereo+offset*2,count,0);
        tsf_render_float(s->part[1],stereo+offset*2,count,1);
        s->phase=fmod(s->phase+6.283185307179586*5.5*count/s->rate,6.283185307179586);
    }
    for(int i=0;i<frames*2;i++) stereo[i]=fmaxf(-1.0f,fminf(1.0f,stereo[i]));
}
