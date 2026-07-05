// SPDX-License-Identifier: BSD-3-Clause
/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 */
#include "firehose.h"
#include <stdlib.h>
#include <string.h>
#define _FILE_OFFSET_BITS 64
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <dirent.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>
#include <unistd.h>

#include "qdl.h"
#include "gpt.h"

struct gpt_guid {
	uint32_t data1;
	uint16_t data2;
	uint16_t data3;
	uint8_t  data4[8];
} __attribute__((packed));

static const struct gpt_guid gpt_zero_guid = {0};

/* Standard GPT CRC32 (zlib/IEEE, reflected, poly 0xedb88320). */
static uint32_t gpt_crc32_update(uint32_t crc, const void *data, size_t len)
{
	const uint8_t *p = data;
	size_t i;
	int k;

	for (i = 0; i < len; i++) {
		crc ^= p[i];
		for (k = 0; k < 8; k++)
			crc = (crc >> 1) ^ (0xedb88320u & -(crc & 1u));
	}

	return crc;
}

struct gpt_header {
	uint8_t signature[8];
	uint32_t revision;
	uint32_t header_size;
	uint32_t header_crc32;
	uint32_t reserved;
	uint64_t current_lba;
	uint64_t backup_lba;
	uint64_t first_usable_lba;
	uint64_t last_usable_lba;
	struct gpt_guid disk_guid;
	uint64_t part_entry_lba;
	uint32_t num_part_entries;
	uint32_t part_entry_size;
	uint32_t part_array_crc32;
	uint8_t reserved2[420];
} __attribute__((packed));

struct gpt_entry {
	struct gpt_guid type_guid;
	struct gpt_guid unique_guid;
	uint64_t first_lba;
	uint64_t last_lba;
	uint64_t attrs;
	uint16_t name_utf16le[36];
} __attribute__((packed));

struct gpt_partition {
	const char *name;
	unsigned int partition;
	unsigned int start_sector;
	unsigned int num_sectors;

	struct gpt_partition *next;
};

static struct gpt_partition *gpt_partitions;
static struct gpt_partition *gpt_partitions_last;

static void utf16le_to_utf8(uint16_t *in, size_t in_len, uint8_t *out, size_t out_len)
{
	uint32_t codepoint;
	uint16_t high;
	uint16_t low;
	uint16_t w;
	size_t i;
	size_t j = 0;

	for (i = 0; i < in_len; i++) {
		w = in[i];

		if (w >= 0xd800 && w <= 0xdbff) {
			high = w - 0xd800;

			if (i < in_len) {
				w = in[++i];
				if (w >= 0xdc00 && w <= 0xdfff) {
					low = w - 0xdc00;
					codepoint = (((uint32_t)high << 10) | low) + 0x10000;
				} else {
					/* Surrogate without low surrogate */
					codepoint = 0xfffd;
				}
			} else {
				/* Lone high surrogate at end of string */
				codepoint = 0xfffd;
			}
		} else if (w >= 0xdc00 && w <= 0xdfff) {
			/* Low surrogate without high */
			codepoint = 0xfffd;
		} else {
			codepoint = w;
		}

		if (codepoint == 0)
			break;

		if (codepoint <= 0x7f) {
			if (j + 1 >= out_len)
				break;
			out[j++] = (uint8_t)codepoint;
		} else if (codepoint <= 0x7ff) {
			if (j + 2 >= out_len)
				break;
			out[j++] = 0xc0 | ((codepoint >> 6) & 0x1f);
			out[j++] = 0x80 | (codepoint & 0x3f);
		} else if (codepoint <= 0xffff) {
			if (j + 3 >= out_len)
				break;
			out[j++] = 0xe0 | ((codepoint >> 12) & 0x0f);
			out[j++] = 0x80 | ((codepoint >> 6) & 0x3f);
			out[j++] = 0x80 | (codepoint & 0x3f);
		} else if (codepoint <= 0x10ffff) {
			if (j + 4 >= out_len)
				break;
			out[j++] = 0xf0 | ((codepoint >> 18) & 0x07);
			out[j++] = 0x80 | ((codepoint >> 12) & 0x3f);
			out[j++] = 0x80 | ((codepoint >> 6) & 0x3f);
			out[j++] = 0x80 | (codepoint & 0x3f);
		}
	}

	out[j] = '\0';
}

