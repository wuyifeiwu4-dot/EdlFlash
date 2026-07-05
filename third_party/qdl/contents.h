/* SPDX-License-Identifier: BSD-3-Clause */
#ifndef __CONTENTS_H__
#define __CONTENTS_H__

#include <stdbool.h>
#include <stddef.h>
#include "list.h"

struct sahara_image;
struct pathbuf;
struct contents_filter;

#ifdef ANDROID_BUILD
static inline int contents_load(struct list_head *ops __attribute__((unused)),
	const char *fn __attribute__((unused)),
	char *spec __attribute__((unused)),
	struct sahara_image *imgs __attribute__((unused)),
	const char *inc __attribute__((unused))) { return -1; }
static inline int contents_resolve_path(struct contents_filter *f __attribute__((unused)),
	const char *p __attribute__((unused)),
	struct pathbuf *out __attribute__((unused))) { return 0; }
#else
int contents_load(struct list_head *ops, const char *filename, char *specifier,
		  struct sahara_image *images, const char *incdir);
int contents_resolve_path(struct contents_filter *filter, const char *filename, struct pathbuf *path);
#endif

#endif
