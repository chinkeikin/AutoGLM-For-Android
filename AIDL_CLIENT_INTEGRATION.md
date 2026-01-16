# AutoGLM AIDL 客户端集成指南（供 AI 参考）

> **文档用途**：此文档供 AI 助手参考，用于在其他 Android 项目中快速实现与 AutoGLM 的跨进程通信。
> 
> **使用场景**：在另一个项目中打开 AI 对话，提供此文档给 AI，AI 将能够直接在该项目中完成 AIDL 客户端实现。

---

## 一、AutoGLM AIDL 接口定义

AutoGLM 提供了两个 AIDL 接口用于跨进程通信：

### 1.1 ITaskService.aidl

**文件路径**：`app/src/main/aidl/com/kevinluo/autoglm/ITaskService.aidl`

```java
package com.kevinluo.autoglm;

import com.kevinluo.autoglm.ITaskOutputCallback;

/**
 * AIDL 接口：用于跨进程接收任务和注册输出回调
 */
interface ITaskService {
    /**
     * 启动一个新任务
     * @param taskDescription 任务描述（自然语言）
     * @return 任务是否成功启动
     */
    boolean startTask(String taskDescription);
    
    /**
     * 取消当前正在运行的任务
     */
    void cancelTask();
    
    /**
     * 暂停当前正在运行的任务
     * @return 是否成功暂停
     */
    boolean pauseTask();
    
    /**
     * 恢复已暂停的任务
     * @return 是否成功恢复
     */
    boolean resumeTask();
    
    /**
     * 检查是否有任务正在运行
     * @return true: 有任务运行中，false: 空闲
     */
    boolean isTaskRunning();
    
    /**
     * 注册任务输出回调
     * @param callback 回调接口实现
     */
    void registerOutputCallback(ITaskOutputCallback callback);
    
    /**
     * 取消注册输出回调
     * @param callback 要取消的回调
     */
    void unregisterOutputCallback(ITaskOutputCallback callback);
}
```

### 1.2 ITaskOutputCallback.aidl

**文件路径**：`app/src/main/aidl/com/kevinluo/autoglm/ITaskOutputCallback.aidl`

```java
package com.kevinluo.autoglm;

/**
 * AIDL 回调接口：用于向外部应用传递任务执行输出
 */
interface ITaskOutputCallback {
    /**
     * 任务开始时调用
     * @param taskDescription 任务描述
     */
    void onTaskStarted(String taskDescription);
    
    /**
     * 步骤开始时调用
     * @param stepNumber 步骤编号
     */
    void onStepStarted(int stepNumber);
    
    /**
     * 思考内容更新时调用
     * @param thinking 模型的思考内容
     */
    void onThinkingUpdate(String thinking);
    
    /**
     * 动作执行时调用
     * @param actionDescription 动作描述（格式化后的可读文本）
     */
    void onActionExecuted(String actionDescription);
    
    /**
     * 任务完成时调用
     * @param success 是否成功完成
     * @param message 完成消息
     * @param stepCount 总步骤数
     */
    void onTaskCompleted(boolean success, String message, int stepCount);
    
    /**
     * 任务失败时调用
     * @param error 错误信息
     * @param stepCount 已执行的步骤数
     */
    void onTaskFailed(String error, int stepCount);
    
    /**
     * 任务状态变化时调用
     * @param status 状态: IDLE, RUNNING, PAUSED, COMPLETED, FAILED
     */
    void onStatusChanged(String status);
}
```

---

## 二、服务连接信息

### 2.1 AutoGLM 服务信息

- **包名**：`com.kevinluo.autoglm`
- **服务类名**：`com.kevinluo.autoglm.ipc.TaskService`
- **完整类名**：`com.kevinluo.autoglm/com.kevinluo.autoglm.ipc.TaskService`

### 2.2 Intent 构造方式

```kotlin
val intent = Intent().apply {
    setClassName(
        "com.kevinluo.autoglm",
        "com.kevinluo.autoglm.ipc.TaskService"
    )
}
```

---

## 三、客户端实现步骤（供 AI 执行）

### 步骤 1：创建 AIDL 文件

在客户端项目中创建相同的 AIDL 文件：

**目录结构**：
```
app/src/main/aidl/com/kevinluo/autoglm/
├── ITaskService.aidl
└── ITaskOutputCallback.aidl
```

**文件内容**：使用上面「一、AutoGLM AIDL 接口定义」中的完整代码。

### 步骤 2：启用 AIDL 构建功能

