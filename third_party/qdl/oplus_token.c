// SPDX-License-Identifier: BSD-3-Clause
/*
 * OnePlus dynamic token authentication for firehose.
 *
 * Ported from bkerler edl edlclient/Library/Modules/oneplus.py. Older OnePlus
 * loaders gate write/erase behind a computed token handshake instead of a
 * pre-built signed digest table (the oplus_vip.c path):
 *
 *   version 1/2:  [demacia] -> setprojmodel    (oneplus1)
 *   version 3:    setprocstart -> setswprojmodel (oneplus2)
 *
 * The token is an AES-256-CBC blob over a comma-joined record whose integrity
 * field is a SHA256 over the device identity. Once authenticated, every
 * program/patch command additionally carries a pk/token pair, mirroring
 * oneplus.py addprogram()/addpatch().
 */
#include <ctype.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "aes256.h"
#include "firehose.h"
#include "gpt.h"
#include "oplus_token.h"
#include "qdl.h"
#include "sha2.h"

#define OPLUS_PK_LEN	16

/* projid -> auth generation, mirrors oneplus.py deviceconfig (param_mode,
 * only used by the unrelated param-rewrite feature, is omitted). cm is the
 * model-verify name used by version 2/3. */
struct oplus_device {
	const char *projid;
	int version;
	const char *cm;
};

static const struct oplus_device deviceconfig[] = {
	{ "16859", 1, NULL }, { "17801", 1, NULL }, { "17819", 1, NULL },
	{ "18801", 1, NULL }, { "18811", 1, NULL }, { "18857", 1, NULL },
	{ "18821", 1, NULL }, { "18825", 1, NULL }, { "18827", 1, NULL },
	{ "18831", 1, NULL }, { "18865", 1, NULL }, { "19863", 1, NULL },
	{ "19801", 1, NULL }, { "19861", 1, NULL },

	{ "19821", 2, "0cffee8a" }, { "19855", 2, "6d9215b4" },
	{ "19867", 2, "4107b2d4" }, { "19868", 2, "178d8213" },
	{ "19811", 2, "40217c07" }, { "19805", 2, "1a5ec176" },
	{ "20809", 2, "d6bc8c36" }, { "20801", 2, "eacf50e7" },

	{ "20885", 3, "3a403a71" }, { "20886", 3, "b8bd9e39" },
	{ "20888", 3, "142f1bd7" }, { "20889", 3, "f2056ae1" },
	{ "20880", 3, "6ccf5913" }, { "20881", 3, "fa9ff378" },
	{ "20882", 3, "4ca1e84e" }, { "20883", 3, "ad9dba4a" },

	{ "19815", 2, "9c151c7f" }, { "20859", 2, "9c151c7f" },
	{ "20857", 2, "9c151c7f" }, { "19825", 2, "0898dcd6" },
	{ "20851", 2, "0898dcd6" }, { "20852", 2, "0898dcd6" },
	{ "20853", 2, "0898dcd6" }, { "20828", 2, "f498b60f" },
	{ "20838", 2, "f498b60f" }, { "20854", 2, "16225d4e" },
	{ "2085A", 2, "7f19519a" },

	{ "20818", 1, NULL }, { "2083C", 1, NULL }, { "2083D", 1, NULL },
	{ "20813", 2, "48ad7b61" },
};

static const struct oplus_device *lookup_device(const char *projid)
{
	size_t i;

	for (i = 0; i < ARRAY_SIZE(deviceconfig); i++)
		if (!strcmp(deviceconfig[i].projid, projid))
			return &deviceconfig[i];
	return NULL;
}

/* Set projid plus the version/cm it implies. Unknown ids fall back to the
 * version 1 path with projid as the model-verify name. */
static void apply_device_config(struct oplus_token_config *cfg, const char *projid)
{
	const struct oplus_device *dev;

	snprintf(cfg->projid, sizeof(cfg->projid), "%s", projid);
	cfg->cm[0] = '\0';
	dev = lookup_device(cfg->projid);
	if (dev) {
		cfg->version = dev->version;
		if (dev->cm)
			snprintf(cfg->cm, sizeof(cfg->cm), "%s", dev->cm);
	} else {
		cfg->version = 1;
		ux_info("oplus token: projid %s not in table, assuming version 1\n",
			cfg->projid);
	}
}

