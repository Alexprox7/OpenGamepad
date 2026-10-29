// OpenGamepad motion test: shows what games read from the motion sensor of every controller
// (players 1-4 as Pro Controller, and handheld). Used to check that the virtual controller of
// the OpenGamepad sysmodule delivers its six-axis data. Launch from the homebrew menu.

#include <math.h>
#include <stdio.h>
#include <string.h>

#include <switch.h>

#define PLAYERS 4

static float magnitude(HidVector v) { return sqrtf(v.x * v.x + v.y * v.y + v.z * v.z); }

int main(int argc, char **argv) {
    (void)argc;
    (void)argv;
    consoleInit(NULL);
    padConfigureInput(PLAYERS + 1, HidNpadStyleSet_NpadStandard);
    PadState pad;
    padInitializeAny(&pad);

    static const HidNpadIdType ids[PLAYERS + 1] = {
        HidNpadIdType_No1, HidNpadIdType_No2, HidNpadIdType_No3, HidNpadIdType_No4, HidNpadIdType_Handheld,
    };
    HidSixAxisSensorHandle handles[PLAYERS + 1];
    for (int i = 0; i < PLAYERS; i++) hidGetSixAxisSensorHandles(&handles[i], 1, ids[i], HidNpadStyleTag_NpadFullKey);
    hidGetSixAxisSensorHandles(&handles[PLAYERS], 1, HidNpadIdType_Handheld, HidNpadStyleTag_NpadHandheld);
    for (int i = 0; i <= PLAYERS; i++) hidStartSixAxisSensor(handles[i]);

    float peak[PLAYERS + 1] = {0};
    float peak_rate[PLAYERS + 1] = {0};
    u64 samples[PLAYERS + 1] = {0};

    while (appletMainLoop()) {
        padUpdate(&pad);
        u64 down = padGetButtonsDown(&pad);
        if (down & HidNpadButton_Plus) break;
        if (down & HidNpadButton_Minus) {
            memset(peak, 0, sizeof(peak));
            memset(peak_rate, 0, sizeof(peak_rate));
        }

        printf("\x1b[1;1HOpenGamepad - test sensore di movimento     (+ esci, - azzera i picchi)\n\n");
        for (int i = 0; i <= PLAYERS; i++) {
            u32 style = hidGetNpadStyleSet(ids[i]);
            const char *name = i < PLAYERS ? "Giocatore" : "Portatile";
            if (!style) {
                printf("%s %d: non collegato                                                    \n\n\n\n", name, i + 1);
                continue;
            }
            HidSixAxisSensorState states[8];
            size_t count = hidGetSixAxisSensorStates(handles[i], states, 8);
            HidSixAxisSensorState s = {0};
            if (count > 0) {
                s = states[0];
                samples[i] = s.sampling_number;
                for (size_t k = 0; k < count; k++) {
                    float a = magnitude(states[k].acceleration);
                    float w = magnitude(states[k].angular_velocity);
                    if (a > peak[i]) peak[i] = a;
                    if (w > peak_rate[i]) peak_rate[i] = w;
                }
            }
            printf("%s %d: stile 0x%03x  campioni %llu  attributi 0x%x                 \n", name, i < PLAYERS ? i + 1 : 0,
                   style, (unsigned long long)samples[i], s.attributes);
            printf("  accel  x=% .3f y=% .3f z=% .3f  |a|=%.3f  picco %.3f g          \n",
                   s.acceleration.x, s.acceleration.y, s.acceleration.z, magnitude(s.acceleration), peak[i]);
            printf("  vel.ang x=% .3f y=% .3f z=% .3f  picco %.3f                    \n",
                   s.angular_velocity.x, s.angular_velocity.y, s.angular_velocity.z, peak_rate[i]);
            printf("  angolo x=% .3f y=% .3f z=% .3f                                 \n",
                   s.angle.x, s.angle.y, s.angle.z);
        }
        consoleUpdate(NULL);
    }

    for (int i = 0; i <= PLAYERS; i++) hidStopSixAxisSensor(handles[i]);
    consoleExit(NULL);
    return 0;
}