static int gpt_load_table_from_partition(struct qdl_device *qdl, unsigned int phys_partition, bool *eof)
{
	struct gpt_partition *partition;
	struct gpt_entry *entry;
	struct gpt_header gpt;
	uint8_t buf[4096];
	struct firehose_op op;
	unsigned int offset;
	uint64_t lba;
	char lba_buf[21];
	uint16_t name_utf16le[36];
	char name[36 * 4];
	uint32_t header_crc;
	uint32_t computed_crc;
	uint64_t array_bytes;
	uint64_t array_consumed;
	uint32_t array_crc;
	struct gpt_partition *local_head = NULL;
	struct gpt_partition *local_tail = NULL;
	int ret;
	unsigned int i;

	memset(&op, 0, sizeof(op));

	op.type = FIREHOSE_OP_READ;
	op.sector_size = qdl->sector_size;
	op.start_sector = "1";
	op.num_sectors = 1;
	op.partition = phys_partition;
	/* OPlus firehose rejects label-less reads; the GPT lives at PrimaryGPT */
	if (qdl->oplus_mode)
		op.label = "PrimaryGPT";

	memset(&buf, 0, sizeof(buf));
	ret = firehose_read_buf(qdl, &op, &gpt, sizeof(gpt));
	if (ret) {
		/* Assume that we're beyond the last partition */
		*eof = true;
		return -1;
	}

	if (memcmp(gpt.signature, "EFI PART", 8)) {
		ux_err("partition %d has not GPT header\n", phys_partition);
		return 0;
	}

	/*
	 * Require an entry size that is a power-of-two divisor of the sector and
	 * at least one struct gpt_entry. This rejects part_entry_size == 0 and
	 * any value that would let "offset + sizeof(entry)" run past the sector
	 * buffer below.
	 */
	if (gpt.part_entry_size < sizeof(struct gpt_entry) ||
	    gpt.part_entry_size > qdl->sector_size ||
	    qdl->sector_size % gpt.part_entry_size != 0 ||
	    qdl->sector_size > sizeof(buf) ||
	    gpt.num_part_entries > 1024) {
		ux_debug("partition %d has invalid GPT header\n", phys_partition);
		return -1;
	}

	/* Verify the header CRC before trusting any geometry it carries. */
	if (gpt.header_size < 92 || gpt.header_size > sizeof(gpt)) {
		ux_err("partition %d has invalid GPT header size\n", phys_partition);
		return -1;
	}
	header_crc = gpt.header_crc32;
	gpt.header_crc32 = 0;
	computed_crc = gpt_crc32_update(0xffffffffu, &gpt, gpt.header_size) ^ 0xffffffffu;
	gpt.header_crc32 = header_crc;
	if (computed_crc != header_crc) {
		ux_err("partition %d GPT header CRC mismatch (got %08x want %08x)\n",
		       phys_partition, computed_crc, header_crc);
		return -1;
	}

	ux_debug("Loading GPT table from physical partition %d\n", phys_partition);
	array_bytes = (uint64_t)gpt.num_part_entries * gpt.part_entry_size;
	array_consumed = 0;
	array_crc = 0xffffffffu;
	for (i = 0; i < gpt.num_part_entries; i++) {
		offset = (i * gpt.part_entry_size) % qdl->sector_size;

		if (offset == 0) {
			lba = gpt.part_entry_lba + i * gpt.part_entry_size / qdl->sector_size;
			snprintf(lba_buf, sizeof(lba_buf), "%" PRIu64, lba);
			op.start_sector = lba_buf;

			memset(buf, 0, sizeof(buf));
			ret = firehose_read_buf(qdl, &op, buf, sizeof(buf));
			if (ret) {
				ux_err("failed to read GPT partition entries from %d:%" PRIu64 "\n", phys_partition, lba);
				return -1;
			}

			/* Accumulate only the bytes belonging to the entry array. */
			if (array_consumed < array_bytes) {
				uint64_t remaining = array_bytes - array_consumed;
				size_t chunk = remaining < qdl->sector_size ? (size_t)remaining : qdl->sector_size;

				array_crc = gpt_crc32_update(array_crc, buf, chunk);
				array_consumed += chunk;
			}
		}

		entry = (struct gpt_entry *)(buf + offset);

		if (!memcmp(&entry->type_guid, &gpt_zero_guid, sizeof(struct gpt_guid)))
			continue;

		memcpy(name_utf16le, entry->name_utf16le, sizeof(name_utf16le));
		utf16le_to_utf8(name_utf16le, 36, (uint8_t *)name, sizeof(name));

		partition = calloc(1, sizeof(*partition));
		partition->name = strdup(name);
		partition->partition = phys_partition;
		partition->start_sector = entry->first_lba;
		/* if first_lba == last_lba there is 1 sector worth of data (IE: add 1 below) */
		partition->num_sectors = entry->last_lba - entry->first_lba + 1;

		ux_debug("  %3d: %s start sector %u, num sectors %u\n", i, partition->name,
			 partition->start_sector, partition->num_sectors);

		/* Stage this LUN's partitions locally; publish only once the array
		 * CRC confirms the geometry, so a bad table never leaks into the
		 * global list (which the caller treats as authoritative). */
		if (local_head) {
			local_tail->next = partition;
			local_tail = partition;
		} else {
			local_head = partition;
			local_tail = partition;
		}
	}

	array_crc ^= 0xffffffffu;
	if (array_crc != gpt.part_array_crc32) {
		ux_err("partition %d GPT partition array CRC mismatch (got %08x want %08x)\n",
		       phys_partition, array_crc, gpt.part_array_crc32);
		while (local_head) {
			partition = local_head->next;
			free((void *)local_head->name);
			free(local_head);
			local_head = partition;
		}
		return -1;
	}

	if (local_head) {
		if (gpt_partitions)
			gpt_partitions_last->next = local_head;
		else
			gpt_partitions = local_head;
		gpt_partitions_last = local_tail;
	}

	return 0;
}

