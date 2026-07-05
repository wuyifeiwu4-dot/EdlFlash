// SPDX-License-Identifier: BSD-3-Clause
/*
 * Copyright (c) 2016-2017, Linaro Ltd.
 * All rights reserved.
 */
#include <sys/types.h>
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "qdl.h"
#include "oscompat.h"

#define SAHARA_HELLO_CMD		0x1  /* Min protocol version 1.0 */
#define SAHARA_HELLO_RESP_CMD		0x2  /* Min protocol version 1.0 */
#define SAHARA_READ_DATA_CMD		0x3  /* Min protocol version 1.0 */
#define SAHARA_END_OF_IMAGE_CMD		0x4  /* Min protocol version 1.0 */
#define SAHARA_DONE_CMD			0x5  /* Min protocol version 1.0 */
#define SAHARA_DONE_RESP_CMD		0x6  /* Min protocol version 1.0 */
#define SAHARA_RESET_CMD		0x7  /* Min protocol version 1.0 */
#define SAHARA_RESET_RESP_CMD		0x8  /* Min protocol version 1.0 */
#define SAHARA_MEM_DEBUG_CMD		0x9  /* Min protocol version 2.0 */
#define SAHARA_MEM_READ_CMD		0xa  /* Min protocol version 2.0 */
#define SAHARA_CMD_READY_CMD		0xb  /* Min protocol version 2.1 */
#define SAHARA_SWITCH_MODE_CMD		0xc  /* Min protocol version 2.1 */
#define SAHARA_EXECUTE_CMD		0xd  /* Min protocol version 2.1 */
#define SAHARA_EXECUTE_RESP_CMD		0xe  /* Min protocol version 2.1 */
#define SAHARA_EXECUTE_DATA_CMD		0xf  /* Min protocol version 2.1 */
#define SAHARA_MEM_DEBUG64_CMD		0x10 /* Min protocol version 2.5 */
#define SAHARA_MEM_READ64_CMD		0x11 /* Min protocol version 2.5 */
#define SAHARA_READ_DATA64_CMD		0x12 /* Min protocol version 2.8 */
#define SAHARA_RESET_STATE_CMD		0x13 /* Min protocol version 2.9 */
#define SAHARA_WRITE_DATA_CMD		0x14 /* Min protocol version 3.0 */

#define SAHARA_VERSION			2
#define SAHARA_SUCCESS			0

#define SAHARA_MODE_IMAGE_TX_PENDING	0x0
#define SAHARA_MODE_IMAGE_TX_COMPLETE	0x1
#define SAHARA_MODE_MEMORY_DEBUG	0x2
#define SAHARA_MODE_COMMAND		0x3

#define SAHARA_HELLO_LENGTH		0x30
#define SAHARA_READ_DATA_LENGTH		0x14
#define SAHARA_READ_DATA64_LENGTH	0x20
#define SAHARA_END_OF_IMAGE_LENGTH	0x10
#define SAHARA_MEM_READ64_LENGTH	0x18
#define SAHARA_MEM_DEBUG64_LENGTH	0x18
#define SAHARA_DONE_LENGTH		0x8
#define SAHARA_DONE_RESP_LENGTH		0xc
#define SAHARA_RESET_LENGTH		0x8

#define DEBUG_BLOCK_SIZE (512u * 1024u)

#define SAHARA_CMD_TIMEOUT_MS	1000

struct sahara_pkt {
	uint32_t cmd;
	uint32_t length;

	union {
		struct {
			uint32_t version;
			uint32_t compatible;
			uint32_t max_len;
			uint32_t mode;
			uint32_t reserved[6];
		} hello_req;
		struct {
			uint32_t version;
			uint32_t compatible;
			uint32_t status;
			uint32_t mode;
			uint32_t reserved[6];
		} hello_resp;
		struct {
			uint32_t image;
			uint32_t offset;
			uint32_t length;
		} read_req;
		struct {
			uint32_t image;
			uint32_t status;
		} eoi;
		struct {
		} done_req;
		struct {
			uint32_t status;
		} done_resp;
		struct {
			uint64_t addr;
			uint64_t length;
		} debug64_req;
		struct {
			uint64_t image;
			uint64_t offset;
			uint64_t length;
		} read64_req;
	};
};

struct sahara_debug_region64 {
	uint64_t type;
	uint64_t addr;
	uint64_t length;
	char region[20];
	char filename[20];
};

