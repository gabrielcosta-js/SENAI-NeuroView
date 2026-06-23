package com.neuroview.app.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Glasses(
    val id: String = "",
    val deviceId: String = "",       // legado: agora gerado automaticamente pelo Firestore
    val name: String = "",
    val ownerUid: String = "",
    val streamUrl: String = "",      // e.g. http://192.168.1.50:81/stream
    val registeredAt: Long = System.currentTimeMillis(),
    val isOnline: Boolean = false
) : Parcelable
