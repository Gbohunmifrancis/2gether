package app.heartpact.ui.food

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.HeartpactApplication
import app.heartpact.data.ApiResult
import app.heartpact.data.CreateFoodEntryRequest
import app.heartpact.data.FoodDay
import app.heartpact.data.FoodEntry
import app.heartpact.data.FoodRepository
import app.heartpact.data.FoodScanRequest
import app.heartpact.data.FoodScanResult
import app.heartpact.data.FoodShareLevel
import app.heartpact.data.MealType
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.data.UpdateFoodEntryRequest
import app.heartpact.data.UpsertTargetRequest
import app.heartpact.i18n.tr
import app.heartpact.media.Jpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

private fun <T> ApiResult<T>.note(): String? = when (this) {
    is ApiResult.Ok -> null
    ApiResult.Offline -> OFFLINE_MESSAGE
    is ApiResult.Failure -> message
}

data class FoodUi(val day: FoodDay? = null, val note: String? = null, val busy: Boolean = false)

class FoodViewModel(private val food: FoodRepository, events: Flow<String> = emptyFlow(), private val today: () -> LocalDate = { LocalDate.now() }) : ViewModel() {
    private val _ui = MutableStateFlow(FoodUi())
    val ui: StateFlow<FoodUi> = _ui.asStateFlow()

    init { viewModelScope.launch { events.collect { if (it == "foodDayUpdated") refresh() } } }

    fun refresh() = viewModelScope.launch {
        when (val day = food.day(today().toString())) {
            is ApiResult.Ok -> _ui.update { it.copy(day = day.value, note = null) }
            else -> _ui.update { it.copy(note = day.note()) }
        }
    }

    fun saveTargets(calories: Int, share: FoodShareLevel) {
        val current = _ui.value.day?.targets
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val saved = food.saveTargets(UpsertTargetRequest(calories, current?.proteinGrams, current?.carbsGrams, current?.fatGrams, share))
            _ui.update { it.copy(busy = false, note = saved.note()) }
            if (saved is ApiResult.Ok) refresh()
        }
    }
}

/** Photo in, estimate out, then one tap to log it. The photo never leaves the phone except for the estimate. */
sealed interface ScanPhase {
    data object Reading : ScanPhase
    data class Ready(val scan: FoodScanResult) : ScanPhase
    data class Failed(val code: String, val message: String) : ScanPhase
}

data class FoodScanUi(
    val photo: File? = null,
    val phase: ScanPhase = ScanPhase.Reading,
    val portion: Portion = Portion.AsShown,
    val meal: MealType = FoodText.mealFor(LocalTime.now()),
    val busy: Boolean = false,
    val note: String? = null,
)

class FoodScanViewModel(application: Application, private val source: Uri) : AndroidViewModel(application) {
    private val food = (application as HeartpactApplication).container.food
    private val _ui = MutableStateFlow(FoodScanUi())
    val ui: StateFlow<FoodScanUi> = _ui.asStateFlow()
    private var payload: String? = null

    init { scan() }

    fun scan() = viewModelScope.launch {
        _ui.update { it.copy(phase = ScanPhase.Reading, note = null) }
        val image = payload ?: withContext(Dispatchers.IO) { prepare() }.also { payload = it }
        if (image == null) {
            _ui.update { it.copy(phase = ScanPhase.Failed("photo", tr("That photo couldn't be read. Try another one."))) }
            return@launch
        }
        _ui.update {
            it.copy(phase = when (val result = food.scan(FoodScanRequest(image))) {
                is ApiResult.Ok -> ScanPhase.Ready(result.value)
                ApiResult.Offline -> ScanPhase.Failed("offline", OFFLINE_MESSAGE)
                is ApiResult.Failure -> ScanPhase.Failed(result.code, result.message)
            })
        }
    }

