// OpenGamepad sysmodule for Atmosphère: receives the OpenGamepad Wi-Fi protocol and
// turns every connected phone into a virtual Pro Controller (hiddbg HDLS).
//
// Protocol (see docs/PROTOCOL.md): the phone opens TCP to port 47800 and sends a hello;
// the reply announces the UDP port for controller states. States can also arrive on TCP.

#include <switch.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define PORT            47800
#define DISCOVERY_PORT  47801
#define MAX_CLIENTS     4
#define RX_HEADER       7   // phone -> console: len, major, minor, seq(2), type
#define TX_HEADER       5   // console -> phone: len, major, minor, type
#define TYPE_HELLO      0
#define TYPE_STATE      1
#define OS_SWITCH       4
#define CLIENT_TIMEOUT_NS (10ULL * 1000000000ULL)

// ---------------------------------------------------------------------------------------
// libnx process setup: small static heap, no applet, only the services we need.

u32 __nx_applet_type = AppletType_None;
u32 __nx_fs_num_sessions = 1;

#define INNER_HEAP_SIZE 0x300000
size_t nx_inner_heap_size = INNER_HEAP_SIZE;
char nx_inner_heap[INNER_HEAP_SIZE];

void __libnx_initheap(void) {
    extern char *fake_heap_start;
    extern char *fake_heap_end;
    fake_heap_start = nx_inner_heap;
    fake_heap_end = nx_inner_heap + nx_inner_heap_size;
}

static const SocketInitConfig socket_config = {
    .tcp_tx_buf_size = 0x1000,
    .tcp_rx_buf_size = 0x1000,
    .tcp_tx_buf_max_size = 0,
    .tcp_rx_buf_max_size = 0,
    .udp_tx_buf_size = 0x2400,
    .udp_rx_buf_size = 0xA500,
    .sb_efficiency = 4,
    .num_bsd_sessions = 3,
    .bsd_service_type = BsdServiceType_Auto,
};

void __appInit(void) {
    Result rc = smInitialize();
    if (R_FAILED(rc)) diagAbortWithResult(rc);
    if (R_SUCCEEDED(setsysInitialize())) {
        SetSysFirmwareVersion fw;
        if (R_SUCCEEDED(setsysGetFirmwareVersion(&fw)))
            hosversionSet(MAKEHOSVERSION(fw.major, fw.minor, fw.micro));
        setsysExit();
    }
    rc = hiddbgInitialize();
    if (R_FAILED(rc)) diagAbortWithResult(rc);
    rc = socketInitialize(&socket_config);
    if (R_FAILED(rc)) diagAbortWithResult(rc);
    smExit();
}

void __appExit(void) {
    socketExit();
    hiddbgExit();
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
} Client;

static Client clients[MAX_CLIENTS];

static bool attach_pad(Client *c) {
    HiddbgHdlsDeviceInfo info = {0};
    info.deviceType = HidDeviceType_FullKey3;               // Pro Controller
    info.npadInterfaceType = HidNpadInterfaceType_Bluetooth;
    info.singleColorBody = RGBA8_MAXALPHA(0x1A, 0x1A, 0x1A); // OpenGamepad black and blue
    info.singleColorButtons = RGBA8_MAXALPHA(0x7C, 0x9C, 0xFF);
    info.colorLeftGrip = RGBA8_MAXALPHA(0x1A, 0x1A, 0x1A);
    info.colorRightGrip = RGBA8_MAXALPHA(0x1A, 0x1A, 0x1A);
    if (R_FAILED(hiddbgAttachHdlsVirtualDevice(&c->pad, &info))) return false;
    memset(&c->state, 0, sizeof(c->state));
    c->state.battery_level = 4;
    c->attached = true;
    hiddbgSetHdlsState(c->pad, &c->state);
    return true;
}

static void detach_pad(Client *c) {
    if (!c->attached) return;
    hiddbgDetachHdlsVirtualDevice(c->pad);
    c->attached = false;
}

static void close_client(Client *c) {
    detach_pad(c);
    if (c->fd >= 0) close(c->fd);
    memset(c, 0, sizeof(*c));
    c->fd = -1;
}

static inline u16 rd16(const u8 *p) { return (u16)(p[0] | (p[1] << 8)); }
static inline s16 rds16(const u8 *p) { return (s16)rd16(p); }

/** XUSB state (Xbox layout) → Pro Controller, by position: Xbox A (bottom) = Switch B. */
static void apply_state(Client *c, const u8 *p) {
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
    c->last_seen = armTicksToNs(armGetSystemTick());
    if (c->attached) hiddbgSetHdlsState(c->pad, &c->state);
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
        bool extension = size >= 11 && memcmp(payload + 8, "OGP", 3) == 0;
        if (!c->attached) attach_pad(c);
        send_hello_reply(c, extension);
        c->greeted = true;
    } else if (type == TYPE_STATE && size >= 12 && c->greeted) {
        apply_state(c, payload);
    }
    // keyboard (16) and other extensions have no meaning on the console: ignored
}

/** Reads TCP data and handles every complete frame. Returns false when the peer closed. */
static bool read_tcp(Client *c) {
    ssize_t n = recv(c->fd, c->buf + c->len, sizeof(c->buf) - c->len, 0);
    if (n == 0) return false;
    if (n < 0) return errno == EAGAIN || errno == EWOULDBLOCK;
    c->len += (size_t)n;
    c->last_seen = armTicksToNs(armGetSystemTick());
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
            if (packet[6] == TYPE_STATE && rd16(packet) >= RX_HEADER + 12) apply_state(c, packet + RX_HEADER);
            return;
        }
    }
}

/** LAN discovery: answers "OGP-DISCOVER" broadcasts with the TCP port and a name. */
static void read_discovery(int fd) {
    char packet[64];
    struct sockaddr_in from;
    socklen_t from_len = sizeof(from);
    ssize_t n = recvfrom(fd, packet, sizeof(packet) - 1, 0, (struct sockaddr *)&from, &from_len);
    if (n <= 0) return;
    packet[n] = 0;
    if (strncmp(packet, "OGP-DISCOVER", 12) != 0) return;
    char reply[64];
    int len = snprintf(reply, sizeof(reply), "OGP-HERE %d Nintendo Switch", PORT);
    sendto(fd, reply, len, 0, (struct sockaddr *)&from, from_len);
}

static int open_socket(int type, int port) {
    int fd = socket(AF_INET, type, 0);
    if (fd < 0) return -1;
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in addr = {0};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    if (type == SOCK_STREAM && listen(fd, MAX_CLIENTS) < 0) {
        close(fd);
        return -1;
    }
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
    return fd;
}

static void accept_client(int listener) {
    struct sockaddr_in from;
    socklen_t from_len = sizeof(from);
    int fd = accept(listener, (struct sockaddr *)&from, &from_len);
    if (fd < 0) return;
    for (int i = 0; i < MAX_CLIENTS; i++) {
        Client *c = &clients[i];
        if (c->fd >= 0) continue;
        int one = 1;
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
        fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
        c->fd = fd;
        c->addr = from.sin_addr;
        c->last_seen = armTicksToNs(armGetSystemTick());
        return;
    }
    close(fd); // all four controller slots are in use
}

int main(int argc, char **argv) {
    (void)argc;
    (void)argv;
    for (int i = 0; i < MAX_CLIENTS; i++) clients[i].fd = -1;

    Result rc = hiddbgAttachHdlsWorkBuffer(&hdls_session, hdls_work, sizeof(hdls_work));
    if (R_FAILED(rc)) diagAbortWithResult(rc);

    int listener = -1, udp = -1, discovery = -1;
    for (;;) {
        // The network may not be ready right after boot: keep trying to open the sockets.
        if (listener < 0) listener = open_socket(SOCK_STREAM, PORT);
        if (udp < 0) udp = open_socket(SOCK_DGRAM, PORT);
        if (discovery < 0) discovery = open_socket(SOCK_DGRAM, DISCOVERY_PORT);
        if (listener < 0 || udp < 0) {
            svcSleepThread(2000000000ULL);
            continue;
        }

        // Only valid descriptors go to poll(): libnx rejects the whole call for a -1 entry.
        struct pollfd fds[3 + MAX_CLIENTS];
        int slot[MAX_CLIENTS];
        int n = 0;
        fds[n++] = (struct pollfd){ .fd = listener, .events = POLLIN };
        fds[n++] = (struct pollfd){ .fd = udp, .events = POLLIN };
        int discovery_slot = -1;
        if (discovery >= 0) {
            discovery_slot = n;
            fds[n++] = (struct pollfd){ .fd = discovery, .events = POLLIN };
        }
        for (int i = 0; i < MAX_CLIENTS; i++) {
            slot[i] = -1;
            if (clients[i].fd < 0) continue;
            slot[i] = n;
            fds[n++] = (struct pollfd){ .fd = clients[i].fd, .events = POLLIN };
        }

        if (poll(fds, n, 1000) < 0) {
            // Typically after sleep mode or a Wi-Fi change: start over with fresh sockets.
            for (int i = 0; i < MAX_CLIENTS; i++) close_client(&clients[i]);
            close(listener);
            close(udp);
            if (discovery >= 0) close(discovery);
            listener = udp = discovery = -1;
            svcSleepThread(1000000000ULL);
            continue;
        }
        if (fds[0].revents & (POLLERR | POLLNVAL)) {
            close(listener);
            listener = -1;
        } else if (fds[0].revents & POLLIN) {
            accept_client(listener);
        }
        if (fds[1].revents & POLLIN) read_udp(udp);
        if (discovery_slot >= 0 && (fds[discovery_slot].revents & POLLIN)) read_discovery(discovery);

        u64 now = armTicksToNs(armGetSystemTick());
        for (int i = 0; i < MAX_CLIENTS; i++) {
            Client *c = &clients[i];
            if (c->fd < 0) continue;
            // A client accepted during this round has no poll entry yet.
            short ev = slot[i] >= 0 ? fds[slot[i]].revents : 0;
            bool alive = true;
            if (ev & (POLLERR | POLLHUP | POLLNVAL)) alive = false;
            else if (ev & POLLIN) alive = read_tcp(c);
            // The phone repeats its state every 200 ms: silence means it is gone.
            if (now - c->last_seen > CLIENT_TIMEOUT_NS) alive = false;
            if (!alive) close_client(c);
        }
    }

    for (int i = 0; i < MAX_CLIENTS; i++) close_client(&clients[i]);
    hiddbgReleaseHdlsWorkBuffer(hdls_session);
    return 0;
}
