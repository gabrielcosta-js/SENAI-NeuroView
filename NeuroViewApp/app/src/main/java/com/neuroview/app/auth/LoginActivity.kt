package com.neuroview.app.auth

import android.content.Intent
import android.os.Bundle
import android.text.Html
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.neuroview.app.databinding.ActivityLoginBinding
import com.neuroview.app.home.HomeActivity
import com.neuroview.app.utils.AuthRepository
import com.neuroview.app.utils.hide
import com.neuroview.app.utils.hideKeyboard
import com.neuroview.app.utils.isValidEmail
import com.neuroview.app.utils.show
import com.neuroview.app.utils.toast
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val repo = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvRegister.text = Html.fromHtml(
            "Não tem conta? <font color='#4ADE80'><b>Cadastre-se</b></font>",
            Html.FROM_HTML_MODE_LEGACY
        )

        binding.btnLogin.setOnClickListener {
            it.hideKeyboard()
            attemptLogin()
        }

        binding.tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        binding.tvForgot.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            if (email.isEmpty() || !email.isValidEmail()) {
                binding.tvError.text = "Insira um e-mail válido para recuperar a senha"
                binding.tvError.show()
            } else {
                sendPasswordReset(email)
            }
        }
    }

    private fun attemptLogin() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()

        binding.tvError.hide()

        if (email.isEmpty() || password.isEmpty()) {
            binding.tvError.text = getString(com.neuroview.app.R.string.error_empty_fields)
            binding.tvError.show(); return
        }
        if (!email.isValidEmail()) {
            binding.tvError.text = getString(com.neuroview.app.R.string.error_invalid_email)
            binding.tvError.show(); return
        }

        setLoading(true)
        lifecycleScope.launch {
            val result = repo.login(email, password)
            setLoading(false)
            result.fold(
                onSuccess = { goHome() },
                onFailure = {
                    binding.tvError.text = getString(com.neuroview.app.R.string.error_login)
                    binding.tvError.show()
                }
            )
        }
    }

    private fun sendPasswordReset(email: String) {
        lifecycleScope.launch {
            val result = repo.resetPassword(email)
            result.fold(
                onSuccess = { toast("E-mail de recuperação enviado!") },
                onFailure = { toast("Erro ao enviar e-mail de recuperação") }
            )
        }
    }

    private fun goHome() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun setLoading(loading: Boolean) {
        binding.btnLogin.isEnabled = !loading
        binding.btnLogin.alpha = if (loading) 0.5f else 1f
        if (loading) binding.progress.show() else binding.progress.hide()
    }
}
