package com.kevinluo.autoglm.ipc

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.RemoteCallbackList
import android.os.RemoteException
import com.kevinluo.autoglm.ComponentManager
import com.kevinluo.autoglm.ITaskOutputCallback
import com.kevinluo.autoglm.ITaskService
import com.kevinluo.autoglm.action.AgentAction
import com.kevinluo.autoglm.agent.PhoneAgentListener
import com.kevinluo.autoglm.ui.FloatingWindowService
import com.kevinluo.autoglm.ui.TaskStatus
import com.kevinluo.autoglm.util.Logger
import com.kevinluo.autoglm.util.stripMarkdown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 跨进程任务服务
 *
 * 此服务允许外部应用通过 AIDL 接口：
 * 1. 启动、暂停、恢复、取消 AutoGLM 任务
 * 2. 注册回调接收任务执行过程的实时输出
 *
 * 使用方式（外部应用）：
 * ```kotlin
 * val intent = Intent()
 * intent.setClassName("com.kevinluo.autoglm", "com.kevinluo.autoglm.ipc.TaskService")
 * bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
 * ```
 */
class TaskService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentTaskJob: Job? = null
    private var currentTaskDescription: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // 使用 RemoteCallbackList 管理多个客户端的回调
    private val callbacks = RemoteCallbackList<ITaskOutputCallback>()

    private val binder = object : ITaskService.Stub() {
        override fun startTask(taskDescription: String?): Boolean {
            Logger.i(TAG, "IPC: startTask called with: $taskDescription")

            if (taskDescription.isNullOrBlank()) {
                Logger.w(TAG, "IPC: Invalid task description")
                return false
            }

            // 检查是否已有任务运行
            val componentManager = ComponentManager.getInstance(this@TaskService)
            val agent = componentManager.phoneAgent

            if (agent == null) {
                Logger.e(TAG, "IPC: PhoneAgent not initialized")
                broadcastToCallbacks { it.onTaskFailed("Agent 未初始化", 0) }
                return false
            }

            if (agent.isRunning()) {
                Logger.w(TAG, "IPC: Task already running")
                return false
            }

            // 重置 agent 状态
            if (agent.getState() != com.kevinluo.autoglm.agent.AgentState.IDLE) {
                agent.reset()
            }

            // 启动悬浮窗（如果有权限）
            if (FloatingWindowService.canDrawOverlays(this@TaskService)) {
                val serviceIntent = Intent(this@TaskService, FloatingWindowService::class.java)
                startService(serviceIntent)
            }

            // 通知任务开始
            currentTaskDescription = taskDescription
            broadcastToCallbacks { it.onTaskStarted(taskDescription) }
            broadcastToCallbacks { it.onStatusChanged("RUNNING") }

            // 设置 Agent 监听器
            agent.setListener(object : PhoneAgentListener {
                override fun onStepStarted(stepNumber: Int) {
                    broadcastToCallbacks { it.onStepStarted(stepNumber) }
                }

                override fun onThinkingUpdate(thinking: String) {
                    broadcastToCallbacks { it.onThinkingUpdate(thinking) }
                }

                override fun onActionExecuted(action: AgentAction) {
                    broadcastToCallbacks { it.onActionExecuted(action.formatForDisplay()) }
                    //TODO 完善onTaskCompleted
                    if (action is AgentAction.Finish) {
                        val stepCount = agent.getCurrentStepNumber()
                        broadcastToCallbacks { it.onTaskCompleted(true, action.message.stripMarkdown(), stepCount) }
                        broadcastToCallbacks { it.onStatusChanged("COMPLETED") }
                    }
                }

                override fun onTaskCompleted(message: String) {
                    val stepCount = agent.getCurrentStepNumber()
                    broadcastToCallbacks { it.onTaskCompleted(true, message, stepCount) }
                    broadcastToCallbacks { it.onStatusChanged("COMPLETED") }
                }

                override fun onTaskFailed(error: String) {
                    val stepCount = agent.getCurrentStepNumber()
                    broadcastToCallbacks { it.onTaskFailed(error, stepCount) }
                    broadcastToCallbacks { it.onStatusChanged("FAILED") }
                }

                override fun onScreenshotStarted() {}
                override fun onScreenshotCompleted() {}
                override fun onFloatingWindowRefreshNeeded() {}

                override fun onTaskPaused(stepNumber: Int) {
                    broadcastToCallbacks { it.onStatusChanged("PAUSED") }
                }

                override fun onTaskResumed(stepNumber: Int) {
                    broadcastToCallbacks { it.onStatusChanged("RUNNING") }
                }
            })

            // 在协程中执行：先解锁屏幕，返回桌面，再开始任务
            currentTaskJob = serviceScope.launch {
                try {
                    // 1. 唤醒并解锁屏幕，持续保持屏幕常亮
                    wakeUpAndKeepScreenOn()
                    
                    // 2. 等待解锁动画完成（1秒）
                    delay(1000)
                    
                    // 3. 返回桌面
                    returnToHome(componentManager)
                    
                    // 4. 等待桌面加载完成（500ms）
                    delay(500)
                    
                    // 5. 隐藏悬浮窗（AIDL 任务全程不显示悬浮窗）
                    hideFloatingWindow()
                    
                    // 6. 开始执行任务
                    Logger.i(TAG, "IPC: Starting task execution")
                    val result = agent.run(taskDescription)
                    Logger.i(TAG, "IPC: Task completed with result: ${result.success}")
                    
                } catch (e: Exception) {
                    Logger.e(TAG, "IPC: Task execution error", e)
                    broadcastToCallbacks {
                        it.onTaskFailed(e.message ?: "Unknown error", agent.getCurrentStepNumber())
                    }
                    broadcastToCallbacks { it.onStatusChanged("FAILED") }
                    
                } finally {
                    // 释放屏幕常亮锁
                    releaseWakeLock()
                }
            }

            return true
        }

        override fun cancelTask() {
            Logger.i(TAG, "IPC: cancelTask called")
            val agent = ComponentManager.getInstance(this@TaskService).phoneAgent
            agent?.cancel()
            currentTaskJob?.cancel()
            broadcastToCallbacks { it.onStatusChanged("IDLE") }
            
            // 取消任务时释放屏幕常亮锁（不显示悬浮窗）
            releaseWakeLock()
        }

        override fun pauseTask(): Boolean {
            Logger.i(TAG, "IPC: pauseTask called")
            val agent = ComponentManager.getInstance(this@TaskService).phoneAgent
            val result = agent?.pause() ?: false
            if (result) {
                broadcastToCallbacks { it.onStatusChanged("PAUSED") }
            }
            return result
        }

        override fun resumeTask(): Boolean {
            Logger.i(TAG, "IPC: resumeTask called")
            val agent = ComponentManager.getInstance(this@TaskService).phoneAgent
            val result = agent?.resume() ?: false
            if (result) {
                broadcastToCallbacks { it.onStatusChanged("RUNNING") }
            }
            return result
        }

        override fun isTaskRunning(): Boolean {
            val agent = ComponentManager.getInstance(this@TaskService).phoneAgent
            return agent?.isRunning() ?: false
        }

        override fun registerOutputCallback(callback: ITaskOutputCallback?) {
            if (callback != null) {
                callbacks.register(callback)
                Logger.i(TAG, "IPC: Callback registered, total: ${callbacks.registeredCallbackCount}")
            }
        }

        override fun unregisterOutputCallback(callback: ITaskOutputCallback?) {
            if (callback != null) {
                callbacks.unregister(callback)
                Logger.i(TAG, "IPC: Callback unregistered, total: ${callbacks.registeredCallbackCount}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Logger.i(TAG, "TaskService bound")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Logger.i(TAG, "TaskService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.i(TAG, "TaskService destroyed")
        currentTaskJob?.cancel()
        serviceScope.cancel()
        callbacks.kill()
        
        // 释放屏幕常亮锁
        releaseWakeLock()
    }

    /**
     * 唤醒并解锁屏幕，持续保持屏幕常亮
     * 适用于无密码锁屏，唤醒后自动上划解锁
     */
    private fun wakeUpAndKeepScreenOn() {
        try {
            // 1. 唤醒屏幕并持续保持常亮
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            
            // 释放之前的 WakeLock（如果有）
            releaseWakeLock()
            
            // 创建新的 WakeLock，持续保持屏幕常亮（不设置超时时间）
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or 
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "AutoGLM::TaskWakeLock"
            ).apply {
                acquire() // 不设置超时，由任务结束时手动释放
            }
            
            Logger.i(TAG, "Screen woken up and kept on")
            
            // 2. 启动透明 Activity 来解锁键盘锁
            // UnlockActivity 会处理解锁逻辑然后立即关闭
            val unlockIntent = Intent(this, UnlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(unlockIntent)
            
            Logger.i(TAG, "Unlock activity started")
            
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to wake up and unlock screen", e)
        }
    }
    
    /**
     * 隐藏悬浮窗
     * AIDL 任务全程不显示悬浮窗
     */
    private fun hideFloatingWindow() {
        try {
            FloatingWindowService.getInstance()?.hide()
            Logger.i(TAG, "Floating window hidden for IPC task")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to hide floating window", e)
        }
    }
    
    /**
     * 释放屏幕常亮锁
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Logger.i(TAG, "WakeLock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to release WakeLock", e)
        }
    }
    
    /**
     * 返回桌面
     */
    private suspend fun returnToHome(componentManager: ComponentManager) {
        try {
            val deviceExecutor = componentManager.deviceExecutor
            if (deviceExecutor == null) {
                Logger.w(TAG, "DeviceExecutor not available, cannot return to home")
                return
            }
            
            // 执行 Home 键操作（KEYCODE_HOME = 3）
            deviceExecutor.pressKey(3)
            Logger.i(TAG, "Returned to home screen")
            
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to return to home", e)
        }
    }

    /**
     * 向所有注册的回调广播消息
     */
    private fun broadcastToCallbacks(block: (ITaskOutputCallback) -> Unit) {
        val count = callbacks.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    val callback = callbacks.getBroadcastItem(i)
                    block(callback)
                } catch (e: RemoteException) {
                    Logger.e(TAG, "Error broadcasting to callback $i", e)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    companion object {
        private const val TAG = "TaskService"
    }
}
