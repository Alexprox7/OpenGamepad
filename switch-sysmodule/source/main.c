// OpenGamepad sysmodule for Atmosphère: receives the OpenGamepad Wi-Fi protocol and
// turns every connected phone into a virtual Pro Controller (hiddbg HDLS).
//
// Protocol (see docs/PROTOCOL.md): the phone opens TCP to port 47800 and sends a hello;
// the reply announces the UDP port for controller states. States can also arrive on TCP.
//
// Motion: type-17 packets (accelerometer + gyroscope in the phone's controller frame) feed the
// virtual six-axis sensor of the HDLS Pro Controller, on HOS 12.0.0+ where HdlsState has it.
//
// Diagnostics: events go to sdmc:/config/OpenGamepad/log.txt, and "OGP-STATUS" sent to
// UDP 47801 returns the latest lines, so the log can be read over Wi-Fi.
//
// Sleep and shutdown: the module registers with PSC and, before the console sleeps, closes
// every connection and controller and stops all IPC (sockets, hid, SD log) until it wakes.
// A sysmodule that keeps calling services while they suspend makes spsm time out and
// abort (omm fatal 2165-0001).

#include <switch.h>
#include "amiibo.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#define PORT            47800
#define DISCOVERY_PORT  47801
#define MAX_CLIENTS     4
#define RX_HEADER       7   // phone -> console: len, major, minor, seq(2), type
#define TX_HEADER       5   // console -> phone: len, major, minor, type
#define TYPE_HELLO      0
#define TYPE_STATE      1
#define TYPE_MOTION     17  // OpenGamepad extension: u32 µs, i16 accel mg ×3, i16 gyro 0.1°/s ×3
#define MOTION_SIZE     16
#define OS_SWITCH       4
#define SECOND_NS       1000000000ULL
#define CLIENT_TIMEOUT_NS (10 * SECOND_NS)
#define REOPEN_DELAY_NS   (2 * SECOND_NS)
#define MOTION_IDLE_NS    (SECOND_NS / 2)
#define LOG_DIR         "sdmc:/config/OpenGamepad"
#define LOG_PATH        LOG_DIR "/log.txt"
// PSC has no registry of module ids: this one is arbitrary ("GP"). Others in use: sys-con 126,
// MissionControl 0xBD, sys-autopilot 0x4150.
#define PSC_MODULE_ID   0x4750

// ---------------------------------------------------------------------------------------
// libnx process setup: static heap, no applet, only the services we need.
//
// Memory: sysmodules share the small System pool with Nintendo's services; taking too much
// makes a later system service fail to start and the console reboots. Keep everything small:
// heap for the socket buffers and stdio only, tiny socket buffers (states are 19-byte packets).

u32 __nx_applet_type = AppletType_None;
u32 __nx_fs_num_sessions = 1;

#define INNER_HEAP_SIZE 0x40000
size_t nx_inner_heap_size = INNER_HEAP_SIZE;
char nx_inner_heap[INNER_HEAP_SIZE];

void __libnx_initheap(void) {
    extern char *fake_heap_start;
    extern char *fake_heap_end;
    fake_heap_start = nx_inner_heap;
    fake_heap_end = nx_inner_heap + nx_inner_heap_size;
}

static const SocketInitConfig socket_config = {
    .tcp_tx_buf_size = 0x800,
    .tcp_rx_buf_size = 0x800,
    .tcp_tx_buf_max_size = 0,
    .tcp_rx_buf_max_size = 0,
    .udp_tx_buf_size = 0x800,
    .udp_rx_buf_size = 0x2000,
    // Buffer pool = sb_efficiency × the sizes above: 4 leaves room for the listener, four phones
    // and both UDP sockets (with 2 the discovery socket could not be created).
    .sb_efficiency = 4,
    .num_bsd_sessions = 3,
    .bsd_service_type = BsdServiceType_Auto,
};

static bool sd_mounted;
static PscPmModule psc_module;
static bool psc_registered;