static int sahara_send_reset(struct qdl_device *qdl)
{
	struct sahara_pkt resp;
	int ret;

	resp.cmd = SAHARA_RESET_CMD;
	resp.length = SAHARA_RESET_LENGTH;

	ret = qdl_write(qdl, &resp, resp.length, SAHARA_CMD_TIMEOUT_MS);
	if (ret < 0)
		return ret;

	return ret == (int)resp.length ? 0 : -EIO;
}

static int sahara_send_hello_resp(struct qdl_device *qdl, unsigned int mode)
{
	struct sahara_pkt resp = {};
	int ret;

	resp.cmd = SAHARA_HELLO_RESP_CMD;
	resp.length = SAHARA_HELLO_LENGTH;
	resp.hello_resp.version = SAHARA_VERSION;
	resp.hello_resp.compatible = 1;
	resp.hello_resp.status = SAHARA_SUCCESS;
	resp.hello_resp.mode = mode;

	ret = qdl_write(qdl, &resp, resp.length, SAHARA_CMD_TIMEOUT_MS);
	return ret < 0 ? ret : 0;
}

static int sahara_hello(struct qdl_device *qdl, struct sahara_pkt *pkt)
{
	if (pkt->length != SAHARA_HELLO_LENGTH) {
		ux_err("unexpected HELLO packet length %u\n", pkt->length);
		sahara_send_reset(qdl);
		return -1;
	}

	ux_debug("HELLO version: 0x%x compatible: 0x%x max_len: %d mode: %d\n",
		 pkt->hello_req.version, pkt->hello_req.compatible, pkt->hello_req.max_len, pkt->hello_req.mode);

	return sahara_send_hello_resp(qdl, pkt->hello_req.mode);
}

static int sahara_read(struct qdl_device *qdl, struct sahara_pkt *pkt,
		       const struct sahara_image *images)
{
	const struct sahara_image *image;
	unsigned int image_idx;
	size_t offset;
	size_t len;
	int ret;

	if (pkt->length != SAHARA_READ_DATA_LENGTH) {
		ux_err("unexpected READ_DATA packet length %u\n", pkt->length);
		sahara_send_reset(qdl);
		return -1;
	}

	ux_debug("READ image: %d offset: 0x%x length: 0x%x\n",
		 pkt->read_req.image, pkt->read_req.offset, pkt->read_req.length);

	image_idx = pkt->read_req.image;
	if (image_idx >= MAPPING_SZ || !images[image_idx].ptr) {
		ux_err("device requested unknown image id %u, ensure that all Sahara images are provided\n",
		       image_idx);
		sahara_send_reset(qdl);
		return -1;
	}

	offset = pkt->read_req.offset;
	len = pkt->read_req.length;

	image = &images[image_idx];
	if (offset > image->len || offset + len > image->len) {
		ux_err("device requested invalid range of image %d\n", image_idx);
		return -1;
	}

	if (offset == 0)
		ux_info("Sahara: sending %s (%zu bytes)\n",
			image->name ? image->name : "(unknown)", image->len);
	ux_progress("%s", offset + len, image->len, image->name ? image->name : "image");

	ret = qdl_write(qdl, image->ptr + offset, len, SAHARA_CMD_TIMEOUT_MS);
	if (ret < 0 || ((size_t)ret != len)) {
		ux_err("failed to write %zu bytes to sahara\n", len);
		return -1;
	}

	return 0;
}

static int sahara_read64(struct qdl_device *qdl, struct sahara_pkt *pkt,
			 const struct sahara_image *images)
{
	const struct sahara_image *image;
	uint64_t image_idx;
	size_t offset;
	size_t len;
	int ret;

	if (pkt->length != SAHARA_READ_DATA64_LENGTH) {
		ux_err("unexpected READ_DATA64 packet length %u\n", pkt->length);
		sahara_send_reset(qdl);
		return -1;
	}

	ux_debug("READ64 image: %" PRId64 " offset: 0x%" PRIx64 " length: 0x%" PRIx64 "\n",
		 pkt->read64_req.image, pkt->read64_req.offset, pkt->read64_req.length);

	/* read64_req.image 是 uint64：必须用 64 位比较拒绝越界，不能先截成 unsigned int
	 * 再比较——否则高 32 位非 0 的 id 会被截断别名到合法索引、读到错误镜像。 */
	image_idx = pkt->read64_req.image;
	if (image_idx >= MAPPING_SZ || !images[image_idx].ptr) {
		ux_err("device requested unknown image id %" PRIu64 ", ensure that all Sahara images are provided\n",
		       image_idx);
		sahara_send_reset(qdl);
		return -1;
	}

	offset = pkt->read64_req.offset;
	len = pkt->read64_req.length;

	image = &images[image_idx];
	/* 用减法形式避免 offset+len 在 size_t 上回绕绕过范围检查 */
	if (offset > image->len || len > image->len - offset) {
		ux_err("device requested invalid range of image %" PRIu64 "\n", image_idx);
		return -1;
	}

	if (offset == 0)
		ux_info("Sahara: sending %s (%zu bytes)\n",
			image->name ? image->name : "(unknown)", image->len);
	ux_progress("%s", offset + len, image->len, image->name ? image->name : "image");

	ret = qdl_write(qdl, image->ptr + offset, len, SAHARA_CMD_TIMEOUT_MS);
	if (ret < 0 || ((size_t)ret != len)) {
		ux_err("failed to write %zu bytes to sahara\n", len);
		return -1;
	}

	return 0;
}

