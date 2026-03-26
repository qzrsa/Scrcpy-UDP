package qzrs.Scrcpy.helper;

import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 统一日志工具 - 详细日志记录
 */
public class Logger {
    
    private static final String TAG = "Scrcpy-UDP";
    private static final boolean ENABLE_LOGGING = true;
    private static final boolean ENABLE_FILE_LOG = true;
    private static final int MAX_LOG_LINES = 1000;
    
    private static final List<LogEntry> logBuffer = new ArrayList<>();
    private static SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    
    public static class LogEntry {
        public long timestamp;
        public String level;
        public String tag;
        public String message;
        public String threadName;
        
        public LogEntry(String level, String tag, String message) {
            this.timestamp = System.currentTimeMillis();
            this.level = level;
            this.tag = tag;
            this.message = message;
            this.threadName = Thread.currentThread().getName();
        }
        
        @Override
        public String toString() {
            return String.format("[%s][%s][%s][%s] %s",
                dateFormat.format(new Date(timestamp)),
                level,
                tag,
                threadName,
                message);
        }
    }
    
    public static void d(String tag, String msg) { log("DEBUG", tag, msg); }
    public static void i(String tag, String msg) { log("INFO ", tag, msg); }
    public static void w(String tag, String msg) { log("WARN ", tag, msg); }
    public static void e(String tag, String msg) { log("ERROR", tag, msg); }
    
    public static void d(String tag, String msg, Throwable t) { log("DEBUG", tag, msg + "\n" + getStackTrace(t)); }
    public static void i(String tag, String msg, Throwable t) { log("INFO ", tag, msg + "\n" + getStackTrace(t)); }
    public static void w(String tag, String msg, Throwable t) { log("WARN ", tag, msg + "\n" + getStackTrace(t)); }
    public static void e(String tag, String msg, Throwable t) { log("ERROR", tag, msg + "\n" + getStackTrace(t)); }
    
    private static void log(String level, String tag, String message) {
        if (!ENABLE_LOGGING) return;
        
        // 添加到缓冲区
        synchronized (logBuffer) {
            LogEntry entry = new LogEntry(level, tag, message);
            logBuffer.add(entry);
            
            // 限制缓冲区大小
            while (logBuffer.size() > MAX_LOG_LINES) {
                logBuffer.remove(0);
            }
        }
        
        // 输出到Logcat
        switch (level) {
            case "DEBUG": Log.d(tag, message); break;
            case "INFO ": Log.i(tag, message); break;
            case "WARN ": Log.w(tag, message); break;
            case "ERROR": Log.e(tag, message); break;
        }
        
        // 写入文件
        if (ENABLE_FILE_LOG) {
            writeToFile(level, tag, message);
        }
    }
    
    /**
     * 获取所有日志
     */
    public static List<LogEntry> getAllLogs() {
        synchronized (logBuffer) {
            return new ArrayList<>(logBuffer);
        }
    }
    
    /**
     * 获取最近N条日志
     */
    public static List<LogEntry> getRecentLogs(int count) {
        synchronized (logBuffer) {
            int size = logBuffer.size();
            if (count >= size) {
                return new ArrayList<>(logBuffer);
            }
            return new ArrayList<>(logBuffer.subList(size - count, size));
        }
    }
    
    /**
     * 清除日志
     */
    public static void clearLogs() {
        synchronized (logBuffer) {
            logBuffer.clear();
        }
    }
    
    /**
     * 获取日志文本
     */
    public static String getLogsAsText() {
        StringBuilder sb = new StringBuilder();
        sb.append("========== Scrcpy-UDP 详细日志 ==========\n");
        sb.append("时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n\n");
        
        synchronized (logBuffer) {
            for (LogEntry entry : logBuffer) {
                sb.append(entry.toString()).append("\n");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 记录连接流程
     */
    public static void logConnection(String step, String details) {
        i("Connection", ">>> " + step + ": " + details);
    }
    
    /**
     * 记录视频流
     */
    public static void logVideo(String action, String details) {
        d("Video", action + ": " + details);
    }
    
    /**
     * 记录控制指令
     */
    public static void logControl(String action, String details) {
        d("Control", action + ": " + details);
    }
    
    /**
     * 记录网络状态
     */
    public static void logNetwork(String action, String details) {
        i("Network", action + ": " + details);
    }
    
    /**
     * 记录错误并记录完整堆栈
     */
    public static void logError(String tag, String message, Throwable t) {
        e(tag, message, t);
    }
    
    private static String getStackTrace(Throwable t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        PrintWriter pw = new PrintWriter(new StringWriter());
        t.printStackTrace(pw);
        sb.append(pw.toString());
        return sb.toString();
    }
    
    private static void writeToFile(String level, String tag, String message) {
        // 可以在此实现写入文件的功能
        // 目前先不实现，避免权限问题
    }
}