    /** A copy in the app's cache to show, and a JPEG of at most 1600 px and about 1.5 MB to send. */
    private fun prepare(): String? = runCatching {
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "food").apply { mkdirs() }
        val copy = File(dir, "scan-${System.currentTimeMillis()}.jpg")
        app.contentResolver.openInputStream(source)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
        _ui.update { it.copy(photo = copy) }
        Base64.encodeToString(Jpeg.shrink(copy, 1600, 1_500_000, startQuality = 85), Base64.NO_WRAP)
    }.getOrNull()

    fun portion(portion: Portion) = _ui.update { it.copy(portion = portion) }
    fun meal(meal: MealType) = _ui.update { it.copy(meal = meal) }

    fun log(onDone: () -> Unit) {
        val scan = (_ui.value.phase as? ScanPhase.Ready)?.scan ?: return
        val portion = _ui.value.portion
        val adjusted = FoodText.adjust(scan, portion)
        _ui.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            val result = food.create(CreateFoodEntryRequest(
                localDate = LocalDate.now().toString(), mealType = _ui.value.meal, description = scan.description.take(120),
                calories = adjusted.calories, proteinGrams = adjusted.proteinGrams, carbsGrams = adjusted.carbsGrams, fatGrams = adjusted.fatGrams,
                scanId = scan.scanId, portionFactor = portion.factor.takeIf { portion != Portion.AsShown },
            ))
            _ui.update { it.copy(busy = false, note = result.note()) }
            if (result is ApiResult.Ok) { _ui.value.photo?.delete(); onDone() }
        }
    }

    override fun onCleared() { _ui.value.photo?.delete() }
}

data class FoodEntryUi(
    val existing: FoodEntry? = null,
    val description: String = "",
    val meal: MealType = FoodText.mealFor(LocalTime.now()),
    val calories: String = "",
    val showErrors: Boolean = false,
    val busy: Boolean = false,
    val note: String? = null,
) {
    val caloriesValue: Int? get() = calories.trim().toIntOrNull()?.takeIf { it in 0..5000 }
    val descriptionError: String? get() = if (description.isBlank()) tr("Say what you ate") else if (description.trim().length > 120) tr("Keep it under 120 characters") else null
    val caloriesError: String? get() = if (caloriesValue == null) tr("A number from 0 to 5,000") else null
}

/** Log a meal by hand, or change or delete one already logged. */
class FoodEntryViewModel(private val food: FoodRepository, private val entryId: String?) : ViewModel() {
    private val _ui = MutableStateFlow(FoodEntryUi())
    val ui: StateFlow<FoodEntryUi> = _ui.asStateFlow()

    init {
        if (entryId != null) viewModelScope.launch {
            val entry = (food.day(LocalDate.now().toString()) as? ApiResult.Ok)?.value?.entries?.firstOrNull { it.id == entryId }
            if (entry != null) _ui.update { it.copy(existing = entry, description = entry.description, meal = entry.mealType, calories = entry.calories.toString()) }
        }
    }

    fun description(value: String) = _ui.update { it.copy(description = value) }
    fun meal(value: MealType) = _ui.update { it.copy(meal = value) }
    fun calories(value: String) = _ui.update { it.copy(calories = value.filter(Char::isDigit).take(4)) }

    fun save(onDone: () -> Unit) {
        val state = _ui.value
        if (state.descriptionError != null || state.caloriesError != null) { _ui.update { it.copy(showErrors = true) }; return }
        _ui.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            val existing = state.existing
            val result = if (existing == null) {
                food.create(CreateFoodEntryRequest(LocalDate.now().toString(), state.meal, state.description.trim(), state.caloriesValue!!))
            } else {
                food.update(existing.id, UpdateFoodEntryRequest(existing.localDate, state.meal, state.description.trim(), state.caloriesValue!!, existing.proteinGrams, existing.carbsGrams, existing.fatGrams))
            }
            _ui.update { it.copy(busy = false, note = result.note()) }
            if (result is ApiResult.Ok) onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        val existing = _ui.value.existing ?: return
        _ui.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            val result = food.delete(existing.id)
            _ui.update { it.copy(busy = false, note = result.note()) }
            if (result is ApiResult.Ok) onDone()
        }
    }
}