static int sahara_eoi(struct qdl_device *qdl, struct sahara_pkt *pkt)
{
	struct sahara_pkt done;

	if (pkt->length != SAHARA_END_OF_IMAGE_LENGTH) {
		ux_err("unexpected END_OF_IMAGE packet length %u\n", pkt->length);
		sahara_send_reset(qdl);
		return -1;
	}

	ux_debug("END OF IMAGE image: %d status: %d\n", pkt->eoi.image, pkt->eoi.status);

	if (pkt->eoi.status != 0) {
		ux_err("received non-successful end-of-image result\n");
		return -1;
	}

	done.cmd = SAHARA_DONE_CMD;
	done.length = SAHARA_DONE_LENGTH;
	qdl_write(qdl, &done, done.length, SAHARA_CMD_TIMEOUT_MS);
	return 0;
}

static int sahara_done(struct qdl_device *qdl, struct sahara_pkt *pkt)
{
	if (pkt->length != SAHARA_DONE_RESP_LENGTH) {
		ux_err("unexpected DONE_RESP packet length %u\n", pkt->length);
		sahara_send_reset(qdl);
		return -1;
	}

	ux_debug("DONE status: %d\n", pkt->done_resp.status);

	// 0 == PENDING, 1 == COMPLETE.  Device expects more images if
	// PENDING is set in status.
	return pkt->done_resp.status;
}

static ssize_t sahara_debug64_one(struct qdl_device *qdl,
				  struct sahara_debug_region64 region,
				  const char *ramdump_path)
{
	struct sahara_pkt read_req;
	uint64_t remain;
	size_t offset, buf_offset;
	size_t chunk;
	size_t written;
	ssize_t n;
	void *buf;
	int fd;

	buf = malloc(DEBUG_BLOCK_SIZE);
	if (!buf)
		return -1;

	char path[PATH_MAX];

	snprintf(path, sizeof(path), "%s/%s", ramdump_path, region.filename);

	fd = open(path, O_WRONLY | O_CREAT | O_BINARY, 0644);
	if (fd < 0) {
		warn("failed to open \"%s\"", region.filename);
		free(buf);
		return -1;
	}

	chunk = 0;
	while (chunk < region.length) {
		remain = MIN((uint64_t)(region.length - chunk), DEBUG_BLOCK_SIZE);

		read_req.cmd = SAHARA_MEM_READ64_CMD;
		read_req.length = SAHARA_MEM_READ64_LENGTH;
		read_req.debug64_req.addr = region.addr + chunk;
		read_req.debug64_req.length = remain;
		n = qdl_write(qdl, &read_req, read_req.length, SAHARA_CMD_TIMEOUT_MS);
		if (n < 0)
			break;

		offset = 0;
		while (offset < remain) {
			buf_offset = 0;
			n = qdl_read(qdl, buf, DEBUG_BLOCK_SIZE, 30000);
			if (n < 0) {
				warn("failed to read ramdump chunk");
				goto out;
			}

			while (buf_offset < (size_t)n) {
				written = write(fd, buf + buf_offset, n - buf_offset);
				if (written <= 0) {
					warn("failed to write ramdump chunk to \"%s\"", region.filename);
					goto out;
				}
				buf_offset += written;
			}

			offset += buf_offset;
		}

		qdl_read(qdl, buf, DEBUG_BLOCK_SIZE, 10);

		chunk += DEBUG_BLOCK_SIZE;

		ux_progress("%s", chunk, region.length, region.filename);
	}
out:

	close(fd);
	free(buf);

	return 0;
}

