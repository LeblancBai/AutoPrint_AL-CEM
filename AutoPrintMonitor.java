import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.print.*;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 自动打印监控程序
 * 功能：
 *   1. 监控 D:\AL 与 D:\CEM 两个文件夹
 *   2. 识别文件名结尾为 NIDEK-ALScan_OPTP_01D528.jpg / NIDEK-CEM530_DETAILP_033CCF.jpg 的图片
 *   3. 自动发送到默认打印机进行完整显示打印（不弹对话框）
 *   4. 右下角显示自动消失的悬浮提示窗
 */
public class AutoPrintMonitor {

    // ==================== 可配置区 ====================

    /** 需要监控的文件夹列表 */
    private static final String[] MONITOR_FOLDERS = {
            "D:\\AL",
            "D:\\CEM"
    };

    /** 需要匹配的文件名后缀（不区分大小写，满足 endsWith 即触发） */
    private static final String[] FILE_SUFFIXES = {
            "NIDEK-ALScan_OPTP_01D528.jpg",
            "NIDEK-CEM530_DETAILP_033CCF.jpg"
    };

    /** 通知窗口停留时长（毫秒），淡入淡出不计入 */
    private static final int NOTIFICATION_HOLD_MS = 2500;

    // ==================== 全局状态 ====================

    /** 记录已处理过的文件绝对路径，避免重复打印 */
    private static final Set<String> printedFiles = new HashSet<>();

    /** 打印任务串行锁（防止多个打印任务并发冲突） */
    private static final Object printLock = new Object();

    /** 处理线程池（避免阻塞 WatchService 主循环） */
    private static final ExecutorService executor = Executors.newFixedThreadPool(2);

    // ==================== 主入口 ====================

