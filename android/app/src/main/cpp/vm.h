/*
 * 极小栈式字节码 VM。授权"最终决策"以字节码形式存在（加密存放，运行前才解密），
 * 逆向者要先还原这套 VM 语义并解密字节码，才能看懂/篡改判定逻辑。
 * 只承载小型密钥/门控状态机，不碰刷机业务流程（避免性能与崩溃风险）。
 */
#ifndef EDLSEC_VM_H
#define EDLSEC_VM_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

enum {
  OP_PUSH = 0x10, /* + int32 LE 立即数 */
  OP_LOAD = 0x11, /* + uint8 输入下标，压入 in[idx] */
  OP_POP  = 0x12,
  OP_DUP  = 0x13,
  OP_SWAP = 0x14,
  OP_ADD  = 0x20,
  OP_SUB  = 0x21,
  OP_MUL  = 0x22,
  OP_AND  = 0x23,
  OP_OR   = 0x24,
  OP_XOR  = 0x25,
  OP_SHL  = 0x26,
  OP_SHR  = 0x27,
  OP_MIX  = 0x28, /* (a^b)+((a&b)<<1)，与 obf.h mix_add 等价 */
  OP_EQ   = 0x30,
  OP_NE   = 0x31,
  OP_LT   = 0x32, /* 无符号小于 */
  OP_GE   = 0x33,
  OP_NOT  = 0x34,
  OP_JZ   = 0x40, /* + int16 LE 相对偏移；栈顶为 0 则跳转 */
  OP_JMP  = 0x41, /* + int16 LE 相对偏移 */
  OP_HALT = 0xff, /* 结束，结果取栈顶 */
};

/*
 * 执行 code[0..len)，输入 in[0..in_count)。成功时 *result 为栈顶值并返回 0；
 * 越界/栈溢出/步数超限返回 -1（result 不可信）。
 */
int vm_run(const uint8_t *code, size_t len,
           const uint32_t *in, size_t in_count, uint32_t *result);

#ifdef __cplusplus
}
#endif

#endif
