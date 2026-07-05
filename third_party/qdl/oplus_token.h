/* SPDX-License-Identifier: BSD-3-Clause */
#ifndef __OPLUS_TOKEN_H__
#define __OPLUS_TOKEN_H__

#include <stdbool.h>

#include <libxml/tree.h>

#include "qdl.h"

/*
 * OnePlus dynamic token authentication (demacia / setprojmodel /
 * setswprojmodel), ported from bkerler edl's oneplus.py. This is the
 * alternative to the pre-built signed-digest VIP path in oplus_vip.c:
 * older OnePlus loaders (OP5..OP9/Nord/N10/N100) unlock write/erase only
 * after a computed token handshake rather than a signed digest table.
 */
int oplus_token_load_env(struct oplus_token_config *cfg);
int oplus_token_auth(struct qdl_device *qdl);
/*
 * EDL_OP_AUTH=auto only: probe the device's param projid and, if it's a known
 * OnePlus token model, enable the handshake. Returns true when enabled. A
 * miss leaves the session byte-identical (no token, no oplus_mode).
 */
bool oplus_token_probe_auto(struct qdl_device *qdl);
void oplus_token_apply_attrs(struct qdl_device *qdl, xmlNode *node);

#endif