/*
 * Read the device's projid from the param partition, exactly as oneplus.py's
 * auto-detection does: locate "param", read its first sector, and take the
 * 5 ASCII hex chars at offset 24. Reads need no auth (same as GPT reads), so
 * there is no chicken-and-egg with the handshake that follows.
 *
 * @quiet downgrades the "not a known OnePlus device" messages to debug: the
 * auto-detect probe calls this speculatively and a miss just means "not our
 * device", whereas an explicit EDL_OP_AUTH=1 wants the error spelled out.
 */
static int oplus_detect_projid(struct qdl_device *qdl, bool quiet)
{
	struct firehose_op op;
	int phys = -1;
	unsigned int start = 0;
	unsigned int num = 0;
	char start_str[16];
	uint8_t buf[64];
	char projid[6];
	int i;

	if (gpt_find_by_name(qdl, "param", &phys, &start, &num)) {
		if (quiet)
			ux_debug("oplus token: no param partition, not a OnePlus token device\n");
		else
			ux_err("oplus token: param partition not found for projid auto-detect\n");
		return -1;
	}

	memset(&op, 0, sizeof(op));
	op.type = FIREHOSE_OP_READ;
	op.sector_size = qdl->sector_size;
	snprintf(start_str, sizeof(start_str), "%u", start);
	op.start_sector = start_str;
	op.num_sectors = 1;
	op.partition = phys;
	op.label = "param";

	memset(buf, 0, sizeof(buf));
	if (firehose_read_buf(qdl, &op, buf, sizeof(buf))) {
		if (quiet)
			ux_debug("oplus token: param read failed, skipping auto auth\n");
		else
			ux_err("oplus token: failed to read param partition\n");
		return -1;
	}

	memcpy(projid, buf + 24, 5);
	projid[5] = '\0';
	for (i = 0; i < 5; i++) {
		if (!isxdigit((unsigned char)projid[i])) {
			if (quiet)
				ux_debug("oplus token: param projid field not hex, skipping auto auth\n");
			else
				ux_err("oplus token: param projid field is not hex (\"%s\")\n", projid);
			return -1;
		}
		/* deviceconfig keys are uppercase (e.g. 2085A); normalize so a param
		 * stored as lowercase hex still matches */
		projid[i] = (char)toupper((unsigned char)projid[i]);
	}

	/* Only trust an auto-detected id we have a config for: an unknown id is
	 * either a newer model (wrong version/cm guess) or a non-OnePlus device,
	 * and guessing would send a token the loader rejects. Require EDL_OP_PROJID
	 * in that case. */
	if (!lookup_device(projid)) {
		if (quiet)
			ux_debug("oplus token: projid %s not in table, skipping auto auth\n", projid);
		else
			ux_err("oplus token: detected projid %s not recognized; set EDL_OP_PROJID explicitly\n",
			       projid);
		return -1;
	}

	apply_device_config(&qdl->oplus_token, projid);
	ux_info("oplus token: detected projid %s from param partition\n",
		qdl->oplus_token.projid);
	return 0;
}

static const char *prodkey_for(const char *projid)
{
	/* key_guacamoles / fajita vs key_op7t/op8/N10 */
	if (!strcmp(projid, "18825") || !strcmp(projid, "18801"))
		return "b2fad511325185e5";
	return "7016147d58e8c038";
}

/* The model-verify name: projid for version 1, cm otherwise. */
static const char *prjname_for(const struct oplus_token_config *cfg)
{
	return cfg->version == 1 ? cfg->projid : cfg->cm;
}

/*
 * The demacia/setprojmodel tokens bind to the device's SoC serial: the loader
 * recomputes the same hash over the serial it read from the chip and rejects a
 * token built from a different one (verify_res="1"), which in turn leaves the
 * write/erase gate ("opcmd") disabled. qdl exposes that serial as the USB
 * iSerial, which on Qualcomm EDL is the chip serial in hex - the same value
 * bkerler derives from the loader's "Chip serial num: 0x..." line. The tokens
 * embed it as a decimal string, so convert hex->decimal here. An explicit
 * EDL_OP_SERIAL always wins; a missing or non-hex serial keeps the default.
 */
