# 只做代码裁剪，不混淆：用户把日志里的崩溃堆栈贴回来时可以直接读懂。
-dontobfuscate

# 源文件名和行号保留在堆栈里
-keepattributes SourceFile,LineNumberTable
