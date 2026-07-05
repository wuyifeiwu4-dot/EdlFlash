// SPDX-License-Identifier: BSD-3-Clause
/*
 * OPlus VIP authentication sequence for firehose.
 *
 * Protocol verified against fh_loader.c SendSignedDigestTable() and
 * OPlus_EDL_Toolkit firehose_service.rs send_loader().
 *
 * The signed digest binary is sent as RAW bytes (no XML wrapper).
 * XML steps (transfercfg, verify, sha256init, configure) are sent as-is.
 *
 * After VIP auth, the standard Qualcomm VIP hash interleaving takes over
 * for subsequent firehose operations via vip_transfer_handle_tables().
 */
#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>

#include "qdl.h"
#include "firehose.h"

#define VIP_READ_TIMEOUT    10000
#define VIP_WRITE_TIMEOUT   30000
#define VIP_XML_MAXLEN      (64 * 1024)
#define VIP_BIN_MAXLEN      (8 * 1024 * 1024)
#define VIP_BANNER_SLICE_MS 100
#define VIP_BANNER_BUDGET_MS 5000

static void *read_file(const char *path, size_t *out_len)
{
	struct stat st;
	void *buf;
	int fd;
	ssize_t n;

	fd = open(path, O_RDONLY);
	if (fd < 0) {
		ux_err("vip: cannot open %s: %s\n", path, strerror(errno));
		return NULL;
	}
	if (fstat(fd, &st) < 0) {
		ux_err("vip: cannot stat %s\n", path);
		close(fd);
		return NULL;
	}
	buf = malloc(st.st_size);
	if (!buf) {
		close(fd);
		return NULL;
	}
	n = read(fd, buf, st.st_size);
	close(fd);
	if (n != st.st_size) {
		ux_err("vip: short read %s (%zd/%zu)\n", path, n, (size_t)st.st_size);
		free(buf);
		return NULL;
	}
	*out_len = (size_t)st.st_size;
	return buf;
}

/*
 * Wait for the device's response, draining any interleaved log messages.
 * Keeps reading in short slices until an explicit ACK/NAK arrives or the
 * budget elapses. Absence of a NAK within the budget is treated as success,
 * since some firmwares don't ACK every VIP step.
 */
static int drain_response(struct qdl_device *qdl, int budget_ms)
{
	struct timeval now, deadline;
	struct timeval delta = {
		.tv_sec = budget_ms / 1000,
		.tv_usec = (budget_ms % 1000) * 1000,
	};
	char buf[4096];
	int n;

	gettimeofday(&now, NULL);
	timeradd(&now, &delta, &deadline);

	for (;;) {
		n = qdl_read(qdl, buf, sizeof(buf) - 1, 200);
		if (n > 0) {
			buf[n] = '\0';
			ux_debug("vip: response: %s\n", buf);
			/* firehose 应答恒为 <response value="ACK"/"NAK" .../>，必须按 value 属性精确匹配：
			 * 裸子串 "ACK"/"NAK" 会被 PACKET/BACK/STACK 等大写词及 <log> 文本误命中，吞掉真正的
			 * NAK 或把日志误判成功（对齐 firehose.c firehose_generic_parser 的 value 精确比较）。 */
			if (memmem(buf, n, "value=\"NAK\"", sizeof("value=\"NAK\"") - 1)) {
				ux_err("vip: device NAK\n");
				return -1;
			}
			/* 设备可能用 <log value="ERROR: ..."> 报致命错误后仍回 ACK，drain 只看
			 * ACK/NAK 会漏检。在 ACK 之前拦截明确致命的措辞（同帧 ERROR+ACK 时优先判
			 * 失败），但不碰 OPlus 设备固有的良性日志(DevprgRSAVerify verify signature
			 * failed 后接 verify passed、Mode= Invalid)，避免误杀正常授权。 */
			if (memmem(buf, n, "signed digest rejected", 22) ||
			    memmem(buf, n, "is unmatch", 10) ||
			    memmem(buf, n, "hash mismatch", 13) ||
			    memmem(buf, n, "PACKET_HASH", 11) ||
			    memmem(buf, n, "not authenticated", 17) ||
			    memmem(buf, n, "authentication failed", 21)) {
				ux_err("vip: device reported fatal error in response\n");
				return -1;
			}
			if (memmem(buf, n, "value=\"ACK\"", sizeof("value=\"ACK\"") - 1))
				return 0;
			/* a log line; keep reading for the real response */
			continue;
		}
		if (n < 0 && n != -ETIMEDOUT)
			return n;

		gettimeofday(&now, NULL);
		if (timercmp(&now, &deadline, >=))
			return 0;
	}
}

/*
 * Send raw binary file directly to device, then read ACK.
 * This matches fh_loader.c SendSignedDigestTable(): the ELF/MBN binary
 * is pushed as-is via USB with no XML envelope.
 */
static int send_raw_binary(struct qdl_device *qdl, const char *path)
{
	void *data;
	size_t len;
	int ret;

	ux_info("vip step: sending %s\n", path);
	data = read_file(path, &len);
	if (!data)
		return -1;

	ret = qdl_write(qdl, data, len, VIP_WRITE_TIMEOUT);
	free(data);
	if (ret < 0) {
		ux_err("vip: write failed for %s (%d bytes)\n", path, ret);
		return -1;
	}
	ux_debug("vip: sent %zu bytes raw binary\n", len);

	return drain_response(qdl, VIP_READ_TIMEOUT);
}

/*
 * Send an XML file as-is (complete firehose command) and wait for ACK.
 */
static int send_xml_file(struct qdl_device *qdl, const char *path)
{
	void *data;
	size_t len;
	int ret;

	ux_info("vip step: sending %s\n", path);
	data = read_file(path, &len);
	if (!data)
		return -1;

	ret = qdl_write(qdl, data, len, VIP_WRITE_TIMEOUT);
	free(data);
	if (ret < 0) {
		ux_err("vip: write failed for %s\n", path);
		return -1;
	}

	return drain_response(qdl, VIP_READ_TIMEOUT);
}

/*
 * A rawprogram/patch XML is a flash command list, not VIP partition info.
 * Sending one here puts the programmer into program rawmode expecting raw
 * sectors that never follow, wedging the session for every later command.
 */
static bool xml_is_flash_command_list(const void *data, size_t len)
{
	/*
	 * Any Firehose data-phase / storage command must never be sent as VIP
	 * partition info. The XMLs this tool generates are canonical lowercase,
	 * so a substring match is sufficient as a guard.
	 */
	return memmem(data, len, "<program", 8) ||
	       memmem(data, len, "<read", 5) ||
	       memmem(data, len, "<patch", 6) ||
	       memmem(data, len, "<erase", 6) ||
	       memmem(data, len, "<poke", 5) ||
	       memmem(data, len, "<power", 6);
}

/*
 * Send the VIP partition-info XML. Unlike the other steps a failure here is
 * fatal: a half-sent or rejected partition info leaves the programmer in a
 * raw-receive state, so we must not let the caller treat the session as good.
 */
static int send_partition_info(struct qdl_device *qdl, const char *path)
{
	void *data;
	size_t len;
	int ret;

	data = read_file(path, &len);
	if (!data)
		return -1;

	if (xml_is_flash_command_list(data, len)) {
		ux_err("vip: %s is a flash command list, not partition info\n", path);
		free(data);
		return -1;
	}

	ux_info("vip step: sending %s\n", path);
	ret = qdl_write(qdl, data, len, VIP_WRITE_TIMEOUT);
	free(data);
	if (ret < 0) {
		ux_err("vip: partition info write failed\n");
		return -1;
	}

	return drain_response(qdl, VIP_READ_TIMEOUT);
}

/*
 * After Sahara completes, the firehose programmer has just booted and
 * streams an unsolicited XML log banner while not yet draining its OUT
 * endpoint. Writing the digest now would block until ETIMEDOUT. Read in
 * short slices until one idle slice follows at least one good read (banner
 * finished), bounded by an overall budget. Returns once the programmer is
 * ready, or after the budget even if it stayed silent so the caller can
 * proceed and let the digest+ACK step surface any real failure.
 */
static int wait_programmer_ready(struct qdl_device *qdl)
{
	struct timeval now, deadline;
	struct timeval delta = {
		.tv_sec = VIP_BANNER_BUDGET_MS / 1000,
		.tv_usec = (VIP_BANNER_BUDGET_MS % 1000) * 1000,
	};
	char buf[4096];
	bool saw_data = false;
	int n;

	gettimeofday(&now, NULL);
	timeradd(&now, &delta, &deadline);

	for (;;) {
		n = qdl_read(qdl, buf, sizeof(buf) - 1, VIP_BANNER_SLICE_MS);
		if (n > 0) {
			buf[n] = '\0';
			ux_debug("vip: programmer banner: %s\n", buf);
			saw_data = true;
			continue;
		}

		/* idle slice after the banner means the programmer is ready */
		if ((n == -ETIMEDOUT || n == 0) && saw_data)
			return 0;

		/* a real transport error: let the caller's write report it */
		if (n < 0 && n != -ETIMEDOUT)
			return n;

		gettimeofday(&now, NULL);
		if (timercmp(&now, &deadline, >=)) {
			if (!saw_data)
				ux_info("vip: programmer silent, proceeding anyway\n");
			return 0;
		}
	}
}

