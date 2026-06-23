package com.neuroview.app.auth

import android.content.Intent
import android.os.Bundle
import android.text.Html
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.neuroview.app.R
import com.neuroview.app.databinding.ActivityRegisterBinding
import com.neuroview.app.home.HomeActivity
import com.neuroview.app.utils.AuthRepository
import com.neuroview.app.utils.hide
import com.neuroview.app.utils.hideKeyboard
import com.neuroview.app.utils.isValidEmail
import com.neuroview.app.utils.show
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private val repo = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvLogin.text = Html.fromHtml(
            "Já tem conta? <font color='#4ADE80'><b>Entrar</b></font>",
            Html.FROM_HTML_MODE_LEGACY
        )

        binding.btnBack.setOnClickListener { finish() }

        binding.btnRegister.setOnClickListener {
            it.hideKeyboard()
            attemptRegister()
        }

        binding.tvLogin.setOnClickListener { finish() }
    }

    private fun attemptRegister() {
        val name = binding.etName.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val confirm = binding.etConfirm.text.toString()

        binding.tvError.hide()

        when {
            name.isEmpty() || email.isEmpty() || password.isEmpty() || confirm.isEmpty() -> {
                showError(getString(R.string.error_empty_fields))
            }
            !email.isValidEmail() -> showError(getString(R.string.error_invalid_email))
            password.length < 6 -> showError(getString(R.string.error_weak_password))
            password != confirm -> showError(getString(R.string.error_passwords_mismatch))
            else -> doRegister(name, email, password)
        }
    }

    private fun doRegister(name: String, email: String, password: String) {
        setLoading(true)
        lifecycleScope.launch {
            val result = repo.register(name, email, password)
            setLoading(false)
            result.fold(
                onSuccess = { goHome() },
                onFailure = {
                    val msg = when {
                        it.message?.contains("email-already-in-use") == true ->
                            "Este e-mail já está em uso"
                        else -> getString(R.string.error_register)
                    }
                    showError(msg)
                }
            )
        }
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.show()
    }

    private fun goHome() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun setLoading(loading: Boolean) {
        binding.btnRegister.isEnabled = !loading
        binding.btnRegister.alpha = if (loading) 0.5f else 1f
        if (loading) binding.progress.show() else binding.progress.hide()
    }
}
