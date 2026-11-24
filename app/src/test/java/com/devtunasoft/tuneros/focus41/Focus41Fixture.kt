package com.devtunasoft.tuneros.focus41

import android.os.Bundle
import com.devtunasoft.tuneros.MainActivity

/** Synthetic-data JVM harness: allocates MainActivity without running its Android constructor. */
internal object Focus41Fixture {
    fun activity(): MainActivity {
        // sun.misc.Unsafe is a JDK runtime class, not on the Kotlin test compile classpath.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        @Suppress("UNCHECKED_CAST")
        return allocate.invoke(unsafe, MainActivity::class.java) as MainActivity
    }

    fun setField(activity: MainActivity, name: String, value: Any?) {
        val field = MainActivity::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(activity, value)
    }

    fun getField(activity: MainActivity, name: String): Any? {
        val field = MainActivity::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(activity)
    }

    fun invokeNoArg(activity: MainActivity, name: String): Any? {
        val method = MainActivity::class.java.getDeclaredMethod(name)
        method.isAccessible = true
        return method.invoke(activity)
    }

    fun saveState(activity: MainActivity): Bundle {
        val out = Bundle()
        val method = MainActivity::class.java.getDeclaredMethod("onSaveInstanceState", Bundle::class.java)
        method.isAccessible = true
        method.invoke(activity, out)
        return out
    }
}
