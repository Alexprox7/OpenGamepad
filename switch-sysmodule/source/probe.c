// Motion probe (diagnostics): "OGP-PROBE" on UDP 47801 attaches four test controllers, each a
// different HDLS variant with fixed six-axis values, so the motion test app shows which variant,
// if any, reaches games. "OGP-PROBE-END" removes them.
//
// Variant k (1-4) reports acceleration x = 0.50, y = 0.25 * k, z = -1.00 and angle x = 0.1 * k.

#include "probe.h"

#include <string.h>

#define VARIANTS 4

static HiddbgHdlsHandle handles[VARIANTS];
static bool attached[VARIANTS];
static u64 list_created; // device created through ApplyHdlsStateList, 0 if none

static const struct {
    u8 device_type;
    u8 interface;
    u32 attributes;
    const char *name;
} variants[VARIANTS] = {
    { HidDeviceType_FullKey3, HidNpadInterfaceType_Bluetooth,
      HiddbgHdlsAttribute_HasVirtualSixAxisSensorAcceleration | HiddbgHdlsAttribute_HasVirtualSixAxisSensorAngle,
      "Pro Controller Bluetooth, accel+angolo" },
    { HidDeviceType_FullKey3, HidNpadInterfaceType_USB,
      HiddbgHdlsAttribute_HasVirtualSixAxisSensorAcceleration | HiddbgHdlsAttribute_HasVirtualSixAxisSensorAngle,
      "Pro Controller USB, accel+angolo" },
    { HidDeviceType_JoyRight1, HidNpadInterfaceType_Bluetooth,
      HiddbgHdlsAttribute_HasVirtualSixAxisSensorAcceleration | HiddbgHdlsAttribute_HasVirtualSixAxisSensorAngle,
      "Joy-Con destro, accel+angolo" },
    { HidDeviceType_FullKey3, HidNpadInterfaceType_Bluetooth,
      HiddbgHdlsAttribute_HasVirtualSixAxisSensorAcceleration,
      "Pro Controller Bluetooth, solo accel" },
};

void probe_start(void) {
    for (int k = 0; k < VARIANTS; k++) {
        if (attached[k]) continue;
        HiddbgHdlsDeviceInfo info = {0};
        info.deviceType = variants[k].device_type;
        info.npadInterfaceType = variants[k].interface;
        info.singleColorBody = RGBA8_MAXALPHA(0xFF, 0x80, 0x00);
        info.singleColorButtons = RGBA8_MAXALPHA(0x00, 0x00, 0x00);
        info.colorLeftGrip = info.singleColorBody;
        info.colorRightGrip = info.singleColorBody;
        Result rc = hiddbgAttachHdlsVirtualDevice(&handles[k], &info);
        if (R_FAILED(rc)) {
            log_line("prova %d (%s): collegamento rc 0x%x", k + 1, variants[k].name, rc);
            continue;
        }
        attached[k] = true;
        HiddbgHdlsState state;
        memset(&state, 0, sizeof(state));
        state.battery_level = 4;
        state.attribute = variants[k].attributes;
        state.six_axis_sensor_acceleration = (HidVector){ 0.50f, 0.25f * (k + 1), -1.0f };
        state.six_axis_sensor_angle = (HidVector){ 0.1f * (k + 1), 0.0f, 0.0f };
        rc = hiddbgSetHdlsState(handles[k], &state);
        log_line("prova %d (%s): stato rc 0x%x", k + 1, variants[k].name, rc);
    }
}

void probe_stop(void) {
    bool any = false;
    for (int k = 0; k < VARIANTS; k++) {
        if (!attached[k]) continue;
        hiddbgDetachHdlsVirtualDevice(handles[k]);
        attached[k] = false;
        any = true;
    }
    if (list_created) {
        hiddbgDetachHdlsVirtualDevice((HiddbgHdlsHandle){ list_created });
        list_created = 0;
        any = true;
    }
    if (any) log_line("prove di movimento rimosse");
}

static HiddbgHdlsStateList dump_list;

