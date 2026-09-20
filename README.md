在Deepseek AI的帮助下编写的在特化的个人使用场景中用来自动监控两个文件夹中新添加的检查结果图片并自动打印的简单Java程序。

默认监控Windows系统下`D:\AL`和`D:\CEM`两个目录，并识别末尾为`NIDEK-ALScan_OPTP_01D528.jpg`和`NIDEK-CEM530_DETAILP_033CCF.jpg`的图片文档。

> 可自行修改源代码中的文件目录和监控文件的命名规则来扩展使用场景。

使用前需要先使用git clone命令克隆仓库到本地或下载源代码到本地，并安装Java后自行编译。

编译命令：
```
javac -encoding UTF-8 AutoPrintMonitor.java
jar -cfe AutoPrintMonitor.jar AutoPrintMonitor *.class
```

然后在win下运行.bat，或自己在终端使用`java -jar AutoPrintMonitor.jar`命令运行。