static void resolve_token_serial(struct qdl_device *qdl)
{
	struct oplus_token_config *cfg = &qdl->oplus_token;
	const char *src = "default";
	unsigned long long sn;
	const char *p;

	if (cfg->serial_explicit) {
		src = "EDL_OP_SERIAL";
	} else if (qdl->serial[0]) {
		for (p = qdl->serial; isxdigit((unsigned char)*p); p++)
			;
		errno = 0;
		sn = strtoull(qdl->serial, NULL, 16);
		if (!*p && !errno && sn) {
			snprintf(cfg->serial, sizeof(cfg->serial), "%llu", sn);
			src = "device serial";
		}
	}

	ux_info("oplus token: SoC serial %s (%s)\n", cfg->serial, src);
}

/*
 * Decide, by device identity, whether to run the token handshake when auth is
 * left to auto-detection (EDL_OP_AUTH=auto, e.g. an external custom loader). The
 * loader source and the supported-functions advertisement are both unreliable
 * here - an external loader has no vendor dir, and a reused programmer session
 * doesn't re-advertise its functions - so the only dependable signal is the
 * device's own projid in the param partition. A hit confirms a known OnePlus
 * token model and enables the handshake; anything else is left untouched so
 * non-OnePlus flashing is byte-identical.
 */
bool oplus_token_probe_auto(struct qdl_device *qdl)
{
	struct oplus_token_config *cfg = &qdl->oplus_token;
	int saved_mode;

	if (cfg->auth_mode != OPLUS_AUTH_AUTO || cfg->enabled || cfg->authenticated)
		return false;
	if (qdl->oplus_vip.enabled || qdl->dev_type == QDL_DEVICE_SIM)
		return false;

	/* An explicit EDL_OP_PROJID already named the device: trust it. */
	if (cfg->projid[0]) {
		cfg->enabled = 1;
		return true;
	}

	/*
	 * Reading param (and the GPT lookup that finds it) needs a label on
	 * OnePlus loaders, which only happens under oplus_mode. Raise it just for
	 * the probe; if this turns out not to be a known OnePlus device, restore
	 * it and drop the GPT the probe cached so the real flash re-reads it
	 * label-less - leaving a non-OnePlus session exactly as it was.
	 */
	saved_mode = qdl->oplus_mode;
	qdl->oplus_mode = 1;
	if (oplus_detect_projid(qdl, true) == 0) {
		cfg->enabled = 1;
		return true;
	}
	qdl->oplus_mode = saved_mode;
	gpt_reset_cache();
	return false;
}

static void make_pk(char pk[OPLUS_PK_LEN + 1])
{
	static const char charset[] =
		"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
	static int seeded;
	int i;

	/* The pk is sent in clear alongside the token and the loader rederives
	 * the AES key from it, so any value from the charset is valid; quality
	 * of randomness is irrelevant to the protocol. */
	if (!seeded) {
		srand((unsigned int)(time(NULL) ^ getpid()));
		seeded = 1;
	}
	for (i = 0; i < OPLUS_PK_LEN; i++)
		pk[i] = charset[rand() % (int)(sizeof(charset) - 1)];
	pk[OPLUS_PK_LEN] = '\0';
}

static void hex_upper(const uint8_t *in, size_t len, char *out)
{
	static const char hex[] = "0123456789ABCDEF";
	size_t i;

	for (i = 0; i < len; i++) {
		out[i * 2] = hex[in[i] >> 4];
		out[i * 2 + 1] = hex[in[i] & 0xf];
	}
	out[len * 2] = '\0';
}

static void sha256_hex_upper(const char *s, char out[SHA256_DIGEST_STRING_LENGTH])
{
	uint8_t digest[SHA256_DIGEST_LENGTH];
	SHA2_CTX ctx;

	SHA256Init(&ctx);
	SHA256Update(&ctx, (const uint8_t *)s, strlen(s));
	SHA256Final(digest, &ctx);
	hex_upper(digest, sizeof(digest), out);
}

