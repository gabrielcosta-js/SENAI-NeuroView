package com.neuroview.app.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.neuroview.app.R
import com.neuroview.app.auth.LoginActivity
import com.neuroview.app.databinding.ActivityHomeBinding
import com.neuroview.app.databinding.ItemGlassesBinding
import com.neuroview.app.glasses.RegisterGlassesActivity
import com.neuroview.app.model.Glasses
import com.neuroview.app.stream.StreamActivity
import com.neuroview.app.utils.AuthRepository
import com.neuroview.app.utils.GlassesRepository
import com.neuroview.app.utils.hide
import com.neuroview.app.utils.show
import com.neuroview.app.utils.toast
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val authRepo = AuthRepository()
    private val glassesRepo = GlassesRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogout.setOnClickListener { confirmLogout() }
        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, RegisterGlassesActivity::class.java))
        }

        loadUserAndGlasses()
    }

    override fun onResume() {
        super.onResume()
        loadUserAndGlasses()
    }

    private fun loadUserAndGlasses() {
        binding.progress.show()
        binding.glassesContainer.hide()
        binding.emptyState.hide()
        binding.planCard.hide()

        lifecycleScope.launch {
            // Load user name
            authRepo.getUserProfile().onSuccess { user ->
                val firstName = user.name.split(" ").firstOrNull() ?: "usuário"
                binding.tvGreeting.text = "Olá, $firstName"
            }

            // Load glasses
            glassesRepo.getMyGlasses().fold(
                onSuccess = { list ->
                    binding.progress.hide()
                    if (list.isEmpty()) {
                        binding.emptyState.show()
                    } else {
                        binding.glassesContainer.show()
                        binding.planCard.show()
                        populateGlassesList(list)
                    }
                },
                onFailure = {
                    binding.progress.hide()
                    binding.emptyState.show()
                    toast("Erro ao carregar dispositivos")
                }
            )
        }
    }

    private fun populateGlassesList(list: List<Glasses>) {
        binding.glassesContainer.removeAllViews()
        list.forEach { glasses ->
            val itemBinding = ItemGlassesBinding.inflate(
                LayoutInflater.from(this), binding.glassesContainer, false
            )
            itemBinding.tvDeviceName.text = glasses.name
            itemBinding.tvDeviceId.text = glasses.streamUrl.replace("http://", "").replace("/stream", "")
            itemBinding.tvStatus.text = "IP salvo"
            itemBinding.tvStatus.setTextColor(getColor(R.color.nv_green_dark))
            itemBinding.statusDot.setBackgroundResource(R.drawable.bg_dot_green)
            itemBinding.statusRow.setBackgroundResource(R.drawable.bg_status_pill_wifi)
            itemBinding.btnConnect.text = "Abrir transmissão"

            itemBinding.btnConnect.setOnClickListener {
                val intent = Intent(this, StreamActivity::class.java).apply {
                    putExtra(StreamActivity.EXTRA_GLASSES, glasses)
                }
                startActivity(intent)
            }

            itemBinding.btnDelete.setOnClickListener {
                confirmDelete(glasses)
            }

            binding.glassesContainer.addView(itemBinding.root)
        }
    }

    private fun confirmDelete(glasses: Glasses) {
        AlertDialog.Builder(this)
            .setTitle("Remover dispositivo")
            .setMessage("Deseja remover \"${glasses.name}\" da sua conta?")
            .setPositiveButton("Remover") { _, _ ->
                lifecycleScope.launch {
                    glassesRepo.deleteGlasses(glasses.id).fold(
                        onSuccess = { loadUserAndGlasses() },
                        onFailure = { toast("Erro ao remover dispositivo") }
                    )
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Sair")
            .setMessage("Deseja encerrar sua sessão?")
            .setPositiveButton("Sair") { _, _ ->
                authRepo.logout()
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
