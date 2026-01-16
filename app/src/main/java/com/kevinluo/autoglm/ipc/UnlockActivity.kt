package com.kevinluo.autoglm.ipc

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.kevinluo.autoglm.util.Logger

/**
 * 透明 Activity，用于唤醒并解锁屏幕
 * 
 * 此 Activity 启动后会：
 * 1. 唤醒屏幕
 * 2. 触发解锁动作（适用于无密码锁屏）
 * 3. 立即关闭自己
 */
class UnlockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 设置窗口属性：在锁屏上显示 + 唤醒屏幕 + 解锁
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        
        // 解锁键盘锁
        unlockKeyguard()
        
        // 立即关闭
        finish()
    }
    
    private fun unlockKeyguard() {
        try {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8.0+ 使用 requestDismissKeyguard，传递 Activity 实例
                keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        Logger.i(TAG, "Keyguard dismissed successfully")
                    }
                    
                    override fun onDismissError() {
                        Logger.w(TAG, "Keyguard dismiss error (may have password)")
                    }
                    
                    override fun onDismissCancelled() {
                        Logger.w(TAG, "Keyguard dismiss cancelled")
                    }
                })
            } else {
                // Android 8.0 以下使用已废弃的 API
                @Suppress("DEPRECATION")
                keyguardManager.newKeyguardLock("AutoGLM").disableKeyguard()
                Logger.i(TAG, "Keyguard disabled (legacy method)")
            }
            
            Logger.i(TAG, "Screen unlocked")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to unlock keyguard", e)
        }
    }
    
    companion object {
        private const val TAG = "UnlockActivity"
    }
}