static void log_list(const char *when) {
    log_line("%s: %d controller", when, dump_list.total_entries);
    for (int i = 0; i < dump_list.total_entries && i < 0x10; i++) {
        const HiddbgHdlsStateListEntry *e = &dump_list.entries[i];
        const HiddbgHdlsState *st = &e->state;
        log_line(" %d tipo %u if %u attr %u acc %.2f %.2f %.2f ang %.2f %.2f %.2f", i, e->device.deviceType,
                 e->device.npadInterfaceType, st->attribute,
                 (double)st->six_axis_sensor_acceleration.x, (double)st->six_axis_sensor_acceleration.y,
                 (double)st->six_axis_sensor_acceleration.z, (double)st->six_axis_sensor_angle.x,
                 (double)st->six_axis_sensor_angle.y, (double)st->six_axis_sensor_angle.z);
    }
}

void probe_experiment(void) {
    probe_start();
    Result rc = hiddbgDumpHdlsStates(hdls_session, &dump_list);
    if (R_FAILED(rc)) {
        log_line("dump stati: rc 0x%x", rc);
        return;
    }
    log_list("dump 1");
    // Same values as probe_start, written through the list command this time.
    for (int i = 0; i < dump_list.total_entries && i < 0x10; i++) {
        HiddbgHdlsStateListEntry *e = &dump_list.entries[i];
        for (int k = 0; k < VARIANTS; k++) {
            if (!attached[k] || e->handle.handle != handles[k].handle) continue;
            e->state.attribute = variants[k].attributes;
            e->state.six_axis_sensor_acceleration = (HidVector){ 0.50f, 0.25f * (k + 1), -1.0f };
            e->state.six_axis_sensor_angle = (HidVector){ 0.1f * (k + 1), 0.0f, 0.0f };
        }
    }
    rc = hiddbgApplyHdlsStateList(hdls_session, &dump_list);
    log_line("apply lista: rc 0x%x", rc);
    rc = hiddbgDumpHdlsStates(hdls_session, &dump_list);
    if (R_SUCCEEDED(rc)) log_list("dump 2");
}

/**
 * Creates a controller through ApplyHdlsStateList with the six-axis attributes already set,
 * in case HID only gives a virtual sensor to devices that have them when created.
 * Reports acceleration x = 0.50, y = 1.25, z = -1.00 and angle x = 0.5.
 */
void probe_create_via_list(void) {
    if (list_created) return;
    Result rc = hiddbgDumpHdlsStates(hdls_session, &dump_list);
    if (R_FAILED(rc) || dump_list.total_entries >= 0x10) {
        log_line("lista: dump rc 0x%x, %d controller", rc, dump_list.total_entries);
        return;
    }
    u64 before[0x10];
    int count = dump_list.total_entries;
    for (int i = 0; i < count; i++) before[i] = dump_list.entries[i].handle.handle;

    HiddbgHdlsStateListEntry *e = &dump_list.entries[count];
    memset(e, 0, sizeof(*e));
    e->handle.handle = 0x4F47500000000001ULL; // not an existing device: HID creates one
    e->device.deviceType = HidDeviceType_FullKey3;
    e->device.npadInterfaceType = HidNpadInterfaceType_Bluetooth;
    e->device.singleColorBody = RGBA8_MAXALPHA(0x90, 0x40, 0xFF);
    e->device.colorLeftGrip = e->device.colorRightGrip = e->device.singleColorBody;
    e->state.battery_level = 4;
    e->state.attribute = HiddbgHdlsAttribute_HasVirtualSixAxisSensorAcceleration | HiddbgHdlsAttribute_HasVirtualSixAxisSensorAngle;
    e->state.six_axis_sensor_acceleration = (HidVector){ 0.50f, 1.25f, -1.0f };
    e->state.six_axis_sensor_angle = (HidVector){ 0.5f, 0.0f, 0.0f };
    dump_list.total_entries = count + 1;
    rc = hiddbgApplyHdlsStateList(hdls_session, &dump_list);
    log_line("lista: creazione rc 0x%x", rc);

    if (R_FAILED(hiddbgDumpHdlsStates(hdls_session, &dump_list))) return;
    for (int i = 0; i < dump_list.total_entries && i < 0x10; i++) {
        bool known = false;
        for (int k = 0; k < count; k++) known |= dump_list.entries[i].handle.handle == before[k];
        if (!known) list_created = dump_list.entries[i].handle.handle;
    }
    log_line("lista: nuovo controller %s", list_created ? "creato" : "non trovato");
    log_list("dump 3");
}

