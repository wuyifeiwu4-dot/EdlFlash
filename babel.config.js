module.exports = {
  presets: ['module:@react-native/babel-preset'],
  plugins: [
    // 把模板字符串转成字符串拼接，使其文字部分(quasi)变成 StringLiteral——否则
    // javascript-obfuscator(Metro 序列化阶段)只处理 StringLiteral，模板串里的中文(如
    // "登录成功…${x}")会残留明文。语义等价、低风险。
    '@babel/plugin-transform-template-literals',
    // reanimated 插件必须放最后
    'react-native-reanimated/plugin',
  ],
};
