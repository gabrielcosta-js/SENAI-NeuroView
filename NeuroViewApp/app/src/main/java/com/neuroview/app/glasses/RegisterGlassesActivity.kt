package com.neuroview.app.glasses

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.neuroview.app.R
import com.neuroview.app.databinding.ActivityRegisterGlassesBinding
import com.neuroview.app.utils.GlassesRepository
import com.neuroview.app.utils.hide
import com.neuroview.app.utils.hideKeyboard
import com.neuroview.app.utils.show
import com.neuroview.app.utils.toast
import kotlinx.coroutines.launch

class RegisterGlassesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterGlassesBinding
    private val repo = GlassesRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterGlassesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnRegister.setOnClickListener {
            it.hideKeyboard()
            attemptRegister()
        }
    }

    private fun attemptRegister() {
        val name = binding.etName.text.toString().trim()
        val ip = binding.etIp.text.toString().trim()

        binding.tvError.hide()

        when {
            name.isEmpty() || ip.isEmpty() -> {
                showError(getString(R.string.error_empty_fields))
            }
            !isValidIpOrHost(ip) -> {
                showError("IP/host inválido. Ex: 192.168.1.50")
            }
            else -> doRegister(name, ip)
        }
    }

    private fun doRegister(name: String, ip: String) {
        setLoading(true)
        lifecycleScope.launch {
            repo.registerGlasses(name, ip).fold(
                onSuccess = {
                    setLoading(false)
                    toast("Dispositivo cadastrado com sucesso!")
                    finish()
                },
                onFailure = {
                    setLoading(false)
                    showError(getString(R.string.error_glasses_register))
                }
            )
        }
    }

    private fun isValidIpOrHost(value: String): Boolean {
        val clean = value.removePrefix("http://").removePrefix("https://").substringBefore("/").substringBefore(":")
        val ipRegex = Regex("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$")
        val hostRegex = Regex("^[a-zA-Z0-9][a-zA-Z0-9.-]{1,251}[a-zA-Z0-9]$")
        return ipRegex.matches(clean) || hostRegex.matches(clean)
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.show()
    }

    private fun setLoading(loading: Boolean) {
        binding.btnRegister.isEnabled = !loading
        binding.btnRegister.alpha = if (loading) 0.5f else 1f
        if (loading) binding.progress.show() else binding.progress.hide()
    }
}