    public static void main(String[] args) {
        // 使用系统默认外观（让 Swing 窗口更像 Windows 原生）
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        log("===== 自动打印监控程序启动 =====");

        // 创建 WatchService
        WatchService watchService;
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (Exception e) {
            log("创建 WatchService 失败: " + e.getMessage());
            return;
        }

        // 注册所有监控目录
        int registered = 0;
        for (String folderStr : MONITOR_FOLDERS) {
            Path folder = Paths.get(folderStr);

            if (!Files.exists(folder)) {
                log("警告：文件夹不存在 -> " + folderStr);
                continue;
            }
            if (!Files.isDirectory(folder)) {
                log("警告：路径不是文件夹 -> " + folderStr);
                continue;
            }

            try {
                // 只关心“新建文件”事件
                folder.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
                log("已开始监控: " + folderStr);
                registered++;
            } catch (Exception e) {
                log("注册监控失败 " + folderStr + ": " + e.getMessage());
            }
        }

        if (registered == 0) {
            log("没有成功注册任何监控目录，程序退出。");
            return;
        }

        log("等待新文件产生...");

        // ==================== 主监控循环 ====================
        while (true) {
            WatchKey key;
            try {
                // take() 阻塞等待，CPU 占用几乎为 0
                key = watchService.take();
            } catch (InterruptedException e) {
                log("监控被中断，退出。");
                break;
            } catch (ClosedWatchServiceException e) {
                log("WatchService 已关闭，退出。");
                break;
            }

            Path dir = (Path) key.watchable();

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue; // 事件被丢弃，跳过
                }

                if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                    Path fileNamePath = (Path) event.context();
                    String fileName = fileNamePath.toString();
                    Path fullPath = dir.resolve(fileNamePath);

                    // 不匹配命名规则则忽略
                    if (!matchesRule(fileName)) {
                        continue;
                    }

                    log("检测到目标文件: " + fullPath);

                    // 提交到线程池处理（避免阻塞监控循环）
                    executor.submit(() -> handleNewFile(fullPath));
                }
            }

            // 必须重置 key，否则后续事件不会被接收
            if (!key.reset()) {
                log("监控目录已失效: " + dir);
            }
        }
    }

    // ==================== 文件匹配 ====================

    /**
     * 判断文件名是否满足“以指定字符串结尾”的规则
     */
    private static boolean matchesRule(String fileName) {
        String lower = fileName.toLowerCase();
        for (String suffix : FILE_SUFFIXES) {
            if (lower.endsWith(suffix.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    // ==================== 单个文件处理 ====================

    private static void handleNewFile(Path file) {
        String key = file.toAbsolutePath().toString();

        // 去重（同一文件短时间内可能触发多次 CREATE）
        synchronized (printedFiles) {
            if (printedFiles.contains(key)) {
                return;
            }
            printedFiles.add(key);
        }

        try {
            // 1. 等待文件被完整写入
            if (!waitForFileReady(file.toFile())) {
                log("文件未就绪或已被删除，跳过: " + file);
                synchronized (printedFiles) { printedFiles.remove(key); }
                return;
            }

            // 2. 打印（串行执行，避免并发冲突）
            synchronized (printLock) {
                printImage(file.toFile());
            }

            // 3. 弹出自动消失的提示
            showNotification("已自动打印", file.getFileName().toString());

        } catch (Exception e) {
            log("处理文件失败 " + file + " : " + e.getMessage());
            e.printStackTrace();
            showNotification("打印失败", file.getFileName().toString());

            // 允许后续重试
            synchronized (printedFiles) { printedFiles.remove(key); }
        }
    }

    /**
     * 等待文件写入完成：
     *   1) 文件长度连续 3 次采样保持不变
     *   2) 能成功获取排他锁
     */
    private static boolean waitForFileReady(File file) throws InterruptedException {
        long lastSize = -1;
        int stableCount = 0;

        // 最多等待 75 * 200ms = 15 秒
        for (int i = 0; i < 75; i++) {
            Thread.sleep(200);

            if (!file.exists()) {
                return false;
            }

            long size = file.length();
            if (size > 0 && size == lastSize) {
                stableCount++;
                if (stableCount >= 3 && tryExclusiveLock(file)) {
                    return true;
                }
            } else {
                stableCount = 0;
            }
            lastSize = size;
        }
        return false;
    }

    /** 尝试获取文件排他锁，成功则说明写入已完成 */
    private static boolean tryExclusiveLock(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw");
             FileChannel channel = raf.getChannel()) {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                lock.release();
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ==================== 打印逻辑 ====================

    private static void printImage(File file) throws Exception {
        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new Exception("ImageIO 无法读取该文件");
        }

        PrinterJob job = PrinterJob.getPrinterJob();

        // ========== 构造 A4 竖版页面 ==========
        // A4 尺寸（单位：pt，1 pt = 1/72 inch）
        // 210mm × 297mm = 595.28 pt × 841.89 pt
        final double A4_W = 595.28;
        final double A4_H = 841.89;
        // 安全边距，避免图片被打印到打印机不可打印区域（约 6.35mm）
        final double MARGIN = 18.0;

        Paper a4Paper = new Paper();
        a4Paper.setSize(A4_W, A4_H);
        // 可打印区域 = A4 去掉四周安全边距
        a4Paper.setImageableArea(
                MARGIN,
                MARGIN,
                A4_W - 2 * MARGIN,
                A4_H - 2 * MARGIN);

        PageFormat pf = new PageFormat();
        pf.setPaper(a4Paper);
        pf.setOrientation(PageFormat.PORTRAIT); // 强制竖版

        job.setPrintable(new FitImagePrintable(image), pf);

        // 静默打印
        job.print();

        log("已提交打印任务: " + file.getName());
    }


    static class FitImagePrintable implements Printable {
        private final BufferedImage image;

        FitImagePrintable(BufferedImage image) {
            this.image = image;
        }

        @Override
        public int print(Graphics g, PageFormat pf, int pageIndex) {
            if (pageIndex > 0) {
                return NO_SUCH_PAGE;
            }

            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            double imgW = image.getWidth();
            double imgH = image.getHeight();
            double pageW = pf.getImageableWidth();
            double pageH = pf.getImageableHeight();

            // 关键：取 min → 图片完整显示，短边方向留白
            //       取 max → 铺满整页，但会裁剪掉长边超出部分
            double scale = Math.min(pageW / imgW, pageH / imgH);

            double drawW = imgW * scale;
            double drawH = imgH * scale;

            // 在可打印区域居中
            double x = pf.getImageableX() + (pageW - drawW) / 2.0;
            double y = pf.getImageableY() + (pageH - drawH) / 2.0;

            g2d.drawImage(image,
                    (int) Math.round(x), (int) Math.round(y),
                    (int) Math.round(drawW), (int) Math.round(drawH),
                    null);

            return PAGE_EXISTS;
        }
    }
    // ==================== 悬浮通知 ====================

    /**
     * 在屏幕右下角显示一个自动消失的悬浮提示窗
     * 效果：淡入 -> 停留 NOTIFICATION_HOLD_MS -> 淡出 -> 销毁
     */
    private static void showNotification(String title, String detail) {
        SwingUtilities.invokeLater(() -> {
            try {
                final JWindow window = new JWindow();
                window.setAlwaysOnTop(true);

                // 使用 HTML 让标题和内容分两行、居中
                String html = "<html><div style='text-align:center;'>"
                        + "<b>" + title + "</b><br>"
                        + "<span style='font-size:10px;'>" + detail + "</span>"
                        + "</div></html>";

                JLabel label = new JLabel(html, SwingConstants.CENTER);
                label.setOpaque(true);
                label.setBackground(new Color(40, 40, 40, 230));
                label.setForeground(Color.WHITE);
                label.setFont(new Font("微软雅黑", Font.PLAIN, 14));
                label.setBorder(BorderFactory.createEmptyBorder(18, 32, 18, 32));

                window.getContentPane().add(label);
                window.pack();

                // 放到屏幕右下角
                Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                int x = screen.width - window.getWidth() - 30;
                int y = screen.height - window.getHeight() - 80;
                window.setLocation(x, y);

                // 尝试设置透明度（部分老系统不支持，忽略异常）
                try {
                    window.setOpacity(0f);
                } catch (Exception ignored) {
                }

                window.setVisible(true);

                // ===== 淡入动画 =====
                final float[] opacity = {0f};
                final Timer fadeIn = new Timer(20, null);
                fadeIn.addActionListener(e -> {
                    opacity[0] += 0.08f;
                    if (opacity[0] >= 1f) {
                        opacity[0] = 1f;
                        fadeIn.stop();

                        // 停留 N 秒后开始淡出
                        Timer hold = new Timer(NOTIFICATION_HOLD_MS,
                                ev -> startFadeOut(window, opacity));
                        hold.setRepeats(false);
                        hold.start();
                    }
                    try { window.setOpacity(opacity[0]); } catch (Exception ignored) {}
                });
                fadeIn.start();

            } catch (Exception e) {
                log("显示通知失败: " + e.getMessage());
            }
        });
    }

    /** 淡出动画，结束销毁窗口 */
    private static void startFadeOut(JWindow window, float[] opacity) {
        final Timer fadeOut = new Timer(20, null);
        fadeOut.addActionListener(e -> {
            opacity[0] -= 0.08f;
            if (opacity[0] <= 0f) {
                fadeOut.stop();
                window.dispose();
            } else {
                try { window.setOpacity(opacity[0]); } catch (Exception ignored) {}
            }
        });
        fadeOut.start();
    }

    // ==================== 日志 ====================

    private static void log(String msg) {
        String time = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        System.out.println("[" + time + "] " + msg);
    }
}
