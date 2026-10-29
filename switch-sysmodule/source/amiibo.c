// Amiibo through emuiibo. See amiibo.h and docs/PROTOCOL.md (types 18-22).
//
// emuiibo IPC ("emuiibo", all buffers HipcMapAlias), from its overlay client:
//   3 SetEmulationStatus(u32: 0 on, 1 off)
//   5 SetActiveVirtualAmiibo(in buffer: folder path)
//   6 ResetActiveVirtualAmiibo()
//   8 SetActiveVirtualAmiiboStatus(u32: 1 connected, 2 disconnected)
// A virtual amiibo is a folder under sdmc:/emuiibo/amiibo (sub-folders allowed) holding
// amiibo.json and amiibo.flag; emuiibo creates the Mii and save areas when missing.

#include "amiibo.h"

#include <dirent.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>

#define AMIIBO_ROOT      "sdmc:/emuiibo/amiibo"
#define PHONE_FOLDER     "OpenGamepad" // where amiibo sent from phones are created
#define MAX_ENTRIES      256
#define LIST_BUFFER      8192
#define LIST_FRAME_MAX   900           // keeps each reply well inside the socket buffer
#define MAX_DEPTH        4

enum { RESULT_ACTIVE = 0, RESULT_CLEARED = 1, RESULT_NO_EMUIIBO = 2, RESULT_ERROR = 3, RESULT_NOT_FOUND = 4 };
enum { SOURCE_CONSOLE = 0, SOURCE_PHONE = 1 };

// ---------------------------------------------------------------------------------------
// emuiibo client

static Service emu;
static bool emu_open;

/** sm would block until the service shows up: ask Atmosphère's sm first (command 65100). */
static bool emuiibo_registered(void) {
    SmServiceName name = smEncodeName("emuiibo");
    u8 present = 0;
    Result rc = hosversionAtLeast(12, 0, 0)
        ? tipcDispatchInOut(smGetServiceSessionTipc(), 65100, name, present)
        : serviceDispatchInOut(smGetServiceSession(), 65100, name, present);
    return R_SUCCEEDED(rc) && present;
}

static Result emu_connect(void) {
    if (emu_open) return 0;
    Result rc = smInitialize();
    if (R_FAILED(rc)) return rc;
    if (!emuiibo_registered()) {
        smExit();
        return MAKERESULT(Module_Libnx, LibnxError_NotFound);
    }
    rc = smGetService(&emu, "emuiibo");
    smExit();
    if (R_SUCCEEDED(rc)) emu_open = true;
    return rc;
}

static Result emu_set_emulation(bool on) {
    u32 status = on ? 0 : 1;
    return serviceDispatchIn(&emu, 3, status);
}

static Result emu_set_active(const char *folder) {
    static char path[FS_MAX_PATH];
    memset(path, 0, sizeof(path));
    snprintf(path, sizeof(path), "%s", folder);
    return serviceDispatch(&emu, 5,
        .buffer_attrs = { SfBufferAttr_HipcMapAlias | SfBufferAttr_In },
        .buffers = { { path, sizeof(path) } });
}

static Result emu_set_connected(bool connected) {
    u32 status = connected ? 1 : 2;
    return serviceDispatchIn(&emu, 8, status);
}

/** Drops the session after an IPC error, so the next request reconnects (emuiibo restarted). */
static Result emu_checked(Result rc) {
    if (R_FAILED(rc) && emu_open) {
        serviceClose(&emu);
        emu_open = false;
    }
    return rc;
}

// ---------------------------------------------------------------------------------------
// Replies

static void send_frame(int fd, u8 type, const u8 *payload, size_t size) {
    static u8 frame[5 + LIST_FRAME_MAX + 64];
    size_t total = 5 + size;
    if (total > sizeof(frame)) return;
    frame[0] = (u8)(total & 0xFF);
    frame[1] = (u8)(total >> 8);
    frame[2] = 1;
    frame[3] = 22;
    frame[4] = type;
    memcpy(frame + 5, payload, size);
    send(fd, frame, total, 0);
}

static void send_status(int fd, u8 result, Result rc, const char *text) {
    u8 out[5 + 96];
    size_t len = strnlen(text, 96);
    out[0] = result;
    memcpy(out + 1, &rc, 4); // little-endian
    memcpy(out + 5, text, len);
    send_frame(fd, TYPE_AMIIBO_STATUS, out, 5 + len);
}

// ---------------------------------------------------------------------------------------
// Listing: relative paths of every folder that holds an amiibo.flag

static char list_buffer[LIST_BUFFER];
static u16 list_offsets[MAX_ENTRIES];
static u16 list_count;
static size_t list_used;

static bool exists(const char *path) {
    struct stat st;
    return stat(path, &st) == 0;
}

