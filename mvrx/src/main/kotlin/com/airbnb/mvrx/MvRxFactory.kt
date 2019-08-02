package com.airbnb.mvrx

import android.os.Bundle
import android.os.Parcelable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.annotation.RestrictTo
import java.io.Serializable
import java.lang.IllegalStateException
import kotlin.reflect.full.primaryConstructor

@RestrictTo(RestrictTo.Scope.LIBRARY)

class MvRxFactory<VM : BaseMvRxViewModel<S>, S : MvRxState>(
    private val viewModelClass: Class<out VM>,
    private val stateClass: Class<out S>,
    private val viewModelContext: ViewModelContext,
    private val key: String,
    private val forExistingViewModel: Boolean,
    private val initialStateFactory: MvRxStateFactory<VM, S>
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(modelClass: Class<T>): T {
        val (viewModelContext, viewModel) = viewModelContext.savedStateRegistry.consumeRestoredStateForKey(key)?.let { savedStateBundle ->
            val restoredArgs = savedStateBundle.get(KEY_MVRX_SAVED_ARGS)
            val restoredState = savedStateBundle.getBundle(KEY_MVRX_SAVED_INSTANCE_STATE)

            requireNotNull(restoredState) { "State was not saved prior to restoring!" }

            val restoredContext = when (viewModelContext) {
                is ActivityViewModelContext -> ActivityViewModelContext(viewModelContext.activity, restoredArgs)
                is FragmentViewModelContext -> FragmentViewModelContext(viewModelContext.activity, restoredArgs, viewModelContext.fragment)
            }
            restoredContext to createViewModel(viewModelClass, stateClass, restoredContext, key, restoredState::restorePersistedState)
        } ?: viewModelContext to createViewModel(viewModelClass, stateClass, viewModelContext, key, forExistingViewModel = forExistingViewModel, initialStateFactory = initialStateFactory)

        viewModelContext.savedStateRegistry.registerSavedStateProvider(key) {
            withState(viewModel) { state ->
                Bundle().apply {
                    putBundle(KEY_MVRX_SAVED_INSTANCE_STATE, state.persistState())
                    when (val args = viewModelContext.args) {
                        is Parcelable -> putParcelable(KEY_MVRX_SAVED_ARGS, args)
                        is Serializable -> putSerializable(KEY_MVRX_SAVED_ARGS, args)
                    }
                }
            }
        }
        return viewModel as T
    }
}

@Suppress("UNCHECKED_CAST")
private fun <VM : BaseMvRxViewModel<S>, S : MvRxState> createViewModel(
    viewModelClass: Class<out VM>,
    stateClass: Class<out S>,
    viewModelContext: ViewModelContext,
    key: String,
    stateRestorer: (S) -> S = { it },
    forExistingViewModel: Boolean = false,
    initialStateFactory: MvRxStateFactory<VM, S> = RealMvRxStateFactory()
): VM {
    if (forExistingViewModel) {
        throw ViewModelDoesNotExistException(viewModelClass, viewModelContext, key)
    }

    val initialState = initialStateFactory.createInitialState(viewModelClass, stateClass, viewModelContext, stateRestorer)
    val factoryViewModel = viewModelClass.factoryCompanion()?.let { factoryClass ->
        try {
            factoryClass.getMethod("create", ViewModelContext::class.java, MvRxState::class.java)
                .invoke(factoryClass.instance(), viewModelContext, initialState) as VM?
        } catch (exception: NoSuchMethodException) {
            // Check for JvmStatic method.
            viewModelClass.getMethod("create", ViewModelContext::class.java, MvRxState::class.java)
                .invoke(null, viewModelContext, initialState) as VM?
        }
    }
    val viewModel = factoryViewModel ?: createDefaultViewModel(viewModelClass, initialState)
    return requireNotNull(viewModel) {
        // If null, use Kotlin reflect for best error message. We will crash anyway, so performance
        // doesn't matter.
        if (viewModelClass.kotlin.primaryConstructor?.parameters?.size?.let { it > 1 } == true) {
            "${viewModelClass.simpleName} takes dependencies other than initialState. " +
                "It must have companion object implementing ${MvRxViewModelFactory::class.java.simpleName} " +
                "with a create method returning a non-null ViewModel."
        } else {
            "${viewModelClass::class.java.simpleName} must have primary constructor with a " +
                "single non-optional parameter that takes initial state of ${stateClass.simpleName}."
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <VM : BaseMvRxViewModel<S>, S : MvRxState> createDefaultViewModel(viewModelClass: Class<VM>, state: S): VM? {
    // If we are checking for a default ViewModel, we expect only a single default constructor. Any other case
    // is a misconfiguration and we will throw an appropriate error under further inspection.
    if (viewModelClass.constructors.size == 1) {
        val primaryConstructor = viewModelClass.constructors[0]
        if (primaryConstructor.parameterTypes.size == 1 && primaryConstructor.parameterTypes[0].isAssignableFrom(state::class.java)) {
            return primaryConstructor?.newInstance(state) as? VM
        }
    }
    return null
}

private const val KEY_MVRX_SAVED_INSTANCE_STATE = "mvrx:saved_instance_state"
private const val KEY_MVRX_SAVED_ARGS = "mvrx:saved_args"

internal class ViewModelDoesNotExistException(
    viewModelClass: Class<*>,
    viewModelContext: ViewModelContext,
    key: String
) : IllegalStateException("ViewModel of type ${viewModelClass.name} for ${viewModelContext.owner}[$key] does not exist yet!")