// Motion probe: diagnostic HDLS controllers with fixed six-axis values (see probe.c).
#pragma once

#include <switch.h>

void probe_start(void);
void probe_stop(void);

/** Defined in main.c. */
void log_line(const char *format, ...);
