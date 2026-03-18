package com.example.sandwitcher.demo

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.sandwitcher.demo.databinding.ActivityMainBinding
import io.sandwitcher.Sandwitcher
import io.sandwitcher.HookAction
import io.sandwitcher.HookCallback
import io.sandwitcher.HookHandle
import io.sandwitcher.MethodHookParam
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val hookHandles = mutableListOf<HookHandle>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnInstallHook.setOnClickListener { installHooks() }
        binding.btnRemoveHook.setOnClickListener { removeHooks() }
        binding.btnMakeRequest.setOnClickListener { makeNetworkRequest() }
        binding.btnClearLog.setOnClickListener { binding.logOutput.text = "" }

        appendLog("Sandwitcher ready. ART-level method hooking via Pine/LSPlant.\n\n")
    }

    private fun installHooks() {
        if (hookHandles.isNotEmpty()) {
            appendLog("[!] Hooks already installed\n")
            return
        }

        // hook URL.openConnection()
        try {
            val openConn = URL::class.java.getDeclaredMethod("openConnection")
            hookHandles += Sandwitcher.hook(openConn, object : HookCallback {
                override fun beforeMethod(param: MethodHookParam): HookAction {
                    val url = param.thisObject as URL
                    val msg = "[HOOK] URL.openConnection()\n" +
                            "  url: $url\n" +
                            "  protocol: ${url.protocol}, host: ${url.host}\n"
                    Log.w("SandwitcherDemo", msg)
                    runOnUiThread { appendLog(msg) }
                    return HookAction.Continue
                }

                override fun afterMethod(param: MethodHookParam) {
                    val conn = param.result
                    if (conn is HttpURLConnection) {
                        conn.setRequestProperty("X-Sandwitcher-Hooked", "true")
                        runOnUiThread { appendLog("  injected: X-Sandwitcher-Hooked: true\n") }
                    }
                }
            })
            appendLog("[+] Hooked: URL.openConnection()\n")
        } catch (e: Exception) {
            appendLog("[!] Failed to hook URL.openConnection: ${e.message}\n")
        }

        // hook HttpURLConnection.getResponseCode()
        try {
            val getResponseCode = HttpURLConnection::class.java.getDeclaredMethod("getResponseCode")
            hookHandles += Sandwitcher.hook(getResponseCode, object : HookCallback {
                override fun afterMethod(param: MethodHookParam) {
                    val code = param.result as? Int
                    val msg = "[HOOK] HttpURLConnection.getResponseCode() -> $code\n"
                    Log.w("SandwitcherDemo", msg)
                    runOnUiThread { appendLog(msg) }
                }
            })
            appendLog("[+] Hooked: HttpURLConnection.getResponseCode()\n")
        } catch (e: Exception) {
            appendLog("[!] Failed to hook getResponseCode: ${e.message}\n")
        }

        // hook System.currentTimeMillis() - static native method, only log first 3 calls
        try {
            val currentTime = System::class.java.getDeclaredMethod("currentTimeMillis")
            val callCount = AtomicInteger(0)
            hookHandles += Sandwitcher.hook(currentTime, object : HookCallback {
                override fun afterMethod(param: MethodHookParam) {
                    if (callCount.incrementAndGet() <= 3) {
                        val msg = "[HOOK] System.currentTimeMillis() -> ${param.result}\n"
                        Log.w("SandwitcherDemo", msg)
                        runOnUiThread { appendLog(msg) }
                    }
                }
            })
            appendLog("[+] Hooked: System.currentTimeMillis()\n")
        } catch (e: Exception) {
            appendLog("[!] Failed to hook currentTimeMillis: ${e.message}\n")
        }

        appendLog("\n")
        binding.hookStatus.text = "Hook: ACTIVE (${hookHandles.size} hooks)"
        binding.hookStatus.setTextColor(0xFF00AA00.toInt())
        binding.btnInstallHook.isEnabled = false
        binding.btnRemoveHook.isEnabled = true
    }

    private fun removeHooks() {
        hookHandles.forEach { it.unhook() }
        hookHandles.clear()

        binding.hookStatus.text = "Hook: INACTIVE"
        binding.hookStatus.setTextColor(0xFFFF0000.toInt())
        binding.btnInstallHook.isEnabled = true
        binding.btnRemoveHook.isEnabled = false
        appendLog("[*] All hooks removed\n\n")
    }

    private fun makeNetworkRequest() {
        appendLog("[>] Making request to httpbin.org/get ...\n")

        thread {
            try {
                val url = URL("https://httpbin.org/get")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000

                val code = conn.responseCode
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                conn.disconnect()

                val preview = if (body.length > 500) body.take(500) + "\n..." else body
                val msg = "[<] Response: $code\n$preview\n\n"
                Log.w("SandwitcherDemo", msg)
                runOnUiThread { appendLog(msg) }
            } catch (e: Exception) {
                val msg = "[!] Request failed: ${e.message}\n\n"
                Log.e("SandwitcherDemo", msg, e)
                runOnUiThread { appendLog(msg) }
            }
        }
    }

    private fun appendLog(text: String) {
        binding.logOutput.append(text)
        binding.scrollView.post {
            binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