在 `app/build.gradle.kts` 或 `app/build.gradle` 中添加：

```kotlin
android {
    buildFeatures {
        aidl = true
    }
}
```

### 步骤 3：实现客户端 Activity

创建一个 Activity，实现服务绑定和任务控制：

```kotlin
package com.example.yourapp  // 替换为实际包名

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kevinluo.autoglm.ITaskOutputCallback
import com.kevinluo.autoglm.ITaskService

class AutoGLMClientActivity : AppCompatActivity() {

    private var taskService: ITaskService? = null
    private var isBound = false
    
    // UI 组件
    private lateinit var etTaskInput: EditText
    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var btnResume: Button
    private lateinit var btnCancel: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvOutput: TextView
    
    // 服务连接回调
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            taskService = ITaskService.Stub.asInterface(service)
            isBound = true
            Log.i(TAG, "Service connected")
            
            try {
                taskService?.registerOutputCallback(outputCallback)
                appendOutput("✅ 已连接到 AutoGLM 服务\n")
                updateButtonStates()
            } catch (e: RemoteException) {
                Log.e(TAG, "Error registering callback", e)
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            taskService = null
            isBound = false
            Log.i(TAG, "Service disconnected")
            appendOutput("⚠️ 服务已断开\n")
            updateButtonStates()
        }
    }
    
    // 输出回调实现
    private val outputCallback = object : ITaskOutputCallback.Stub() {
        override fun onTaskStarted(taskDescription: String?) {
            appendOutput("🚀 任务开始: $taskDescription\n")
        }
        
        override fun onStepStarted(stepNumber: Int) {
            appendOutput("📍 步骤 $stepNumber\n")
        }
        
        override fun onThinkingUpdate(thinking: String?) {
            if (!thinking.isNullOrBlank()) {
                appendOutput("💭 $thinking\n")
            }
        }
        
        override fun onActionExecuted(actionDescription: String?) {
            appendOutput("⚡ $actionDescription\n")
        }
        
        override fun onTaskCompleted(success: Boolean, message: String?, stepCount: Int) {
            appendOutput("✅ 完成: $message (共 $stepCount 步)\n")
            runOnUiThread { updateButtonStates() }
        }
        
        override fun onTaskFailed(error: String?, stepCount: Int) {
            appendOutput("❌ 失败: $error\n")
            runOnUiThread { updateButtonStates() }
        }
        
        override fun onStatusChanged(status: String?) {
            runOnUiThread {
                tvStatus.text = "状态: $status"
                updateButtonStates()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_autoglm_client)
        
        initViews()
        setupListeners()
        bindAutoGLMService()
    }
    
    private fun initViews() {
        etTaskInput = findViewById(R.id.etTaskInput)
        btnStart = findViewById(R.id.btnStart)
        btnPause = findViewById(R.id.btnPause)
        btnResume = findViewById(R.id.btnResume)
        btnCancel = findViewById(R.id.btnCancel)
        tvStatus = findViewById(R.id.tvStatus)
        tvOutput = findViewById(R.id.tvOutput)
    }
    
    private fun setupListeners() {
        btnStart.setOnClickListener { startTask() }
        btnPause.setOnClickListener { pauseTask() }
        btnResume.setOnClickListener { resumeTask() }
        btnCancel.setOnClickListener { cancelTask() }
    }
    
    private fun bindAutoGLMService() {
        try {
            val intent = Intent().apply {
                setClassName(
                    "com.kevinluo.autoglm",
                    "com.kevinluo.autoglm.ipc.TaskService"
                )
            }
            val bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            
            if (bound) {
                appendOutput("🔄 正在连接到 AutoGLM...\n")
            } else {
                appendOutput("❌ 无法绑定服务\n")
                Toast.makeText(this, "无法绑定 AutoGLM 服务", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error binding service", e)
            appendOutput("❌ 绑定失败: ${e.message}\n")
        }
    }
    
    private fun startTask() {
        val taskDescription = etTaskInput.text.toString().trim()
        
        if (taskDescription.isEmpty()) {
            Toast.makeText(this, "请输入任务描述", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            val started = taskService?.startTask(taskDescription) ?: false
            if (started) {
                Toast.makeText(this, "任务已启动", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "任务启动失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Error starting task", e)
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun pauseTask() {
        try {
            taskService?.pauseTask()
        } catch (e: RemoteException) {
            Log.e(TAG, "Error pausing task", e)
        }
    }
    
    private fun resumeTask() {
        try {
            taskService?.resumeTask()
        } catch (e: RemoteException) {
            Log.e(TAG, "Error resuming task", e)
        }
    }
    
    private fun cancelTask() {
        try {
            taskService?.cancelTask()
            Toast.makeText(this, "任务已取消", Toast.LENGTH_SHORT).show()
        } catch (e: RemoteException) {
            Log.e(TAG, "Error canceling task", e)
        }
    }
    
    private fun updateButtonStates() {
        val isRunning = try {
            taskService?.isTaskRunning() ?: false
        } catch (e: RemoteException) {
            false
        }
        
        btnStart.isEnabled = !isRunning && isBound
        btnPause.isEnabled = isRunning
        btnResume.isEnabled = isRunning
        btnCancel.isEnabled = isRunning
    }
    
    private fun appendOutput(text: String) {
        runOnUiThread {
            tvOutput.append(text)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        if (isBound) {
            try {
                taskService?.unregisterOutputCallback(outputCallback)
            } catch (e: RemoteException) {
                Log.e(TAG, "Error unregistering callback", e)
            }
            unbindService(serviceConnection)
        }
    }
    
    companion object {
        private const val TAG = "AutoGLMClient"
    }
}
```

