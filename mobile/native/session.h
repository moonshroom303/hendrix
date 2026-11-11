#pragma once
#include "hendrix_mobile.h"

typedef struct HendrixHub HendrixHub;
typedef struct HendrixAudioQueue HendrixAudioQueue;

HendrixHub *hub_create(void);
void hub_destroy(HendrixHub *);
bool hub_set_audio(HendrixHub *, float rate, float reference, bool poly);
void hub_reset_audio(HendrixHub *, uint64_t ms, HendrixEventCallback, void *);
bool hub_tuner(HendrixHub *, HendrixTunerReading *);
void hub_set_reference(HendrixHub *, float);
void hub_set_guitar_parameters(HendrixHub *, const float *, size_t);
bool hub_guitar_metrics(HendrixHub *, HendrixMetrics *, uint8_t notes[128]);
void hub_feed(HendrixHub *, const float *, uint32_t, uint64_t ms, HendrixEventCallback, void *);
void hub_key(HendrixHub *, uint8_t note, uint8_t velocity, uint64_t ms, HendrixEventCallback, void *);
void hub_control(HendrixHub *, uint8_t controller, uint8_t value, uint64_t ms, HendrixEventCallback, void *);
void hub_release_keys(HendrixHub *, uint64_t ms, HendrixEventCallback, void *);
bool hub_start_take(HendrixHub *, uint64_t ms);
bool hub_stop_take(HendrixHub *, uint64_t ms);
size_t hub_export(HendrixHub *, uint8_t *, size_t);
size_t hub_score(HendrixHub *, HendrixScoreNote *, size_t);

HendrixAudioQueue *audio_queue_create(void);
void audio_queue_destroy(HendrixAudioQueue *);
bool audio_queue_push(HendrixAudioQueue *, const float *, uint32_t);
bool audio_queue_drain(HendrixAudioQueue *, HendrixHub *, uint64_t ms, HendrixEventCallback, void *);
