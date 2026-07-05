// SPDX-License-Identifier: BSD-3-Clause
/*
 * Copyright (c) 2016-2017, Linaro Ltd.
 * All rights reserved.
 */
#include "contents.h"
#include "pathbuf.h"
#include <assert.h>
#define _FILE_OFFSET_BITS 64
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <libgen.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <libxml/parser.h>
#include <libxml/tree.h>

#include "program.h"
#include "file.h"
#include "qdl.h"
#include "firehose.h"
#include "sparse.h"
#include "gpt.h"

static int load_erase_tag(struct list_head *ops, xmlNode *node, bool is_nand)
{
	struct firehose_op *program;
	int errors = 0;

	int optional = 0;

	program = firehose_alloc_op(FIREHOSE_OP_ERASE);
	if (!program)
		return -1;

	program->is_nand = is_nand;

	program->sector_size = attr_as_unsigned(node, "SECTOR_SIZE_IN_BYTES", &errors);
	program->num_sectors = attr_as_unsigned(node, "num_partition_sectors", &errors);
	program->partition = attr_as_unsigned(node, "physical_partition_number", &errors);
	program->start_sector = attr_as_string(node, "start_sector", &errors);
	/* OPlus firehose requires a label; absent on stock targets */
	program->label = attr_as_string(node, "label", &optional);
	if (is_nand) {
		program->pages_per_block = attr_as_unsigned(node, "PAGES_PER_BLOCK", &errors);
	}

	if (errors) {
		ux_err("errors while parsing erase tag\n");
		free((void *)program->label);
		free((void *)program->start_sector);
		free(program);
		return -EINVAL;
	}

	if (!program->num_sectors) {
		ux_err("erase tag with num_sectors=0 not allowed\n");
		free((void *)program->label);
		free((void *)program->start_sector);
		free(program);
		return -EINVAL;
	}

	list_append(ops, &program->node);

	return 0;
}

static int program_load_sparse(struct list_head *ops, struct firehose_op *program, struct qdl_file *file)
{
	struct firehose_op *program_sparse = NULL;
	char tmp[PATH_MAX];

	sparse_header_t sparse_header;
	unsigned int start_sector;
	uint32_t sparse_fill_value;
	uint64_t chunk_size;
	off_t sparse_offset;
	uint64_t out_sectors = 0;
	uint64_t out_blocks = 0;
	int chunk_type;

	if (sparse_header_parse(file, &sparse_header)) {
		/*
		 * If the XML tag "program" contains the attribute 'sparse="true"'
		 * for a partition node but lacks a sparse header,
		 * it will be validated against the defined partition size.
		 * If the sizes match, it is likely that the 'sparse="true"' attribute
		 * was set by mistake, fix the sparse flag and add the
		 * program entry to the list.
		 */
		if ((off_t)program->sector_size * program->num_sectors == (off_t)qdl_file_getsize(file)) {
			program->sparse = false;
			return 0;
		}

		ux_err("[PROGRAM] Unable to parse sparse header at %s...failed\n",
		       program->filename);
		return -1;
	}

	for (uint32_t i = 0; i < sparse_header.total_chunks; ++i) {
		chunk_type = sparse_chunk_header_parse(file, &sparse_header,
						       &chunk_size,
						       &sparse_fill_value,
						       &sparse_offset);
		if (chunk_type < 0) {
			ux_err("[PROGRAM] Unable to parse sparse chunk %i at %s...failed\n",
			       i, program->filename);
			return -1;
		}

		if (chunk_size == 0)
			continue;

		if (chunk_size % program->sector_size != 0) {
			ux_err("[SPARSE] File chunk #%u size %" PRIu64 " is not a sector-multiple\n",
			       i, chunk_size);
			return -1;
		}

		if (chunk_size / program->sector_size >= UINT_MAX) {
			/*
			 * Perhaps the programmer can handle larger "num_sectors"?
			 * Let's cap it for now, it's big enough for now...
			 */
			ux_err("[SPARSE] File chunk #%u size %" PRIu64 " is too large\n",
			       i, chunk_size);
			return -1;
		}

		/*
		 * Keep the running output within the declared partition. A
		 * malformed image could otherwise emit program ops that run past
		 * the partition and overwrite the neighbouring one. DONT_CARE
		 * chunks also advance start_sector, so account for every chunk.
		 */
		out_sectors += chunk_size / program->sector_size;
		/* total_blks 对账(blk_sz 单位，与 out_sectors 的 sector_size 单位不同)：累加每个非零块的
		 * 块数，循环结束后须等于头声明 total_blks，否则镜像被截断。chunk_size>0 蕴含 blk_sz>0，除法安全。 */
		out_blocks += chunk_size / sparse_header.blk_sz;
		if (program->num_sectors && out_sectors > program->num_sectors) {
			ux_err("[SPARSE] %s output (%" PRIu64 " sectors) exceeds partition size (%u sectors)\n",
			       program->filename, out_sectors, program->num_sectors);
			return -1;
		}

		if (chunk_type == CHUNK_TYPE_RAW || chunk_type == CHUNK_TYPE_FILL) {
			program_sparse = firehose_alloc_op(FIREHOSE_OP_PROGRAM);
			if (!program_sparse)
				return -1;

			program_sparse->pages_per_block = program->pages_per_block;
			program_sparse->sector_size = program->sector_size;
			program_sparse->file_offset = program->file_offset;
			program_sparse->filename = strdup(program->filename);
			program_sparse->label = program->label ? strdup(program->label) : NULL;
			program_sparse->partition = program->partition;
			program_sparse->sparse = program->sparse;
			program_sparse->start_sector = strdup(program->start_sector);
			program_sparse->last_sector = program->last_sector;
			program_sparse->is_nand = program->is_nand;
			program_sparse->zip = qdl_zip_get(program->zip);

			program_sparse->sparse_chunk_type = chunk_type;
			program_sparse->num_sectors = chunk_size / program->sector_size;

			if (chunk_type == CHUNK_TYPE_RAW)
				program_sparse->sparse_offset = sparse_offset;
			else
				program_sparse->sparse_fill_value = sparse_fill_value;

			list_append(ops, &program_sparse->node);
		}

		start_sector = (unsigned int)strtoul(program->start_sector, NULL, 0);
		start_sector += chunk_size / program->sector_size;
		sprintf(tmp, "%u", start_sector);
		free((void *)program->start_sector);
		program->start_sector = strdup(tmp);
	}

	/* 全部块累加须等于头声明 total_blks，否则 sparse 镜像被截断(尾部分区未写)。对齐 AOSP libsparse。 */
	if (sparse_header.total_blks != out_blocks) {
		ux_err("[SPARSE] %s declares %u blocks but chunks sum to %" PRIu64 "...failed\n",
		       program->filename, sparse_header.total_blks, out_blocks);
		return -1;
	}

	return 0;
}

