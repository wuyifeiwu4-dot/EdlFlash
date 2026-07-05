/* SPDX-License-Identifier: BSD-3-Clause */
#ifndef __AES256_H__
#define __AES256_H__

#include <stddef.h>
#include <stdint.h>

/*
 * AES-256-CBC encryption, no padding. @len must be a multiple of 16.
 * @out and @in may alias. Returns 0 on success, -1 if @len is not a
 * multiple of the block size.
 */
int aes256_cbc_encrypt(uint8_t *out, const uint8_t *in, size_t len,
		       const uint8_t key[32], const uint8_t iv[16]);

#endif
