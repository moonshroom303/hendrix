#pragma once
#include <cstdint>
extern "C" {
using HendrixMidiFn=void(*)(void*,int,uint8_t,uint8_t,uint8_t);
void* hendrix_create();void hendrix_destroy(void*);void hendrix_prepare(void*,double);
const void* hendrix_output_handle(const void*);void hendrix_release_output(const void*);
void hendrix_snapshot(const void*,float*,uint8_t*);
void hendrix_process(void*,const double*const*,double*const*,int,int,int,const float*,HendrixMidiFn,void*);
void hendrix_reset(void*,HendrixMidiFn,void*);
void hendrix_open_link(int);void* hendrix_virtual_create();void hendrix_virtual_destroy(void*);void hendrix_virtual_send(void*,uint8_t,uint8_t,uint8_t);
int hendrix_load_app_state(char*,int);void hendrix_save_app_state(const char*);
int hendrix_load_default(char*,int);
void hendrix_save_default(const char*);
}
