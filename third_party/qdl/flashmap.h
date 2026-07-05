/* SPDX-License-Identifier: BSD-3-Clause */
#ifndef __FLASHMAP_H__
#define __FLASHMAP_H__

#include <stdbool.h>
#include "list.h"

struct sahara_image;

#ifdef ANDROID_BUILD
static inline int flashmap_load(struct list_head *ops __attribute__((unused)),
	const char *fn __attribute__((unused)),
	char *spec __attribute__((unused)),
	struct sahara_image *imgs __attribute__((unused)),
	const char *inc __attribute__((unused))) { return -1; }
static inline int zipper_write(const char *fn __attribute__((unused)),
	struct list_head *ops __attribute__((unused)),
	struct sahara_image *imgs __attribute__((unused))) { return -1; }
#else
int flashmap_load(struct list_head *ops, const char *filename, char *specifier,
		  struct sahara_image *images, const char *incdir);
int zipper_write(const char *filename, struct list_head *ops, struct sahara_image *images);
#endif

#endif