### 步骤 4：创建布局文件

创建 `res/layout/activity_autoglm_client.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <!-- 状态显示 -->
    <TextView
        android:id="@+id/tvStatus"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="状态: 未连接"
        android:textSize="16sp"
        android:padding="12dp"
        android:background="#CCCCCC"
        android:layout_marginBottom="16dp" />

    <!-- 任务输入 -->
    <EditText
        android:id="@+id/etTaskInput"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="输入任务描述，如：打开微信"
        android:minLines="2"
        android:gravity="top"
        android:layout_marginBottom="16dp" />

    <!-- 控制按钮 -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:layout_marginBottom="16dp">

        <Button
            android:id="@+id/btnStart"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="启动"
            android:layout_marginEnd="4dp" />

        <Button
            android:id="@+id/btnPause"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="暂停"
            android:layout_marginStart="4dp"
            android:layout_marginEnd="4dp"
            android:enabled="false" />

        <Button
            android:id="@+id/btnResume"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="恢复"
            android:layout_marginStart="4dp"
            android:layout_marginEnd="4dp"
            android:enabled="false" />

        <Button
            android:id="@+id/btnCancel"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="取消"
            android:layout_marginStart="4dp"
            android:enabled="false" />
    </LinearLayout>

    <!-- 输出显示 -->
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="任务输出："
        android:textSize="16sp"
        android:textStyle="bold"
        android:layout_marginBottom="8dp" />

    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:background="#000000"
        android:padding="8dp">

        <TextView
            android:id="@+id/tvOutput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="等待连接...\n"
            android:textColor="#FFFFFF"
            android:fontFamily="monospace"
            android:textSize="12sp" />
    </ScrollView>

</LinearLayout>
```

### 步骤 5：注册 Activity

在 `AndroidManifest.xml` 中注册：

```xml
<activity
    android:name=".AutoGLMClientActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

---

## 四、任务示例

以下是一些可以用于测试的任务描述：

```kotlin
// 基础操作
"打开设置"
"打开微信"
"返回桌面"

// 复杂任务
"打开微信，给文件传输助手发送消息：测试"
"打开淘宝，搜索无线耳机"
"打开设置，关闭蓝牙"
```

---

## 五、常见问题处理

### 5.1 服务绑定失败

**原因**：
- AutoGLM 未安装
- 包名或类名错误

**解决**：
```kotlin
// 检查 AutoGLM 是否安装
val pm = packageManager
try {
    pm.getPackageInfo("com.kevinluo.autoglm", 0)
    // 已安装
} catch (e: PackageManager.NameNotFoundException) {
    // 未安装
    Toast.makeText(this, "请先安装 AutoGLM", Toast.LENGTH_LONG).show()
}
```

### 5.2 任务启动失败（返回 false）

**原因**：
- AutoGLM 未初始化（Shizuku 未连接）
- 已有任务正在运行

**解决**：
```kotlin
// 检查任务状态
val isRunning = taskService?.isTaskRunning() ?: false
if (isRunning) {
    Toast.makeText(this, "已有任务运行中", Toast.LENGTH_SHORT).show()
} else {
    Toast.makeText(this, "AutoGLM 未就绪，请先打开 AutoGLM 应用", Toast.LENGTH_LONG).show()
}
```

### 5.3 收不到回调

**原因**：
- 未注册回调
- 回调对象被 GC 回收

**解决**：
```kotlin
// 确保回调对象是成员变量
private val outputCallback = object : ITaskOutputCallback.Stub() {
    // ...
}

