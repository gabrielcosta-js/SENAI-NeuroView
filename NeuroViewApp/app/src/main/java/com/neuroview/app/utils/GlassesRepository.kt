package com.neuroview.app.utils

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.neuroview.app.model.Glasses
import kotlinx.coroutines.tasks.await

class GlassesRepository {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private fun currentUid() = auth.currentUser?.uid ?: throw Exception("Not authenticated")

    suspend fun registerGlasses(name: String, streamIp: String): Result<Glasses> {
        return try {
            val uid = currentUid()
            val streamUrl = buildStreamUrl(streamIp)
            val docRef = db.collection("glasses").document()
            val glasses = Glasses(
                id = docRef.id,
                deviceId = docRef.id,
                name = name,
                ownerUid = uid,
                streamUrl = streamUrl
            )
            docRef.set(glasses).await()
            Result.success(glasses)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMyGlasses(): Result<List<Glasses>> {
        return try {
            val uid = currentUid()
            val snapshot = db.collection("glasses")
                .whereEqualTo("ownerUid", uid)
                .get().await()
            val list = snapshot.documents.mapNotNull { it.toObject(Glasses::class.java) }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteGlasses(glassesId: String): Result<Unit> {
        return try {
            db.collection("glasses").document(glassesId).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateStreamUrl(glassesId: String, streamIp: String): Result<Unit> {
        return try {
            val url = buildStreamUrl(streamIp)
            db.collection("glasses").document(glassesId)
                .update("streamUrl", url).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildStreamUrl(streamIp: String): String {
        val clean = streamIp.trim().removeSuffix("/")
        val hasScheme = clean.startsWith("http://") || clean.startsWith("https://")
        val withoutScheme = clean.removePrefix("http://").removePrefix("https://")
        val hasPath = "/" in withoutScheme
        val hostPort = withoutScheme.substringBefore("/")

        return when {
            hasScheme && hasPath -> clean
            hasScheme && ":" in hostPort -> "$clean/stream"
            hasScheme -> "$clean:81/stream"
            hasPath -> "http://$clean"
            ":" in clean -> "http://$clean/stream"
            else -> "http://$clean:81/stream"
        }
    }
}