int oplus_vip_load_env(struct oplus_vip_config *cfg)
{
	const char *val;

	memset(cfg, 0, sizeof(*cfg));
	cfg->digest_path    = getenv("EDL_VIP_DIGEST");
	cfg->sign_path      = getenv("EDL_VIP_SIG");
	cfg->transfer_xml   = getenv("EDL_VIP_TRANSFER");
	cfg->verify_xml     = getenv("EDL_VIP_VERIFY");
	cfg->sha256init_xml = getenv("EDL_VIP_SHA");
	cfg->configure_xml  = getenv("EDL_VIP_CFG");
	cfg->partition_xml  = getenv("EDL_VIP_PARTITION");

	val = getenv("EDL_VIP_SKIP_TRANSFER");
	cfg->skip_transfer = val && val[0] == '1';

	if (cfg->digest_path || cfg->sign_path)
		cfg->enabled = 1;

	return 0;
}

/*
 * Execute OPlus VIP authentication sequence.
 *
 * Verified against:
 *   - fh_loader.c SendSignedDigestTable() — raw binary, no XML wrap
 *   - OPlus_EDL_Toolkit firehose_service.rs send_loader() — 7-step sequence
 *   - OplusEdlTool FirehoseService.cs — same sequence
 *
 * Step 1: Digest table (raw binary)
 * Step 2: transfercfg.xml
 * Step 3: verify.xml (EnableVip=1)
 * Step 4: Signature (raw binary)
 * Step 5: sha256init.xml
 * Step 6: configure.xml
 * Step 7: partition info xml (optional)
 */
int oplus_vip_auth(struct qdl_device *qdl)
{
	struct oplus_vip_config *cfg = &qdl->oplus_vip;
	int ret;

	/* drain the programmer's startup banner before the first write */
	ret = wait_programmer_ready(qdl);
	if (ret < 0) {
		ux_err("vip: programmer not reachable after Sahara\n");
		return -1;
	}

	/* step 1: signed digest table — raw binary */
	if (cfg->digest_path) {
		ret = send_raw_binary(qdl, cfg->digest_path);
		if (ret < 0) {
			ux_err("vip: signed digest table failed\n");
			return -1;
		}
		ux_info("vip: signed digest table sent\n");
	}

	/* step 2: transfercfg */
	if (cfg->transfer_xml && !cfg->skip_transfer) {
		ret = send_xml_file(qdl, cfg->transfer_xml);
		if (ret < 0) {
			ux_err("vip: transfercfg failed\n");
			return -1;
		}
	}

	/* step 3: verify (EnableVip=1) */
	if (cfg->verify_xml) {
		ret = send_xml_file(qdl, cfg->verify_xml);
		if (ret < 0) {
			ux_err("vip: verify failed\n");
			return -1;
		}
	}

	/* step 4: signature data — raw binary */
	if (cfg->sign_path) {
		ret = send_raw_binary(qdl, cfg->sign_path);
		if (ret < 0) {
			ux_err("vip: signed digest signature failed\n");
			return -1;
		}
		ux_info("vip: signature sent\n");
	}

	/* step 5: sha256init */
	if (cfg->sha256init_xml) {
		ret = send_xml_file(qdl, cfg->sha256init_xml);
		if (ret < 0) {
			ux_err("vip: sha256init failed\n");
			return -1;
		}
	}

	/* step 6: configure */
	if (cfg->configure_xml) {
		ret = send_xml_file(qdl, cfg->configure_xml);
		if (ret < 0) {
			ux_err("vip: configure failed\n");
			return -1;
		}
	}

	/* step 7: partition info (optional, but fatal once attempted) */
	if (cfg->partition_xml) {
		ret = send_partition_info(qdl, cfg->partition_xml);
		if (ret < 0) {
			ux_err("vip: partition info failed\n");
			return -1;
		}
	}

	ux_info("vip auth done\n");
	return 0;
}
