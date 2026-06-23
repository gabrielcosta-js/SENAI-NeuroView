package com.neuroview.app.model

data class User(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val plan: String = "free",    // "free" or "premium"
    val createdAt: Long = System.currentTimeMillis()
)