/*
 * Encrypt @plain (already the full block-aligned plaintext length @block_len,
 * zero-filled by the caller) and hex-encode uppercase into @token. @demacia
 * selects the demacia key/iv; otherwise version 3 derives the key from the
 * device timestamp and versions 1/2 use the fixed setprojmodel key.
 */
static int crypt_token(const struct oplus_token_config *cfg, const char *pk,
		       bool demacia, const uint8_t *plain, size_t block_len,
		       char *token, size_t token_size)
{
	static const uint8_t key_normal_a[8] = { 0x10, 0x45, 0x63, 0x87, 0xe3, 0x7e, 0x23, 0x71 };
	static const uint8_t key_normal_b[8] = { 0xa2, 0xd4, 0xa0, 0x74, 0x0f, 0xd3, 0x28, 0x96 };
	static const uint8_t iv_normal[16] = {
		0x9d, 0x61, 0x4a, 0x1e, 0xac, 0x81, 0xc9, 0xb2,
		0xd3, 0x76, 0xd7, 0x49, 0x31, 0x03, 0x63, 0x79 };
	static const uint8_t key_demacia_a[8] = { 0x01, 0x63, 0xa0, 0xd1, 0xfd, 0xe2, 0x67, 0x11 };
	static const uint8_t key_demacia_b[8] = { 0x48, 0x27, 0xc2, 0x08, 0xfb, 0xb0, 0xe6, 0xf0 };
	static const uint8_t iv_demacia[16] = {
		0x96, 0xe0, 0x79, 0x0c, 0xae, 0x2b, 0xb4, 0xaf,
		0x68, 0x4c, 0x36, 0xcb, 0x0b, 0xec, 0x49, 0xce };
	static const uint8_t key_v3_a[8] = { 0x46, 0xa5, 0x97, 0x30, 0xbb, 0x0d, 0x41, 0xe8 };
	static const uint8_t iv_v3[16] = {
		0xdc, 0x91, 0x0d, 0x88, 0xe3, 0xc6, 0xee, 0x65,
		0xf0, 0xc7, 0x44, 0xb4, 0x02, 0x30, 0xce, 0x40 };
	bool v3 = cfg->version == 3 && !demacia;
	uint8_t cipher[1024];
	uint8_t key[32];
	const uint8_t *iv;
	int i;

	if (block_len > sizeof(cipher) || token_size < block_len * 2 + 1)
		return -1;

	memcpy(key + 8, pk, OPLUS_PK_LEN);
	if (v3) {
		memcpy(key, key_v3_a, 8);
		for (i = 0; i < 8; i++)
			key[24 + i] = (uint8_t)(cfg->device_timestamp >> (i * 8));
		iv = iv_v3;
	} else if (demacia) {
		memcpy(key, key_demacia_a, 8);
		memcpy(key + 24, key_demacia_b, 8);
		iv = iv_demacia;
	} else {
		memcpy(key, key_normal_a, 8);
		memcpy(key + 24, key_normal_b, 8);
		iv = iv_normal;
	}

	if (aes256_cbc_encrypt(cipher, plain, block_len, key, iv))
		return -1;

	hex_upper(cipher, block_len, token);
	return 0;
}

/*
 * Build the setprojmodel/setswprojmodel token plus the cached program token,
 * sharing one timestamp and secret so a program token always matches the
 * model token (oneplus.py recomputes the timestamp per call, but reusing it
 * is strictly safe whether the loader checks self-consistency or equality
 * against the stored model values). The session pk is cfg->program_pk.
 */