// simple pattern matching function supporting * and ?
bool pattern_match(const char *pattern, const char *string)
{
	if (*pattern == '\0' && *string == '\0')
		return true;

	if (*pattern == '*')
		return pattern_match(pattern + 1, string) ||
		       (*string != '\0' && pattern_match(pattern, string + 1));

	if (*pattern == '?')
		return (*string != '\0') && pattern_match(pattern + 1, string + 1);

	if (*pattern == *string)
		return pattern_match(pattern + 1, string + 1);

	return false;
}

static bool sahara_debug64_filter(const char *filename, const char *filter)
{
	char stem[PATH_MAX] = {};
	bool anymatch = false;
	const char *dot;
	char *ptr;
	char *tmp;
	char *s;

	if (!filter)
		return false;

	/* Build a stem (filename without extension) for bare-name filter tokens */
	dot = strrchr(filename, '.');
	if (dot && dot != filename) {
		size_t len = dot - filename;

		if (len < sizeof(stem)) {
			memcpy(stem, filename, len);
		}
	}

	tmp = strdup(filter);
	for (s = strtok_r(tmp, ",", &ptr); s; s = strtok_r(NULL, ",", &ptr)) {
		if (pattern_match(s, filename) || (stem[0] && pattern_match(s, stem))) {
			anymatch = true;
			break;
		}
	}
	free(tmp);

	return !anymatch;
}

static void sahara_debug64(struct qdl_device *qdl, struct sahara_pkt *pkt,
			   const char *ramdump_path, const char *filter)
{
	struct sahara_debug_region64 *table;
	struct sahara_pkt read_req;
	ssize_t n;
	size_t i;

	if (pkt->length != SAHARA_MEM_DEBUG64_LENGTH) {
		ux_err("unexpected MEM_DEBUG64 packet length %u\n", pkt->length);
		sahara_send_reset(qdl);
		return;
	}

	ux_debug("DEBUG64 address: 0x%" PRIx64 " length: 0x%" PRIx64 "\n",
		 pkt->debug64_req.addr, pkt->debug64_req.length);

	read_req.cmd = SAHARA_MEM_READ64_CMD;
	read_req.length = SAHARA_MEM_READ64_LENGTH;
	read_req.debug64_req.addr = pkt->debug64_req.addr;
	read_req.debug64_req.length = pkt->debug64_req.length;

	n = qdl_write(qdl, &read_req, read_req.length, SAHARA_CMD_TIMEOUT_MS);
	if (n < 0)
		return;

	if (read_req.debug64_req.length > 64 * 1024) {
		ux_err("DEBUG64 table length 0x%" PRIx64 " exceeds limit\n",
		       read_req.debug64_req.length);
		return;
	}

	table = malloc(read_req.debug64_req.length);
	if (!table)
		return;

	n = qdl_read(qdl, table, pkt->debug64_req.length, SAHARA_CMD_TIMEOUT_MS);
	if (n < 0) {
		free(table);
		return;
	}

	for (i = 0; i < pkt->debug64_req.length / sizeof(table[0]); i++) {
		if (sahara_debug64_filter(table[i].filename, filter)) {
			ux_info("%s skipped per filter\n", table[i].filename);
			continue;
		}

		ux_debug("%-2d: type 0x%" PRIx64 " address: 0x%" PRIx64 " length: 0x%"
			 PRIx64 " region: %s filename: %s\n",
			 i, table[i].type, table[i].addr, table[i].length,
			 table[i].region, table[i].filename);

		n = sahara_debug64_one(qdl, table[i], ramdump_path);
		if (n < 0)
			break;

		ux_info("%s dumped successfully\n", table[i].filename);
	}

	free(table);

	sahara_send_reset(qdl);
}

static bool sahara_has_done_pending_quirk(const struct sahara_image *images)
{
	unsigned int count = 0;
	int i;

	/*
	 * E.g MSM8916 EDL reports done = pending, allow this when one a single
	 * image is provided, and it's used as SAHARA_ID_EHOSTDL_IMG.
	 */
	for (i = 0; i < MAPPING_SZ; i++) {
		if (images[i].ptr)
			count++;
	}

	return count == 1 && images[SAHARA_ID_EHOSTDL_IMG].ptr;
}

static void sahara_debug_list_images(const struct sahara_image *images)
{
	int i;

	ux_debug("Sahara images:\n");
	for (i = 0; i < MAPPING_SZ; i++) {
		if (images[i].ptr)
			ux_debug("  %2d: %s\n", i, images[i].name ? : "(unknown)");
	}
}

