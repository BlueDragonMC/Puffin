package com.bluedragonmc.puffin.app

object Env {

    val K8S_NAMESPACE = System.getenv("PUFFIN_K8S_NAMESPACE") ?: "default"

    val WORLDS_FOLDER = System.getenv("PUFFIN_WORLD_FOLDER") ?: "/puffin/worlds/"

    val MONGO_HOSTNAME = System.getenv("PUFFIN_MONGO_HOSTNAME") ?: "mongo"
    val MONGO_PORT = System.getenv("PUFFIN_MONGO_PORT")?.toInt() ?: 27017

    val LUCKPERMS_API_URL = System.getenv("PUFFIN_LUCKPERMS_URL") ?: "http://luckperms:8080"

    val DEV_MODE = System.getenv("PUFFIN_DEV_MODE")?.toBoolean() ?: false
    val DEFAULT_GS_IP = System.getenv("PUFFIN_DEFAULT_GAMESERVER_IP") ?: "minecraft"
    val DEFAULT_PROXY_IP = System.getenv("PUFFIN_DEFAULT_PROXY_IP") ?: "velocity"
}