static int build_tokens(struct oplus_token_config *cfg, char *model_token,
			size_t model_size)
{
	const char *prodkey = prodkey_for(cfg->projid);
	const char *prjname = prjname_for(cfg);
	char hashtoken[SHA256_DIGEST_STRING_LENGTH];
	char secret[SHA256_DIGEST_STRING_LENGTH];
	char timestamp[16];
	char h1[128];
	char h2[256];
	char model_data[768];
	char program_data[160];
	size_t block_len;
	uint8_t plain[1024];

	if (!prjname || !prjname[0])
		return -1;

	snprintf(timestamp, sizeof(timestamp), "%lld", (long long)time(NULL));

	if (cfg->version == 3) {
		const char *postfix = "c75oVnz8yUgLZObh";
		const char *version = "billie8_14_E.01_201028";
		char device_id[24];

		/* device_id is the decimal of the hex model name; cm values like
		 * fa9ff378 exceed signed 32-bit, so parse as unsigned long long */
		snprintf(device_id, sizeof(device_id), "%llu", strtoull(prjname, NULL, 16));

		snprintf(h1, sizeof(h1), "%s%s%s", prodkey, prjname, postfix);
		sha256_hex_upper(h1, hashtoken);
		snprintf(h2, sizeof(h2), "%s%s%s%s%s%s8f7359c8a2951e8c",
			 prodkey, prjname, cfg->serial, version, timestamp, hashtoken);
		sha256_hex_upper(h2, secret);
		snprintf(model_data, sizeof(model_data), "%s,%s,%s,%s,%s,%s,%s,%s,%s,%s",
			 prjname, postfix, hashtoken, cfg->ato_build, cfg->flash_mode,
			 version, cfg->serial, device_id, timestamp, secret);
		block_len = 512;
	} else {
		const char *postfix = "0iyFR00pPnoqjVNL";
		const char *version = "guacamoles_21_O.22_191107";

		snprintf(h1, sizeof(h1), "%s%s%s", prodkey, prjname, postfix);
		sha256_hex_upper(h1, hashtoken);
		snprintf(h2, sizeof(h2), "c4b95538c57df231%s%s%s%s%s%s5b0217457e49381b",
			 prjname, cfg->cf, cfg->serial, version, timestamp, hashtoken);
		sha256_hex_upper(h2, secret);
		snprintf(model_data, sizeof(model_data), "%s,%s,%s,%s,%s,%s,%s,%s",
			 prjname, postfix, hashtoken, version, cfg->cf, cfg->serial,
			 timestamp, secret);
		block_len = 256;
	}

	if (strlen(model_data) > block_len)
		return -1;
	memset(plain, 0, block_len);
	memcpy(plain, model_data, strlen(model_data));
	if (crypt_token(cfg, cfg->program_pk, false, plain, block_len, model_token, model_size))
		return -1;

	/* program token: encrypt("timestamp,secret") with the same pk */
	snprintf(program_data, sizeof(program_data), "%s,%s", timestamp, secret);
	memset(plain, 0, block_len);
	memcpy(plain, program_data, strlen(program_data));
	return crypt_token(cfg, cfg->program_pk, false, plain, block_len,
			   cfg->program_token, sizeof(cfg->program_token));
}

static int build_demacia_token(struct oplus_token_config *cfg, const char *pk,
			       char *token, size_t token_size)
{
	uint8_t plain[256];
	uint8_t digest[SHA256_DIGEST_LENGTH];
	SHA2_CTX ctx;
	char serial[sizeof(cfg->serial)];
	char h1[64];
	size_t len = strlen(cfg->serial);

	/* serial zero-padded on the left to 10 digits */
	memset(serial, '0', 10);
	serial[10] = '\0';
	if (len >= 10)
		snprintf(serial, sizeof(serial), "%s", cfg->serial);
	else
		memcpy(serial + 10 - len, cfg->serial, len);

	snprintf(h1, sizeof(h1), "2e7006834dafe8ad%sa6674c6b039707ff", serial);
	SHA256Init(&ctx);
	SHA256Update(&ctx, (const uint8_t *)h1, strlen(h1));
	SHA256Final(digest, &ctx);

	memset(plain, 0, sizeof(plain));
	memcpy(plain, "907heavyworkload", 16);
	memcpy(plain + 16, digest, sizeof(digest));

	return crypt_token(cfg, pk, true, plain, sizeof(plain), token, token_size);
}

enum oplus_expect {
	EXPECT_DEMACIA,
	EXPECT_MODEL,
	EXPECT_PROCSTART,
};

struct auth_ctx {
	struct qdl_device *qdl;
	enum oplus_expect expect;
	int model_check;	/* EXPECT_MODEL verdict bits: 0 pass, 1 fail, -1 unseen */
	int auth_token_verify;
};

static bool prop_is_zero(xmlNode *node, const char *name)
{
	xmlChar *value = xmlGetProp(node, (const xmlChar *)name);
	bool ok = value && !xmlStrcmp(value, (const xmlChar *)"0");

	if (value)
		xmlFree(value);
	return ok;
}

static int auth_response_parser(xmlNode *node, void *data, bool *rawmode __unused)
{
	struct auth_ctx *ctx = data;
	xmlChar *value;
	bool ack;

	if (!xmlStrcmp(node->name, (const xmlChar *)"log")) {
		value = xmlGetProp(node, (const xmlChar *)"value");
		if (value) {
			ux_log("LOG: %s\n", value);
			xmlFree(value);
		}
		return -EAGAIN;
	}

	/* Capture the model verdict bits before the ACK gate below: a model
	 * mismatch comes back as value="NAK" model_check="1" auth_token_verify="0",
	 * and the caller needs auth_token_verify to tell a wrong model (retry
	 * another one) from a real token failure. */
	if (ctx->expect == EXPECT_MODEL) {
		ctx->model_check = prop_is_zero(node, "model_check") ? 0 : 1;
		ctx->auth_token_verify = prop_is_zero(node, "auth_token_verify") ? 0 : 1;
	}

	/* a <response>; only an explicit ACK value is success, so a NAK that
	 * happens to also carry device_timestamp can't slip through */
	value = xmlGetProp(node, (const xmlChar *)"value");
	if (!value)
		return -EAGAIN;
	ack = !xmlStrcmp(value, (const xmlChar *)"ACK");
	xmlFree(value);
	if (!ack)
		return FIREHOSE_NAK;

	if (ctx->expect == EXPECT_PROCSTART) {
		value = xmlGetProp(node, (const xmlChar *)"device_timestamp");
		if (!value)
			return FIREHOSE_NAK;
		ctx->qdl->oplus_token.device_timestamp = strtoull((char *)value, NULL, 10);
		xmlFree(value);
		return ctx->qdl->oplus_token.device_timestamp ? FIREHOSE_ACK : FIREHOSE_NAK;
	}

	if (ctx->expect == EXPECT_DEMACIA)
		return prop_is_zero(node, "verify_res") ? FIREHOSE_ACK : FIREHOSE_NAK;

	return (!ctx->model_check && !ctx->auth_token_verify) ? FIREHOSE_ACK : FIREHOSE_NAK;
}

static int send_command(struct qdl_device *qdl, const char *tag, const char *pk,
			const char *token, enum oplus_expect expect,
			struct auth_ctx *out)
{
	struct auth_ctx ctx = { .qdl = qdl, .expect = expect,
				.model_check = -1, .auth_token_verify = -1 };
	xmlDoc *doc;
	xmlNode *root;
	xmlNode *node;
	int ret;

	doc = xmlNewDoc((const xmlChar *)"1.0");
	root = xmlNewNode(NULL, (const xmlChar *)"data");
	xmlDocSetRootElement(doc, root);
	node = xmlNewChild(root, NULL, (const xmlChar *)tag, NULL);
	if (token)
		xmlSetProp(node, (const xmlChar *)"token", (const xmlChar *)token);
	if (pk)
		xmlSetProp(node, (const xmlChar *)"pk", (const xmlChar *)pk);

	ret = firehose_write(qdl, doc);
	if (ret < 0) {
		xmlFreeDoc(doc);
		return ret;
	}
	ret = firehose_read(qdl, 10000, auth_response_parser, &ctx);
	xmlFreeDoc(doc);

	if (out)
		*out = ctx;
	/* FIREHOSE_ACK(0) / FIREHOSE_NAK(1) / negative errno on transport error */
	return ret;
}