static int gpt_load_tables(struct qdl_device *qdl)
{
	unsigned int max_luns;
	unsigned int i;
	bool eof;

	if (gpt_partitions)
		return 0;

	/*
	 * eMMC/NAND/NVMe expose a single physical partition; UFS spans several
	 * LUNs, but the count is device-specific (commonly 6). Probe upward and
	 * stop at the device boundary instead of hammering a fixed eight: an
	 * absent LUN rejects every read with a "failed to open" NAK, and running
	 * the handshake against those phantom LUNs is a real source of flashing
	 * stalls. Only an open failure sets eof; a LUN that merely lacks a GPT
	 * does not, so it never truncates the scan. Once partitions have been
	 * read, confirm the first open failure with a retry, so a transient
	 * glitch on a real LUN isn't mistaken for the end of the device. Eight
	 * stays as the upper bound for UFS.
	 */
	max_luns = (qdl->current_storage_type == QDL_STORAGE_UFS) ? 8 : 1;

	for (i = 0; i < max_luns; i++) {
		eof = false;
		gpt_load_table_from_partition(qdl, i, &eof);
		if (!eof || !gpt_partitions)
			continue;

		/* Re-probe before trusting the boundary: a real LUN that hit a
		 * transient error answers on the retry, an absent one does not. */
		eof = false;
		gpt_load_table_from_partition(qdl, i, &eof);
		if (eof)
			break;
	}

	/* Success as long as at least one LUN produced a valid GPT. */
	return gpt_partitions ? 0 : -1;
}

void gpt_reset_cache(void)
{
	struct gpt_partition *part;

	while (gpt_partitions) {
		part = gpt_partitions->next;
		free((void *)gpt_partitions->name);
		free(gpt_partitions);
		gpt_partitions = part;
	}
	gpt_partitions_last = NULL;
}

int gpt_find_by_name(struct qdl_device *qdl, const char *name, int *phys_partition,
		     unsigned int *start_sector, unsigned int *num_sectors)
{
	struct gpt_partition *gpt_part;
	bool found = false;
	int ret;

	if (qdl->dev_type == QDL_DEVICE_SIM)
		return 0;

	ret = gpt_load_tables(qdl);
	if (ret < 0)
		return -1;

	for (gpt_part = gpt_partitions; gpt_part; gpt_part = gpt_part->next) {
		if (*phys_partition >= 0 && gpt_part->partition != (unsigned int)(*phys_partition))
			continue;

		if (strcmp(gpt_part->name, name))
			continue;

		if (found) {
			ux_err("duplicate candidates for partition \"%s\" found\n", name);
			return -1;
		}

		*phys_partition = gpt_part->partition;
		*start_sector = gpt_part->start_sector;
		*num_sectors = gpt_part->num_sectors;

		found = true;
	}

	if (!found) {
		if (*phys_partition >= 0)
			ux_err("no partition \"%s\" found on physical partition %d\n", name, *phys_partition);
		else
			ux_err("no partition \"%s\" found\n", name);
		return -1;
	}

	return 0;
}

int gpt_resolve_deferrals(struct qdl_device *qdl, struct list_head *ops)
{
	unsigned int start_sector;
	struct firehose_op *op;
	char buf[20];
	int ret;

	list_for_each_entry(op, ops, node) {
		if (op->type != FIREHOSE_OP_PROGRAM &&
		    op->type != FIREHOSE_OP_ERASE &&
		    op->type != FIREHOSE_OP_READ &&
		    op->type != FIREHOSE_OP_GET_SHA256_DIGEST)
			continue;

		if (!op->gpt_partition)
			continue;

		ret = gpt_find_by_name(qdl, op->gpt_partition, &op->partition,
				       &start_sector, &op->num_sectors);
		if (ret < 0)
			return -1;

		sprintf(buf, "%u", start_sector);
		free((void *)op->start_sector);
		op->start_sector = strdup(buf);

		/* OPlus firehose requires a label; reuse the partition name */
		if (qdl->oplus_mode && !op->label)
			op->label = strdup(op->gpt_partition);
	}

	return 0;
}
