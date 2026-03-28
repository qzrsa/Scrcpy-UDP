package qzrs.Scrcpy.client.tools;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Pair;
import android.view.Surface;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;

import qzrs.Scrcpy.client.Client;
import qzrs.Scrcpy.client.decode.AudioDecode;
import qzrs.Scrcpy.client.decode.VideoDecode;
import qzrs.Scrcpy.helper.Logger;
import qzrs.Scrcpy.helper.PublicTools;

public class ClientPlayer {
  private boolean isClose = false;
  private final ClientController clientController;
  private final ClientStream clientStream;
  private final StatsOverlay statsOverlay;
  private final Thread mainStreamInThread = new Thread(this::mainStreamIn);
  private final Thread videoStreamInThread = new Thread(this::videoStreamIn);
  private Handler playHandler = null;
  private final HandlerThread playHandlerThread = new HandlerThread("easycontrol_play", Thread.MAX_PRIORITY);
  private static final int AUDIO_EVENT = 1;
  private static final int CLIPBOARD_EVENT = 2;
  private static final int CHANGE_SIZE_EVENT = 3;

  public ClientPlayer(String uuid, ClientStream clientStream) {
    clientController = Client.getClientController(uuid);
    this.clientStream = clientStream;
    statsOverlay = clientStream.getStatsOverlay();
    if (clientController == null) return;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      playHandlerThread.start();
      playHandler = new Handler(playHandlerThread.getLooper());
    }
    mainStreamInThread.start();
    videoStreamInThread.start();
  }

  private void mainStreamIn() {
    AudioDecode audioDecode = null;
    boolean useOpus = true;
    try {
      if (clientStream.readByteFromMain() == 1) useOpus = clientStream.readByteFromMain() == 1;
      while (!Thread.interrupted()) {
        switch (clientStream.readByteFromMain()) {
          case AUDIO_EVENT:
            ByteBuffer audioFrame = clientStream.readFrameFromMain();
            if (audioDecode != null) audioDecode.decodeIn(audioFrame);
            else audioDecode = new AudioDecode(useOpus, audioFrame, playHandler);
            break;
          case CLIPBOARD_EVENT:
            clientController.handleAction("setClipBoard", clientStream.readByteArrayFromMain(clientStream.readIntFromMain()), 0);
            break;
          case CHANGE_SIZE_EVENT:
            clientController.handleAction("updateVideoSize", clientStream.readByteArrayFromMain(8), 0);
            break;
          case 4:
            // 收到心跳回复，计算真实RTT往返延迟
            if (clientStream.pingSendTime != 0) {
              long rtt = System.currentTimeMillis() - clientStream.pingSendTime;
              statsOverlay.onLatency(rtt);
              clientStream.pingSendTime = 0;
            }
            break;
        }
      }
    } catch (InterruptedException ignored) {
    } catch (Exception e) {
      PublicTools.logToast("player", e.toString(), false);
    } finally {
      if (audioDecode != null) audioDecode.release();
    }
  }

  private void videoStreamIn() {
    VideoDecode videoDecode = null;
    try {
      Logger.i("ClientPlayer", ">>> 视频流线程启动");
      
      // 读取编码格式
      byte codecByte = clientStream.readByteFromVideo();
      boolean useH265 = codecByte == 1;
      Logger.i("ClientPlayer", ">>> 读取编码格式: " + (useH265 ? "H265" : "H264") + " (byte=" + codecByte + ")");
      
      // 读取视频分辨率
      int videoWidth = clientStream.readIntFromVideo();
      int videoHeight = clientStream.readIntFromVideo();
      Logger.i("ClientPlayer", ">>> 视频分辨率: " + videoWidth + "x" + videoHeight);
      
      Surface surface = new Surface(clientController.getTextureView().getSurfaceTexture());
      Logger.i("ClientPlayer", ">>> Surface已获取");
      
      // 读取CSD数据
      ByteBuffer csd0 = clientStream.readFrameFromVideo();
      Logger.i("ClientPlayer", ">>> CSD0已读取: " + csd0.remaining() + " bytes");
      
      ByteBuffer csd1 = useH265 ? null : clientStream.readFrameFromVideo();
      if (csd1 != null) Logger.i("ClientPlayer", ">>> CSD1已读取: " + csd1.remaining() + " bytes");
      
      videoDecode = new VideoDecode(new Pair<>(videoWidth, videoHeight), surface, csd0, csd1, playHandler);
      Logger.i("ClientPlayer", ">>> VideoDecode已创建，开始解码循环");
      
      int frameCount = 0;
      while (!Thread.interrupted()) {
        ByteBuffer frame = clientStream.readFrameFromVideo();
        if (frame == null) {
          Logger.w("ClientPlayer", ">>> 视频帧为null，继续等待...");
          Thread.sleep(100);
          continue;
        }
        if (statsOverlay != null) statsOverlay.onVideoFrame(frame.remaining());
        videoDecode.decodeIn(frame);
        frameCount++;
        if (frameCount % 30 == 0) {
          Logger.d("ClientPlayer", ">>> 已解码 " + frameCount + " 帧");
        }
      }
    } catch (InterruptedException ignored) {
    } catch (Exception e) {
      String msg = ">>> 视频流异常: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName());
      Logger.e("ClientPlayer", msg);
      StringWriter sw = new StringWriter();
      e.printStackTrace(new PrintWriter(sw));
      Logger.e("ClientPlayer", "堆栈: " + sw.toString());
    } finally {
      if (videoDecode != null) videoDecode.release();
      Logger.i("ClientPlayer", ">>> 视频流线程结束");
    }
  }

  public void close() {
    if (isClose) return;
    isClose = true;
    if (statsOverlay != null) statsOverlay.hide();
    mainStreamInThread.interrupt();
    videoStreamInThread.interrupt();
    playHandlerThread.interrupt();
  }
}