void __appInit(void) {
    Result rc = smInitialize();
    if (R_FAILED(rc)) diagAbortWithResult(rc);
    if (R_SUCCEEDED(setsysInitialize())) {
        SetSysFirmwareVersion fw;
        if (R_SUCCEEDED(setsysGetFirmwareVersion(&fw)))
            hosversionSet(MAKEHOSVERSION(fw.major, fw.minor, fw.micro));
        setsysExit();
    }
    // The log is optional: keep going without the SD card.
    if (R_SUCCEEDED(fsInitialize())) sd_mounted = R_SUCCEEDED(fsdevMountSdmc());
    rc = hiddbgInitialize();
    if (R_FAILED(rc)) diagAbortWithResult(rc);
    rc = socketInitialize(&socket_config);
    if (R_FAILED(rc)) diagAbortWithResult(rc);
    // Depending on fs, hid and sockets means we are told to sleep before them and to wake after.
    if (R_SUCCEEDED(pscmInitialize())) {
        const u32 dependencies[] = { PscPmModuleId_Fs, PscPmModuleId_Hid, PscPmModuleId_WlanSockets };
        psc_registered = R_SUCCEEDED(pscmGetPmModule(&psc_module, (PscPmModuleId)PSC_MODULE_ID, dependencies, 3, true));
    }
    smExit();
}

void __appExit(void) {
    if (psc_registered) pscPmModuleFinalize(&psc_module);
    pscmExit();
    socketExit();
    hiddbgExit();
    if (sd_mounted) fsdevUnmountAll();
    fsExit();
}

// ---------------------------------------------------------------------------------------
// Log: file on the SD card plus the latest lines in memory for OGP-STATUS.

static FILE *log_file;
static bool log_to_sd = true; // off while the console sleeps: fs is suspended
static char log_tail[4096];
static size_t log_tail_len;

static u64 now_ns(void) { return armTicksToNs(armGetSystemTick()); }

void log_line(const char *format, ...) {
    char line[192];
    u64 ms = now_ns() / 1000000ULL;
    int n = snprintf(line, sizeof(line), "%llu.%03llu ", (unsigned long long)(ms / 1000), (unsigned long long)(ms % 1000));
    va_list args;
    va_start(args, format);
    n += vsnprintf(line + n, sizeof(line) - n, format, args);
    va_end(args);
    if (n > (int)sizeof(line) - 2) n = sizeof(line) - 2;
    line[n++] = '\n';
    line[n] = 0;

    if (log_tail_len + n > sizeof(log_tail)) {
        size_t drop = log_tail_len + n - sizeof(log_tail);
        memmove(log_tail, log_tail + drop, log_tail_len - drop);
        log_tail_len -= drop;
    }
    memcpy(log_tail + log_tail_len, line, n);
    log_tail_len += n;
    if (log_file && log_to_sd) {
        fputs(line, log_file);
        fflush(log_file);
    }
}

static void log_open(void) {
    if (!sd_mounted) return;
    mkdir("sdmc:/config", 0777);
    mkdir(LOG_DIR, 0777);
    log_file = fopen(LOG_PATH, "w");
}

// ---------------------------------------------------------------------------------------
// Virtual controllers

static u8 hdls_work[0x1000] __attribute__((aligned(0x1000)));
static HiddbgHdlsSessionId hdls_session;

typedef struct {
    int fd;                     // TCP control socket, -1 when free
    struct in_addr addr;
    u8 buf[512];                // partial TCP frames
    size_t len;
    bool greeted;
    bool attached;
    HiddbgHdlsHandle pad;
    HiddbgHdlsState state;
    u64 last_seen;
    u32 states;                 // controller states received
    Result last_rc;             // last hiddbgSetHdlsState result, logged when it changes
    u64 last_motion;            // time of the latest motion sample, 0 when at rest
    u32 motion_time;            // phone timestamp of that sample (µs)
    u32 motions;                // motion samples received
} Client;

static Client clients[MAX_CLIENTS];
// HdlsState carries the virtual six-axis sensor (attribute, acceleration, angle) from 12.0.0;
// libnx converts the struct to the older layouts, which drop those fields.
static bool six_axis;

/** Controller lying flat and still: gravity on -z (see apply_motion for the axes). */
static void motion_rest(Client *c) {
    c->state.six_axis_sensor_acceleration = (HidVector){ 0.0f, 0.0f, -1.0f };
    c->last_motion = 0;
}

/**
 * HID gives a virtual controller a motion sensor only when the six-axis attributes are already
 * in its state when it is created, and only ApplyHdlsStateList can create one that way: a
 * device made with AttachHdlsVirtualDevice keeps the values but games read zeros (checked with
 * tools/motion-test on HOS 21.1.0). The current list is dumped, our device appended with an
 * unknown handle (HID then creates it) and applied; the new handle is the one not seen before.
 */
static HiddbgHdlsStateList hdls_list;

