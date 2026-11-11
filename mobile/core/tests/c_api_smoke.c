#include "hendrix_mobile.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct { uint64_t sample; uint8_t status, a, b; } Captured;
typedef struct { Captured values[2048]; size_t count; } Events;
static void collect(void *context, uint64_t sample, uint8_t status, uint8_t a, uint8_t b) {
    Events *events = context;
    assert(events->count < 2048);
    events->values[events->count++] = (Captured){sample, status, a, b};
}

int main(void) {
    HendrixMobile *engine = hendrix_mobile_create(48000.f);
    assert(engine && hendrix_mobile_set_parameter(engine, 8, 432.f));
    Events live = {0};
    float audio[128];
    for (int block = 0; block < 200; block++) {
        for (int i = 0; i < 128; i++) audio[i] = .2f * sinf(2.f * 3.141592653589793f * 432.f * (block * 128 + i) / 48000.f);
        assert(hendrix_mobile_process(engine, audio, 128, collect, &live));
    }
    HendrixTunerReading tuner;
    assert(hendrix_mobile_tuner(engine, &tuner));
    assert(tuner.note == 69 && fabsf(tuner.cents) < 5.f);
    HendrixMetrics metrics;
    uint8_t notes[128];
    assert(hendrix_mobile_metrics(engine, &metrics, notes));
    assert(notes[69] > 0 && metrics.level > -48.f && isfinite(metrics.bend));
    uint64_t origin = hendrix_mobile_sample_position(engine);
    Events snapshot = {0};
    hendrix_mobile_snapshot(engine, collect, &snapshot);
    HendrixRecording *recording = hendrix_recording_create_at(48000, origin);
    assert(recording && snapshot.count > 0);
    for (size_t i = 0; i < snapshot.count; i++) {
        Captured e = snapshot.values[i];
        assert(hendrix_recording_add(recording, e.sample, e.status, e.a, e.b));
    }
    uint64_t end = origin + 24000;
    assert(hendrix_recording_notes(recording, end, NULL, 0) == 1);
    HendrixScoreNote note;
    assert(hendrix_recording_notes(recording, end, &note, 1) == 1);
    assert(note.pitch == 69 && note.guitar_string == 1 && note.guitar_fret == 5);
    size_t size = hendrix_recording_export(recording, end, NULL, 0);
    assert(size > 22);
    uint8_t *file = malloc(size);
    assert(file && hendrix_recording_export(recording, end, file, size) == size);
    assert(!memcmp(file, "MThd", 4) && !memcmp(file + 14, "MTrk", 4));
    free(file);
    hendrix_recording_destroy(recording);
    hendrix_mobile_reset(engine, collect, &live);
    assert(hendrix_mobile_metrics(engine, &metrics, notes));
    assert(notes[69] == 0 && metrics.level == -120.f);
    hendrix_mobile_destroy(engine);
    puts("C interface: audio, tuning, recording, MIDI export and tablature OK");
}
