// Amiibo through emuiibo: the phone lists and picks virtual amiibo, or sends the identity of a
// dump it stores, and emuiibo (https://github.com/XorTroll/emuiibo) presents it to games.
#pragma once

#include <switch.h>

#define TYPE_AMIIBO_LIST_REQUEST 18 // phone -> console: u16 offset
#define TYPE_AMIIBO_LIST         19 // console -> phone: u16 total, u16 offset, u8 flags, u8 count, entries
#define TYPE_AMIIBO_ACTIVATE     20 // phone -> console: u8 source, then a path or a dump identity
#define TYPE_AMIIBO_STATUS       21 // console -> phone: u8 result, u32 rc, UTF-8 text
#define TYPE_AMIIBO_CLEAR        22 // phone -> console: remove the amiibo from the reader

/** Handles one amiibo message from the phone on TCP socket [fd]; replies go to the same socket. */
void amiibo_handle(int fd, u8 type, const u8 *payload, size_t size);

/** Defined in main.c. */
void log_line(const char *format, ...);
