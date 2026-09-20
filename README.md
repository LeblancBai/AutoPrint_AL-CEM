在Deepseek帮助下编写的特化的个人使用场景中用来自动监控两个文件夹中新添加的检查结果图片并自动打印的简单Java程序。

可自行修改源代码中的文件目录和监控文件的命名规则来扩展使用场景。

使用前需要先克隆仓库并安装Java后自行编译。

编译命令：
```
javac -encoding UTF-8 AutoPrintMonitor.java
jar -cfe AutoPrintMonitor.jar AutoPrintMonitor *.class
```

然后在win下运行.bat。
