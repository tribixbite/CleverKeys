package juloo.keyboard2

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat

/**
 * Resource access helper for CleverKeys
 * Kotlin implementation with type-safe resource access
 */
object Resources {
    
    /**
     * String resources with fallback
     */
    fun getString(context: Context, resId: Int, fallback: String = ""): String {
        return try {
            context.getString(resId)
        } catch (e: Exception) {
            fallback
        }
    }
    
    /**
     * Dimension resources with fallback
     */
    fun getDimension(context: Context, resId: Int, fallback: Float = 0f): Float {
        return try {
            context.resources.getDimension(resId)
        } catch (e: Exception) {
            fallback
        }
    }
    
    /**
     * Color resources with fallback
     */
    fun getColor(context: Context, resId: Int, fallback: Int = 0): Int {
        return try {
            ContextCompat.getColor(context, resId)
        } catch (e: Exception) {
            fallback
        }
    }
    
    /**
     * Drawable resources with fallback
     */
    fun getDrawable(context: Context, resId: Int): Drawable? {
        return try {
            ContextCompat.getDrawable(context, resId)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Safe resource access with type checking
     */
    inline fun <reified T> safeGetResource(context: Context, resId: Int, fallback: T): T {
        return try {
            when (T::class) {
                String::class -> context.getString(resId) as T
                Float::class -> context.resources.getDimension(resId) as T
                Int::class -> ContextCompat.getColor(context, resId) as T
                else -> fallback
            }
        } catch (e: Exception) {
            fallback
        }
    }
}