// Devices created through the list outlive this process (stopping the module from
// ovl-sysmodules leaves them frozen, on player 1). Their handles are the ones we pass, so they
// carry this prefix; every start removes the ones left behind.
#define OUR_HANDLE_PREFIX 0x4F47500000000000ULL
#define OUR_HANDLE_MASK   0xFFFFFF0000000000ULL

static void remove_leftover_controllers(void) {
    if (R_FAILED(hiddbgDumpHdlsStates(hdls_session, &hdls_list))) return;
    int removed = 0;
    for (int i = 0; i < hdls_list.total_entries && i < 0x10; i++) {
        u64 handle = hdls_list.entries[i].handle.handle;
        if ((handle & OUR_HANDLE_MASK) != OUR_HANDLE_PREFIX) continue;
        Result rc = hiddbgDetachHdlsVirtualDevice((HiddbgHdlsHandle){ handle });
        if (R_SUCCEEDED(rc)) removed++;
        else log_line("controller rimasto da una sessione precedente non rimosso: rc 0x%x", rc);
    }
    if (removed) log_line("rimossi %d controller rimasti da una sessione precedente", removed);
}

static Result attach_with_motion(Client *c, const HiddbgHdlsDeviceInfo *info) {
    Result rc = hiddbgDumpHdlsStates(hdls_session, &hdls_list);
    if (R_FAILED(rc)) return rc;
    int count = hdls_list.total_entries;
    if (count < 0 || count >= 0x10) return MAKERESULT(Module_Libnx, LibnxError_OutOfMemory);
    u64 before[0x10];
    for (int i = 0; i < count; i++) before[i] = hdls_list.entries[i].handle.handle;
    // Unique, so it never matches a device left by an earlier run (HID would just update it).
    u64 handle = OUR_HANDLE_PREFIX | (armGetSystemTick() & ~OUR_HANDLE_MASK);
    HiddbgHdlsStateListEntry *e = &hdls_list.entries[count];
    memset(e, 0, sizeof(*e));
    e->handle.handle = handle;
    e->device = *info;
    e->state = c->state;
    hdls_list.total_entries = count + 1;
    rc = hiddbgApplyHdlsStateList(hdls_session, &hdls_list);
    if (R_FAILED(rc)) return rc;

    rc = hiddbgDumpHdlsStates(hdls_session, &hdls_list);
    if (R_FAILED(rc)) return rc;
    // HID keeps the handle we passed; if it ever assigns its own, take the device that is new.
    u64 created = 0;
    for (int i = 0; i < hdls_list.total_entries && i < 0x10; i++) {
        u64 h = hdls_list.entries[i].handle.handle;
        bool known = false;
        for (int k = 0; k < count; k++) known |= h == before[k];
        if (h == handle) created = h;
        else if (!known && !created) created = h;
    }
    if (!created) return MAKERESULT(Module_Libnx, LibnxError_NotFound);
    c->pad.handle = created;
    return 0;
}

static bool attach_pad(Client *c) {
    HiddbgHdlsDeviceInfo info = {0};
    info.deviceType = HidDeviceType_FullKey3;               // Pro Controller
    info.npadInterfaceType = HidNpadInterfaceType_Bluetooth;
    info.singleColorBody = RGBA8_MAXALPHA(0x1A, 0x1A, 0x1A); // OpenGamepad black and blue
    info.singleColorButtons = RGBA8_MAXALPHA(0x7C, 0x9C, 0xFF);
    info.colorLeftGrip = RGBA8_MAXALPHA(0x1A, 0x1A, 0x1A);
    info.colorRightGrip = RGBA8_MAXALPHA(0x1A, 0x1A, 0x1A);
    memset(&c->state, 0, sizeof(c->state));
    c->state.battery_level = 4;
    Result rc = 1;
    if (six_axis) {
        c->state.attribute = HiddbgHdlsAttribute_HasVirtualSixAxisSensorAcceleration |
                             HiddbgHdlsAttribute_HasVirtualSixAxisSensorAngle;
        motion_rest(c);
        rc = attach_with_motion(c, &info);
        if (R_FAILED(rc)) log_line("controller con sensore non creato (rc 0x%x): senza movimento", rc);
    }
    // Fallback: a plain virtual controller, buttons and sticks only.
    if (R_FAILED(rc)) rc = hiddbgAttachHdlsVirtualDevice(&c->pad, &info);
    if (R_FAILED(rc)) {
        log_line("controller virtuale NON creato per %s: rc 0x%x", inet_ntoa(c->addr), rc);
        return false;
    }
    c->attached = true;
    c->last_rc = hiddbgSetHdlsState(c->pad, &c->state);
    log_line("controller virtuale creato per %s (stato rc 0x%x)", inet_ntoa(c->addr), c->last_rc);
    return true;
}

