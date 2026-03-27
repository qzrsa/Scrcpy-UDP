package qzrs.Scrcpy;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.graphics.Color;
import android.view.View;
import java.util.List;

import qzrs.Scrcpy.helper.Logger;

/**
 * 日志查看Activity
 */
public class LogActivity extends Activity {
    
    private TextView logTextView;
    private ScrollView scrollView;
    private boolean autoScroll = true;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(10, 10, 10, 10);
        
        // 标题
        TextView title = new TextView(this);
        title.setText("Scrcpy-UDP 日志查看器");
        title.setTextSize(20);
        title.setTextColor(Color.BLACK);
        layout.addView(title);
        
        // 按钮区域
        LinearLayout buttonLayout = new LinearLayout(this);
        buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
        
        Button refreshBtn = new Button(this);
        refreshBtn.setText("刷新");
        refreshBtn.setOnClickListener(v -> refreshLogs());
        buttonLayout.addView(refreshBtn);
        
        Button clearBtn = new Button(this);
        clearBtn.setText("清除");
        clearBtn.setOnClickListener(v -> {
            Logger.clearLogs();
            logTextView.setText("日志已清除\n");
        });
        buttonLayout.addView(clearBtn);
        
        Button copyBtn = new Button(this);
        copyBtn.setText("复制全部");
        copyBtn.setOnClickListener(v -> {
            String logs = Logger.getLogsAsText();
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("Scrcpy Logs", logs);
            clipboard.setPrimaryClip(clip);
            android.widget.Toast.makeText(this, "日志已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show();
        });
        buttonLayout.addView(copyBtn);
        
        layout.addView(buttonLayout);
        
        // 日志文本区域
        scrollView = new ScrollView(this);
        logTextView = new TextView(this);
        logTextView.setTextSize(12);
        logTextView.setTextColor(Color.parseColor("#00FF00")); // 绿色文字
        logTextView.setBackgroundColor(Color.parseColor("#1E1E1E")); // 黑色背景
        logTextView.setPadding(10, 10, 10, 10);
        logTextView.setTypeface(android.graphics.Typeface.MONOSPACE); // 等宽字体
        scrollView.addView(logTextView);
        layout.addView(scrollView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        
        // 添加一条测试日志
        Logger.i("LogActivity", "日志系统初始化完成");
        
        setContentView(layout);
        
        // 初始加载
        refreshLogs();
        
        // 自动刷新
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    runOnUiThread(this::refreshLogs);
                } catch (Exception e) {
                    break;
                }
            }
        }).start();
    }
    
    private void refreshLogs() {
        StringBuilder sb = new StringBuilder();
        sb.append("========== Scrcpy-UDP 详细日志 ==========\n");
        sb.append("时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(new java.util.Date())).append("\n");
        sb.append("日志数量: ").append(Logger.getAllLogs().size()).append(" 条\n");
        sb.append("\n");
        
        for (Logger.LogEntry entry : Logger.getAllLogs()) {
            sb.append(formatEntry(entry)).append("\n");
        }
        
        logTextView.setText(sb.toString());
        
        // 自动滚动到底部
        if (autoScroll) {
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        }
    }
    
    private String formatEntry(Logger.LogEntry entry) {
        String level = entry.level;
        String colorCode;
        switch (level) {
            case "ERROR": colorCode = "#FF4444"; break;
            case "WARN ": colorCode = "#FFAA00"; break;
            case "INFO ": colorCode = "#44FF44"; break;
            default: colorCode = "#AAAAAA";
        }
        return String.format("[%s][%s] %s",
            entry.tag,
            level,
            entry.message);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
