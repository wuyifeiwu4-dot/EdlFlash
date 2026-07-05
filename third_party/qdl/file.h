/* SPDX-License-Identifier: BSD-3-Clause */
#ifndef __QDL_FILE_H__
#define __QDL_FILE_H__

#include <sys/types.h>

#ifdef ANDROID_BUILD

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>

struct qdl_zip;

struct qdl_file {
	int fd;
	size_t size;
};

static inline int qdl_file_open(struct qdl_zip *qzip __attribute__((unused)),
	const char *filename, struct qdl_file *file)
{
	struct stat st;
	file->fd = open(filename, O_RDONLY);
	if (file->fd < 0)
		return -1;
	if (fstat(file->fd, &st) < 0) {
		close(file->fd);
		return -1;
	}
	file->size = st.st_size;
	return 0;
}

static inline void qdl_file_close(struct qdl_file *file)
{
	if (file->fd >= 0)
		close(file->fd);
	file->fd = -1;
}

static inline size_t qdl_file_getsize(struct qdl_file *file) { return file->size; }

static inline ssize_t qdl_file_read(struct qdl_file *file, void *buf, size_t len)
{
	return read(file->fd, buf, len);
}

static inline ssize_t qdl_file_read_exact(struct qdl_file *file, void *buf, size_t len)
{
	size_t got = 0;
	while (got < len) {
		ssize_t n = read(file->fd, (char *)buf + got, len - got);
		/* A read error must not look like EOF: returning the partial count
		 * would let the caller zero-fill the rest and flash a corrupt image. */
		if (n < 0) return -1;
		if (n == 0) break;
		got += n;
	}
	return (ssize_t)got;
}

static inline off_t qdl_file_seek(struct qdl_file *file, off_t offset, int whence)
{
	return lseek(file->fd, offset, whence);
}

static inline void *qdl_file_load(struct qdl_file *file, size_t *len)
{
	void *buf = malloc(file->size);
	if (!buf) return NULL;
	lseek(file->fd, 0, SEEK_SET);
	/* Read the whole file; a short read means truncation, not success. */
	ssize_t n = qdl_file_read_exact(file, buf, file->size);
	if (n < 0 || (size_t)n != file->size) { free(buf); return NULL; }
	*len = (size_t)n;
	return buf;
}

static inline int qdl_zip_open(const char *fn __attribute__((unused)),
	struct qdl_zip **z __attribute__((unused))) { return -1; }
static inline struct qdl_zip *qdl_zip_get(struct qdl_zip *z) { return z; }
static inline void qdl_zip_put(struct qdl_zip *z __attribute__((unused))) {}

#else /* !ANDROID_BUILD */

struct zip_file;

enum qdl_file_type {
	QDL_FILE_TYPE_UNKNOWN,
	QDL_FILE_TYPE_POSIX,
	QDL_FILE_TYPE_ZIP,
};

struct qdl_file {
	enum qdl_file_type type;
	size_t size;
	int fd;
	struct zip_file *zip_file;
};

struct qdl_zip;

int qdl_file_open(struct qdl_zip *qzip, const char *filename, struct qdl_file *file);
void *qdl_file_load(struct qdl_file *file, size_t *len);
void qdl_file_close(struct qdl_file *file);
size_t qdl_file_getsize(struct qdl_file *file);
ssize_t qdl_file_read(struct qdl_file *file, void *buf, size_t len);
ssize_t qdl_file_read_exact(struct qdl_file *file, void *buf, size_t len);
off_t qdl_file_seek(struct qdl_file *file, off_t offset, int whence);

int qdl_zip_open(const char *filename, struct qdl_zip **__qdl_zip);
struct qdl_zip *qdl_zip_get(struct qdl_zip *qzip);
void qdl_zip_put(struct qdl_zip *qzip);

#endif /* ANDROID_BUILD */
#endif
