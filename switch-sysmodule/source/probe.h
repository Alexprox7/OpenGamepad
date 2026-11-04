// Motion probe: diagnostic HDLS controllers with fixed six-axis values (see probe.c).
#pragma once

#include <switch.h>

void probe_start(void);
void probe_stop(void);

/** Dumps every HDLS state (virtual and real controllers), rewrites the probes' six-axis values
 *  with ApplyHdlsStateList and dumps again, logging what HID keeps. */
void probe_experiment(void);

/** Creates a controller through ApplyHdlsStateList with the six-axis attributes set from the start. */
void probe_create_via_list(void);

/** Defined in main.c. */
extern HiddbgHdlsSessionId hdls_session;

/** Defined in main.c. */
void log_line(const char *format, ...);