static void close_client(Client *c, const char *reason) {
    if (c->fd < 0) return;
    log_line("%s chiuso (%s), %u stati ricevuti", inet_ntoa(c->addr), reason, c->states);
    if (c->attached) hiddbgDetachHdlsVirtualDevice(c->pad);
    close(c->fd);
    memset(c, 0, sizeof(*c));
    c->fd = -1;
}

static inline u16 rd16(const u8 *p) { return (u16)(p[0] | (p[1] << 8)); }
static inline s16 rds16(const u8 *p) { return (s16)rd16(p); }
static inline u32 rd32(const u8 *p) { return (u32)rd16(p) | ((u32)rd16(p + 2) << 16); }

static void push_state(Client *c) {
    if (!c->attached) return;
    Result rc = hiddbgSetHdlsState(c->pad, &c->state);
    if (rc != c->last_rc) {
        log_line("aggiornamento controller di %s: rc 0x%x", inet_ntoa(c->addr), rc);
        c->last_rc = rc;
    }
}

/** XUSB state (Xbox layout) → Pro Controller, by position: Xbox A (bottom) = Switch B. */
static void apply_state(Client *c, const u8 *p, bool udp) {
    u16 xb = rd16(p);
    u64 b = 0;
    if (xb & 0x0001) b |= HidNpadButton_Up;
    if (xb & 0x0002) b |= HidNpadButton_Down;
    if (xb & 0x0004) b |= HidNpadButton_Left;
    if (xb & 0x0008) b |= HidNpadButton_Right;
    if (xb & 0x0010) b |= HidNpadButton_Plus;
    if (xb & 0x0020) b |= HidNpadButton_Minus;
    if (xb & 0x0040) b |= HidNpadButton_StickL;
    if (xb & 0x0080) b |= HidNpadButton_StickR;
    if (xb & 0x0100) b |= HidNpadButton_L;
    if (xb & 0x0200) b |= HidNpadButton_R;
    if (xb & 0x0400) b |= HiddbgNpadButton_Home;
    if (xb & 0x1000) b |= HidNpadButton_B;
    if (xb & 0x2000) b |= HidNpadButton_A;
    if (xb & 0x4000) b |= HidNpadButton_Y;
    if (xb & 0x8000) b |= HidNpadButton_X;
    if (p[10] > 128) b |= HidNpadButton_ZL;
    if (p[11] > 128) b |= HidNpadButton_ZR;

    c->state.buttons = b;
    c->state.analog_stick_l.x = rds16(p + 2);
    c->state.analog_stick_l.y = rds16(p + 4);
    c->state.analog_stick_r.x = rds16(p + 6);
    c->state.analog_stick_r.y = rds16(p + 8);
    c->last_seen = now_ns();
    if (c->states++ == 0) log_line("primo stato da %s via %s", inet_ntoa(c->addr), udp ? "UDP" : "TCP");
    push_state(c);
}

/**
 * Motion sample → virtual six-axis sensor. The phone sends its controller frame (x right edge,
 * y top edge, z out of the screen; proper acceleration, +1 g up at rest; right-handed rates).
 * The Switch reports acceleration as the gravity vector, the opposite of the phone's proper
 * acceleration (measured with the console's own sensor: flat 0,0,-1; upright 0,-1,0; right side
 * down +1,0,0), so all three acceleration axes are negated. Rotation follows the same opposite
 * convention, so all three axes are negated too: with only z negated, HID's orientation fused a
 * gyro that disagreed with gravity and Mario Kart's tilt steering drifted. Acceleration is in g; the angle is
 * the rotation integrated per axis in revolutions (1.0 = 360°), like HidSixAxisSensorState.angle.
 */
