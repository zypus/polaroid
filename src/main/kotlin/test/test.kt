package test

import androidx.compose.animation.core.*
import androidx.compose.animation.transition
import androidx.compose.desktop.Window
import androidx.compose.foundation.layout.Column
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*

val index = IntPropKey()

fun main() {

    Window {

        var initState by remember { mutableStateOf("A") }
        var toState by remember { mutableStateOf("B") }

        val state = transition(
            transitionDefinition<String> {

                state("A") {
                    this[index] = 0
                }
                state("B") {
                    this[index] = 1
                }

                transition("A" to "B") {
                    index using snap(1000)
                }
                transition("B" to "A") {
                    index using snap(1000)
                }
            },
            initState = initState,
            toState = toState
        )

        resolveState(state, {initState = it}, {toState = it})

        Column {
            Test2(state)
        }

    }
}

@Composable
fun resolveText(state: TransitionState): String {
    val text = remember(state[index]) { state[index].toString() }
    return text
}

@Composable
fun resolveState(state: TransitionState, setInitState: (String) -> Unit, setToState: (String) -> Unit) {
    val trans = remember(state[index]) { if (state[index] == 1) "B" to "A" else "A" to "B" }
    setInitState(trans.first)
    setToState(trans.second)
}

@Composable
fun Test(state: TransitionState) {
    val text = remember(state[index]) { state[index].toString() }
    Text(text, fontSize = 100.sp)
}

@Composable
fun Test2(state: TransitionState) {
    val text = resolveText(state)
    Text(text, fontSize = 100.sp)
}