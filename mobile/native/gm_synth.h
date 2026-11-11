#ifndef HENDRIX_GM_SYNTH_H
#define HENDRIX_GM_SYNTH_H
#include <stddef.h>
typedef struct GmSynth GmSynth;
GmSynth *gm_create(const char *path, int rate);
void gm_destroy(GmSynth *synth);
void gm_program(GmSynth *synth, int part, int program);
void gm_midi(GmSynth *synth, int part, int status, int a, int b);
void gm_panic(GmSynth *synth, int part);
void gm_render(GmSynth *synth, float *stereo, int frames);
#endif
