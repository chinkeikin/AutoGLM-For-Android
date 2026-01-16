package com.example.autoglmclient

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kevinluo.autoglm.ITaskOutputCallback
import com.kevinluo.autoglm.ITaskService

/**
 * AutoGLM 客户端示例
 * 
 * 展示如何通过 AIDL 接口从外部应用控制 AutoGLM 并接收实时输出
 * 
 * 使用前准备：
 * 1. 将 ITaskService.aidl 和 ITaskOutputCallback.aidl 复制到项目的 aidl 目录
 * 2. 确保 AutoGLM 应用已安装并授予了必要权限
 * 3. 在 build.gradle 中启用 AIDL: buildFeatures { aidl = true }
 */
class AutoGLMClient : AppCompatActivity() {

    // UI 组件
    private lateinit var taskInput: EditText
    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var btnResume: Button
    private lateinit var btnCancel: Button
    private lateinit var btnCheckStatus: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvOutput: TextView
    private lateinit var scrollView: ScrollView

    // 服务连接
    private var taskService: ITaskService? = null
    private var isBound = false

    // 服务连接回调
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            taskService = ITaskService.Stub.asInterface(service)
            isBound = true
            
            // 注册输出回调
            try {
                taskService?.registerOutputCallback(outputCallback)
                appendOutput("✅ 已连接到 AutoGLM 服务\n\n")
            } catch (e: Exception) {
                e.printStackTrace()
                appendOutput("❌ 注册回调失败: ${e.message}\n\n")
            }
            