/*
 * Send setprojmodel/setswprojmodel and confirm the loader accepts it. The
 * loader's model_check gates on the device's real hardware board-ID (read from
 * PMIC resistor IDs), which the param projid can misreport - 8T firmware
 * (projid 19805) on different SM8250 hardware, or a loader whose model table
 * only matches a sibling model. When the crypto is fine (auth_token_verify=0)
 * but model_check fails, sweep the same-version family and retry, exactly as
 * the OnePlus toolbox tries each candidate projid in a series. The winning
 * model also fixes cfg->program_token, which every later program/erase carries.
 */
static int authenticate_model(struct qdl_device *qdl)
{
	struct oplus_token_config *cfg = &qdl->oplus_token;
	const char *tag = cfg->version == 3 ? "setswprojmodel" : "setprojmodel";
	char detected_projid[sizeof(cfg->projid)];
	char model_token[1025];
	struct auth_ctx ctx;
	bool crypto_ok = false;
	size_t i;

	snprintf(detected_projid, sizeof(detected_projid), "%s", cfg->projid);

	/*
	 * i==0 is the detected/explicit model; i>0 sweeps the same-version table.
	 * The unlock (demacia / setprocstart) already ran once and latches opcmd
	 * open for the rest of the session (the loader never clears that flag), so
	 * only the model verification is retried here. The winning model also fixes
	 * cfg->program_token, which every later program/erase carries.
	 */
	for (i = 0; i <= ARRAY_SIZE(deviceconfig); i++) {
		int ret;

		if (i) {
			const struct oplus_device *d = &deviceconfig[i - 1];

			if (d->version != cfg->version ||
			    !strcmp(d->projid, detected_projid))
				continue;
			apply_device_config(cfg, d->projid);
		}

		if (build_tokens(cfg, model_token, sizeof(model_token)))
			continue;

		ret = send_command(qdl, tag, cfg->program_pk, model_token,
				   EXPECT_MODEL, &ctx);
		if (ret < 0) {
			ux_err("oplus token: model verification transport error\n");
			return -1;
		}
		if (ret == FIREHOSE_ACK) {
			if (i)
				ux_info("oplus token: model accepted as projid %s (param reported %s)\n",
					cfg->projid, detected_projid);
			return 0;
		}
		if (ctx.auth_token_verify == 0)
			crypto_ok = true;
	}

	apply_device_config(cfg, detected_projid);
	if (crypto_ok)
		ux_err("oplus token: no model in the v%d family passed the loader hardware check\n",
		       cfg->version);
	else
		ux_err("oplus token: model verification rejected (auth_token_verify never passed)\n");
	return -1;
}

int oplus_token_auth(struct qdl_device *qdl)
{
	struct oplus_token_config *cfg = &qdl->oplus_token;

	/* VIP signed-digest auth owns the session on its devices; never run the
	 * token handshake alongside it (also enforced at startup, qdl.c) */
	if (!cfg->enabled || cfg->authenticated || qdl->oplus_vip.enabled)
		return 0;

	/* dry-run uses the SIM backend; there is no device to authenticate and
	 * the param read would only yield zeros, so skip the handshake */
	if (qdl->dev_type == QDL_DEVICE_SIM)
		return 0;

	/* the loader enforces a label on every read/program/erase once this
	 * handshake is required, same as the VIP path */
	qdl->oplus_mode = 1;

	/* projid drives prodkey/cm and the v1/v2/v3 path; when the caller didn't
	 * supply one (and the auto probe didn't already fill it), read it from the
	 * device's param partition */
	if (!cfg->projid[0] && oplus_detect_projid(qdl, false)) {
		ux_err("oplus token: projid unknown; set EDL_OP_PROJID or ensure param is readable\n");
		return -1;
	}

	/* demacia/setprojmodel must carry the device's real SoC serial */
	resolve_token_serial(qdl);

	make_pk(cfg->program_pk);

	/* Unlock opcmd once: version 3 via setprocstart (its device_timestamp also
	 * feeds the token key), version 1/2 via demacia. The loader latches the gate
	 * open for the rest of the session, so the model sweep below needs no repeat. */
	if (cfg->version == 3) {
		if (send_command(qdl, "setprocstart", NULL, NULL, EXPECT_PROCSTART, NULL) != FIREHOSE_ACK) {
			ux_err("oplus token: setprocstart failed\n");
			return -1;
		}
	} else if (cfg->do_demacia) {
		char demacia_token[513];
		int dret;

		/* demacia is a v1/v2 pre-step gated by EDL_OP_DEMACIA. A clean NAK is
		 * allowed to pass through to setprojmodel - the real write gate - so a
		 * loader that doesn't want demacia still authenticates. A transport
		 * error, though, means the session itself is unreliable, so it's fatal. */
		if (build_demacia_token(cfg, cfg->program_pk, demacia_token,
					sizeof(demacia_token))) {
			ux_info("oplus token: demacia token unavailable, skipping\n");
		} else {
			dret = send_command(qdl, "demacia", cfg->program_pk, demacia_token,
					    EXPECT_DEMACIA, NULL);
			if (dret < 0) {
				ux_err("oplus token: demacia transport error\n");
				return -1;
			}
			ux_info("oplus token: demacia %s\n",
				dret == FIREHOSE_ACK ? "ok" : "not accepted, continuing");
		}
	}

	if (authenticate_model(qdl))
		return -1;

	cfg->authenticated = 1;
	ux_info("oplus token: authenticated (projid %s, version %d)\n",
		cfg->projid, cfg->version);
	return 0;
}