static void apply_motion(Client *c, const u8 *p) {
    c->last_seen = now_ns();
    if (!six_axis || !c->attached) return;
    u32 t = rd32(p);
    float dt = 0.0f;
    if (c->last_motion) {
        s32 delta = (s32)(t - c->motion_time);
        if (delta <= 0) return;                     // late UDP packet: already past it
        dt = delta > 100000 ? 0.1f : delta / 1e6f;  // after a gap, do not extrapolate further
    }
    if (c->motions++ == 0) log_line("primo dato di movimento da %s", inet_ntoa(c->addr));
    c->motion_time = t;
    c->last_motion = c->last_seen;
    HidVector *acc = &c->state.six_axis_sensor_acceleration;
    acc->x = -rds16(p + 4) / 1000.0f;
    acc->y = -rds16(p + 6) / 1000.0f;
    acc->z = -rds16(p + 8) / 1000.0f;
    const float rev = dt * 0.1f / 360.0f;           // 0.1 °/s units over dt → revolutions
    HidVector *angle = &c->state.six_axis_sensor_angle;
    angle->x -= rds16(p + 10) * rev;
    angle->y -= rds16(p + 12) * rev;
    angle->z -= rds16(p + 14) * rev;
    push_state(c);
}

// ---------------------------------------------------------------------------------------
// Protocol

static void send_hello_reply(Client *c, bool extension) {
    u8 out[16];
    size_t n = TX_HEADER;
    out[2] = 1;
    out[3] = 22;
    out[4] = TYPE_HELLO;
    out[n++] = PORT & 0xFF;          // UDP port for states (same number as TCP)
    out[n++] = PORT >> 8;
    out[n++] = OS_SWITCH;
    if (extension) {
        memcpy(out + n, "OGP", 3);
        n += 3;
        out[n++] = 1;
    }
    out[0] = (u8)(n & 0xFF);
    out[1] = (u8)(n >> 8);
    send(c->fd, out, n, 0);
}

static void handle_message(Client *c, const u8 *msg, size_t len) {
    u8 type = msg[6];
    const u8 *payload = msg + RX_HEADER;
    size_t size = len - RX_HEADER;
    if (type == TYPE_HELLO && size >= 8) {
        // A phone has one connection: an older one from the same address is stale
        // (app restarted, Wi-Fi changed) and would otherwise keep receiving its UDP states.
        for (int i = 0; i < MAX_CLIENTS; i++)
            if (&clients[i] != c && clients[i].fd >= 0 && clients[i].addr.s_addr == c->addr.s_addr)
                close_client(&clients[i], "sostituito da una nuova connessione");
        bool extension = size >= 11 && memcmp(payload + 8, "OGP", 3) == 0;
        log_line("hello da %s", inet_ntoa(c->addr));
        if (!c->attached) attach_pad(c);
        send_hello_reply(c, extension);
        c->greeted = true;
    } else if (type == TYPE_STATE && size >= 12 && c->greeted) {
        apply_state(c, payload, false);
    } else if (type == TYPE_MOTION && size >= MOTION_SIZE && c->greeted) {
        apply_motion(c, payload);
    } else if (type >= TYPE_AMIIBO_LIST_REQUEST && type <= TYPE_AMIIBO_CLEAR && c->greeted) {
        amiibo_handle(c->fd, type, payload, size);
    }
    // keyboard (16) and other extensions have no meaning on the console: ignored
}

static size_t build_status(char *out, size_t capacity);
static char status_buffer[256 + sizeof(log_tail)];

/** Reads TCP data and handles every complete frame. Returns false when the peer closed. */
static bool read_tcp(Client *c) {
    ssize_t n = recv(c->fd, c->buf + c->len, sizeof(c->buf) - c->len, 0);
    if (n == 0) return false;
    if (n < 0) return errno == EAGAIN || errno == EWOULDBLOCK;
    c->len += (size_t)n;
    c->last_seen = now_ns();
    // Diagnostics over TCP too ("OGP-STATUS" instead of a frame), in case UDP 47801 is down.
    if (!c->greeted && c->len >= 10 && memcmp(c->buf, "OGP-STATUS", 10) == 0) {
        size_t len = build_status(status_buffer, sizeof(status_buffer));
        send(c->fd, status_buffer, len, 0);
        return false;
    }
    for (;;) {
        if (c->len < 2) break;
        u16 frame = rd16(c->buf);
        if (frame < RX_HEADER || frame > sizeof(c->buf)) return false;
        if (c->len < frame) break;
        handle_message(c, c->buf, frame);
        memmove(c->buf, c->buf + frame, c->len - frame);
        c->len -= frame;
    }
    return true;
}

static u32 udp_unknown;

