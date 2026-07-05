const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');

/**
 * Metro 配置 + JS 层混淆(obfuscator-io-metro-plugin / javascript-obfuscator)。
 * RN 的业务逻辑/UI/中文文案/卡密流程都在 JS→Hermes bundle 里，DEX 层 ASM 混淆碰不到，
 * 故在 Metro 出 bundle 时对整包做混淆，重点是**字符串数组加密**(让 bundle 里看不到明文串)。
 *
 * Hermes/RN 安全铁律(会崩的项一律关闭)：
 *  - controlFlowFlattening / deadCodeInjection：体积膨胀+Hermes 编译慢/易崩，关。
 *  - selfDefending / debugProtection：依赖 Function.toString 自检，Hermes 下行为不同→崩，关。
 *  - renameGlobals / transformObjectKeys：会破坏 RN 模块/原生桥查找，关。
 *  - stringArray(rc4)+splitStrings+标识符重命名+数字表达式化：值保留、对框架安全，是主力。
 */
const jsoMetroPlugin = require('obfuscator-io-metro-plugin')(
  {
    compact: true,
    sourceMap: false,
    stringArray: true,
    stringArrayThreshold: 1,
    stringArrayEncoding: ['rc4'],
    // javascript-obfuscator 默认不把 2 字符等极短串放进数组(刷机/登录/授权 等中文 UI 标签会
    // 残留明文)。forceTransformStrings 用正则强制：所有含 CJK 的串 + 敏感关键词串一律入加密数组。
    forceTransformStrings: [
      '[\\u4e00-\\u9fff]',
      'http', '/api', 'token', 'secret', 'card', 'verify', 'sign',
      'rawprogram', 'patch', 'partition', 'super', 'qdl', 'edl', 'vip', 'loader',
    ],
    stringArrayCallsTransform: true,
    stringArrayWrappersCount: 3,
    stringArrayWrappersType: 'function',
    stringArrayShuffle: true,
    stringArrayRotate: true,
    splitStrings: true,
    splitStringsChunkLength: 6,
    identifierNamesGenerator: 'hexadecimal',
    numbersToExpressions: true,
    simplify: true,
    unicodeEscapeSequence: false,
    // —— 超强控制流：平坦化 + 死代码注入(主力,把 JS 逻辑打成 dispatcher 散转,反编译跟不动)。
    controlFlowFlattening: true,
    controlFlowFlatteningThreshold: 0.9,
    deadCodeInjection: true,
    deadCodeInjectionThreshold: 0.4,
    // —— 以下在 Hermes 下无效(依赖 Function.toString/调试器)且极易硬卡，不开；
    //    renameGlobals/transformObjectKeys 会彻底断 RN 原生桥(不可修)，不开。
    selfDefending: false,
    debugProtection: false,
    renameGlobals: false,
    transformObjectKeys: false,
    disableConsoleOutput: false,
  },
  {
    runInDev: false,
    logObfuscatedFiles: false,
  },
);

module.exports = mergeConfig(getDefaultConfig(__dirname), jsoMetroPlugin);
