package com.kevinluo.autoglm;

import com.kevinluo.autoglm.ITaskOutputCallback;

/**
 * AIDL 接口：用于跨进程接收任务和注册输出回调
 * 
 * 外部应用可以通过 bindService 绑定到此服务，实现：
 * 1. 启动 AutoGLM 任务
 * 2. 注册回调接收任务执行输出
 */
interface ITaskService {
    /**
     * 启动一个新任务
     * 
     * @param taskDescription 任务描述（自然语言）
     * @return 任务是否成功启动（true: 已启动，false: 启动失败，如已有任务运行）
     */
    boolean startTask(String taskDescription);
    
    /**
     * 取消当前正在运行的任务
     */
    void cancelTask();
    
    /**
     * 暂停当前正在运行的任务
     * 
     * @return 是否成功暂停
     */
    boolean pauseTask();
    
    /**
     * 恢复已暂停的任务
     * 
     * @return 是否成功恢复
     */
    boolean resumeTask();
    
    /**
     * 检查是否有任务正在运行
     * 
     * @return true: 有任务运行中，false: 空闲
     */
    boolean isTaskRunning();
    
    /**
     * 注册任务输出回调
     * 外部应用通过此方法注册回调，接收实时的任务执行输出
     * 
     * @param callback 回调接口实现
     */
    void registerOutputCallback(ITaskOutputCallback callback);
    
    /**
     * 取消注册输出回调
     * 
     * @param callback 要取消的回调
     */
    void unregisterOutputCallback(ITaskOutputCallback callback);
}