static void read_udp(int fd) {
    u8 packet[128];
    struct sockaddr_in from;
    socklen_t from_len = sizeof(from);
    ssize_t n = recvfrom(fd, packet, sizeof(packet), 0, (struct sockaddr *)&from, &from_len);
    if (n < RX_HEADER || rd16(packet) > n) return;
    // States are only accepted from a phone that completed the TCP handshake.
    for (int i = 0; i < MAX_CLIENTS; i++) {
        Client *c = &clients[i];
        if (c->fd >= 0 && c->greeted && c->addr.s_addr == from.sin_addr.s_addr) {
            if (packet[6] == TYPE_STATE && rd16(packet) >= RX_HEADER + 12) apply_state(c, packet + RX_HEADER, true);
            else if (packet[6] == TYPE_MOTION && rd16(packet) >= RX_HEADER + MOTION_SIZE) apply_motion(c, packet + RX_HEADER);
            return;
        }
    }
    if (udp_unknown++ == 0) log_line("stati UDP da %s ignorati: nessun hello da quell'indirizzo", inet_ntoa(from.sin_addr));
}

/** Summary line, connected phones and the latest log lines. */
static size_t build_status(char *out, size_t capacity) {
    int len = snprintf(out, capacity, "OpenGamepad sysmodule, HOS %u.%u.%u\n",
                       HOSVER_MAJOR(hosversionGet()), HOSVER_MINOR(hosversionGet()), HOSVER_MICRO(hosversionGet()));
    for (int i = 0; i < MAX_CLIENTS && len < (int)capacity - 80; i++)
        if (clients[i].fd >= 0)
            len += snprintf(out + len, capacity - len, "slot %d: %s hello=%d pad=%d stati=%u movimento=%u\n", i,
                            inet_ntoa(clients[i].addr), clients[i].greeted, clients[i].attached, clients[i].states,
                            clients[i].motions);
    size_t tail = log_tail_len < capacity - len ? log_tail_len : capacity - len;
    memcpy(out + len, log_tail + log_tail_len - tail, tail);
    return len + tail;
}

/** LAN discovery ("OGP-DISCOVER") and diagnostics ("OGP-STATUS") on UDP 47801. */
static void read_discovery(int fd) {
    char packet[64];
    struct sockaddr_in from;
    socklen_t from_len = sizeof(from);
    ssize_t n = recvfrom(fd, packet, sizeof(packet) - 1, 0, (struct sockaddr *)&from, &from_len);
    if (n <= 0) return;
    packet[n] = 0;
    if (strncmp(packet, "OGP-DISCOVER", 12) == 0) {
        char reply[64];
        int len = snprintf(reply, sizeof(reply), "OGP-HERE %d Nintendo Switch", PORT);
        sendto(fd, reply, len, 0, (struct sockaddr *)&from, from_len);
    } else if (strncmp(packet, "OGP-STATUS", 10) == 0) {
        size_t len = build_status(status_buffer, sizeof(status_buffer));
        sendto(fd, status_buffer, len, 0, (struct sockaddr *)&from, from_len);
    }
}

/** Logs a socket failure only when it differs from the previous one for the same socket. */
static void log_socket_error(int *last, const char *step, const char *kind, int port) {
    int code = errno * 4 + (step[0] == 's' ? 1 : step[0] == 'b' ? 2 : 3);
    if (code == *last) return;
    *last = code;
    log_line("%s %s %d: errno %d (riprovo ogni 2 s)", step, kind, port, errno);
}

static int open_socket(int type, int port) {
    static int last_error[3];
    int *last = &last_error[type == SOCK_STREAM ? 0 : port == PORT ? 1 : 2];
    const char *kind = type == SOCK_STREAM ? "TCP" : "UDP";
    int fd = socket(AF_INET, type, 0);
    if (fd < 0) {
        log_socket_error(last, "socket", kind, port);
        return -1;
    }
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in addr = {0};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        log_socket_error(last, "bind", kind, port);
        close(fd);
        return -1;
    }
    if (type == SOCK_STREAM && listen(fd, MAX_CLIENTS) < 0) {
        log_socket_error(last, "listen", kind, port);
        close(fd);
        return -1;
    }
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
    *last = 0;
    log_line("%s %d aperto", kind, port);
    return fd;
}

/** Returns false when the listener itself is broken and must be reopened. */
static bool accept_client(int listener) {
    struct sockaddr_in from;
    socklen_t from_len = sizeof(from);
    int fd = accept(listener, (struct sockaddr *)&from, &from_len);
    if (fd < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == ECONNABORTED || errno == EINTR) return true;
        log_line("accept: errno %d", errno);
        return errno != EBADF && errno != EINVAL && errno != ENOTSOCK;
    }
    for (int i = 0; i < MAX_CLIENTS; i++) {
        Client *c = &clients[i];
        if (c->fd >= 0) continue;
        int one = 1;
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
        fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
        c->fd = fd;
        c->addr = from.sin_addr;
        c->last_seen = now_ns();
        return true;
    }
    log_line("connessione da %s rifiutata: 4 controller già collegati", inet_ntoa(from.sin_addr));
    close(fd);
    return true;
}