int sahara_run(struct qdl_device *qdl, const struct sahara_image *images,
	       const char *ramdump_path,
	       const char *ramdump_filter)
{
	/*
	 * Auto-detect that the device is already running the Firehose
	 * programmer (e.g. left running by a previous --skip-reset
	 * invocation): a Firehose XML banner instead of a Sahara HELLO means we
	 * skip Sahara entirely. A silent first read is ambiguous (missed HELLO
	 * vs. idle Firehose), so it is handled by the staged recovery below
	 * rather than blindly skipping Sahara. Disabled in ramdump mode, where
	 * Sahara is the only valid protocol.
	 */
	const bool detect_firehose = !ramdump_path;
	bool first_read = true;
	bool hello_resp_sent = false;
	bool sahara_reset_sent = false;
	bool usb_reset_done = false;
	bool firehose_probe_sent = false;
	struct sahara_pkt *pkt;
	char buf[4096];
	char tmp[32];
	bool done = false;
	int ret;
	int n;

	if (images)
		sahara_debug_list_images(images);

	/*
	 * Don't need to do anything in simulation mode with Sahara,
	 * we care only about Firehose protocol
	 */
	if (qdl->dev_type == QDL_DEVICE_SIM)
		return 0;

	while (!done) {
		n = qdl_read(qdl, buf, sizeof(buf), SAHARA_CMD_TIMEOUT_MS);
		if (n < 0) {
			if (first_read && detect_firehose && n == -ETIMEDOUT) {
				/*
				 * 首读收不到 HELLO 有两类成因，恢复按"先非破坏、写失败再升级"排序：
				 *   ①设备活着，但那一次 HELLO 已被前序会话消费/错过，PBL 正等
				 *     HELLO_RESPONSE（小米 SDM845 反复进 9008 的常见态）。
				 *   ②端点被 wedge、bulk 双向静默，连写都失败。
				 * 先发非破坏性的协议 nudge（情形①一发即活，且能用写是否成功探出
				 * 到底是哪类）；只有写都失败、确属端点 wedge，才做破坏性的 USB
				 * 软件 replug——实测 libusb_reset_device 的总线复位救不活已 wedge 的
				 * 高通 PBL，所以放到最后、失败即引导物理 replug。
				 */

				/*
				 * ① 主动补发 HELLO_RESPONSE：设备若在等它，会接着发 READ_DATA
				 * 请求加载器（与 linux-msm 上游处理 QUD 吞 HELLO、qdlrs
				 * --skip-hello-wait 同理）。写成功才 continue 等回包；写失败说明
				 * 端点已 wedge，落到下面升级。mode=IMAGE_TX_PENDING：host 有镜像要发。
				 */
				if (!hello_resp_sent) {
					hello_resp_sent = true;
					if (sahara_send_hello_resp(qdl, SAHARA_MODE_IMAGE_TX_PENDING) == 0) {
						ux_info("no Sahara HELLO; sent unsolicited HELLO response to nudge the device\n");
						continue;
					}
					ux_debug("HELLO response write failed; endpoints likely wedged\n");
				}
				/*
				 * ② 协议层 Sahara RESET：让 PBL 回到会重发 HELLO 的初态。绝不注入
				 * Firehose XML——对仍等 HELLO_RESP 的 PBL 它是非法命令。
				 */
				if (!sahara_reset_sent) {
					sahara_reset_sent = true;
					if (sahara_send_reset(qdl) == 0) {
						ux_info("no Sahara HELLO; sent Sahara reset to re-trigger HELLO\n");
						continue;
					}
					ux_debug("Sahara reset write failed; endpoints wedged, escalating to USB replug\n");
				}
				/*
				 * ③ 破坏性兜底：USB 软件 replug（端口 reset + 丢句柄 + 按序列号重开）。
				 * 仅当上面协议 nudge 都写失败（确属端点 wedge）才做。
				 */
				if (!usb_reset_done) {
					usb_reset_done = true;
					ret = qdl_reset(qdl);
					if (ret == 0) {
						/*
						 * 重开后 SET_INTERFACE 已复位 toggle、设备重新枚举，
						 * fresh PBL 通常会自发重发 HELLO。万一这次读仍超时，要让它
						 * 在干净连接上重走非破坏 nudge（HELLO_RESP→Sahara RESET），
						 * 而不是带着旧标志直接跳到对等 HELLO 的 PBL 非法的 Firehose
						 * 探测。usb_reset_done 保持 true 不再二次 replug。
						 */
						hello_resp_sent = false;
						sahara_reset_sent = false;
						ux_info("no Sahara HELLO; software-replugged device, reading fresh HELLO\n");
						continue;
					}
					if (ret == -ENODEV) {
						ux_info("device did not return after USB reset; replug into 9008\n");
						return -ENODEV;
					}
					ux_debug("USB reset unavailable (ret=%d)\n", ret);
				}
				/*
				 * ④ 也许设备其实是热 Firehose（programmer 已在跑）。探一下，
				 * 活着的 programmer 会回 <?xml（下方首读分支识别后跳过 Sahara）。
				 */
				if (!firehose_probe_sent) {
					static const char fh_probe[] =
						"<?xml version=\"1.0\" ?><data><nop /></data>";
					firehose_probe_sent = true;
					if (qdl_write(qdl, fh_probe, sizeof(fh_probe) - 1,
						      SAHARA_CMD_TIMEOUT_MS) < 0)
						ux_debug("Firehose probe write failed\n");
					continue;
				}
				ux_info("no Sahara HELLO; device unresponsive — physically replug into 9008 (bus reset can't revive a wedged Qualcomm PBL)\n");
				return -ETIMEDOUT;
			}
			ux_err("failed to read sahara request from device\n");
			break;
		}

		if (first_read && detect_firehose &&
		    n >= 5 && !memcmp(buf, "<?xml", 5)) {
			/*
			 * A healthy hot Firehose programmer emits a clean banner/log.
			 * A leftover rawmode error ("Read non multiple sector size",
			 * rawmode="true") means a prior session wedged the programmer
			 * mid-raw-transfer; treat it as not-healthy and fall through so
			 * the staged recovery (USB replug) gets a chance instead of
			 * skipping straight into another configure death loop.
			 */
			buf[n < (int)sizeof(buf) ? n : (int)sizeof(buf) - 1] = '\0';
			if (!strstr(buf, "Read non multiple sector size") &&
			    !strstr(buf, "rawmode=\"true\"")) {
				ux_info("device is already in Firehose mode, skipping Sahara\n");
				return 0;
			}
			ux_info("stale Firehose rawmode banner detected; not skipping Sahara\n");
		}
		first_read = false;

		pkt = (struct sahara_pkt *)buf;
		if (pkt->length < sizeof(pkt->cmd) + sizeof(pkt->length) ||
		    (uint32_t)n < pkt->length) {
			ux_err("request length not matching received request\n");
			return -EINVAL;
		}
		/*
		 * 一次传输可能带回多个背靠背的 Sahara 包（如 RESET 后的 RESET_RESP
		 * 紧跟重发的 HELLO）。把多出来的尾部交给 pushback 缓冲，下一次
		 * qdl_read() 会先取到它们，而不是在长度校验处误判。
		 */
		if ((uint32_t)n > pkt->length) {
			if (qdl_push_back(qdl, buf + pkt->length, n - pkt->length) < 0)
				return -ENOMEM;
			n = pkt->length;
		}

		switch (pkt->cmd) {
		case SAHARA_HELLO_CMD:
			if (sahara_hello(qdl, pkt) < 0)
				return -1;
			break;
		case SAHARA_READ_DATA_CMD:
			if (sahara_read(qdl, pkt, images) < 0)
				return -1;
			break;
		case SAHARA_END_OF_IMAGE_CMD:
			if (sahara_eoi(qdl, pkt) < 0)
				return -1;
			break;
		case SAHARA_DONE_RESP_CMD:
			n = sahara_done(qdl, pkt);
			if (n < 0)
				return -1;
			done = n;

			/* E.g MSM8916 EDL reports done = 0 here */
			if (sahara_has_done_pending_quirk(images))
				done = true;
			break;
		case SAHARA_MEM_DEBUG64_CMD:
			sahara_debug64(qdl, pkt, ramdump_path, ramdump_filter);
			break;
		case SAHARA_READ_DATA64_CMD:
			if (sahara_read64(qdl, pkt, images) < 0)
				return -1;
			break;
		case SAHARA_RESET_RESP_CMD:
			if (pkt->length != SAHARA_RESET_LENGTH) {
				ux_err("unexpected RESET_RESP packet length %u\n", pkt->length);
				return -1;
			}
			if (ramdump_path)
				done = true;
			break;
		default:
			sprintf(tmp, "CMD%x", pkt->cmd);
			print_hex_dump(tmp, buf, n);
			break;
		}
	}

	return done ? 0 : -1;
}