static int program_resolve_path(struct firehose_op *program, const char *program_file,
				struct contents_filter *contents_filter, const char *incdir)
{
	char *program_file_copy;
	char candidate[PATH_MAX];
	const char *filename = program->filename;
	const char *program_dir;
	struct pathbuf pathbuf = {};
	char *resolved;
	size_t len;
	int ret;

	/* Don't attempt to resolve filenames in zip files */
	if (program->zip)
		return 0;

	/* Attempt to look up the file in the contents database */
	ret = contents_resolve_path(contents_filter, filename, &pathbuf);
	if (ret == 1) {
		resolved = strdup(qdl_pathbuf_str(&pathbuf));
		if (!resolved)
			return -1;

		free((void *)program->filename);
		program->filename = resolved;
		return 0;
	}

	/* Look for the file in include directory */
	if (incdir) {
		snprintf(candidate, sizeof(candidate), "%s/%s", incdir, filename);
		if (!access(candidate, F_OK)) {
			resolved = strdup(candidate);
			if (!resolved)
				return -ENOMEM;
			free((void *)program->filename);
			program->filename = resolved;
			return 0;
		}
	}

	/* Look for the file adjacent to the program XML */
	len = strlen(program_file) + 1;
	program_file_copy = alloca(len);
	memcpy(program_file_copy, program_file, len);
	program_dir = dirname(program_file_copy);
	if (strcmp(program_dir, ".")) {
		ret = snprintf(candidate, sizeof(candidate), "%s/%s", program_dir, filename);
		if (ret < 0 || (size_t)ret >= sizeof(candidate))
			return -ENAMETOOLONG;

		if (!access(candidate, F_OK)) {
			resolved = strdup(candidate);
			if (!resolved)
				return -ENOMEM;
			free((void *)program->filename);
			program->filename = resolved;
		}
	}

	return 0;
}