// 在 onServiceConnected 中注册
override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
    taskService = ITaskService.Stub.asInterface(service)
    taskService?.registerOutputCallback(outputCallback)  // 注册回调
}
```

---

## 六、关键注意事项

### 6.1 线程安全

回调方法在 Binder 线程中执行，需要使用 `runOnUiThread` 更新 UI：

```kotlin
override fun onTaskCompleted(success: Boolean, message: String?, stepCount: Int) {
    runOnUiThread {
        // 更新 UI
        tvStatus.text = "任务完成"
    }
}
```

### 6.2 异常处理

所有 AIDL 调用都应该捕获 `RemoteException`：

```kotlin
try {
    taskService?.startTask(taskDescription)
} catch (e: RemoteException) {
    Log.e(TAG, "IPC error", e)
}
```

### 6.3 资源清理

在 `onDestroy` 中必须取消注册回调并解绑服务：

```kotlin
override fun onDestroy() {
    super.onDestroy()
    if (isBound) {
        try {
            taskService?.unregisterOutputCallback(outputCallback)
        } catch (e: RemoteException) {
            // 忽略
        }
        unbindService(serviceConnection)
    }
}
```

---

## 七、完整的依赖配置

### 7.1 build.gradle.kts

```kotlin
android {
    namespace = "com.example.yourapp"
    compileSdk = 34
    
    defaultConfig {
        applicationId = "com.example.yourapp"
        minSdk = 24
        targetSdk = 34
    }
    
    buildFeatures {
        aidl = true  // 必须启用
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
}
```

---

## 八、AI 实现检查清单

当 AI 在另一个项目中实现客户端时，应完成以下步骤：

- [ ] 创建 AIDL 文件目录：`app/src/main/aidl/com/kevinluo/autoglm/`
- [ ] 复制 `ITaskService.aidl` 文件（完整内容见 1.1）
- [ ] 复制 `ITaskOutputCallback.aidl` 文件（完整内容见 1.2）
- [ ] 在 `build.gradle.kts` 中启用 AIDL：`buildFeatures { aidl = true }`
- [ ] 同步项目：Sync Now
- [ ] 创建 Activity 类（完整代码见三、步骤 3）
- [ ] 创建布局文件（完整代码见三、步骤 4）
- [ ] 在 Manifest 中注册 Activity
- [ ] 测试服务绑定
- [ ] 测试任务启动和回调接收

---

## 九、验证步骤

### 9.1 编译验证

```bash
./gradlew clean build
```

应该没有编译错误，AIDL 接口会自动生成 Java 代码。

### 9.2 运行验证

1. 确保 AutoGLM 已安装并授予权限
2. 运行客户端应用
3. 输入任务描述
4. 点击"启动"按钮
5. 观察输出区域是否有实时日志

### 9.3 预期输出

```
✅ 已连接到 AutoGLM 服务
🚀 任务开始: 打开微信
📍 步骤 1
💭 用户想要打开微信应用...
⚡ 启动应用 - 微信 (com.tencent.mm)
✅ 完成: 任务成功完成 (共 2 步)
```

---

## 十、总结

### AutoGLM 服务端已实现的功能

- ✅ AIDL 接口定义
- ✅ TaskService 服务实现
- ✅ 任务执行和状态管理
- ✅ 实时输出回调
- ✅ 任务控制（启动/暂停/恢复/取消）

### 客户端需要实现的内容

1. 复制 AIDL 接口文件
2. 启用 AIDL 构建
3. 实现服务绑定逻辑
4. 实现回调接口
5. 创建 UI 和交互逻辑

### 最小化实现（仅启动任务）

如果只需要启动任务，不需要 UI，最小化代码：

```kotlin
class SimpleClient : AppCompatActivity() {
    private var taskService: ITaskService? = null
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            taskService = ITaskService.Stub.asInterface(service)
            // 启动任务
            taskService?.startTask("打开微信")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            taskService = null
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val intent = Intent().setClassName(
            "com.kevinluo.autoglm",
            "com.kevinluo.autoglm.ipc.TaskService"
        )
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unbindService(serviceConnection)
    }
}
```

---

**文档版本**：1.0  
**生成时间**：2026-01-16  
**适用于**：AutoGLM 跨进程通信客户端实现
