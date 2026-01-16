package com.kevinluo.autoglm.ipc

import android.app.Service
import android.content.Intent
import android.os.IBinder
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
            
            // 在协程中执行任务
            currentTaskJob = serviceScope.launch {
                try {
                    val result = agent.run(taskDescription)
                    Logger.i(TAG, "IPC: Task completed with result: ${result.success}")
                } catch (e: Exception) {
                    Logger.e(TAG, "IPC: Task execution error", e)
                    broadcastToCallbacks { 
                        it.onTaskFailed(e.message ?: "Unknown error", agent.getCurrentStepNumber()) 
                    }
                    broadcastToCallbacks { it.onStatusChanged("FAILED") }
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