            updateButtonStates()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            taskService = null
            isBound = false
            appendOutput("⚠️ 服务已断开\n\n")
            updateButtonStates()
        }
    }

    // 输出回调实现
    private val outputCallback = object : ITaskOutputCallback.Stub() {
        override fun onTaskStarted(taskDescription: String?) {
            appendOutput("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            appendOutput("🚀 任务开始\n")
            appendOutput("任务描述: $taskDescription\n")
            appendOutput("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
        }

        override fun onStepStarted(stepNumber: Int) {
            appendOutput("📍 步骤 $stepNumber 开始\n")
        }

        override fun onThinkingUpdate(thinking: String?) {
            if (!thinking.isNullOrBlank()) {
                appendOutput("💭 思考: $thinking\n")
            }
        }

        override fun onActionExecuted(actionDescription: String?) {
            appendOutput("⚡ 执行: $actionDescription\n\n")
        }

        override fun onTaskCompleted(success: Boolean, message: String?, stepCount: Int) {
            appendOutput("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            appendOutput("✅ 任务完成\n")
            appendOutput("状态: ${if (success) "成功" else "失败"}\n")
            appendOutput("消息: $message\n")
            appendOutput("总步骤数: $stepCount\n")
            appendOutput("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
            
            runOnUiThread { 
                updateButtonStates()
                updateStatusDisplay()
            }
        }

        override fun onTaskFailed(error: String?, stepCount: Int) {
            appendOutput("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            appendOutput("❌ 任务失败\n")
            appendOutput("错误: $error\n")
            appendOutput("已执行步骤数: $stepCount\n")
            appendOutput("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
            
            runOnUiThread { 
                updateButtonStates()
                updateStatusDisplay()
            }
        }

        override fun onStatusChanged(status: String?) {
            runOnUiThread {
                tvStatus.text = "状态: ${getStatusText(status)}"
                updateButtonStates()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_autoglm_client)

        // 初始化 UI 组件
        initViews()

        // 设置按钮点击监听
        setupListeners()

        // 绑定 AutoGLM 服务
        bindAutoGLMService()
    }

    override fun onDestroy() {
        super.onDestroy()

        // 取消注册回调并解绑服务
        if (isBound) {
            try {
                taskService?.unregisterOutputCallback(outputCallback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            unbindService(serviceConnection)
        }
    }

    private fun initViews() {
        taskInput = findViewById(R.id.taskInput)
        btnStart = findViewById(R.id.btnStart)
        btnPause = findViewById(R.id.btnPause)
        btnResume = findViewById(R.id.btnResume)
        btnCancel = findViewById(R.id.btnCancel)
        btnCheckStatus = findViewById(R.id.btnCheckStatus)
        tvStatus = findViewById(R.id.tvStatus)
        tvOutput = findViewById(R.id.tvOutput)
        scrollView = findViewById(R.id.scrollView)

        // 设置一些示例任务
        taskInput.hint = "例如：打开微信，给文件传输助手发送消息：测试"
    }

    private fun setupListeners() {
        btnStart.setOnClickListener { startTask() }
        btnPause.setOnClickListener { pauseTask() }
        btnResume.setOnClickListener { resumeTask() }
        btnCancel.setOnClickListener { cancelTask() }
        btnCheckStatus.setOnClickListener { checkStatus() }
    }

    /**
     * 绑定 AutoGLM 服务
     */
    private fun bindAutoGLMService() {
        try {
            val intent = Intent()
            intent.setClassName(
                "com.kevinluo.autoglm",
                "com.kevinluo.autoglm.ipc.TaskService"
            )
            val bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            
            if (bound) {
                appendOutput("🔄 正在连接到 AutoGLM 服务...\n\n")
            } else {
                appendOutput("❌ 无法绑定 AutoGLM 服务\n")
                appendOutput("请确认 AutoGLM 已安装\n\n")
                Toast.makeText(this, "无法绑定服务，请确认 AutoGLM 已安装", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            appendOutput("❌ 绑定服务出错: ${e.message}\n\n")
            Toast.makeText(this, "绑定服务失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 启动任务
     */
    private fun startTask() {
        val taskDescription = taskInput.text.toString().trim()

        if (taskDescription.isEmpty()) {
            Toast.makeText(this, "请输入任务描述", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isBound || taskService == null) {
            Toast.makeText(this, "服务未连接", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val started = taskService?.startTask(taskDescription) ?: false
            
            if (started) {
                Toast.makeText(this, "任务已启动", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "任务启动失败（可能已有任务运行）", Toast.LENGTH_SHORT).show()
                appendOutput("⚠️ 任务启动失败，可能原因：\n")
                appendOutput("  - 已有任务正在运行\n")
                appendOutput("  - AutoGLM 未初始化（Shizuku 未连接）\n\n")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            appendOutput("❌ 启动失败: ${e.message}\n\n")
        }
    }

    /**
     * 暂停任务
     */
    private fun pauseTask() {
        try {
            val paused = taskService?.pauseTask() ?: false
            
            if (paused) {
                Toast.makeText(this, "任务已暂停", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "暂停失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "暂停失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 恢复任务
     */
    private fun resumeTask() {
        try {
            val resumed = taskService?.resumeTask() ?: false
            
            if (resumed) {
                Toast.makeText(this, "任务已恢复", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "恢复失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "恢复失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 取消任务
     */
    private fun cancelTask() {
        try {
            taskService?.cancelTask()
            Toast.makeText(this, "任务已取消", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "取消失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 检查任务状态
     */
    private fun checkStatus() {
        updateStatusDisplay()
    }

    /**
     * 更新状态显示
     */
    private fun updateStatusDisplay() {
        try {
            val isRunning = taskService?.isTaskRunning() ?: false
            val statusText = if (isRunning) {
                "状态: 🔴 运行中"
            } else {
                "状态: 🟢 空闲"
            }
            tvStatus.text = statusText
        } catch (e: Exception) {
            e.printStackTrace()
            tvStatus.text = "状态: ⚠️ 未知"
        }
    }

    /**
     * 更新按钮状态
     */
    private fun updateButtonStates() {
        val isRunning = try {
            taskService?.isTaskRunning() ?: false
        } catch (e: Exception) {
            false
        }

        btnStart.isEnabled = !isRunning && isBound
        btnPause.isEnabled = isRunning
        btnResume.isEnabled = isRunning
        btnCancel.isEnabled = isRunning
        btnCheckStatus.isEnabled = isBound
    }

    /**
     * 添加输出文本
     */
    private fun appendOutput(text: String) {
        runOnUiThread {
            tvOutput.append(text)
            
            // 滚动到底部
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    /**
     * 获取状态文本
     */
    private fun getStatusText(status: String?): String {
        return when (status) {
            "IDLE" -> "🟢 空闲"
            "RUNNING" -> "🔴 运行中"
            "PAUSED" -> "🟡 已暂停"
            "COMPLETED" -> "✅ 已完成"
            "FAILED" -> "❌ 已失败"
            else -> "⚪ 未知"
        }
    }

    companion object {
        private const val TAG = "AutoGLMClient"
    }
}
