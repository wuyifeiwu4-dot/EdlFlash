#include "vm.h"

#define VM_STACK_MAX 64
#define VM_STEP_MAX 4096

static int16_t rd_i16(const uint8_t *p) {
  return (int16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
}
static int32_t rd_i32(const uint8_t *p) {
  return (int32_t)((uint32_t)p[0] | ((uint32_t)p[1] << 8) |
                   ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24));
}

int vm_run(const uint8_t *code, size_t len,
           const uint32_t *in, size_t in_count, uint32_t *result) {
  uint32_t st[VM_STACK_MAX];
  int sp = 0;
  size_t pc = 0;
  int steps = 0;

#define NEED(n) do { if (sp < (n)) return -1; } while (0)
#define ROOM(n) do { if (sp + (n) > VM_STACK_MAX) return -1; } while (0)

  while (pc < len) {
    if (++steps > VM_STEP_MAX) return -1;
    uint8_t op = code[pc++];
    switch (op) {
      case OP_PUSH: {
        if (pc + 4 > len) return -1;
        ROOM(1);
        st[sp++] = (uint32_t)rd_i32(code + pc);
        pc += 4;
        break;
      }
      case OP_LOAD: {
        if (pc + 1 > len) return -1;
        uint8_t idx = code[pc++];
        if (idx >= in_count) return -1;
        ROOM(1);
        st[sp++] = in[idx];
        break;
      }
      case OP_POP: NEED(1); sp--; break;
      case OP_DUP: NEED(1); ROOM(1); st[sp] = st[sp - 1]; sp++; break;
      case OP_SWAP: {
        NEED(2);
        uint32_t t = st[sp - 1]; st[sp - 1] = st[sp - 2]; st[sp - 2] = t;
        break;
      }
      case OP_ADD: NEED(2); st[sp - 2] += st[sp - 1]; sp--; break;
      case OP_SUB: NEED(2); st[sp - 2] -= st[sp - 1]; sp--; break;
      case OP_MUL: NEED(2); st[sp - 2] *= st[sp - 1]; sp--; break;
      case OP_AND: NEED(2); st[sp - 2] &= st[sp - 1]; sp--; break;
      case OP_OR:  NEED(2); st[sp - 2] |= st[sp - 1]; sp--; break;
      case OP_XOR: NEED(2); st[sp - 2] ^= st[sp - 1]; sp--; break;
      case OP_SHL: NEED(2); st[sp - 2] <<= (st[sp - 1] & 31); sp--; break;
      case OP_SHR: NEED(2); st[sp - 2] >>= (st[sp - 1] & 31); sp--; break;
      case OP_MIX: {
        NEED(2);
        uint32_t a = st[sp - 2], b = st[sp - 1];
        st[sp - 2] = (a ^ b) + ((a & b) << 1);
        sp--;
        break;
      }
      case OP_EQ: NEED(2); st[sp - 2] = (st[sp - 2] == st[sp - 1]); sp--; break;
      case OP_NE: NEED(2); st[sp - 2] = (st[sp - 2] != st[sp - 1]); sp--; break;
      case OP_LT: NEED(2); st[sp - 2] = (st[sp - 2] <  st[sp - 1]); sp--; break;
      case OP_GE: NEED(2); st[sp - 2] = (st[sp - 2] >= st[sp - 1]); sp--; break;
      case OP_NOT: NEED(1); st[sp - 1] = (st[sp - 1] == 0); break;
      case OP_JZ: {
        if (pc + 2 > len) return -1;
        int16_t off = rd_i16(code + pc);
        pc += 2;
        NEED(1);
        uint32_t v = st[--sp];
        if (v == 0) {
          int64_t np = (int64_t)pc + off;
          if (np < 0 || (size_t)np > len) return -1;
          pc = (size_t)np;
        }
        break;
      }
      case OP_JMP: {
        if (pc + 2 > len) return -1;
        int16_t off = rd_i16(code + pc);
        pc += 2;
        int64_t np = (int64_t)pc + off;
        if (np < 0 || (size_t)np > len) return -1;
        pc = (size_t)np;
        break;
      }
      case OP_HALT:
        NEED(1);
        *result = st[sp - 1];
        return 0;
      default:
        return -1;
    }
  }
  return -1;  /* 未遇 HALT 即耗尽 */
}
