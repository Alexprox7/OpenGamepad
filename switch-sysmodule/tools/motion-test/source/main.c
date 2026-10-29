// OpenGamepad motion test: shows what games read from the motion sensor of every controller
// (players 1-8 with their actual style, and handheld). Used to check that the virtual
// controllers of the OpenGamepad sysmodule deliver six-axis data. Launch from the homebrew menu.

#include <math.h>
#include <stdio.h>
#include <string.h>

#include <switch.h>

#define PADS 9 // players 1-8 and handheld

static float magnitude(HidVector v) { return sqrtf(v.x * v.x + v.y * v.y + v.z * v.z); }

typedef struct {
    HidNpadIdType id;
    HidSixAxisSensorHandle full_key, joy_left, joy_right, joy_dual[2], handheld;
    float peak, peak_rate;
} Pad;

static void start(HidSixAxisSensorHandle h) { hidStartSixAxisSensor(h); }
static void stop(HidSixAxisSensorHandle h) { hidStopSixAxisSensor(h); }

int main(int argc, char **argv) {
    (void)argc;
    (void)argv;
    consoleInit(NULL);
    padConfigureInput(8, HidNpadStyleSet_NpadStandard);
    PadState input;
    padInitializeAny(&input);

    static const HidNpadIdType ids[PADS] = {
        HidNpadIdType_No1, HidNpadIdType_No2, HidNpadIdType_No3, HidNpadIdType_No4,
        HidNpadIdType_No5, HidNpadIdType_No6, HidNpadIdType_No7, HidNpadIdType_No8, HidNpadIdType_Handheld,
    };
    static Pad pads[PADS];
    for (int i = 0; i < PADS; i++) {
        Pad *p = &pads[i];
        p->id = ids[i];
        if (ids[i] == HidNpadIdType_Handheld) {
            hidGetSixAxisSensorHandles(&p->handheld, 1, p->id, HidNpadStyleTag_NpadHandheld);
            start(p->handheld);
            continue;
        }
        hidGetSixAxisSensorHandles(&p->full_key, 1, p->id, HidNpadStyleTag_NpadFullKey);
        hidGetSixAxisSensorHandles(&p->joy_left, 1, p->id, HidNpadStyleTag_NpadJoyLeft);
        hidGetSixAxisSensorHandles(&p->joy_right, 1, p->id, HidNpadStyleTag_NpadJoyRight);
        hidGetSixAxisSensorHandles(p->joy_dual, 2, p->id, HidNpadStyleTag_NpadJoyDual);
        start(p->full_key);
        start(p->joy_left);
        start(p->joy_right);
        start(p->joy_dual[0]);
        start(p->joy_dual[1]);
    }

    while (appletMainLoop()) {
        padUpdate(&input);
        u64 down = padGetButtonsDown(&input);
        if (down & HidNpadButton_Plus) break;
        if (down & HidNpadButton_Minus)
            for (int i = 0; i < PADS; i++) pads[i].peak = pads[i].peak_rate = 0;

        printf("\x1b[1;1HOpenGamepad - test sensore di movimento   (+ esci, - azzera i picchi)\n");
        for (int i = 0; i < PADS; i++) {
            Pad *p = &pads[i];
            u32 style = hidGetNpadStyleSet(p->id);
            const char *kind = "";
            HidSixAxisSensorHandle h = p->full_key;
            if (p->id == HidNpadIdType_Handheld) { h = p->handheld; kind = "portatile"; }
            else if (style & HidNpadStyleTag_NpadFullKey) kind = "Pro Controller";
            else if (style & HidNpadStyleTag_NpadJoyDual) { h = p->joy_dual[1]; kind = "Joy-Con doppio (R)"; }
            else if (style & HidNpadStyleTag_NpadJoyRight) { h = p->joy_right; kind = "Joy-Con R"; }
            else if (style & HidNpadStyleTag_NpadJoyLeft) { h = p->joy_left; kind = "Joy-Con L"; }

            printf("\n");
            if (!style) {
                printf("%s %d: -                                                                  \n\n",
                       p->id == HidNpadIdType_Handheld ? "Portatile" : "Giocatore", i + 1);
                continue;
            }
            HidSixAxisSensorState states[8];
            size_t count = hidGetSixAxisSensorStates(h, states, 8);
            HidSixAxisSensorState s = {0};
            if (count > 0) {
                s = states[0];
                for (size_t k = 0; k < count; k++) {
                    float a = magnitude(states[k].acceleration), w = magnitude(states[k].angular_velocity);
                    if (a > p->peak) p->peak = a;
                    if (w > p->peak_rate) p->peak_rate = w;
                }
            }
            printf("G%d %-18s camp.%-8llu acc % .2f % .2f % .2f  picco %.2f g       \n", i + 1, kind,
                   (unsigned long long)s.sampling_number, s.acceleration.x, s.acceleration.y, s.acceleration.z, p->peak);
            printf("   vel.ang % .2f % .2f % .2f picco %.2f  angolo % .2f % .2f % .2f  attr 0x%x     \n",
                   s.angular_velocity.x, s.angular_velocity.y, s.angular_velocity.z, p->peak_rate,
                   s.angle.x, s.angle.y, s.angle.z, s.attributes);
        }
        consoleUpdate(NULL);
    }

    for (int i = 0; i < PADS; i++) {
        Pad *p = &pads[i];
        if (p->id == HidNpadIdType_Handheld) { stop(p->handheld); continue; }
        stop(p->full_key);
        stop(p->joy_left);
        stop(p->joy_right);
        stop(p->joy_dual[0]);
        stop(p->joy_dual[1]);
    }
    consoleExit(NULL);
    return 0;
}