/** True when a descriptor is still usable: poll() with a bad one fails as a whole. */
static bool socket_ok(int fd) {
    struct pollfd one = { .fd = fd, .events = POLLIN };
    return poll(&one, 1, 0) >= 0 && !(one.revents & POLLNVAL);
}

static void close_socket(int *fd, const char *name) {
    if (*fd < 0) return;
    log_line("socket %s chiuso, verrà riaperto", name);
    close(*fd);
    *fd = -1;
}

enum { TAG_LISTENER = -1, TAG_UDP = -2, TAG_DISCOVERY = -3 };

static int listener = -1, udp = -1, discovery = -1;
static u64 next_open;
static bool suspended;

/**
 * Answers a PSC request. Before sleep or shutdown everything that talks to other services is
 * closed, then the request is acknowledged; nothing touches sockets, hid or the SD card until
 * the console is awake again (libnx calls that state ReadyAwaken; it is MinimumAwake).
 */
static void handle_power_request(void) {
    PscPmState state;
    u32 flags;
    if (R_FAILED(pscPmModuleGetRequest(&psc_module, &state, &flags))) return;
    switch (state) {
    case PscPmState_ReadySleep:
    case PscPmState_ReadyShutdown:
        if (!suspended) {
            log_line(state == PscPmState_ReadySleep ? "sospensione: chiudo connessioni e controller" : "spegnimento");
            for (int i = 0; i < MAX_CLIENTS; i++) close_client(&clients[i], "console in sospensione");
            if (listener >= 0) close(listener);
            if (udp >= 0) close(udp);
            if (discovery >= 0) close(discovery);
            listener = udp = discovery = -1;
            log_to_sd = false;
            suspended = true;
        }
        break;
    case PscPmState_ReadyAwaken:
        if (suspended) {
            suspended = false;
            log_to_sd = true;
            log_line("risveglio");
            next_open = 0; // sockets are reopened (and retried) by the main loop
        }
        break;
    default:
        break;
    }
    pscPmModuleAcknowledge(&psc_module, state);
}

