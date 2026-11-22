// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.core.exception

import androidx.annotation.StringRes

/**
 * The root exception to the MT 论坛 client
 * Every Custom Exception should extend this Exception
 */
abstract class AppException : RuntimeException {
    constructor() : super()
    constructor(message: String?) : super(message)
    constructor(message: String?, cause: Throwable?) : super(message, cause)
    constructor(cause: Throwable?) : super(cause)

    /**
     * Return the user-friendly error string resource id of the exception
     * @return string resource id
     */
    @StringRes
    abstract fun getStringResId(): Int
}
