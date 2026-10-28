package com.abujhan.container.clone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * حالة عملية الاستنساخ والتثبيت الحالية، تعرضها نافذة التقدم داخل التطبيق.
 * null يعني لا توجد عملية جارية.
 */
object CloneProgress {

    enum class Phase { CLONING, INSTALLING, WAITING_CONFIRM, DONE, FAILED }

    /** percent = -1 يعني تقدّم غير محدد. */
    data class State(
        val label: String,
        val phase: Phase,
        val percent: Int = -1,
        val message: String? = null,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    fun set(state: State?) {
        _state.value = state
    }
}
