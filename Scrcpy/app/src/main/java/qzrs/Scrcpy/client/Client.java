package qzrs.Scrcpy.client;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Pair;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Objects;

import qzrs.Scrcpy.client.tools.AdbTools;
import qzrs.Scrcpy.client.tools.ClientController;
import qzrs.Scrcpy.client.tools.ClientPlayer;
import qzrs.Scrcpy.client.tools.ClientStream;
import qzrs.Scrcpy.client.tools.ControlPacket;
import qzrs.Scrcpy.client.tools.UdpClientStream;
import qzrs.Scrcpy.databinding.ItemLoadingBinding;
import qzrs.Scrcpy.entity.AppData;
import qzrs.Scrcpy.entity.Device;
import qzrs.Scrcpy.helper.Logger;
import qzrs.Scrcpy.helper.PublicTools;
import qzrs.Scrcpy.helper.ViewTools;

public class Client {
  private static final HashMap<String, Client> allClient = new HashMap<>();
  private boolean isClosed = false;

  // 组件
  private ClientStream clientStream = null;
  private ClientController clientController = null;
  private ClientPlayer clientPlayer = null;
  private Device device;

  public Client(Device device) {
    Logger.i("Client", "========== Client 开始初始化 ==========");
    Logger.i("Client", "设备UUID: " + device.uuid);
    Logger.i("Client", "设备地址: " + device.address);
    Logger.i("Client", "服务器端口: " + device.serverPort);
    Logger.i("Client", "UDP模式: " + device.useUdpMode);
    
    PublicTools.logToast("Client", "开始连接: " + device.address, true);
    
    if (allClient.containsKey(device.uuid)) {
      Logger.w("Client", "设备已连接，跳过");
      PublicTools.logToast("Client", "设备已连接", true);
      return;
    }
    this.device = device;
    Pair<ItemLoadingBinding, Dialog> loading = ViewTools.createLoading(AppData.mainActivity);
    loading.second.show();
    Logger.i("Client", "Loading对话框已显示");
    PublicTools.logToast("Client", "Loading显示中...", true);
    
    // 根据模式选择连接方式
    if (device.useUdpMode) {
      // UDP模式
      Logger.i("Client", ">>> 使用UDP模式连接");
      PublicTools.logToast("Client", "UDP模式", true);
      clientStream = new UdpClientStream(device, bool -> onConnected(bool, loading));
    } else {
      // TCP模式
      Logger.i("Client", ">>> 使用TCP模式连接");
      PublicTools.logToast("Client", "TCP模式", true);
      clientStream = new ClientStream(device, bool -> onConnected(bool, loading));
    }
  }
  
  private void onConnected(boolean bool, Pair<ItemLoadingBinding, Dialog> loading) {
    Logger.i("Client", "========== onConnected 回调 ==========");
    Logger.i("Client", "连接结果: " + (bool ? "成功" : "失败"));
    
    if (bool) {
      allClient.put(device.uuid, this);
      Logger.i("Client", "设备已添加到客户端列表");
      
      // 控制器、播放器
      Logger.i("Client", "创建ClientController...");
      clientController = new ClientController(device, clientStream, () -> {
        Logger.i("Client", "创建ClientPlayer...");
        clientPlayer = new ClientPlayer(device.uuid, clientStream);
      });
      
      // 临时设备
      boolean isTempDevice = device.isTempDevice();
      // 启动界面
      Logger.i("Client", "启动界面...");
      clientController.handleAction(device.changeToFullOnConnect ? "changeToFull" : "changeToSmall", null, 0);
      // 运行启动时操作
      if (device.customResolutionOnConnect) clientController.handleAction("writeByteBuffer", ControlPacket.createChangeResolutionEvent(device.customResolutionWidth, device.customResolutionHeight), 0);
      if (!isTempDevice && device.wakeOnConnect) clientController.handleAction("buttonWake", null, 0);
      if (!isTempDevice && device.lightOffOnConnect) clientController.handleAction("buttonLightOff", null, 2000);
      
      Logger.i("Client", "========== 连接流程完成 ==========");
    } else {
      Logger.e("Client", "连接失败!");
    }
    if (loading.second.isShowing()) loading.second.cancel();
  }

  public static void startDevice(Device device) {
    if (device == null) return;
    // 检查悬浮窗权限
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(AppData.applicationContext)) {
      PublicTools.logToast("Client", "请先开启悬浮窗权限！", true);
      // 跳转到悬浮窗权限设置页面
      Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
          Uri.parse("package:" + AppData.applicationContext.getPackageName()));
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      AppData.applicationContext.startActivity(intent);
      return;
    }
    new Client(device);
  }

  public static Device getDevice(String uuid) {
    Client client = allClient.get(uuid);
    if (client == null) return null;
    return client.device;
  }

  public static ClientController getClientController(String uuid) {
    Client client = allClient.get(uuid);
    if (client == null) return null;
    return client.clientController;
  }

  public static void sendAction(String uuid, String action, ByteBuffer byteBuffer, int delay) {
    if (action == null || uuid == null) return;
    if (action.equals("start")) {
      for (Device device : AdbTools.devicesList) if (Objects.equals(device.uuid, uuid)) startDevice(device);
    } else {
      Client client = allClient.get(uuid);
      if (client == null) return;
      if (action.equals("close")) {
        client.close(byteBuffer);
      } else {
        if (client.clientController == null) return;
        client.clientController.handleAction(action, byteBuffer, delay);
      }
    }
  }

  private void close(ByteBuffer byteBuffer) {
    if (isClosed) return;
    isClosed = true;
    // 临时设备
    boolean isTempDevice = device.isTempDevice();
    // 更新数据库
    if (!isTempDevice) AppData.dbHelper.update(device);
    allClient.remove(device.uuid);
    // 运行断开时操作
    if (!isTempDevice && device.lockOnClose) clientController.handleAction("buttonLock", null, 0);
    else if (!isTempDevice && device.lightOnClose) clientController.handleAction("buttonLight", null, 0);
    // 关闭组件
    if (clientPlayer != null) clientPlayer.close();
    if (clientController != null) clientController.close();
    if (clientStream != null) clientStream.close();
    // 如果设置了自动重连
    if (byteBuffer != null) {
      PublicTools.logToast("Client", new String(byteBuffer.array()), true);
      if (device.reconnectOnClose) startDevice(device);
    }
  }

}
