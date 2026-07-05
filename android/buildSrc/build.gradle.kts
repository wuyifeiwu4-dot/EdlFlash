// buildSrc：编译期字节码插桩工具（对 com.edlflash.edl.** 做字符串加密 + 控制流混淆）。
// 直接 implementation 完整 AGP：其 instrumentation API 与自带 ASM 进 buildSrc(父)类加载器，
// 项目 buildscript 的 AGP 经父优先委派共享同一份类，避免 AsmClassVisitorFactory 找不到 /
// 双 ASM 版本导致 ClassVisitor 类型不兼容。必须与项目 AGP 同版本(8.5.0)。
plugins { java }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("com.android.tools.build:gradle:8.5.0")
    // 控制流混淆需 tree API(MethodNode/InsnList) 做全方法回看分析、analysis 做构建期栈校验、
    // util 做 CheckClassAdapter 自检。版本对齐 AGP 8.5.0 内置的 ASM 9.6，避免双版本类型不兼容。
    implementation("org.ow2.asm:asm-tree:9.6")
    implementation("org.ow2.asm:asm-analysis:9.6")
    implementation("org.ow2.asm:asm-util:9.6")
}
