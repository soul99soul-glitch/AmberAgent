package app.amber.review

import java.lang.reflect.Method
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import sun.misc.Unsafe

/** Isolates private production entry points without constructing unrelated runtimes. */
internal object ReflectionFixture {
    private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let {
        it.isAccessible = true
        it.get(null) as Unsafe
    }

    fun allocate(type: Class<*>): Any = unsafe.allocateInstance(type)

    inline fun <reified T> allocate(): T = allocate(T::class.java) as T

    fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    suspend fun invokeSuspend(target: Any?, method: Method, vararg arguments: Any?): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            method.isAccessible = true
            method.invoke(target, *(arguments.toList() + continuation).toTypedArray())
        }
}