static void scan(char *path, size_t root_len, int depth) {
    DIR *dir = opendir(path);
    if (!dir) return;
    size_t base = strlen(path);
    struct dirent *entry;
    while ((entry = readdir(dir)) && list_count < MAX_ENTRIES) {
        if (entry->d_type != DT_DIR || entry->d_name[0] == '.') continue;
        if (base + 1 + strlen(entry->d_name) + 16 >= FS_MAX_PATH) continue;
        snprintf(path + base, FS_MAX_PATH - base, "/%s", entry->d_name);
        size_t len = strlen(path);
        strcpy(path + len, "/amiibo.flag");
        bool amiibo = exists(path);
        path[len] = 0;
        if (amiibo) {
            const char *relative = path + root_len + 1;
            size_t size = strlen(relative) + 1;
            if (size <= 255 && list_used + size <= sizeof(list_buffer)) {
                list_offsets[list_count++] = (u16)list_used;
                memcpy(list_buffer + list_used, relative, size);
                list_used += size;
            }
        } else if (depth < MAX_DEPTH) {
            scan(path, root_len, depth + 1);
        }
        path[base] = 0;
    }
    closedir(dir);
}

static void handle_list(int fd, const u8 *payload, size_t size) {
    u16 offset = size >= 2 ? (u16)(payload[0] | payload[1] << 8) : 0;
    if (offset == 0) {
        static char path[FS_MAX_PATH];
        strcpy(path, AMIIBO_ROOT);
        list_count = 0;
        list_used = 0;
        scan(path, strlen(AMIIBO_ROOT), 0);
        log_line("amiibo: %u sulla scheda SD", list_count);
    }
    static u8 out[LIST_FRAME_MAX];
    out[0] = list_count & 0xFF;
    out[1] = list_count >> 8;
    out[2] = offset & 0xFF;
    out[3] = offset >> 8;
    out[4] = emu_open || R_SUCCEEDED(emu_connect()) ? 1 : 0; // flags: bit 0 = emuiibo available
    size_t n = 6;
    u8 count = 0;
    for (u16 i = offset; i < list_count; i++) {
        const char *name = list_buffer + list_offsets[i];
        size_t len = strlen(name);
        if (n + 1 + len > sizeof(out)) break;
        out[n++] = (u8)len;
        memcpy(out + n, name, len);
        n += len;
        count++;
    }
    out[5] = count;
    send_frame(fd, TYPE_AMIIBO_LIST, out, n);
}

// ---------------------------------------------------------------------------------------
// Activation

/** Keeps a name usable as a folder name on FAT32. */
static void sanitize(char *out, size_t capacity, const u8 *in, size_t len) {
    size_t n = 0;
    for (size_t i = 0; i < len && n + 1 < capacity; i++) {
        char ch = (char)in[i];
        if ((u8)ch < 0x20 || strchr("/\\:*?\"<>|", ch)) ch = '_';
        out[n++] = ch;
    }
    while (n > 0 && (out[n - 1] == ' ' || out[n - 1] == '.')) n--;
    out[n] = 0;
    if (n == 0) strcpy(out, "amiibo");
}

/** JSON string body with quotes and backslashes escaped. */
static void json_escape(char *out, size_t capacity, const char *in) {
    size_t n = 0;
    for (; *in && n + 2 < capacity; in++) {
        if (*in == '"' || *in == '\\') out[n++] = '\\';
        out[n++] = *in;
    }
    out[n] = 0;
}

/**
 * Creates (once) a virtual amiibo for a dump stored on the phone. [id] is the 8-byte amiibo id
 * from offset 0x54 of the dump, [uid] the 7-byte tag UID. Number formats follow emuiibo's own
 * conversion: game_character_id read little-endian, model_number big-endian.
 */
