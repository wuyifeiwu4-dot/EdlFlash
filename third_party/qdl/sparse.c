// SPDX-License-Identifier: BSD-3-Clause
/*
 * Copyright (c) 2025, Maksim Paimushkin <maxim.paymushkin.development@gmail.com>
 * All rights reserved.
 */
#define _FILE_OFFSET_BITS 64
#ifdef _WIN32
#include <winsock2.h>
#else
#include <arpa/inet.h>
#endif
#include <ctype.h>
#include <errno.h>
#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "file.h"
#include "sparse.h"
#include "qdl.h"

int sparse_header_parse(struct qdl_file *file, sparse_header_t *sparse_header)
{
	qdl_file_seek(file, 0, SEEK_SET);

	if (qdl_file_read(file, sparse_header, sizeof(sparse_header_t)) != sizeof(sparse_header_t)) {
		ux_err("[SPARSE] Unable to read sparse header\n");
		return -EINVAL;
	}

	if (sparse_header->magic != SPARSE_HEADER_MAGIC) {
		ux_err("[SPARSE] Invalid magic in sparse header\n");
		return -EINVAL;
	}

	if (sparse_header->major_version != SPARSE_HEADER_MAJOR_VER) {
		ux_err("[SPARSE] Invalid major version in sparse header\n");
		return -EINVAL;
	}

	/*
	 * The minor version is meant to be forward compatible: a newer minor
	 * just adds fields a reader can ignore, so don't reject it. This matches
	 * AOSP libsparse, fh_loader and bkerler sparse.py.
	 */

	/*
	 * A header shorter than the struct would leave us reading fields that
	 * aren't there and desync every following chunk; reject it.
	 */
	if (sparse_header->file_hdr_sz < sizeof(sparse_header_t)) {
		ux_err("[SPARSE] Invalid file header size in sparse header\n");
		return -EINVAL;
	}

	if (sparse_header->file_hdr_sz > sizeof(sparse_header_t))
		qdl_file_seek(file, sparse_header->file_hdr_sz - sizeof(sparse_header_t), SEEK_CUR);

	return 0;
}

int sparse_chunk_header_parse(struct qdl_file *file,
			      sparse_header_t *sparse_header,
			      uint64_t *chunk_size,
			      uint32_t *value,
			      off_t *offset)
{
	chunk_header_t chunk_header;
	uint32_t fill_value = 0;
	unsigned int type;

	*chunk_size = 0;
	*value = 0;

	if (qdl_file_read(file, &chunk_header, sizeof(chunk_header_t)) != sizeof(chunk_header_t)) {
		ux_err("[SPARSE] Unable to read sparse chunk header\n");
		return -EINVAL;
	}

	if (sparse_header->chunk_hdr_sz < sizeof(chunk_header_t)) {
		ux_err("[SPARSE] Invalid chunk header size in sparse header\n");
		return -EINVAL;
	}

	if (sparse_header->chunk_hdr_sz > sizeof(chunk_header_t))
		qdl_file_seek(file, sparse_header->chunk_hdr_sz - sizeof(chunk_header_t), SEEK_CUR);

	type = chunk_header.chunk_type;
	*chunk_size = (uint64_t)chunk_header.chunk_sz * sparse_header->blk_sz;

	switch (type) {
	case CHUNK_TYPE_RAW:
		if (chunk_header.total_sz != (sparse_header->chunk_hdr_sz + *chunk_size)) {
			ux_err("[SPARSE] Bogus chunk size, type Raw\n");
			return -EINVAL;
		}

		/* Save the current file offset in the 'value' variable */
		*offset = qdl_file_seek(file, 0, SEEK_CUR);

		/*
		 * The chunk header is self-describing but says nothing about
		 * whether the payload is actually present. A truncated image would
		 * seek past EOF here and later be silently zero-padded at flash
		 * time, masking a corrupt image - reject it now.
		 */
		if (*offset >= 0 &&
		    (uint64_t)*offset + *chunk_size > (uint64_t)qdl_file_getsize(file)) {
			ux_err("[SPARSE] Raw chunk payload extends past end of file\n");
			return -EINVAL;
		}

		/* Move the file cursor forward by the size of the chunk */
		qdl_file_seek(file, *chunk_size, SEEK_CUR);
		break;
	case CHUNK_TYPE_DONT_CARE:
		if (chunk_header.total_sz != sparse_header->chunk_hdr_sz) {
			ux_err("[SPARSE] Bogus chunk size, type Don't Care\n");
			return -EINVAL;
		}
		break;
	case CHUNK_TYPE_FILL:
		if (chunk_header.total_sz != (sparse_header->chunk_hdr_sz + sizeof(fill_value))) {
			ux_err("[SPARSE] Bogus chunk size, type Fill\n");
			return -EINVAL;
		}

		if (qdl_file_read(file, &fill_value, sizeof(fill_value)) != sizeof(fill_value)) {
			ux_err("[SPARSE] Unable to read fill value\n");
			return -EINVAL;
		}

		/* Save the current fill value in the 'value' variable */
		*value = fill_value;
		break;
	case CHUNK_TYPE_CRC32:
		/*
		 * CRC32 chunk carries only a 4-byte checksum and no output
		 * blocks (chunk_sz must be 0). Skip its payload so the next
		 * chunk header is read at the right offset; program.c then
		 * skips it via the chunk_size == 0 check. Matches AOSP libsparse
		 * and bkerler sparse.py, both of which treat CRC as zero blocks.
		 */
		if (chunk_header.chunk_sz != 0) {
			ux_err("[SPARSE] Bogus chunk size, type CRC32\n");
			return -EINVAL;
		}
		if (chunk_header.total_sz > sparse_header->chunk_hdr_sz) {
			qdl_file_seek(file,
				      chunk_header.total_sz - sparse_header->chunk_hdr_sz,
				      SEEK_CUR);
		}
		break;
	default:
		ux_err("[SPARSE] Unknown chunk type: %#x\n", type);
		return -EINVAL;
	}

	return type;
}