void oplus_token_apply_attrs(struct qdl_device *qdl, xmlNode *node)
{
	struct oplus_token_config *cfg = &qdl->oplus_token;

	if (!cfg->authenticated || !cfg->program_token[0])
		return;

	xmlSetProp(node, (const xmlChar *)"pk", (const xmlChar *)cfg->program_pk);
	xmlSetProp(node, (const xmlChar *)"token", (const xmlChar *)cfg->program_token);
}

int oplus_token_load_env(struct oplus_token_config *cfg)
{
	const char *projid;
	const char *val;

	memset(cfg, 0, sizeof(*cfg));

	/* Load the token parameters unconditionally: the AUTO mode below decides
	 * whether to authenticate only after probing the device, and that probe
	 * (and the eventual handshake) needs valid defaults regardless. */
	val = getenv("EDL_OP_SERIAL");
	cfg->serial_explicit = (val && val[0]) ? 1 : 0;
	snprintf(cfg->serial, sizeof(cfg->serial), "%s", cfg->serial_explicit ? val : "123456");
	val = getenv("EDL_OP_CF");
	snprintf(cfg->cf, sizeof(cfg->cf), "%s", (val && val[0]) ? val : "0");
	val = getenv("EDL_OP_ATO_BUILD");
	snprintf(cfg->ato_build, sizeof(cfg->ato_build), "%s", (val && val[0]) ? val : "0");
	val = getenv("EDL_OP_FLASH_MODE");
	snprintf(cfg->flash_mode, sizeof(cfg->flash_mode), "%s", (val && val[0]) ? val : "0");
	val = getenv("EDL_OP_DEMACIA");
	cfg->do_demacia = val && !strcmp(val, "1");

	/* projid is optional: when absent it is auto-detected from the param
	 * partition during the handshake / probe */
	projid = getenv("EDL_OP_PROJID");
	if (projid && projid[0])
		apply_device_config(cfg, projid);

	/*
	 * EDL_OP_AUTH is tri-state. "1" forces the handshake (builtin OnePlus
	 * loader, or a manual/projid override) and is enabled up-front. "auto"
	 * (the default for an external/unknown loader) defers the decision to a
	 * device-identity probe in oplus_token_probe_auto. Anything else is off.
	 */
	val = getenv("EDL_OP_AUTH");
	if (val && !strcmp(val, "1")) {
		cfg->auth_mode = OPLUS_AUTH_FORCE;
		cfg->enabled = 1;
	} else if (val && !strcmp(val, "auto")) {
		cfg->auth_mode = OPLUS_AUTH_AUTO;
	} else {
		cfg->auth_mode = OPLUS_AUTH_OFF;
	}
	return 0;
}
