package com.bluedragonmc.puffin.services

import kotlin.reflect.KClass

interface ServiceHolder {
    fun <T: Service> get(type: KClass<out T>): T
    fun <T: Service> register(service: T): T
    fun unregister(type: KClass<out Service>): Boolean
}