static bool create_from_phone(char *folder, const u8 *id, const u8 *uid, const char *name, const char *group) {
    mkdir("sdmc:/emuiibo", 0777);
    mkdir(AMIIBO_ROOT, 0777);
    mkdir(AMIIBO_ROOT "/" PHONE_FOLDER, 0777);
    if (group[0]) {
        snprintf(folder, FS_MAX_PATH, AMIIBO_ROOT "/" PHONE_FOLDER "/%s", group);
        mkdir(folder, 0777);
        snprintf(folder, FS_MAX_PATH, AMIIBO_ROOT "/" PHONE_FOLDER "/%s/%s", group, name);
    } else {
        snprintf(folder, FS_MAX_PATH, AMIIBO_ROOT "/" PHONE_FOLDER "/%s", name);
    }
    mkdir(folder, 0777);

    static char path[FS_MAX_PATH];
    if (snprintf(path, sizeof(path), "%s/amiibo.json", folder) >= (int)sizeof(path)) return false;
    // An existing one keeps its Mii, write counter and game saves.
    if (!exists(path)) {
        FILE *json = fopen(path, "w");
        if (!json) return false;
        char escaped[160];
        json_escape(escaped, sizeof(escaped), name);
        fprintf(json,
            "{\"first_write_date\":{\"y\":2019,\"m\":1,\"d\":1},"
            "\"id\":{\"game_character_id\":%u,\"character_variant\":%u,\"figure_type\":%u,"
            "\"model_number\":%u,\"series\":%u},"
            "\"last_write_date\":{\"y\":2019,\"m\":1,\"d\":1},"
            "\"mii_charinfo_file\":\"mii-charinfo.bin\",\"name\":\"%s\","
            "\"uuid\":[%u,%u,%u,%u,%u,%u,%u,0,0,0],\"use_random_uuid\":false,"
            "\"version\":0,\"write_counter\":0}\n",
            (unsigned)(id[0] | id[1] << 8), id[2], id[3], (unsigned)(id[4] << 8 | id[5]), id[6],
            escaped, uid[0], uid[1], uid[2], uid[3], uid[4], uid[5], uid[6]);
        fclose(json);
        log_line("amiibo: creato %s", folder + sizeof(AMIIBO_ROOT));
    }
    if (snprintf(path, sizeof(path), "%s/amiibo.flag", folder) >= (int)sizeof(path)) return false;
    if (!exists(path)) {
        FILE *flag = fopen(path, "w");
        if (!flag) return false;
        fclose(flag);
    }
    return true;
}

static void activate(int fd, const char *folder, const char *label) {
    Result rc = emu_connect();
    if (R_FAILED(rc)) {
        log_line("amiibo: emuiibo non disponibile (rc 0x%x)", rc);
        send_status(fd, RESULT_NO_EMUIIBO, rc, "emuiibo");
        return;
    }
    rc = emu_checked(emu_set_active(folder));
    if (R_SUCCEEDED(rc)) rc = emu_checked(emu_set_emulation(true));
    if (R_SUCCEEDED(rc)) rc = emu_checked(emu_set_connected(true));
    log_line("amiibo: %s → rc 0x%x", label, rc);
    send_status(fd, R_SUCCEEDED(rc) ? RESULT_ACTIVE : RESULT_ERROR, rc, label);
}

static void handle_activate(int fd, const u8 *payload, size_t size) {
    static char folder[FS_MAX_PATH];
    if (size >= 2 && payload[0] == SOURCE_CONSOLE) {
        size_t len = size - 1;
        if (len >= 200) len = 199;
        char relative[200];
        memcpy(relative, payload + 1, len);
        relative[len] = 0;
        if (strstr(relative, "..")) return;
        snprintf(folder, sizeof(folder), AMIIBO_ROOT "/%s", relative);
        static char flag[FS_MAX_PATH];
        if (snprintf(flag, sizeof(flag), "%s/amiibo.flag", folder) >= (int)sizeof(flag) || !exists(flag)) {
            send_status(fd, RESULT_NOT_FOUND, 0, relative);
            return;
        }
        const char *label = strrchr(relative, '/');
        activate(fd, folder, label ? label + 1 : relative);
    } else if (size >= 17 && payload[0] == SOURCE_PHONE) {
        // u8 source, u8 id[8], u8 uid[7], u8 name_len, name, u8 group_len, group
        const u8 *id = payload + 1, *uid = payload + 9;
        size_t name_len = payload[16];
        if (17 + name_len > size) return;
        char name[64], group[64] = "";
        sanitize(name, sizeof(name), payload + 17, name_len);
        size_t at = 17 + name_len;
        if (at < size) {
            size_t group_len = payload[at];
            if (at + 1 + group_len <= size && group_len > 0) sanitize(group, sizeof(group), payload + at + 1, group_len);
        }
        if (!create_from_phone(folder, id, uid, name, group)) {
            log_line("amiibo: impossibile scrivere %s", folder);
            send_status(fd, RESULT_ERROR, 0, name);
            return;
        }
        activate(fd, folder, name);
    }
}

static void handle_clear(int fd) {
    Result rc = emu_connect();
    if (R_SUCCEEDED(rc)) rc = emu_checked(emu_set_connected(false));
    log_line("amiibo: rimosso dal lettore (rc 0x%x)", rc);
    send_status(fd, R_SUCCEEDED(rc) ? RESULT_CLEARED : RESULT_NO_EMUIIBO, rc, "");
}

void amiibo_handle(int fd, u8 type, const u8 *payload, size_t size) {
    switch (type) {
    case TYPE_AMIIBO_LIST_REQUEST: handle_list(fd, payload, size); break;
    case TYPE_AMIIBO_ACTIVATE: handle_activate(fd, payload, size); break;
    case TYPE_AMIIBO_CLEAR: handle_clear(fd); break;
    default: break;
    }
}