static int load_program_tag(struct list_head *ops, xmlNode *node, bool is_nand,
			    bool allow_missing, struct qdl_zip *zip,
			    const char *program_file, struct contents_filter *contents_filter, const char *incdir)
{
	struct firehose_op *program;
	struct qdl_file file = {};
	int errors = 0;
	int ret;

	program = firehose_alloc_op(FIREHOSE_OP_PROGRAM);
	if (!program)
		return -1;

	program->is_nand = is_nand;

	program->sector_size = attr_as_unsigned(node, "SECTOR_SIZE_IN_BYTES", &errors);
	program->zip = qdl_zip_get(zip);
	program->filename = attr_as_string(node, "filename", &errors);
	program->label = attr_as_string(node, "label", &errors);
	program->num_sectors = attr_as_unsigned(node, "num_partition_sectors", &errors);
	program->partition = attr_as_unsigned(node, "physical_partition_number", &errors);
	program->sparse = attr_as_bool(node, "sparse", &errors);
	program->start_sector = attr_as_string(node, "start_sector", &errors);

	if (is_nand) {
		program->pages_per_block = attr_as_unsigned(node, "PAGES_PER_BLOCK", &errors);
		if (xmlGetProp(node, (xmlChar *)"last_sector")) {
			program->last_sector = attr_as_unsigned(node, "last_sector", &errors);
		}
	} else {
		/* file_sector_offset is optional and defaults to 0 */
		int optional = 0;
		program->file_offset = attr_as_unsigned(node, "file_sector_offset", &optional);
	}

	if (errors) {
		ux_err("errors while parsing program tag\n");
		goto err_free_op;
	}

	if (program->filename) {
		ret = program_resolve_path(program, program_file, contents_filter, incdir);
		if (ret < 0)
			goto err_free_op;

		ret = qdl_file_open(zip, program->filename, &file);
		if (ret < 0) {
			ux_info("unable to open %s", program->filename);
			if (!allow_missing) {
				ux_info("...failing\n");
				goto err_free_op;
			}
			ux_info("...ignoring\n");

			free((void *)program->filename);
			program->filename = NULL;
		}
	}

	if (program->filename && program->sparse) {
		ret = program_load_sparse(ops, program, &file);
		if (ret < 0)
			goto err_free_op;

		/*
		 * A real sparse image: the chunk ops were added to the list, so
		 * drop the parent's filename to keep it from being written too.
		 * If program_load_sparse detected the file is actually raw it
		 * cleared ->sparse; keep the filename so the parent is written as
		 * a plain raw program.
		 */
		if (program->sparse) {
			free((void *)program->filename);
			program->filename = NULL;
		}
	}

	list_append(ops, &program->node);

	qdl_file_close(&file);

	return 0;

err_free_op:
	qdl_file_close(&file);
	qdl_zip_put(program->zip);
	free((void *)program->filename);
	free((void *)program->label);
	free((void *)program->start_sector);
	free(program);

	return -1;
}

int program_load_xml(struct list_head *ops, xmlDoc *doc, struct qdl_zip *zip, const char *program_file,
		     bool is_nand, bool allow_missing, struct contents_filter *contents_filter, const char *incdir)
{
	xmlNode *node;
	xmlNode *root;
	int errors = 0;

	root = xmlDocGetRootElement(doc);
	for (node = root->children; node ; node = node->next) {
		if (node->type != XML_ELEMENT_NODE)
			continue;

		if (!xmlStrcmp(node->name, (xmlChar *)"erase"))
			errors = load_erase_tag(ops, node, is_nand);
		else if (!xmlStrcmp(node->name, (xmlChar *)"program"))
			errors = load_program_tag(ops, node, is_nand, allow_missing, zip,
						  program_file, contents_filter, incdir);
		else {
			ux_err("unrecognized tag \"%s\" in program-type file \"%s\"\n", node->name, program_file);
			errors = -EINVAL;
		}

		if (errors)
			break;
	}

	return errors;
}

int program_load(struct list_head *ops, const char *program_file, bool is_nand,
		 bool allow_missing, struct contents_filter *contents_filter, const char *incdir)
{
	xmlDoc *doc;
	int errors;

	doc = xmlReadFile(program_file, NULL, 0);
	if (!doc) {
		ux_err("failed to parse program-type file \"%s\"\n", program_file);
		return -EINVAL;
	}

	errors = program_load_xml(ops, doc, NULL, program_file, is_nand, allow_missing, contents_filter, incdir);

	xmlFreeDoc(doc);

	return errors;
}

