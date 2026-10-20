package com.gusak.lab.radarnebo

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform