#ifndef HENDRIX_MOBILE_H
#define HENDRIX_MOBILE_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct MobileEngine HendrixMobile;
typedef struct Recording HendrixRecording;
typedef void (*HendrixEventCallback)(void *context, uint64_t sample, uint8_t status, uint8_t a, uint8_t b);
typedef struct { float hz; uint8_t note; float cents; float level_db; } HendrixTunerReading;
typedef struct { float level, window, bend; uint64_t attacks; } HendrixMetrics;
typedef struct {
    uint64_t start_sample, end_sample;
    uint8_t pitch, velocity, channel, guitar_string, guitar_fret;
} HendrixScoreNote;
HendrixMobile *hendrix_mobile_create(float sample_rate);
void hendrix_mobile_destroy(HendrixMobile *handle);
bool hendrix_mobile_set_parameter(HendrixMobile *handle, uint32_t index, float value);
bool hendrix_mobile_set_channel(HendrixMobile *handle, uint8_t channel);
uint64_t hendrix_mobile_sample_position(const HendrixMobile *handle);
bool hendrix_mobile_tuner(const HendrixMobile *handle, HendrixTunerReading *output);
bool hendrix_mobile_metrics(const HendrixMobile *handle, HendrixMetrics *output, uint8_t notes[128]);
void hendrix_mobile_snapshot(const HendrixMobile *handle, HendrixEventCallback callback, void *context);
bool hendrix_mobile_process(HendrixMobile *handle, const float *input, uint32_t frames, HendrixEventCallback callback, void *context);
void hendrix_mobile_reset(HendrixMobile *handle, HendrixEventCallback callback, void *context);
HendrixRecording *hendrix_recording_create(uint32_t sample_rate);
HendrixRecording *hendrix_recording_create_at(uint32_t sample_rate, uint64_t origin_sample);
void hendrix_recording_destroy(HendrixRecording *handle);
bool hendrix_recording_add(HendrixRecording *handle, uint64_t sample, uint8_t status, uint8_t a, uint8_t b);
size_t hendrix_recording_export(const HendrixRecording *handle, uint64_t end_sample, uint8_t *buffer, size_t capacity);
size_t hendrix_recording_notes(const HendrixRecording *handle, uint64_t end_sample, HendrixScoreNote *output, size_t capacity);
#ifdef __cplusplus
}
#endif
#endif