int erase_execute(struct qdl_device *qdl, struct firehose_op *op,
		  int (*apply)(struct qdl_device *qdl, struct firehose_op *op))
{
	int ret;

	assert(op->type == FIREHOSE_OP_ERASE);

	ret = apply(qdl, op);
	if (ret)
		return ret;

	return 0;
}

static struct firehose_op *program_find_partition(struct list_head *ops, const char *partition)
{
	struct firehose_op *program;
	const char *label;

	list_for_each_entry(program, ops, node) {
		if (program->type != FIREHOSE_OP_PROGRAM)
			continue;

		label = program->label;
		if (!label)
			continue;

		if (!strcmp(label, partition))
			return program;
	}

	return NULL;
}

/**
 * program_find_bootable_partition() - find one bootable partition
 *
 * Returns partition number, or negative errno on failure.
 *
 * Scan program tags for a partition with the label "sbl1", "xbl" or "xbl_a"
 * and return the partition number for this. If more than one line matches
 * we're informing the caller so that they can warn the user about the
 * uncertainty of this logic.
 */
int program_find_bootable_partition(struct list_head *ops, bool *multiple_found)
{
	struct firehose_op *program;
	int part = -ENOENT;

	*multiple_found = false;

	program = program_find_partition(ops, "xbl");
	if (program)
		part = program->partition;

	program = program_find_partition(ops, "xbl_a");
	if (program) {
		if (part != -ENOENT)
			*multiple_found = true;
		else
			part = program->partition;
	}

	program = program_find_partition(ops, "sbl1");
	if (program) {
		if (part != -ENOENT)
			*multiple_found = true;
		else
			part = program->partition;
	}

	return part;
}

/**
 * program_is_sec_partition_flashed() - find if secdata partition is flashed
 *
 * Returns true if filename for secdata is set in program*.xml,
 * or false otherwise.
 */
int program_is_sec_partition_flashed(struct list_head *ops)
{
	struct firehose_op *program;

	program = program_find_partition(ops, "secdata");
	if (!program)
		return false;

	if (program->filename)
		return true;

	return false;
}

int program_cmd_add(struct list_head *ops, const char *address, const char *filename)
{
	unsigned int start_sector;
	unsigned int num_sectors;
	struct firehose_op *program;
	char *gpt_partition;
	int partition;
	char buf[20];
	int ret;

	ret = parse_storage_address(address, &partition, &start_sector, &num_sectors, &gpt_partition);
	if (ret < 0)
		return ret;

	program = firehose_alloc_op(FIREHOSE_OP_PROGRAM);
	if (!program) {
		ux_err("failed to allocate program command\n");
		return -1;
	}

	program->sector_size = 0;
	program->file_offset = 0;
	program->filename = filename ? strdup(filename) : NULL;
	program->label = filename ? strdup(filename) : NULL;
	program->num_sectors = num_sectors;
	program->partition = partition;
	program->sparse = false;
	sprintf(buf, "%u", start_sector);
	program->start_sector = strdup(buf);
	program->last_sector = 0;
	program->is_nand = false;
	program->gpt_partition = gpt_partition;

	list_append(ops, &program->node);

	return 0;
}

int erase_cmd_add(struct list_head *ops, const char *address)
{
	unsigned int start_sector;
	unsigned int num_sectors;
	struct firehose_op *program;
	char *gpt_partition;
	int partition;
	char buf[20];
	int ret;

	ret = parse_storage_address(address, &partition, &start_sector, &num_sectors, &gpt_partition);
	if (ret < 0)
		return ret;

	/*
	 * A start sector with no length and no partition name would fall through
	 * to firehose_erase as a full-partition erase (it omits both attributes
	 * when num_sectors is 0), wiping the whole LUN instead of the range the
	 * caller meant. Reject it; "erase 0" (start 0, no length) stays the
	 * explicit whole-partition form.
	 */
	if (num_sectors == 0 && start_sector != 0 && !gpt_partition) {
		ux_err("erase with start sector but no length not supported\n");
		return -1;
	}

	program = firehose_alloc_op(FIREHOSE_OP_ERASE);
	if (!program) {
		ux_err("failed to allocate erase command\n");
		return -1;
	}

	program->num_sectors = num_sectors;
	program->partition = partition;
	sprintf(buf, "%u", start_sector);
	program->start_sector = strdup(buf);
	program->gpt_partition = gpt_partition;

	list_append(ops, &program->node);

	return 0;
}
