#include "session.h"
#include <stdatomic.h>
#include <stdlib.h>

#define TAKE_RATE 48000
#define QUEUE_SIZE 32768

struct HendrixHub {
    HendrixMobile *audio;
    HendrixRecording *active;
    HendrixRecording *take;
    uint64_t origin;
    uint64_t take_origin;
    uint64_t take_end;
    uint64_t last_sample;
    uint8_t keys[128];
    uint8_t modulation;
    uint8_t sustain;
    uint8_t bend_range, recorded_bend_range;
};

struct HendrixAudioQueue {
    atomic_uint head;
    atomic_uint tail;
    atomic_bool overrun;
    float samples[QUEUE_SIZE];
};

typedef struct {
    HendrixHub *hub;
    uint64_t sample;
    HendrixEventCallback callback;
    void *context;
} Output;

static Output output(HendrixHub *hub, uint64_t ms, HendrixEventCallback callback, void *context) {
    uint64_t sample = ms * (TAKE_RATE / 1000);
    if (sample < hub->last_sample) sample = hub->last_sample;
    hub->last_sample = sample;
    return (Output){ hub, sample, callback, context };
}

static void emit(void *context, uint64_t input_sample, uint8_t status, uint8_t a, uint8_t b) {
    (void)input_sample;
    Output *out = context;
    if (out->hub->active) hendrix_recording_add(out->hub->active, out->sample, status, a, b);
    if (out->callback) out->callback(out->context, out->sample, status, a, b);
}

HendrixHub *hub_create(void) {
    HendrixHub *hub = calloc(1, sizeof(HendrixHub));
    if (hub) hub->bend_range = 2;
    return hub;
}

void hub_destroy(HendrixHub *hub) {
    if (!hub) return;
    hendrix_mobile_destroy(hub->audio);
    hendrix_recording_destroy(hub->active);
    hendrix_recording_destroy(hub->take);
    free(hub);
}

bool hub_set_audio(HendrixHub *hub, float rate, float reference, bool poly) {
    HendrixMobile *audio = hendrix_mobile_create(rate);
    if (!audio) return false;
    hendrix_mobile_set_channel(audio, 1);
    hendrix_mobile_set_parameter(audio, 8, reference);
    hendrix_mobile_set_parameter(audio, 0, poly ? 1.f : 0.f);
    hendrix_mobile_destroy(hub->audio);
    hub->audio = audio;
    return true;
}

void hub_reset_audio(HendrixHub *hub, uint64_t ms, HendrixEventCallback callback, void *context) {
    Output out = output(hub, ms, callback, context);
    hendrix_mobile_reset(hub->audio, emit, &out);
}

bool hub_tuner(HendrixHub *hub, HendrixTunerReading *reading) {
    return hendrix_mobile_tuner(hub->audio, reading);
}

void hub_set_reference(HendrixHub *hub, float reference) {
    hendrix_mobile_set_parameter(hub->audio, 8, reference);
}

void hub_set_guitar_parameters(HendrixHub *hub, const float *values, size_t count) {
    if (!values || count != 20) return;
    hub->bend_range = values[14] >= 1.5f ? 24 : values[14] >= .5f ? 12 : 2;
    for (size_t index = 0; index < count; index++) {
        // The mobile audio session captures input only.
        if (index != 12) hendrix_mobile_set_parameter(hub->audio, (uint32_t)index, values[index]);
    }
}

bool hub_guitar_metrics(HendrixHub *hub, HendrixMetrics *metrics, uint8_t notes[128]) {
    return hendrix_mobile_metrics(hub->audio, metrics, notes);
}

static void record_bend_range(Output *out) {
    // Make recorded bends self-contained for both GM playback and external MIDI.
    // This setup is recording-only; the existing live MIDI route is unchanged.
    Output record = *out;
    record.callback = NULL;
    emit(&record, 0, 0xb1, 101, 0);
    emit(&record, 0, 0xb1, 100, 0);
    emit(&record, 0, 0xb1, 6, out->hub->bend_range);
    emit(&record, 0, 0xb1, 38, 0);
    emit(&record, 0, 0xb1, 101, 127);
    emit(&record, 0, 0xb1, 100, 127);
    out->hub->recorded_bend_range = out->hub->bend_range;
}

void hub_feed(HendrixHub *hub, const float *samples, uint32_t count, uint64_t ms,
              HendrixEventCallback callback, void *context) {
    Output out = output(hub, ms, callback, context);
    if (hub->active && hub->recorded_bend_range != hub->bend_range) record_bend_range(&out);
    hendrix_mobile_process(hub->audio, samples, count, emit, &out);
}

