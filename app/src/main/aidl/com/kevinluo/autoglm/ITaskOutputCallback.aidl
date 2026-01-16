package com.kevinluo.autoglm;

/**
 * AIDL 回调接口：用于向外部应用传递任务执行输出
 * 
 * 外部应用实现此接口，注册到 ITaskService 后，
 * 可以实时接收任务执行过程中的各种输出信息
 */
interface ITaskOutputCallback {
    /**
     * 任务开始时调用
     * 
     * @param taskDescription 任务描述
     */
    void onTaskStarted(String taskDescription);
    
    /**
     * 步骤开始时调用
     * 
     * @param stepNumber 步骤编号
     */
    void onStepStarted(int stepNumber);
    
    /**
     * 思考内容更新时调用
     * 
     * @param thinking 模型的思考内容
     */
    void onThinkingUpdate(String thinking);
    
    /**
     * 动作执行时调用
     * 
     * @param actionDescription 动作描述（格式化后的可读文本）
     */
    void onActionExecuted(String actionDescription);
    
    /**
     * 任务完成时调用
     * 
     * @param success 是否成功完成
     * @param message 完成消息
     * @param stepCount 总步骤数
     */
    void onTaskCompleted(boolean success, String message, int stepCount);
    
    /**
     * 任务失败时调用
     * 
     * @param error 错误信息
     * @param stepCount 已执行的步骤数
     */
    void onTaskFailed(String error, int stepCount);
    
    /**
     * 任务状态变化时调用
     * 
     * @param status 状态: IDLE, RUNNING, PAUSED, COMPLETED, FAILED
     */
    void onStatusChanged(String status);
}