int main(int argc, char **argv) {
    (void)argc;
    (void)argv;
    for (int i = 0; i < MAX_CLIENTS; i++) clients[i].fd = -1;
    log_open();
    log_line("avvio, HOS %u.%u.%u%s", HOSVER_MAJOR(hosversionGet()), HOSVER_MINOR(hosversionGet()),
             HOSVER_MICRO(hosversionGet()), hosversionIsAtmosphere() ? " (Atmosphere)" : "");

    u64 total = 0, used = 0, pool_total = 0, pool_used = 0;
    svcGetInfo(&total, InfoType_TotalMemorySize, CUR_PROCESS_HANDLE, 0);
    svcGetInfo(&used, InfoType_UsedMemorySize, CUR_PROCESS_HANDLE, 0);
    svcGetSystemInfo(&pool_total, 0, INVALID_HANDLE, 2); // System pool
    svcGetSystemInfo(&pool_used, 1, INVALID_HANDLE, 2);
    log_line("memoria: processo %llu/%llu KiB, pool di sistema libero %llu KiB su %llu",
             (unsigned long long)(used / 1024), (unsigned long long)(total / 1024),
             (unsigned long long)((pool_total - pool_used) / 1024), (unsigned long long)(pool_total / 1024));

    six_axis = hosversionAtLeast(12, 0, 0);
    log_line(six_axis ? "giroscopio virtuale disponibile" : "giroscopio virtuale non disponibile (serve HOS 12.0.0+)");

    Result rc = hiddbgAttachHdlsWorkBuffer(&hdls_session, hdls_work, sizeof(hdls_work));
    log_line("buffer HDLS: rc 0x%x", rc);
    if (R_FAILED(rc)) diagAbortWithResult(rc);
    remove_leftover_controllers();
    log_line(psc_registered ? "registrato per sospensione/spegnimento" : "ATTENZIONE: registrazione PSC fallita");

    for (;;) {
        if (psc_registered) {
            // While suspended nothing else may run: just wait for the next power request.
            if (suspended) {
                if (R_SUCCEEDED(eventWait(&psc_module.event, UINT64_MAX))) handle_power_request();
                continue;
            }
            if (R_SUCCEEDED(eventWait(&psc_module.event, 0))) {
                handle_power_request();
                if (suspended) continue;
            }
        }

        // The network may not be ready after boot or after sleep: missing sockets are
        // reopened every 2 s, while the ones already open keep being served.
        u64 now = now_ns();
        if ((listener < 0 || udp < 0 || discovery < 0) && now >= next_open) {
            if (listener < 0) listener = open_socket(SOCK_STREAM, PORT);
            if (udp < 0) udp = open_socket(SOCK_DGRAM, PORT);
            if (discovery < 0) discovery = open_socket(SOCK_DGRAM, DISCOVERY_PORT);
            next_open = now + REOPEN_DELAY_NS;
        }

        // Only valid descriptors go to poll(): a -1 entry makes the whole call fail.
        struct pollfd fds[3 + MAX_CLIENTS];
        int tags[3 + MAX_CLIENTS];
        int n = 0;
        if (listener >= 0) { tags[n] = TAG_LISTENER; fds[n++] = (struct pollfd){ .fd = listener, .events = POLLIN }; }
        if (udp >= 0) { tags[n] = TAG_UDP; fds[n++] = (struct pollfd){ .fd = udp, .events = POLLIN }; }
        if (discovery >= 0) { tags[n] = TAG_DISCOVERY; fds[n++] = (struct pollfd){ .fd = discovery, .events = POLLIN }; }
        for (int i = 0; i < MAX_CLIENTS; i++)
            if (clients[i].fd >= 0) { tags[n] = i; fds[n++] = (struct pollfd){ .fd = clients[i].fd, .events = POLLIN }; }

        if (n == 0) {
            svcSleepThread(SECOND_NS / 10);
            continue;
        }
        if (poll(fds, n, 100) < 0) {
            // One descriptor went bad (typically after sleep mode): drop just that one.
            log_line("poll: errno %d", errno);
            if (listener >= 0 && !socket_ok(listener)) close_socket(&listener, "TCP");
            if (udp >= 0 && !socket_ok(udp)) close_socket(&udp, "UDP");
            if (discovery >= 0 && !socket_ok(discovery)) close_socket(&discovery, "ricerca");
            for (int i = 0; i < MAX_CLIENTS; i++)
                if (clients[i].fd >= 0 && !socket_ok(clients[i].fd)) close_client(&clients[i], "socket non valido");
            svcSleepThread(SECOND_NS / 10);
            continue;
        }

        for (int k = 0; k < n; k++) {
            short ev = fds[k].revents;
            if (!ev) continue;
            switch (tags[k]) {
            case TAG_LISTENER:
                if ((ev & POLLNVAL) || ((ev & POLLIN) && !accept_client(listener))) close_socket(&listener, "TCP");
                break;
            case TAG_UDP:
                if (ev & POLLNVAL) close_socket(&udp, "UDP");
                else if (ev & POLLIN) read_udp(udp);
                break;
            case TAG_DISCOVERY:
                if (ev & POLLNVAL) close_socket(&discovery, "ricerca");
                else if (ev & POLLIN) read_discovery(discovery);
                break;
            default: {
                Client *c = &clients[tags[k]];
                if (c->fd != fds[k].fd) break; // closed earlier in this round
                if (ev & POLLIN) {
                    if (!read_tcp(c)) close_client(c, "connessione chiusa dal telefono");
                } else if (ev & (POLLERR | POLLHUP | POLLNVAL)) {
                    close_client(c, "errore di connessione");
                }
                break;
            }
            }
        }

        // The phone repeats its state every 200 ms: silence means it is gone.
        now = now_ns();
        for (int i = 0; i < MAX_CLIENTS; i++) {
            Client *c = &clients[i];
            if (c->fd >= 0 && now - c->last_seen > CLIENT_TIMEOUT_NS) {
                close_client(c, "nessun dato da 10 s");
            } else if (c->fd >= 0 && c->last_motion && now - c->last_motion > MOTION_IDLE_NS) {
                // Gyro switched off on the phone: back to a still controller, not frozen mid-tilt.
                motion_rest(c);
                push_state(c);
            }
        }
    }

    for (int i = 0; i < MAX_CLIENTS; i++) close_client(&clients[i], "uscita");
    hiddbgReleaseHdlsWorkBuffer(hdls_session);
    return 0;
}