void hub_key(HendrixHub *hub, uint8_t note, uint8_t velocity, uint64_t ms,
             HendrixEventCallback callback, void *context) {
    if (note > 127 || velocity > 127) return;
    Output out = output(hub, ms, callback, context);
    if (hub->keys[note]) emit(&out, 0, 0x80, note, 0);
    hub->keys[note] = velocity;
    if (velocity) emit(&out, 0, 0x90, note, velocity);
}

void hub_release_keys(HendrixHub *hub, uint64_t ms, HendrixEventCallback callback, void *context) {
    for (int note = 0; note < 128; note++)
        if (hub->keys[note]) hub_key(hub, (uint8_t)note, 0, ms, callback, context);
}

void hub_control(HendrixHub *hub, uint8_t controller, uint8_t value, uint64_t ms,
                 HendrixEventCallback callback, void *context) {
    uint8_t *current = controller == 1 ? &hub->modulation : controller == 64 ? &hub->sustain : NULL;
    if (!current || value > 127 || *current == value) return;
    *current = value;
    Output out = output(hub, ms, callback, context);
    emit(&out, 0, 0xb0, controller, value);
}

bool hub_start_take(HendrixHub *hub, uint64_t ms) {
    if (hub->active) return false;
    Output out = output(hub, ms, NULL, NULL);
    hub->origin = out.sample;
    hub->active = hendrix_recording_create_at(TAKE_RATE, out.sample);
    if (!hub->active) return false;
    record_bend_range(&out);
    hendrix_mobile_snapshot(hub->audio, emit, &out);
    if (hub->modulation) emit(&out, 0, 0xb0, 1, hub->modulation);
    if (hub->sustain) emit(&out, 0, 0xb0, 64, hub->sustain);
    for (int note = 0; note < 128; note++)
        if (hub->keys[note]) emit(&out, 0, 0x90, (uint8_t)note, hub->keys[note]);
    return true;
}

bool hub_stop_take(HendrixHub *hub, uint64_t ms) {
    if (!hub->active) return false;
    Output out = output(hub, ms, NULL, NULL);
    if (hub->sustain) emit(&out, 0, 0xb0, 64, 0);
    hendrix_recording_destroy(hub->take);
    hub->take = hub->active;
    hub->active = NULL;
    hub->take_origin = hub->origin;
    hub->take_end = out.sample;
    return true;
}

size_t hub_export(HendrixHub *hub, uint8_t *buffer, size_t capacity) {
    return hendrix_recording_export(hub->take, hub->take_end, buffer, capacity);
}

size_t hub_score(HendrixHub *hub, HendrixScoreNote *notes, size_t capacity) {
    if (!hub->take) return 0;
    size_t count = hendrix_recording_notes(hub->take, hub->take_end, notes, capacity);
    if (notes && count <= capacity)
        for (size_t index = 0; index < count; index++) {
            notes[index].start_sample -= hub->take_origin;
            notes[index].end_sample -= hub->take_origin;
        }
    return count;
}

HendrixAudioQueue *audio_queue_create(void) {
    HendrixAudioQueue *queue = calloc(1, sizeof(HendrixAudioQueue));
    if (queue) {
        atomic_init(&queue->head, 0);
        atomic_init(&queue->tail, 0);
        atomic_init(&queue->overrun, false);
    }
    return queue;
}

void audio_queue_destroy(HendrixAudioQueue *queue) { free(queue); }

bool audio_queue_push(HendrixAudioQueue *queue, const float *samples, uint32_t count) {
    unsigned head = atomic_load_explicit(&queue->head, memory_order_relaxed);
    unsigned tail = atomic_load_explicit(&queue->tail, memory_order_acquire);
    if (count > QUEUE_SIZE - (head - tail)) {
        atomic_store_explicit(&queue->overrun, true, memory_order_release);
        return false;
    }
    for (unsigned i = 0; i < count; i++) queue->samples[(head + i) % QUEUE_SIZE] = samples[i];
    atomic_store_explicit(&queue->head, head + count, memory_order_release);
    return true;
}

bool audio_queue_drain(HendrixAudioQueue *queue, HendrixHub *hub, uint64_t ms,
                       HendrixEventCallback callback, void *context) {
    if (atomic_exchange_explicit(&queue->overrun, false, memory_order_acq_rel)) return false;
    unsigned tail = atomic_load_explicit(&queue->tail, memory_order_relaxed);
    unsigned head = atomic_load_explicit(&queue->head, memory_order_acquire);
    while (tail != head) {
        unsigned offset = tail % QUEUE_SIZE;
        unsigned count = QUEUE_SIZE - offset;
        if (count > head - tail) count = head - tail;
        hub_feed(hub, queue->samples + offset, count, ms, callback, context);
        tail += count;
    }
    atomic_store_explicit(&queue->tail, tail, memory_order_release);
    return true;
}
